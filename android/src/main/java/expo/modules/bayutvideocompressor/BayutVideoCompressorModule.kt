package expo.modules.bayutvideocompressor

import android.net.Uri
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class BayutVideoCompressorModule : Module() {
    private val activeCompressions = ConcurrentHashMap<String, AtomicBoolean>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun definition() = ModuleDefinition {
        Name("BayutVideoCompressor")

        Events("onCompressProgress", "onBackgroundTaskExpired")

        // MARK: - Compress

        AsyncFunction("video_compress") { fileUrl: String, options: Map<String, Any?> ->
            val uuid = options["uuid"] as? String ?: java.util.UUID.randomUUID().toString()
            val cancelled = AtomicBoolean(false)
            activeCompressions[uuid] = cancelled

            try {
                val context = appContext.reactContext
                    ?: throw IllegalStateException("React context not available")

                val config = VideoCompressor.CompressConfig(
                    videoMaxDimensionLongEdge = (options["videoMaxDimensionLongEdge"] as? Number)?.toInt() ?: 0,
                    videoMaxDimensionShortEdge = (options["videoMaxDimensionShortEdge"] as? Number)?.toInt() ?: 0,
                    videoBitrateTarget = (options["videoBitrateTarget"] as? Number)?.toInt() ?: 0,
                    speed = options["speed"] as? String ?: "ultrafast",
                    videoEncodingMode = options["videoEncodingMode"] as? String ?: "vbr",
                    videoCodec = options["videoCodec"] as? String ?: "h264",
                    videoQuality = (options["videoQuality"] as? Number)?.toFloat() ?: 0.5f,
                    videoBitrateMax = (options["videoBitrateMax"] as? Number)?.toInt() ?: 0,
                    videoKeyframeInterval = (options["videoKeyframeInterval"] as? Number)?.toFloat() ?: 0f,
                    audioCodec = options["audioCodec"] as? String,
                    audioBitrate = (options["audioBitrate"] as? Number)?.toInt() ?: 0,
                )

                val minimumFileSize = (options["minimumFileSizeForCompress"] as? Number)?.toDouble() ?: 0.0

                // Resolve input URI
                val inputUri = Uri.parse(fileUrl)

                // Check minimum file size
                if (minimumFileSize > 0) {
                    val fileSize = getFileSizeFromUri(inputUri)
                    if (fileSize <= minimumFileSize * 1024 * 1024) {
                        return@AsyncFunction fileUrl
                    }
                }

                // Create temp output file
                val outputFile = File(context.cacheDir, "compressed_${uuid}.mp4")

                val compressor = VideoCompressor(context)
                val result = compressor.compress(
                    inputUri = inputUri,
                    outputFile = outputFile,
                    config = config,
                    cancelled = cancelled,
                    listener = object : VideoCompressor.ProgressListener {
                        override fun onProgress(progress: Float) {
                            sendEvent("onCompressProgress", mapOf(
                                "progress" to progress,
                                "uuid" to uuid
                            ))
                        }
                    }
                )

                activeCompressions.remove(uuid)
                "file://$result"
            } catch (e: InterruptedException) {
                activeCompressions.remove(uuid)
                throw e
            } catch (e: Exception) {
                activeCompressions.remove(uuid)
                throw e
            }
        }

        // MARK: - Image Compress

        AsyncFunction("image_compress") { value: String, options: Map<String, Any?> ->
            val context = appContext.reactContext
                ?: throw IllegalStateException("React context not available")

            val config = ImageCompressor.CompressConfig(
                maxWidth = (options["maxWidth"] as? Number)?.toInt() ?: 1280,
                maxHeight = (options["maxHeight"] as? Number)?.toInt() ?: 1280,
                quality = (options["quality"] as? Number)?.toFloat() ?: 0.8f,
                output = options["output"] as? String ?: "jpg",
                input = options["input"] as? String ?: "uri",
            )

            ImageCompressor.compress(value, config, context.cacheDir)
        }

        // MARK: - Cancel

        Function("cancel") { uuid: String ->
            activeCompressions[uuid]?.set(true)
        }

        // MARK: - Get Metadata

        AsyncFunction("getMetadata") { fileUrl: String ->
            val context = appContext.reactContext
                ?: throw IllegalStateException("React context not available")

            val retriever = android.media.MediaMetadataRetriever()
            val uri = Uri.parse(fileUrl)

            try {
                // file:// URIs need setDataSource(path), content:// URIs need setDataSource(context, uri)
                if (uri.scheme == "file") {
                    val path = uri.path ?: throw IllegalArgumentException("Invalid file URI: $fileUrl")
                    retriever.setDataSource(path)
                } else {
                    retriever.setDataSource(context, uri)
                }

                val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val bitrate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0

                val fileSize = getFileSizeFromUri(uri)

                mapOf(
                    "width" to width,
                    "height" to height,
                    "duration" to (duration / 1000.0),
                    "size" to fileSize,
                    "bitrate" to bitrate,
                    "extension" to "mp4"
                )
            } finally {
                retriever.release()
            }
        }

        // MARK: - Background Task (Android is more lenient, but we support the API)

        AsyncFunction("activateBackgroundTask") {
            "android-background-task"
        }

        AsyncFunction("deactivateBackgroundTask") {
            // No-op on Android
        }
    }

    private fun getFileSizeFromUri(uri: Uri): Long {
        if (uri.scheme == "file") {
            val path = uri.path ?: return 0
            return try {
                File(path).length()
            } catch (e: Exception) {
                0
            }
        }

        val context = appContext.reactContext ?: return 0
        return try {
            val fd = context.contentResolver.openFileDescriptor(uri, "r")
            val size = fd?.statSize ?: 0
            fd?.close()
            size
        } catch (e: Exception) {
            0
        }
    }
}
