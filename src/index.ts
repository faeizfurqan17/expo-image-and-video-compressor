import { type EventSubscription } from 'react-native';
import BayutVideoCompressorModule from './BayutVideoCompressorModule';
import type { CompressOptions, CompressionProgressPayload, ImageCompressOptions } from './BayutVideoCompressor.types';

export * from './BayutVideoCompressor.types';

/**
 * Compress a video with hardware-accelerated encoding.
 *
 * @param fileUrl - File URI of the video to compress
 * @param options - Compression options
 * @param onProgress - Optional progress callback (0.0 - 1.0)
 * @returns Promise resolving to the compressed file URI
 *
 * @example
 * ```ts
 * import { compressVideo } from 'expo-image-and-video-compressor';
 *
 * const result = await compressVideo(videoUri, {
 *   bitrate: 2_500_000,
 *   videoMaxDimensionLongEdge: 1080,
 *   videoCodec: 'h264',
 *   speed: 'ultrafast',
 * }, (progress) => {
 *   console.log(`${Math.round(progress * 100)}%`);
 * });
 * ```
 */
export async function compressVideo(
    fileUrl: string,
    options?: CompressOptions,
    onProgress?: (progress: number) => void,
): Promise<string> {
    let subscription: EventSubscription | undefined;
    const uuid = options?.uuid || Math.random().toString(36).substring(7);

    try {
        const opts: CompressOptions = {
            videoCodec: 'h264',
            speed: 'ultrafast',
            videoEncodingMode: 'vbr',
            minimumFileSizeForCompress: 0,
            progressDivider: 0,
            ...options,
            uuid,
        };

        if (onProgress) {
            subscription = BayutVideoCompressorModule.addListener(
                'onCompressProgress',
                (event: CompressionProgressPayload) => {
                    if (event.uuid === uuid) {
                        onProgress(event.progress);
                    }
                },
            );
        }

        if (opts.getCancellationId) {
            opts.getCancellationId(uuid);
        }

        const result = await BayutVideoCompressorModule.video_compress(fileUrl, opts);
        return result;
    } finally {
        subscription?.remove();
    }
}

/**
 * Compress an image file.
 *
 * @param fileUrl - File URI of the image to compress
 * @param options - Image compression options (maxWidth, maxHeight, quality)
 * @returns Promise resolving to the compressed file URI
 *
 * @example
 * ```ts
 * import { compressImage } from 'expo-image-and-video-compressor';
 *
 * const result = await compressImage(imageUri, {
 *   maxWidth: 1080,
 *   maxHeight: 1920,
 *   quality: 0.8,
 * });
 * ```
 */
export async function compressImage(
    fileUrl: string,
    options?: ImageCompressOptions,
): Promise<string> {
    if (!fileUrl) {
        throw new Error('Image URI is empty, please provide a value for compression.');
    }

    const opts: ImageCompressOptions = {
        maxWidth: 1280,
        maxHeight: 1280,
        quality: 0.8,
        output: 'jpg',
        input: 'uri',
        ...options,
    };

    return BayutVideoCompressorModule.image_compress(fileUrl, opts);
}

/**
 * Cancel an ongoing compression.
 * @param uuid - The cancellation ID received from getCancellationId
 */
export function cancel(uuid: string): void {
    BayutVideoCompressorModule.cancel(uuid);
}

/**
 * Get metadata for a video file.
 * @param fileUrl - File URI of the video
 */
export function getMetadata(fileUrl: string) {
    return BayutVideoCompressorModule.getMetadata(fileUrl);
}

/**
 * Activate a background task to keep compression running when app is backgrounded.
 * @param onExpired - Optional callback when the background task expires
 */
export async function activateBackgroundTask(onExpired?: () => void): Promise<string> {
    if (onExpired) {
        const sub = BayutVideoCompressorModule.addListener('onBackgroundTaskExpired', () => {
            onExpired();
            sub.remove();
        });
    }
    return BayutVideoCompressorModule.activateBackgroundTask();
}

/**
 * Deactivate the background task.
 */
export function deactivateBackgroundTask(): Promise<void> {
    BayutVideoCompressorModule.removeAllListeners('onBackgroundTaskExpired');
    return BayutVideoCompressorModule.deactivateBackgroundTask();
}
