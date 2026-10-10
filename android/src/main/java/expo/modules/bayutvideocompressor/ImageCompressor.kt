package expo.modules.bayutvideocompressor

import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Image compressor: load → orient → resize → compress → EXIF copy → save.
 */
object ImageCompressor {
    private const val TAG = "ImageCompressor"

    // EXIF tags to preserve from source image
    private val EXIF_TAGS = arrayOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_IMAGE_LENGTH,
        ExifInterface.TAG_IMAGE_WIDTH,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_WHITE_BALANCE,
    )

    data class CompressConfig(
        val maxWidth: Int = 1280,
        val maxHeight: Int = 1280,
        val quality: Float = 0.8f,
        val output: String = "jpg",
        val input: String = "uri",
    )

    /**
     * Main entry point: compress an image.
     * Returns the URI of the compressed image, or the original if compression didn't reduce size.
     */
    fun compress(value: String, config: CompressConfig, cacheDir: File): String {
        Log.i(TAG, "Compressing image: maxWidth=${config.maxWidth}, maxHeight=${config.maxHeight}, quality=${config.quality}, output=${config.output}")

        // 1. Load bitmap
        val imagePath = resolveFilePath(value, config.input)
        val originalBitmap = if (config.input == "base64") {
            decodeBase64(value)
        } else {
            loadFromPath(imagePath!!)
        } ?: throw IllegalArgumentException("Failed to load image from: $value")

        // 2. Correct orientation
        val orientedBitmap = if (config.input == "uri" && imagePath != null) {
            correctOrientation(originalBitmap, imagePath)
        } else {
            originalBitmap
        }

        // 3. Resize
        val resizedBitmap = resize(orientedBitmap, config.maxWidth, config.maxHeight)

        // 4. Compress to bytes
        val outputStream = compressBitmap(resizedBitmap, config.output, config.quality)

        // 5. Write to cache file
        val extension = if (config.output == "png") "png" else "jpg"
        val outputFile = File(cacheDir, "compressed_img_${System.currentTimeMillis()}.$extension")
        FileOutputStream(outputFile).use { fos ->
            outputStream.writeTo(fos)
        }

        // 6. Copy EXIF metadata
        if (config.input == "uri" && imagePath != null) {
            copyExifInfo(imagePath, outputFile.absolutePath)
        }

        // 7. Check if compressed is smaller than original
        if (config.input == "uri" && imagePath != null) {
            val originalSize = File(imagePath).length()
            val compressedSize = outputFile.length()
            if (compressedSize >= originalSize) {
                Log.i(TAG, "Compressed ($compressedSize) >= original ($originalSize), returning original")
                outputFile.delete()
                return "file://$imagePath"
            }
        }

        val resultUri = "file://${outputFile.absolutePath}"
        Log.i(TAG, "Compressed to: $resultUri (${outputFile.length()} bytes)")
        return resultUri
    }

    private fun resolveFilePath(value: String, input: String): String? {
        if (input == "base64") return null
        val uri = Uri.parse(value)
        return uri.path
    }

    private fun decodeBase64(value: String): Bitmap? {
        val cleanValue = value.replace(Regex("^data:image/.*;(?:charset=.{3,5};)?base64,"), "")
        val data = Base64.decode(cleanValue, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(data, 0, data.size)
    }

    private fun loadFromPath(path: String): Bitmap? {
        return BitmapFactory.decodeFile(path)
    }

    private fun correctOrientation(bitmap: Bitmap, imagePath: String): Bitmap {
        return try {
            val exif = ExifInterface(imagePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                else -> return bitmap
            }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to correct orientation", e)
            bitmap
        }
    }

    private fun resize(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()

        // Only resize if image exceeds bounds
        if (width <= maxWidth && height <= maxHeight) return bitmap

        val scale: Float = if (width > height) {
            maxWidth.toFloat() / width
        } else {
            maxHeight.toFloat() / height
        }

        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        val scaledBitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaledBitmap)
        val scaleMatrix = Matrix()
        scaleMatrix.setScale(scale, scale, 0f, 0f)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        paint.isDither = true
        paint.isAntiAlias = true
        paint.isFilterBitmap = true
        canvas.drawBitmap(bitmap, scaleMatrix, paint)
        return scaledBitmap
    }

    private fun compressBitmap(bitmap: Bitmap, output: String, quality: Float): ByteArrayOutputStream {
        val stream = ByteArrayOutputStream()
        if (output == "png") {
            bitmap.compress(CompressFormat.PNG, 100, stream)
        } else {
            bitmap.compress(CompressFormat.JPEG, (quality * 100).toInt(), stream)
        }
        return stream
    }

    private fun copyExifInfo(sourcePath: String, destPath: String) {
        try {
            val sourceExif = ExifInterface(sourcePath)
            val destExif = ExifInterface(destPath)
            for (tag in EXIF_TAGS) {
                val destValue = destExif.getAttribute(tag)
                if (destValue == null) {
                    val sourceValue = sourceExif.getAttribute(tag)
                    if (sourceValue != null) {
                        destExif.setAttribute(tag, sourceValue)
                    }
                }
            }
            destExif.saveAttributes()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy EXIF info", e)
        }
    }
}
