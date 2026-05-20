package com.example.rokidglasses.service.photo

import android.bluetooth.BluetoothSocket
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import com.example.rokidcommon.protocol.photo.PhotoTransferConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Image Compressor for Photo Transfer
 * 
 * Handles image compression and optimization before Bluetooth transfer.
 * Targets 720p resolution with JPEG quality 70 for optimal transfer speed.
 * 
 * Typical compression results:
 * - 1080p (2MP) photo: ~500KB → ~150KB
 * - 720p photo: ~300KB → ~80KB
 * - Transfer time: ~150KB / 50KB/s = ~3 seconds
 */
object ImageCompressor {
    
    private const val TAG = "ImageCompressor"
    
    /**
     * Compress image data for transfer.
     * 
     * @param imageData Raw image data (JPEG, PNG, etc.)
     * @param targetWidth Target width (default 1280 for 720p)
     * @param targetHeight Target height (default 720 for 720p)
     * @param quality JPEG quality (0-100, default 70)
     * @param maxSize Maximum output size in bytes (default 200KB)
     * @return Compressed JPEG data
     */
    suspend fun compressForTransfer(
        imageData: ByteArray,
        targetWidth: Int = PhotoTransferConstants.TARGET_IMAGE_WIDTH,
        targetHeight: Int = PhotoTransferConstants.TARGET_IMAGE_HEIGHT,
        quality: Int = PhotoTransferConstants.JPEG_QUALITY,
        maxSize: Int = PhotoTransferConstants.MAX_COMPRESSED_SIZE,
        centerCropToTargetAspect: Boolean = false,
        zoomFactor: Float = 1.0f
    ): ByteArray = withContext(Dispatchers.Default) {
        
        Log.d(TAG, "Compressing image: input=${imageData.size} bytes")
        
        // Decode original image
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(imageData, 0, imageData.size, options)
        
        val originalWidth = options.outWidth
        val originalHeight = options.outHeight
        
        Log.d(TAG, "Original dimensions: ${originalWidth}x${originalHeight}")
        
        // Calculate sample size for memory efficiency
        options.inSampleSize = calculateInSampleSize(
            originalWidth, originalHeight,
            targetWidth, targetHeight
        )
        options.inJustDecodeBounds = false
        
        // Decode with sample size
        val sampledBitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size, options)
            ?: throw IllegalArgumentException("Failed to decode image")
        
        val preparedBitmap = if (centerCropToTargetAspect || zoomFactor > 1.0f) {
            centerCropBitmap(
                sampledBitmap,
                targetAspectRatio = targetWidth.toFloat() / targetHeight.toFloat(),
                zoomFactor = zoomFactor
            )
        } else {
            sampledBitmap
        }

        // Scale to exact target size while maintaining aspect ratio
        val scaledBitmap = if (centerCropToTargetAspect || zoomFactor > 1.0f) {
            Bitmap.createScaledBitmap(preparedBitmap, targetWidth, targetHeight, true)
        } else {
            scaleBitmap(preparedBitmap, targetWidth, targetHeight)
        }
        
        if (sampledBitmap != preparedBitmap) {
            sampledBitmap.recycle()
        }
        if (preparedBitmap != scaledBitmap) {
            preparedBitmap.recycle()
        }
        
        // Compress to JPEG with adaptive quality
        var currentQuality = quality
        var compressedData: ByteArray
        
        do {
            compressedData = compressBitmapToJpeg(scaledBitmap, currentQuality)
            
            if (compressedData.size > maxSize && currentQuality > 30) {
                currentQuality -= 10
                Log.d(TAG, "Reducing quality to $currentQuality (size=${compressedData.size})")
            } else {
                break
            }
        } while (compressedData.size > maxSize)
        
        // Recycle bitmap
        scaledBitmap.recycle()
        
        Log.d(TAG, "Compressed: ${imageData.size} → ${compressedData.size} bytes " +
                   "(${100 - (compressedData.size * 100 / imageData.size)}% reduction)")
        
        compressedData
    }
    
    /**
     * Compress a Bitmap directly.
     */
    suspend fun compressBitmap(
        bitmap: Bitmap,
        quality: Int = PhotoTransferConstants.JPEG_QUALITY,
        targetWidth: Int = PhotoTransferConstants.TARGET_IMAGE_WIDTH,
        targetHeight: Int = PhotoTransferConstants.TARGET_IMAGE_HEIGHT
    ): ByteArray = withContext(Dispatchers.Default) {
        
        val scaledBitmap = scaleBitmap(bitmap, targetWidth, targetHeight)
        val compressed = compressBitmapToJpeg(scaledBitmap, quality)
        
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }
        
        compressed
    }
    
    /**
     * Calculate optimal inSampleSize for BitmapFactory.
     */
    private fun calculateInSampleSize(
        actualWidth: Int,
        actualHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Int {
        var inSampleSize = 1
        
        if (actualHeight > targetHeight || actualWidth > targetWidth) {
            val halfHeight = actualHeight / 2
            val halfWidth = actualWidth / 2
            
            while ((halfHeight / inSampleSize) >= targetHeight &&
                   (halfWidth / inSampleSize) >= targetWidth) {
                inSampleSize *= 2
            }
        }
        
        return inSampleSize
    }
    
    /**
     * Scale bitmap to target dimensions while maintaining aspect ratio.
     */
    private fun scaleBitmap(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        // Calculate scale to fit within target dimensions
        val scaleWidth = targetWidth.toFloat() / width
        val scaleHeight = targetHeight.toFloat() / height
        val scale = minOf(scaleWidth, scaleHeight)
        
        // If already smaller, return original
        if (scale >= 1.0f) {
            return bitmap
        }
        
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Center-crop to the desired aspect ratio, then crop tighter for an optical-style zoom.
     */
    private fun centerCropBitmap(
        bitmap: Bitmap,
        targetAspectRatio: Float,
        zoomFactor: Float
    ): Bitmap {
        val safeZoom = zoomFactor.coerceAtLeast(1.0f)
        val sourceWidth = bitmap.width
        val sourceHeight = bitmap.height
        val sourceAspectRatio = sourceWidth.toFloat() / sourceHeight.toFloat()

        var cropWidth = sourceWidth
        var cropHeight = sourceHeight
        if (sourceAspectRatio > targetAspectRatio) {
            cropWidth = (sourceHeight * targetAspectRatio).toInt()
        } else {
            cropHeight = (sourceWidth / targetAspectRatio).toInt()
        }

        cropWidth = (cropWidth / safeZoom).toInt().coerceIn(1, sourceWidth)
        cropHeight = (cropHeight / safeZoom).toInt().coerceIn(1, sourceHeight)

        val left = ((sourceWidth - cropWidth) / 2).coerceAtLeast(0)
        val top = ((sourceHeight - cropHeight) / 2).coerceAtLeast(0)

        Log.d(TAG, "Center crop zoom: ${sourceWidth}x${sourceHeight} -> ${cropWidth}x${cropHeight}, zoom=$safeZoom")
        return Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
    }
    
    /**
     * Compress bitmap to JPEG byte array.
     */
    private fun compressBitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        return outputStream.toByteArray()
    }
    
    /**
     * Rotate image if needed based on EXIF data.
     * 
     * @param imageData Original image data
     * @param rotationDegrees Rotation in degrees (0, 90, 180, 270)
     * @return Rotated image data
     */
    suspend fun rotateImage(
        imageData: ByteArray,
        rotationDegrees: Int
    ): ByteArray = withContext(Dispatchers.Default) {
        
        if (rotationDegrees == 0) {
            return@withContext imageData
        }
        
        val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
            ?: return@withContext imageData
        
        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
        }
        
        val rotatedBitmap = Bitmap.createBitmap(
            bitmap, 0, 0, 
            bitmap.width, bitmap.height, 
            matrix, true
        )
        
        val output = ByteArrayOutputStream()
        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
        
        bitmap.recycle()
        if (rotatedBitmap != bitmap) {
            rotatedBitmap.recycle()
        }
        
        output.toByteArray()
    }
    
    /**
     * Get image dimensions without decoding.
     */
    fun getImageDimensions(imageData: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(imageData, 0, imageData.size, options)
        return Pair(options.outWidth, options.outHeight)
    }
    
    /**
     * Estimate transfer time based on image size.
     * Assumes ~50 KB/s Bluetooth SPP throughput.
     */
    fun estimateTransferTimeMs(imageSize: Int): Long {
        val throughputBytesPerMs = 50.0 // ~50 KB/s = 50 bytes/ms
        return (imageSize / throughputBytesPerMs).toLong()
    }
}
