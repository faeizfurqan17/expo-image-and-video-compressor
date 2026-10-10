/**
 * Bayut Video Compressor Types
 * Hardware-accelerated video compression for React Native
 */

/**
 * Codec to use for compression.
 * - 'h264': Widest compatibility, fast hardware encoding
 * - 'hevc': 50% smaller output, modern HW encoders (iOS 11+, Android 5+)
 */
export type CompressionCodec = 'h264' | 'hevc';

/**
 * Speed preset for compression.
 * - 'ultrafast': Prioritize speed over compression ratio
 * - 'fast': Good balance of speed and quality
 * - 'balanced': Best compression ratio, slower
 */
export type CompressionSpeed = 'ultrafast' | 'fast' | 'balanced';

/**
 * Rate control mode.
 * - 'vbr': Variable bitrate. `bitrate` is a target average; the encoder floats with content complexity.
 * - 'cq': Constant quality (like ffmpeg `-crf`). `videoQuality` sets the level and `bitrate` is ignored.
 *   Falls back to VBR (using `bitrate`) if the encoder lacks support. Output size is content-dependent.
 * - 'cbr': Constant bitrate. Android requests BITRATE_MODE_CBR (falls back to VBR if the encoder
 *   lacks support). iOS has no guaranteed CBR through AVAssetWriter, so it uses the average bitrate
 *   plus a tight 1-second data-rate cap as the closest approximation.
 */
export type BitrateMode = 'vbr' | 'cbr' | 'cq';

/**
 * Audio codec for the output. MP4 output only supports AAC re-encoding or stream copy.
 */
export type AudioCodec = 'aac' | 'copy';

/**
 * Options for video compression.
 */
export type CompressOptions = CompressOptionsBase &
  (
    | {
        /**
         * Rate control mode.
         * Default: 'vbr'
         */
        videoEncodingMode?: 'vbr';

        /**
         * Peak bitrate cap in bits per second.
         * - iOS: applied via `kVTCompressionPropertyKey_DataRateLimits` (1-second window); enforced.
         * - Android: passed as the `max-bitrate` MediaFormat key; only honored by some encoders.
         * Default: no cap.
         * Example: 2_500_000 for 2.5 Mbps
         */
        videoBitrateMax?: number;
        videoQuality?: never;
      }
    | {
        videoEncodingMode: 'cq';

        /**
         * Peak bitrate cap in bits per second.
         * - iOS: applied via `kVTCompressionPropertyKey_DataRateLimits` (1-second window); enforced.
         * - Android: passed as the `max-bitrate` MediaFormat key; only honored by some encoders.
         * Default: no cap.
         * Example: 2_500_000 for 2.5 Mbps
         */
        videoBitrateMax?: number;

        /**
         * Quality from 0 (smallest) to 1 (best). Default: 0.5.
         * - iOS: `kVTCompressionPropertyKey_Quality`.
         * - Android: encoder `quality` key (0-100) in BITRATE_MODE_CQ.
         */
        videoQuality?: number;
      }
    | {
        videoEncodingMode: 'cbr';
        videoBitrateMax?: never;
        videoQuality?: never;
      }
  );

export interface CompressOptionsBase {
  /**
   * Target bitrate in bits per second.
   * Default: auto-calculated based on resolution.
   * Example: 2_500_000 for 2.5 Mbps
   */
  videoBitrateTarget?: number;
  
  /**
   * Maximum keyframe (I-frame) interval in seconds.
   * Shorter = faster seeking and error recovery, larger files; longer = better compression.
   * Default: 1 on Android, encoder default on iOS.
   */
  videoKeyframeInterval?: number;

  /**
   * Audio codec.
   * - 'aac': Re-encode to AAC-LC (uses `audioBitrate`, default 128 kbps).
   * - 'copy': Keep the original audio stream untouched (`audioBitrate` is ignored).
   * Default: iOS 'aac'; Android 'copy' unless `audioBitrate` is set, then 'aac'.
   */
  audioCodec?: AudioCodec;

  /**
   * Audio bitrate in bits per second for AAC output. Ignored with `audioCodec: 'copy'`.
   * Default: 128_000
   */
  audioBitrate?: number;

  /**
   * Maximum output dimension (width or height).
   * The video will be scaled proportionally.
   * Given the input resolution `2420 × 1668` with `videoMaxDimensionShortEdge: 1080`, 
   * the output resolution will be `1566 × 1080`
   */
  videoMaxDimensionShortEdge?: number;

  /**
   * Maximum output dimension (width or height).
   * The video will be scaled proportionally.
   * Given a input resolution `2420 × 1668` with `videoMaxDimensionLongEdge: 1080`, 
   * the output resolution will be `1080 × 744`.
   */
  videoMaxDimensionLongEdge?: number;

  /**
   * Video codec to use.
   * Default: 'h264'
   */
  videoCodec?: CompressionCodec;

  /**
   * Compression speed preset.
   * Default: 'ultrafast'
   */
  speed?: CompressionSpeed;

  /**
   * Minimum file size in MB before compression applies.
   * Videos smaller than this will be returned as-is.
   * Default: 0 (always compress)
   */
  minimumFileSizeForCompress?: number;

  /**
   * Callback to receive the cancellation ID.
   * Use this ID with `cancel()` to stop compression.
   */
  getCancellationId?: (id: string) => void;

  /**
   * Divider for progress events.
   * 0 = every frame, 5 = every 5%.
   * Default: 0
   */
  progressDivider?: number;

  /**
   * Internal session ID for tracking and cancellation.
   * If not provided, one will be generated.
   */
  uuid?: string;
}

/**
 * Payload for compression progress events.
 */
export type CompressionProgressPayload = {
  /** Progress value from 0.0 to 1.0 */
  progress: number;
  /** Unique compression session ID */
  uuid: string;
};

/**
 * Video metadata information.
 */
export interface VideoMetadata {
  /** Video width in pixels */
  width: number;
  /** Video height in pixels */
  height: number;
  /** Duration in seconds */
  duration: number;
  /** File size in bytes */
  size: number;
  /** Video bitrate in bps */
  bitrate?: number;
  /** File extension */
  extension: string;
}

/**
 * Module event types.
 */
export type BayutVideoCompressorModuleEvents = {
  onCompressProgress: (params: CompressionProgressPayload) => void;
  onBackgroundTaskExpired: () => void;
};

/**
 * Output image format.
 */
export type ImageOutputType = 'jpg' | 'png';

/**
 * Options for image compression.
 */
export interface ImageCompressOptions {
  /**
   * Maximum width boundary for the output image.
   * Default: 1280
   */
  maxWidth?: number;

  /**
   * Maximum height boundary for the output image.
   * Default: 1280
   */
  maxHeight?: number;

  /**
   * JPEG compression quality (0.0 - 1.0).
   * Ignored when output is 'png'.
   * Default: 0.8
   */
  quality?: number;

  /**
   * Output image format.
   * Default: 'jpg'
   */
  output?: ImageOutputType;

  /**
   * Input type.
   * Default: 'uri'
   */
  input?: 'uri' | 'base64';
}
