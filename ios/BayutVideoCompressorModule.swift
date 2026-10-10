import ExpoModulesCore
import AVFoundation
import VideoToolbox
import MobileCoreServices

public class BayutVideoCompressorModule: Module {
  private var backgroundTaskId: UIBackgroundTaskIdentifier = .invalid
  private var activeSessions: [String: VTCompressionSession] = [:]
  private var cancelledUUIDs: Set<String> = []

  public func definition() -> ModuleDefinition {
    Name("BayutVideoCompressor")

    Events("onCompressProgress", "onBackgroundTaskExpired")

    // MARK: - Compress

    AsyncFunction("video_compress") { (fileUrl: String, options: [String: Any]) -> String in
      let uuid = options["uuid"] as? String ?? UUID().uuidString

      // Send UUID back via options callback (handled in JS layer)
      // Emit it as part of progress events

      let videoMaxDimensionLongEdge = (options["videoMaxDimensionLongEdge"] as? NSNumber)?.intValue ?? 0
      let videoMaxDimensionShortEdge = (options["videoMaxDimensionShortEdge"] as? NSNumber)?.intValue ?? 0
      let videoBitrateTarget = (options["videoBitrateTarget"] as? NSNumber)?.intValue ?? 0
      let speed = options["speed"] as? String ?? "ultrafast"
      let videoEncodingMode = options["videoEncodingMode"] as? String ?? "vbr"
      let videoCodec = options["videoCodec"] as? String ?? "h264"
      let videoQuality = (options["videoQuality"] as? NSNumber)?.doubleValue ?? 0.5
      let videoBitrateMax = (options["videoBitrateMax"] as? NSNumber)?.intValue ?? 0
      let videoKeyframeInterval = (options["videoKeyframeInterval"] as? NSNumber)?.doubleValue ?? 0
      let audioCodec = options["audioCodec"] as? String
      let audioBitrate = (options["audioBitrate"] as? NSNumber)?.intValue ?? 0
      let minimumFileSize = (options["minimumFileSizeForCompress"] as? NSNumber)?.doubleValue ?? 0
      let progressDivider = (options["progressDivider"] as? NSNumber)?.intValue ?? 0

      let inputURL = URL(string: fileUrl)!
      let resolvedURL = self.resolveFileURL(inputURL)

      // Check minimum file size
      if minimumFileSize > 0 {
        let fileSize = self.getFileSizeMB(url: resolvedURL)
        if fileSize <= minimumFileSize {
          return resolvedURL.absoluteString
        }
      }

      // Get source video info
      let asset = AVURLAsset(url: resolvedURL, options: [AVURLAssetPreferPreciseDurationAndTimingKey: true])
      guard let videoTrack = asset.tracks(withMediaType: .video).first else {
        throw CompressorError.invalidVideo("No video track found")
      }

      let naturalSize = videoTrack.naturalSize
      let sourceWidth = abs(naturalSize.width)
      let sourceHeight = abs(naturalSize.height)
      let sourceBitrate = videoTrack.estimatedDataRate

      // Calculate output dimensions
      let (outputWidth, outputHeight) = self.calculateOutputSize(
        sourceWidth: sourceWidth,
        sourceHeight: sourceHeight,
        videoMaxDimensionLongEdge: CGFloat(videoMaxDimensionLongEdge),
        videoMaxDimensionShortEdge: CGFloat(videoMaxDimensionShortEdge)
      )

      // Calculate output bitrate
      let outputBitrate: Int
      if videoBitrateTarget > 0 {
        outputBitrate = videoBitrateTarget
      } else {
        outputBitrate = self.calculateAutoBitrate(
          width: Int(outputWidth),
          height: Int(outputHeight),
          sourceBitrate: Int(sourceBitrate),
          speed: speed
        )
      }

      // Create output URL
      let outputURL = self.createTempURL()

      // Compress using VTCompressionSession for maximum speed
      try await self.compressWithVTSession(
        asset: asset,
        videoTrack: videoTrack,
        outputURL: outputURL,
        outputWidth: Int(outputWidth),
        outputHeight: Int(outputHeight),
        outputBitrate: outputBitrate,
        speed: speed,
        videoEncodingMode: videoEncodingMode,
        videoCodec: videoCodec,
        videoQuality: videoQuality,
        videoBitrateMax: videoBitrateMax,
        videoKeyframeInterval: videoKeyframeInterval,
        audioCodec: audioCodec,
        audioBitrate: audioBitrate,
        uuid: uuid,
        progressDivider: progressDivider
      )

      return outputURL.absoluteString
    }

    // MARK: - Cancel

    Function("cancel") { (uuid: String) in
      self.cancelledUUIDs.insert(uuid)
      if let session = self.activeSessions[uuid] {
        VTCompressionSessionInvalidate(session)
        self.activeSessions.removeValue(forKey: uuid)
      }
    }

    // MARK: - Image Compress

    AsyncFunction("image_compress") { (value: String, options: [String: Any]) -> String in
      let maxWidth = (options["maxWidth"] as? NSNumber)?.intValue ?? 1280
      let maxHeight = (options["maxHeight"] as? NSNumber)?.intValue ?? 1280
      let quality = (options["quality"] as? NSNumber)?.doubleValue ?? 0.8
      let output = options["output"] as? String ?? "jpg"
      let input = options["input"] as? String ?? "uri"

      // 1. Load image
      var image: UIImage?
      if input == "base64" {
        let cleanValue = value.replacingOccurrences(of: "^data:image/.*;(?:charset=.{3,5};)?base64,", with: "", options: .regularExpression)
        if let data = Data(base64Encoded: cleanValue, options: .ignoreUnknownCharacters) {
          image = UIImage(data: data)
        }
      } else {
        let url = URL(string: value)!
        let resolvedURL = self.resolveFileURL(url)
        if let data = try? Data(contentsOf: resolvedURL) {
          image = UIImage(data: data)
        }
      }

      guard let loadedImage = image else {
        throw CompressorError.invalidVideo("Failed to load image from: \(value)")
      }

      // 2. Fix orientation
      let orientedImage = self.fixImageOrientation(loadedImage)

      // 3. Resize
      let resizedImage = self.resizeImage(orientedImage, maxWidth: maxWidth, maxHeight: maxHeight)

      // 4. Compress
      var imageData: Data
      if output == "png" {
        imageData = resizedImage.pngData()!
      } else {
        imageData = resizedImage.jpegData(compressionQuality: CGFloat(quality))!
      }

      // 5. Copy EXIF from source
      if input == "uri" {
        let sourceURL = URL(string: value)!
        let resolvedURL = self.resolveFileURL(sourceURL)
        imageData = self.copyExifInfo(from: resolvedURL.path, to: imageData, image: UIImage(data: imageData) ?? resizedImage, isPNG: output == "png")
      }

      // 6. Write to cache file
      let ext = output == "png" ? "png" : "jpg"
      let tempDir = FileManager.default.temporaryDirectory
      let filename = ProcessInfo.processInfo.globallyUniqueString + "." + ext
      let outputURL = tempDir.appendingPathComponent(filename)
      try imageData.write(to: outputURL, options: .atomic)

      // 7. Check if compressed is smaller
      if input == "uri" {
        let sourceURL = URL(string: value)!
        let resolvedURL = self.resolveFileURL(sourceURL)
        let sourceSize = self.getFileSizeBytes(url: resolvedURL)
        let compressedSize = UInt64(imageData.count)
        if compressedSize >= sourceSize {
          try? FileManager.default.removeItem(at: outputURL)
          return resolvedURL.absoluteString
        }
      }

      return outputURL.absoluteString
    }

    // MARK: - Get Metadata

    AsyncFunction("getMetadata") { (fileUrl: String) -> [String: Any] in
      let url = URL(string: fileUrl)!
      let resolvedURL = self.resolveFileURL(url)

      let asset = AVURLAsset(url: resolvedURL, options: [AVURLAssetPreferPreciseDurationAndTimingKey: true])

      var result: [String: Any] = [:]

      if let track = asset.tracks(withMediaType: .video).first {
        let size = track.naturalSize
        let duration = CMTimeGetSeconds(asset.duration)
        let fileSize = self.getFileSizeBytes(url: resolvedURL)
        let ext = resolvedURL.pathExtension

        result["width"] = size.width
        result["height"] = size.height
        result["duration"] = duration
        result["size"] = fileSize
        result["bitrate"] = track.estimatedDataRate
        result["extension"] = ext
      }

      return result
    }

    // MARK: - Background Task

    AsyncFunction("activateBackgroundTask") { () -> String in
      guard self.backgroundTaskId == .invalid else {
        throw CompressorError.backgroundTask("Background task already active")
      }

      self.backgroundTaskId = UIApplication.shared.beginBackgroundTask(withName: "video-compress") {
        self.sendEvent("onBackgroundTaskExpired", [:])
        UIApplication.shared.endBackgroundTask(self.backgroundTaskId)
        self.backgroundTaskId = .invalid
      }

      return "\(self.backgroundTaskId.rawValue)"
    }

    AsyncFunction("deactivateBackgroundTask") { () in
      guard self.backgroundTaskId != .invalid else {
        throw CompressorError.backgroundTask("No active background task")
      }
      UIApplication.shared.endBackgroundTask(self.backgroundTaskId)
      self.backgroundTaskId = .invalid
    }
  }

  // MARK: - VTCompressionSession (Hardware Accelerated)

  private func compressWithVTSession(
    asset: AVURLAsset,
    videoTrack: AVAssetTrack,
    outputURL: URL,
    outputWidth: Int,
    outputHeight: Int,
    outputBitrate: Int,
    speed: String,
    videoEncodingMode: String,
    videoCodec: String,
    videoQuality: Double,
    videoBitrateMax: Int,
    videoKeyframeInterval: Double,
    audioCodec: String?,
    audioBitrate: Int,
    uuid: String,
    progressDivider: Int
  ) async throws {
    // Set up reader
    let reader = try AVAssetReader(asset: asset)
    let readerSettings: [String: Any] = [
      kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
    ]
    let readerOutput = AVAssetReaderTrackOutput(track: videoTrack, outputSettings: readerSettings)
    readerOutput.alwaysCopiesSampleData = false // Zero-copy optimization
    reader.add(readerOutput)

    // Set up writer
    let codecType: AVVideoCodecType = videoCodec == "hevc" ? .hevc : .h264
    let profileLevel: String = videoCodec == "hevc"
      ? kVTProfileLevel_HEVC_Main_AutoLevel as String
      : kVTProfileLevel_H264_High_AutoLevel as String

    var compressionProps: [String: Any] = [
      AVVideoAverageBitRateKey: outputBitrate,
      AVVideoProfileLevelKey: profileLevel,
    ]

    // Constant quality: VideoToolbox picks the bitrate for the requested quality, so the
    // average-bitrate key is dropped (videoBitrateMax can still cap peaks below).
    if videoEncodingMode == "cq" {
      compressionProps.removeValue(forKey: AVVideoAverageBitRateKey)
      compressionProps[kVTCompressionPropertyKey_Quality as String] = min(max(videoQuality, 0), 1)
    }

    if videoKeyframeInterval > 0 {
      compressionProps[AVVideoMaxKeyFrameIntervalDurationKey] = videoKeyframeInterval
    }

    // Rate control. AVAssetWriter has no true CBR switch, so CBR is approximated by
    // capping the data rate at the target over a 1-second window. VBR may take an
    // optional peak cap (videoBitrateMax).
    let rateCap = videoEncodingMode == "cbr"
      ? outputBitrate
      : (videoBitrateMax > 0 ? (videoEncodingMode == "cq" ? videoBitrateMax : max(videoBitrateMax, outputBitrate)) : 0)
    if rateCap > 0 {
      // [bytes, seconds]
      compressionProps[kVTCompressionPropertyKey_DataRateLimits as String] = [rateCap / 8, 1]
    }

    // Speed optimizations
    if speed == "ultrafast" {
      compressionProps[AVVideoAllowFrameReorderingKey] = false
      compressionProps[kVTCompressionPropertyKey_RealTime as String] = true
    } else if speed == "fast" {
      compressionProps[AVVideoAllowFrameReorderingKey] = false
    }

    let videoSettings: [String: Any] = [
      AVVideoCodecKey: codecType,
      AVVideoWidthKey: outputWidth,
      AVVideoHeightKey: outputHeight,
      AVVideoScalingModeKey: AVVideoScalingModeResizeAspectFill,
      AVVideoCompressionPropertiesKey: compressionProps,
    ]

    let writerInput = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings)
    writerInput.expectsMediaDataInRealTime = speed == "ultrafast"
    writerInput.transform = videoTrack.preferredTransform

    let writer = try AVAssetWriter(outputURL: outputURL, fileType: .mp4)
    writer.shouldOptimizeForNetworkUse = true
    writer.add(writerInput)

    // Audio: re-encode to AAC (default, 128 kbps) or pass the compressed samples through ('copy')
    var audioReaderOutput: AVAssetReaderTrackOutput?
    var audioWriterInput: AVAssetWriterInput?

    if let audioTrack = asset.tracks(withMediaType: .audio).first {
      if audioCodec == "copy" {
        let audioOutput = AVAssetReaderTrackOutput(track: audioTrack, outputSettings: nil)
        audioOutput.alwaysCopiesSampleData = false
        reader.add(audioOutput)
        audioReaderOutput = audioOutput

        let audioInput = AVAssetWriterInput(
          mediaType: .audio,
          outputSettings: nil,
          sourceFormatHint: (audioTrack.formatDescriptions.first).map { $0 as! CMFormatDescription }
        )
        writer.add(audioInput)
        audioWriterInput = audioInput
      } else {
        // Read as decompressed Linear PCM so writer can re-encode to AAC
        let audioDecompressSettings: [String: Any] = [
          AVFormatIDKey: kAudioFormatLinearPCM,
          AVLinearPCMIsBigEndianKey: false,
          AVLinearPCMIsFloatKey: false,
          AVLinearPCMBitDepthKey: 16,
          AVLinearPCMIsNonInterleaved: false,
        ]
        let audioOutput = AVAssetReaderTrackOutput(track: audioTrack, outputSettings: audioDecompressSettings)
        audioOutput.alwaysCopiesSampleData = false
        reader.add(audioOutput)
        audioReaderOutput = audioOutput

        let audioInput = AVAssetWriterInput(mediaType: .audio, outputSettings: [
          AVFormatIDKey: kAudioFormatMPEG4AAC,
          AVEncoderBitRateKey: audioBitrate > 0 ? audioBitrate : 128000,
          AVNumberOfChannelsKey: 2,
          AVSampleRateKey: 44100.0,
        ])
        writer.add(audioInput)
        audioWriterInput = audioInput
      }
    }

    // Start processing
    reader.startReading()
    writer.startWriting()
    writer.startSession(atSourceTime: .zero)

    let totalDuration = CMTimeGetSeconds(asset.duration)
    var lastReportedProgress: Int = 0

    // Process video frames
    await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
      let videoQueue = DispatchQueue(label: "com.bayut.videocompressor.video")
      let audioQueue = DispatchQueue(label: "com.bayut.videocompressor.audio")

      let group = DispatchGroup()

      // Video processing
      group.enter()
      writerInput.requestMediaDataWhenReady(on: videoQueue) {
        while writerInput.isReadyForMoreMediaData {
          // Check writer is still valid
          guard writer.status == .writing else {
            writerInput.markAsFinished()
            group.leave()
            return
          }

          if self.cancelledUUIDs.contains(uuid) {
            reader.cancelReading()
            writerInput.markAsFinished()
            group.leave()
            return
          }

          if let sampleBuffer = readerOutput.copyNextSampleBuffer() {
            if !writerInput.append(sampleBuffer) {
              writerInput.markAsFinished()
              group.leave()
              return
            }

            // Progress
            let pts = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sampleBuffer))
            let progress = Float(pts / totalDuration)
            let roundedProgress = Int(progress * 100)

            if roundedProgress > lastReportedProgress && (progressDivider == 0 || roundedProgress % max(progressDivider, 1) == 0) {
              lastReportedProgress = roundedProgress
              self.sendEvent("onCompressProgress", [
                "progress": min(progress, 1.0),
                "uuid": uuid
              ])
            }
          } else {
            writerInput.markAsFinished()
            group.leave()
            return
          }
        }
      }

      // Audio processing
      if let audioOutput = audioReaderOutput, let audioInput = audioWriterInput {
        group.enter()
        audioInput.requestMediaDataWhenReady(on: audioQueue) {
          while audioInput.isReadyForMoreMediaData {
            guard writer.status == .writing else {
              audioInput.markAsFinished()
              group.leave()
              return
            }

            if let sampleBuffer = audioOutput.copyNextSampleBuffer() {
              if !audioInput.append(sampleBuffer) {
                audioInput.markAsFinished()
                group.leave()
                return
              }
            } else {
              audioInput.markAsFinished()
              group.leave()
              return
            }
          }
        }
      }

      group.notify(queue: .main) {
        continuation.resume()
      }
    }

    // Finalize
    if self.cancelledUUIDs.contains(uuid) {
      writer.cancelWriting()
      self.cancelledUUIDs.remove(uuid)
      throw CompressorError.cancelled
    }

    await writer.finishWriting()

    if writer.status == .failed {
      throw writer.error ?? CompressorError.unknown
    }

    // Send 100% progress
    self.sendEvent("onCompressProgress", [
      "progress": 1.0,
      "uuid": uuid
    ])
  }

  // MARK: - Helpers

  private func resolveFileURL(_ url: URL) -> URL {
    if url.scheme == "file" || url.scheme == nil {
      return url
    }
    // Handle ph:// and other schemes if needed
    return url
  }

  private func calculateOutputSize(sourceWidth: CGFloat, sourceHeight: CGFloat, videoMaxDimensionLongEdge: CGFloat, videoMaxDimensionShortEdge: CGFloat) -> (CGFloat, CGFloat) {
    if videoMaxDimensionLongEdge <= 0 && videoMaxDimensionShortEdge <= 0 {
      return (sourceWidth, sourceHeight)
    }

    // A limit <= 0 means "unset". Long edge caps max(w, h), short edge caps min(w, h).
    let sourceLong = max(sourceWidth, sourceHeight)
    let sourceShort = min(sourceWidth, sourceHeight)
    var scale: CGFloat = 1.0
    if videoMaxDimensionLongEdge > 0 { scale = min(scale, videoMaxDimensionLongEdge / sourceLong) }
    if videoMaxDimensionShortEdge > 0 { scale = min(scale, videoMaxDimensionShortEdge / sourceShort) }

    // Round to even numbers (required by video encoders)
    let w = round(sourceWidth * scale / 2.0) * 2.0
    let h = round(sourceHeight * scale / 2.0) * 2.0

    return (w, h)
  }

  private func calculateAutoBitrate(width: Int, height: Int, sourceBitrate: Int, speed: String) -> Int {
    let pixels = width * height
    let compressFactor: Float

    switch speed {
    case "ultrafast":
      compressFactor = 0.9
    case "fast":
      compressFactor = 0.8
    default:
      compressFactor = 0.7
    }

    let baseBitrate = Float(pixels) * 1.5
    let targetBitrate = Int(baseBitrate * compressFactor)
    let videoBitrateMax = 5_000_000

    return min(max(targetBitrate, 500_000), min(sourceBitrate, videoBitrateMax))
  }

  private func createTempURL() -> URL {
    let tempDir = FileManager.default.temporaryDirectory
    let filename = ProcessInfo.processInfo.globallyUniqueString + ".mp4"
    return tempDir.appendingPathComponent(filename)
  }

  private func getFileSizeMB(url: URL) -> Double {
    guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
          let size = attrs[.size] as? UInt64 else { return 0 }
    return Double(size) / (1024 * 1024)
  }

  private func getFileSizeBytes(url: URL) -> UInt64 {
    guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
          let size = attrs[.size] as? UInt64 else { return 0 }
    return size
  }

  // MARK: - Image Helpers

  private func fixImageOrientation(_ image: UIImage) -> UIImage {
    if image.imageOrientation == .up {
      return image
    }

    var transform = CGAffineTransform.identity

    switch image.imageOrientation {
    case .down, .downMirrored:
      transform = transform.translatedBy(x: image.size.width, y: image.size.height)
      transform = transform.rotated(by: CGFloat.pi)
    case .left, .leftMirrored:
      transform = transform.translatedBy(x: image.size.width, y: 0)
      transform = transform.rotated(by: CGFloat.pi / 2)
    case .right, .rightMirrored:
      transform = transform.translatedBy(x: 0, y: image.size.height)
      transform = transform.rotated(by: -CGFloat.pi / 2)
    default:
      break
    }

    switch image.imageOrientation {
    case .upMirrored, .downMirrored:
      transform = transform.translatedBy(x: image.size.width, y: 0)
      transform = transform.scaledBy(x: -1, y: 1)
    case .leftMirrored, .rightMirrored:
      transform = transform.translatedBy(x: image.size.height, y: 0)
      transform = transform.scaledBy(x: -1, y: 1)
    default:
      break
    }

    if let cgImage = image.cgImage, let colorSpace = cgImage.colorSpace {
      guard let context = CGContext(
        data: nil,
        width: Int(image.size.width),
        height: Int(image.size.height),
        bitsPerComponent: cgImage.bitsPerComponent,
        bytesPerRow: 0,
        space: colorSpace,
        bitmapInfo: cgImage.bitmapInfo.rawValue
      ) else {
        return image
      }

      context.concatenate(transform)

      switch image.imageOrientation {
      case .left, .leftMirrored, .right, .rightMirrored:
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: image.size.height, height: image.size.width))
      default:
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: image.size.width, height: image.size.height))
      }

      if let rotatedCGImage = context.makeImage() {
        return UIImage(cgImage: rotatedCGImage)
      }
    }

    return image
  }

  private func resizeImage(_ image: UIImage, maxWidth: Int, maxHeight: Int) -> UIImage {
    let width = image.size.width
    let height = image.size.height

    // No resize needed
    if width <= CGFloat(maxWidth) && height <= CGFloat(maxHeight) {
      return image
    }

    let scale: CGFloat
    if width > height {
      scale = CGFloat(maxWidth) / width
    } else {
      scale = CGFloat(maxHeight) / height
    }

    let newWidth = width * scale
    let newHeight = height * scale

    let rect = CGRect(x: 0, y: 0, width: newWidth, height: newHeight)
    UIGraphicsBeginImageContext(rect.size)
    image.draw(in: rect)
    let resizedImage = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()

    return resizedImage ?? image
  }

  private func copyExifInfo(from sourcePath: String, to data: Data, image: UIImage, isPNG: Bool) -> Data {
    let url = URL(fileURLWithPath: sourcePath)
    guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
          let metadata = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] else {
      return data
    }

    let dataProvider = CGDataProvider(data: data as CFData)
    guard let dataProvider = dataProvider,
          let dataSource = CGImageSourceCreateWithDataProvider(dataProvider, nil) else {
      return data
    }
    var destMetadata = CGImageSourceCopyPropertiesAtIndex(dataSource, 0, nil) as? [CFString: Any] ?? [:]

    // Copy metadata keys that don't exist in destination
    for (key, value) in metadata {
      if destMetadata[key] == nil {
        destMetadata[key] = value
      }
    }

    let outputFormat = isPNG ? kUTTypePNG : kUTTypeJPEG
    let destData = NSMutableData()
    guard let destination = CGImageDestinationCreateWithData(destData, outputFormat, 1, nil),
          let cgImage = image.cgImage else {
      return data
    }

    CGImageDestinationAddImage(destination, cgImage, destMetadata as CFDictionary)
    CGImageDestinationFinalize(destination)
    return destData as Data
  }
}

// MARK: - Errors

enum CompressorError: Error, LocalizedError {
  case invalidVideo(String)
  case cancelled
  case backgroundTask(String)
  case unknown

  var errorDescription: String? {
    switch self {
    case .invalidVideo(let msg): return "Invalid video: \(msg)"
    case .cancelled: return "Compression was cancelled"
    case .backgroundTask(let msg): return msg
    case .unknown: return "Unknown compression error"
    }
  }
}
