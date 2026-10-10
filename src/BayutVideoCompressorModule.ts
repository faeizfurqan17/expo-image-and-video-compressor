import { NativeModule, requireNativeModule } from 'expo';

import type { BayutVideoCompressorModuleEvents, CompressOptions, ImageCompressOptions, VideoMetadata } from './BayutVideoCompressor.types';

declare class BayutVideoCompressorModule extends NativeModule<BayutVideoCompressorModuleEvents> {
  /**
   * Compress a video file with hardware acceleration.
   * @param fileUrl - File URI of the video to compress
   * @param options - Compression options
   * @returns Promise resolving to the compressed file URI
   */
  video_compress(fileUrl: string, options: CompressOptions): Promise<string>;

  /**
   * Compress an image file.
   * @param value - File URI or base64 string
   * @param options - Image compression options
   * @returns Promise resolving to the compressed file URI
   */
  image_compress(value: string, options: ImageCompressOptions): Promise<string>;

  /**
   * Cancel an ongoing compression.
   * @param uuid - The cancellation ID from getCancellationId callback
   */
  cancel(uuid: string): void;

  /**
   * Get metadata for a video file.
   * @param fileUrl - File URI of the video
   * @returns Promise resolving to video metadata
   */
  getMetadata(fileUrl: string): Promise<VideoMetadata>;

  /**
   * Activate a background task to keep compression running when app is backgrounded.
   * iOS only - uses UIApplication background task.
   */
  activateBackgroundTask(): Promise<string>;

  /**
   * Deactivate the background task.
   */
  deactivateBackgroundTask(): Promise<void>;
}

// This call loads the native module object from the JSI.
export default requireNativeModule<BayutVideoCompressorModule>('BayutVideoCompressor');
