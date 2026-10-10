package expo.modules.bayutvideocompressor

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardware-accelerated video compressor using MediaCodec.
 * Uses GL surface pipeline to properly set presentation timestamps
 * via eglPresentationTimeANDROID for correct encoder bitrate control.
 */
class VideoCompressor(private val context: Context) {

    companion object {
        private const val TAG = "BayutCompressor"
        private const val I_FRAME_INTERVAL = 1
        private const val DEFAULT_AUDIO_BITRATE = 128_000
    }

    data class CompressConfig(
        val videoMaxDimensionLongEdge: Int = 0,
        val videoMaxDimensionShortEdge: Int = 0,
        val videoBitrateTarget: Int = 0,
        val speed: String = "ultrafast",
        val videoEncodingMode: String = "vbr",
        val videoCodec: String = "h264",
        val videoQuality: Float = 0.5f,
        val videoBitrateMax: Int = 0,
        val videoKeyframeInterval: Float = 0f,
        val audioCodec: String? = null,
        val audioBitrate: Int = 0,
    )

    interface ProgressListener {
        fun onProgress(progress: Float)
    }

    fun compress(
        inputUri: Uri,
        outputFile: File,
        config: CompressConfig,
        cancelled: AtomicBoolean,
        listener: ProgressListener?,
    ): String {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, inputUri)
        } catch (e: Exception) {
            throw IllegalArgumentException("Cannot read video: ${e.message}")
        }

        val sourceWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
        val sourceHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val sourceBitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 5_000_000
        retriever.release()

        val (outputWidth, outputHeight) = calculateOutputSize(sourceWidth, sourceHeight, config.videoMaxDimensionLongEdge, config.videoMaxDimensionShortEdge, rotation)

        val outputBitrate = if (config.videoBitrateTarget > 0) {
            config.videoBitrateTarget
        } else {
            calculateAutoBitrate(outputWidth, outputHeight, sourceBitrate, config.speed)
        }

        val mimeType = when (config.videoCodec) {
            "hevc" -> MediaFormat.MIMETYPE_VIDEO_HEVC
            else -> MediaFormat.MIMETYPE_VIDEO_AVC
        }

        Log.i(TAG, "Compressing: ${sourceWidth}x${sourceHeight} -> ${outputWidth}x${outputHeight}, " +
                "bitrate: $outputBitrate, codec: $mimeType, speed: ${config.speed}")

        val startTime = System.currentTimeMillis()

        // Set up extractor
        val extractor = MediaExtractor()
        val fd = context.contentResolver.openFileDescriptor(inputUri, "r")
            ?: throw IllegalArgumentException("Cannot open video file")
        extractor.setDataSource(fd.fileDescriptor)

        val videoTrackIndex = findTrack(extractor, true)
        if (videoTrackIndex < 0) throw IllegalStateException("No video track found")
        val audioTrackIndex = findTrack(extractor, false)

        // Audio source: the original track (pass-through) or a temp AAC re-encode.
        // Default is pass-through unless an audioBitrate is requested.
        val reencodeAudio = audioTrackIndex >= 0 &&
            (config.audioCodec == "aac" || (config.audioCodec == null && config.audioBitrate > 0))
        var audioTempFile: File? = null
        var audioExtractor: MediaExtractor? = null
        var audioSourceFd: android.os.ParcelFileDescriptor? = null
        var audioSourceTrack = -1
        if (audioTrackIndex >= 0) {
            if (reencodeAudio) {
                val tmp = File(context.cacheDir, "audio_${System.nanoTime()}.m4a")
                val aacBitrate = if (config.audioBitrate > 0) config.audioBitrate else DEFAULT_AUDIO_BITRATE
                if (transcodeAudioToAac(inputUri, tmp, aacBitrate)) {
                    audioTempFile = tmp
                    audioExtractor = MediaExtractor().apply { setDataSource(tmp.absolutePath) }
                } else {
                    tmp.delete()
                    Log.w(TAG, "Audio re-encode failed, falling back to pass-through")
                }
            }
            if (audioExtractor == null) {
                audioSourceFd = context.contentResolver.openFileDescriptor(inputUri, "r")
                audioExtractor = MediaExtractor().apply { setDataSource(audioSourceFd!!.fileDescriptor) }
            }
            audioSourceTrack = findTrack(audioExtractor, false)
        }

        // Set up encoder
        val outputFormat = MediaFormat.createVideoFormat(mimeType, outputWidth, outputHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, outputBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            if (config.videoKeyframeInterval > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, config.videoKeyframeInterval)
            } else {
                setInteger(
                    MediaFormat.KEY_I_FRAME_INTERVAL,
                    if (config.videoKeyframeInterval > 0f) Math.round(config.videoKeyframeInterval).coerceAtLeast(1) else I_FRAME_INTERVAL
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_PRIORITY, 0)  // Realtime priority
                setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())  // Max speed
            }
        }

        val encoder = findHardwareEncoder(mimeType, outputFormat)
        Log.i(TAG, "Using encoder: ${encoder.name} (${if (isHardwareEncoder(encoder.name)) "HARDWARE" else "SOFTWARE"})")

        val encoderCaps = encoder.codecInfo.getCapabilitiesForType(mimeType).encoderCapabilities
        fun supports(mode: Int) = encoderCaps?.isBitrateModeSupported(mode) == true
        val mode = when {
            config.videoEncodingMode == "cbr" && supports(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) ->
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            config.videoEncodingMode == "cq" && supports(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ) ->
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
            else -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        }
        if (config.videoEncodingMode != "vbr" && mode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) {
            Log.w(TAG, "${config.videoEncodingMode.uppercase()} not supported by ${encoder.name}, falling back to VBR")
        }
        outputFormat.setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
        if (mode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ) {
            // "videoQuality" is a 0-100 scale for CQ mode on most encoders (KEY_QUALITY, API 28+)
            outputFormat.setInteger("videoQuality", (config.videoQuality.coerceIn(0f, 1f) * 100).toInt())
        }
        // Peak cap is vendor-dependent; unsupported encoders ignore the key.
        if (mode != MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR && config.videoBitrateMax > 0) {
            outputFormat.setInteger("max-bitrate", maxOf(config.videoBitrateMax, outputBitrate))
        }
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderInputSurface = encoder.createInputSurface()
        encoder.start()

        // GL pipeline: decoder → SurfaceTexture → GL → eglPresentationTimeANDROID → encoder
        // Required for correct timestamps and bitrate control
        extractor.selectTrack(videoTrackIndex)
        val inputFormat = extractor.getTrackFormat(videoTrackIndex)
        val decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)

        val outputSurface = OutputSurface(encoderInputSurface, outputWidth, outputHeight)
        decoder.configure(inputFormat, outputSurface.surface, null, 0)
        decoder.start()

        // Set up muxer
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        // NOTE: Do NOT set orientationHint here. The GL pipeline already applies
        // rotation via SurfaceTexture.getTransformMatrix(), so the encoded frames
        // are already in the correct orientation. Setting the hint would cause
        // double rotation (e.g. portrait → landscape).

        // Frame dropping: if source fps > 30, skip excess frames to save processing time
        val TARGET_FPS = 30
        val sourceFps = try {
            inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
        } catch (e: Exception) { 0 }
        val frameDropEnabled = sourceFps > TARGET_FPS
        // Interval between target frames in microseconds (e.g. 33333us for 30fps)
        val targetFrameIntervalUs = if (frameDropEnabled) 1_000_000L / TARGET_FPS else 0L
        var nextTargetPtsUs = 0L
        var droppedFrames = 0

        if (frameDropEnabled) {
            Log.i(TAG, "Frame dropping: ${sourceFps}fps → ${TARGET_FPS}fps (will skip ~${((1.0 - TARGET_FPS.toFloat()/sourceFps) * 100).toInt()}% of frames)")
        }

        var videoMuxTrack = -1
        var audioMuxTrack = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var decoderDone = false
        var outputDone = false
        var lastProgressPercent = -1
        var frameCount = 0
        var renderedFrames = 0

        Log.i(TAG, "Starting transcode loop...")

        while (!outputDone) {
            if (cancelled.get()) {
                cleanupAll(decoder, encoder, muxer, extractor, outputSurface, encoderInputSurface, fd)
                outputFile.delete()
                throw InterruptedException("Compression cancelled")
            }

            // --- Feed input to decoder (non-blocking) ---
            if (!inputDone) {
                val idx = decoder.dequeueInputBuffer(0)
                if (idx >= 0) {
                    val buf = decoder.getInputBuffer(idx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                        frameCount++
                    }
                }
            }

            // --- Drain decoder → GL render → encoder (with proper timestamps) ---
            if (!decoderDone) {
                val status = decoder.dequeueOutputBuffer(bufferInfo, 2_500)
                when {
                    status >= 0 -> {
                        val isEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

                        // Frame dropping: skip frames between target timestamps
                        // Note: bufferInfo.size is 0 when decoding to a surface, so we check !isEos
                        val shouldRender = if (frameDropEnabled && !isEos) {
                            if (bufferInfo.presentationTimeUs >= nextTargetPtsUs) {
                                nextTargetPtsUs = bufferInfo.presentationTimeUs + targetFrameIntervalUs
                                true
                            } else {
                                droppedFrames++
                                false
                            }
                        } else {
                            !isEos
                        }

                        decoder.releaseOutputBuffer(status, shouldRender)

                        if (shouldRender) {
                            outputSurface.awaitNewImage()
                            outputSurface.drawImage()
                            outputSurface.setPresentationTime(bufferInfo.presentationTimeUs * 1000)
                            outputSurface.swapBuffers()
                            renderedFrames++
                        }

                        if (isEos) {
                            encoder.signalEndOfInputStream()
                            decoderDone = true
                        }

                        if (duration > 0 && !isEos) {
                            val pct = ((bufferInfo.presentationTimeUs.toFloat() / (duration * 1000f)) * 100)
                                .toInt().coerceIn(0, 100)
                            if (pct > lastProgressPercent) {
                                lastProgressPercent = pct
                                listener?.onProgress(pct / 100f)
                            }
                        }
                    }
                    // INFO_OUTPUT_FORMAT_CHANGED, INFO_TRY_AGAIN_LATER — continue
                }
            }

            // --- Drain encoder → write to muxer ---
            while (true) {
                val encStatus = encoder.dequeueOutputBuffer(bufferInfo, 0)
                when {
                    encStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    encStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoMuxTrack = muxer.addTrack(encoder.outputFormat)
                        if (audioExtractor != null && audioSourceTrack >= 0) {
                            audioMuxTrack = muxer.addTrack(audioExtractor.getTrackFormat(audioSourceTrack))
                        }
                        muxer.start()
                        muxerStarted = true
                    }
                    encStatus >= 0 -> {
                        val data = encoder.getOutputBuffer(encStatus)!!
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && muxerStarted) {
                            data.position(bufferInfo.offset)
                            data.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoMuxTrack, data, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(encStatus, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                            break
                        }
                    }
                }
            }
        }

        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0

        // Audio: copy samples from the (original or re-encoded) audio source
        if (audioExtractor != null && audioMuxTrack >= 0 && muxerStarted) {
            processAudio(audioExtractor, muxer, audioSourceTrack, audioMuxTrack)
        }

        try { audioExtractor?.release() } catch (_: Exception) {}
        try { audioSourceFd?.close() } catch (_: Exception) {}
        audioTempFile?.delete()
        cleanupAll(decoder, encoder, muxer, extractor, outputSurface, encoderInputSurface, fd)

        listener?.onProgress(1.0f)
        val outKB = outputFile.length() / 1024
        val fps = if (elapsed > 0) (renderedFrames / elapsed).toInt() else 0
        val dropInfo = if (droppedFrames > 0) ", dropped $droppedFrames" else ""
        Log.i(TAG, "Compression complete: ${outKB}KB in ${String.format("%.1f", elapsed)}s ($renderedFrames rendered / $frameCount decoded, $fps fps$dropInfo)")

        return outputFile.absolutePath
    }

    // *** Hardware encoder selection ***
    private fun findHardwareEncoder(mimeType: String, format: MediaFormat): MediaCodec {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)

        val compatibleEncoders = codecList.codecInfos
            .filter { it.isEncoder }
            .filter { it.supportedTypes.any { type -> type.equals(mimeType, ignoreCase = true) } }

        Log.i(TAG, "Available encoders for $mimeType:")
        compatibleEncoders.forEach { codec ->
            val hw = if (isHardwareEncoder(codec.name)) "HARDWARE" else "SOFTWARE"
            Log.i(TAG, "  - ${codec.name} ($hw)")
        }

        val hwEncoders = compatibleEncoders
            .filter { isHardwareEncoder(it.name) }
            .filter { !it.name.contains("secure", ignoreCase = true) }

        if (hwEncoders.isNotEmpty()) {
            val selected = hwEncoders.first()
            Log.i(TAG, "Selected HARDWARE encoder: ${selected.name}")
            return MediaCodec.createByCodecName(selected.name)
        }

        val swEncoder = compatibleEncoders.firstOrNull()
        if (swEncoder != null) {
            Log.w(TAG, "No HW encoder, using SOFTWARE: ${swEncoder.name}")
            return MediaCodec.createByCodecName(swEncoder.name)
        }

        return MediaCodec.createEncoderByType(mimeType)
    }

    private fun isHardwareEncoder(name: String): Boolean {
        val softwarePrefixes = listOf("OMX.google.", "c2.android.", "c2.google.")
        return softwarePrefixes.none { name.startsWith(it, ignoreCase = true) }
    }

    private fun processAudio(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        trackIndex: Int,
        muxTrackIndex: Int
    ) {
        extractor.selectTrack(trackIndex)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val buffer = ByteBuffer.allocate(256 * 1024)
        val info = MediaCodec.BufferInfo()

        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(muxTrackIndex, buffer, info)
            extractor.advance()
        }
    }


    /**
     * Decodes the first audio track and re-encodes it as AAC-LC into a standalone .m4a.
     * Returns false on any failure so the caller can fall back to pass-through.
     */
    private fun transcodeAudioToAac(inputUri: Uri, outFile: File, videoBitrateTarget: Int): Boolean {
        val ex = MediaExtractor()
        var pfd: android.os.ParcelFileDescriptor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            pfd = context.contentResolver.openFileDescriptor(inputUri, "r") ?: return false
            ex.setDataSource(pfd.fileDescriptor)
            val track = findTrack(ex, false)
            if (track < 0) return false
            ex.selectTrack(track)
            val inFmt = ex.getTrackFormat(track)
            decoder = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(inFmt, null, null, 0)
            decoder.start()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val info = MediaCodec.BufferInfo()
            var muxTrack = -1
            var inputEos = false
            var decoderEos = false
            var encoderEos = false
            var bytesPerSecond = 0L
            var ptsUs = 0L

            fun drainEncoder() {
                val enc = encoder ?: return
                while (true) {
                    val encInfo = MediaCodec.BufferInfo()
                    val idx = enc.dequeueOutputBuffer(encInfo, 0)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            muxTrack = muxer!!.addTrack(enc.outputFormat)
                            muxer!!.start()
                            muxerStarted = true
                        }
                        idx >= 0 -> {
                            val buf = enc.getOutputBuffer(idx)!!
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                                encInfo.size > 0 && muxerStarted) {
                                buf.position(encInfo.offset)
                                buf.limit(encInfo.offset + encInfo.size)
                                muxer!!.writeSampleData(muxTrack, buf, encInfo)
                            }
                            enc.releaseOutputBuffer(idx, false)
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                encoderEos = true
                                return
                            }
                        }
                    }
                }
            }

            while (!encoderEos) {
                if (!inputEos) {
                    val i = decoder.dequeueInputBuffer(0)
                    if (i >= 0) {
                        val size = ex.readSampleData(decoder.getInputBuffer(i)!!, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            decoder.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }

                if (!decoderEos) {
                    val o = decoder.dequeueOutputBuffer(info, 0)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && encoder == null) {
                        // Configure the encoder from what the decoder really outputs
                        // (e.g. HE-AAC may double the sample rate).
                        val pcm = decoder.outputFormat
                        val sr = pcm.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val ch = pcm.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        bytesPerSecond = sr.toLong() * ch * 2
                        val encFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, ch).apply {
                            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                            setInteger(MediaFormat.KEY_BIT_RATE, videoBitrateTarget)
                        }
                        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                            it.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                            it.start()
                        }
                    } else if (o >= 0) {
                        val enc = encoder ?: return false
                        val pcmBuf = decoder.getOutputBuffer(o)!!
                        var pos = info.offset
                        var remaining = info.size
                        var chunkPts = info.presentationTimeUs
                        while (remaining > 0) {
                            val ei = enc.dequeueInputBuffer(10_000)
                            if (ei < 0) { drainEncoder(); continue }
                            val eb = enc.getInputBuffer(ei)!!
                            eb.clear()
                            val n = minOf(remaining, eb.capacity())
                            pcmBuf.position(pos)
                            pcmBuf.limit(pos + n)
                            eb.put(pcmBuf)
                            enc.queueInputBuffer(ei, 0, n, chunkPts, 0)
                            pos += n
                            remaining -= n
                            chunkPts += n * 1_000_000L / bytesPerSecond
                            drainEncoder()
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decoderEos = true
                            var queued = false
                            while (!queued) {
                                val ei = enc.dequeueInputBuffer(10_000)
                                if (ei < 0) { drainEncoder(); continue }
                                enc.queueInputBuffer(ei, 0, 0, chunkPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                queued = true
                            }
                        }
                        decoder.releaseOutputBuffer(o, false)
                    }
                }
                drainEncoder()
            }
            return muxerStarted
        } catch (e: Exception) {
            Log.w(TAG, "Audio transcode error: ${e.message}")
            return false
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { ex.release() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    private fun findTrack(extractor: MediaExtractor, isVideo: Boolean): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (isVideo && mime.startsWith("video/")) return i
            if (!isVideo && mime.startsWith("audio/")) return i
        }
        return -1
    }

    private fun calculateOutputSize(srcW: Int, srcH: Int, videoMaxDimensionLongEdge: Int, videoMaxDimensionShortEdge: Int, rotation: Int): Pair<Int, Int> {
        val w = if (rotation == 90 || rotation == 270) srcH else srcW
        val h = if (rotation == 90 || rotation == 270) srcW else srcH

        if (videoMaxDimensionLongEdge <= 0 && videoMaxDimensionShortEdge <= 0)
            return Pair(w, h)

        // A limit <= 0 means "unset". Long edge caps max(w, h), short edge caps min(w, h).
        val srcLong = maxOf(w, h).toFloat()
        val srcShort = minOf(w, h).toFloat()
        var scale = 1f
        if (videoMaxDimensionLongEdge > 0) scale = minOf(scale, videoMaxDimensionLongEdge / srcLong)
        if (videoMaxDimensionShortEdge > 0) scale = minOf(scale, videoMaxDimensionShortEdge / srcShort)
        return Pair((Math.round(w * scale / 2f) * 2), (Math.round(h * scale / 2f) * 2))
    }

    private fun calculateAutoBitrate(width: Int, height: Int, sourceBitrate: Int, speed: String): Int {
        val pixels = width * height
        val factor = when (speed) { "ultrafast" -> 0.9f; "fast" -> 0.8f; else -> 0.7f }
        val target = (pixels * 1.5f * factor).toInt()
        return target.coerceIn(500_000, minOf(sourceBitrate, 5_000_000))
    }

    private fun cleanupAll(
        decoder: MediaCodec, encoder: MediaCodec, muxer: MediaMuxer,
        extractor: MediaExtractor, outputSurface: OutputSurface,
        inputSurface: Surface, fd: android.os.ParcelFileDescriptor
    ) {
        try { decoder.stop() } catch (_: Exception) {}
        try { decoder.release() } catch (_: Exception) {}
        try { encoder.stop() } catch (_: Exception) {}
        try { encoder.release() } catch (_: Exception) {}
        try { muxer.stop() } catch (_: Exception) {}
        try { muxer.release() } catch (_: Exception) {}
        try { extractor.release() } catch (_: Exception) {}
        try { outputSurface.release() } catch (_: Exception) {}
        try { inputSurface.release() } catch (_: Exception) {}
        try { fd.close() } catch (_: Exception) {}
    }
}
