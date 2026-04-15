package com.homeai.assistant.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * PersonDetector – lightweight person presence detection.
 *
 * This implementation uses a simple pixel-activity heuristic as a stub
 * placeholder. It is structured so you can replace [analyzeWithModel] with a
 * real TFLite model (e.g. MobileNet SSD trained on COCO or EfficientDet-Lite0)
 * by dropping a `person_detect.tflite` file into `app/src/main/assets/` and
 * uncommenting the TFLite initialisation block.
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  To plug in a real TFLite model:                                │
 * │  1. Put person_detect.tflite in app/src/main/assets/           │
 * │  2. Uncomment the interpreter init in init{}                   │
 * │  3. Replace analyzeWithModel() with real inference logic       │
 * └─────────────────────────────────────────────────────────────────┘
 */
class PersonDetector {

    companion object {
        private const val TAG = "PersonDetector"
        private const val MODEL_FILENAME = "person_detect.tflite"
        /** Detection confidence threshold (0..1) */
        private const val CONFIDENCE_THRESHOLD = 0.55f
    }

    // ── TFLite interpreter (uncomment to activate) ────────────────
    // private var interpreter: Interpreter? = null
    // init {
    //     try {
    //         val modelBuffer = loadModelFile(context)
    //         val options = Interpreter.Options().apply { setNumThreads(2) }
    //         interpreter = Interpreter(modelBuffer, options)
    //         Log.d(TAG, "TFLite interpreter loaded")
    //     } catch (e: Exception) {
    //         Log.w(TAG, "TFLite model not loaded, falling back to heuristic: ${e.message}")
    //     }
    // }

    /** Previous detected state – reduces noisy callbacks */
    private var lastPersonPresent = false

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Analyze a single camera frame.
     * @return true if a person is likely present.
     */
    fun analyze(imageProxy: ImageProxy): Boolean {
        return try {
            val bitmap = imageProxyToBitmap(imageProxy) ?: return lastPersonPresent
            val result = analyzeWithModel(bitmap)
            lastPersonPresent = result
            result
        } catch (e: Exception) {
            Log.w(TAG, "Detection error: ${e.message}")
            lastPersonPresent
        }
    }

    /** Release any resources held by the detector. */
    fun close() {
        // interpreter?.close()
    }

    // ─────────────────────────────────────────────────────────────
    // Model inference (stub – replace with real TFLite inference)
    // ─────────────────────────────────────────────────────────────

    /**
     * Placeholder heuristic: measures the fraction of non-background pixels
     * in the centre region of the frame. Replace this with real model inference
     * once you add person_detect.tflite to assets.
     */
    private fun analyzeWithModel(bitmap: Bitmap): Boolean {
        // ── Uncomment for real TFLite person detection ────────────
        // interpreter?.let { interp ->
        //     val resized = Bitmap.createScaledBitmap(bitmap, 300, 300, false)
        //     val inputBuffer = TensorImage.fromBitmap(resized)
        //     // ... run inference, parse output tensors for "person" class ...
        //     return detectedScore > CONFIDENCE_THRESHOLD
        // }

        // ── Heuristic stub: simple centre-region activity detection ──
        val cx = bitmap.width / 2
        val cy = bitmap.height / 2
        val regionW = bitmap.width / 4
        val regionH = bitmap.height / 3

        var nonBgPixels = 0
        val total = regionW * regionH

        for (y in (cy - regionH / 2) until (cy + regionH / 2)) {
            for (x in (cx - regionW / 2) until (cx + regionW / 2)) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8)  and 0xFF
                val b =  pixel         and 0xFF
                // Skin tone heuristic: reddish, not too dark, not too bright
                val isSkinLike = r > 80 && r > g + 10 && r > b + 15 && r < 240
                if (isSkinLike) nonBgPixels++
            }
        }
        val skinFraction = nonBgPixels.toFloat() / total.toFloat()
        return skinFraction > 0.06f   // > 6% skin-like pixels → likely a person
    }

    // ─────────────────────────────────────────────────────────────
    // Conversion helpers
    // ─────────────────────────────────────────────────────────────

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return when (imageProxy.format) {
            ImageFormat.JPEG -> {
                val buffer: ByteBuffer = imageProxy.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            ImageFormat.YUV_420_888 -> yuvToBitmap(imageProxy)
            else -> {
                Log.w(TAG, "Unsupported image format: ${imageProxy.format}")
                null
            }
        }
    }

    private fun yuvToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(
                nv21, ImageFormat.NV21,
                imageProxy.width, imageProxy.height, null
            )
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(
                Rect(0, 0, imageProxy.width, imageProxy.height),
                75, out
            )
            val bytes = out.toByteArray()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.w(TAG, "YUV conversion failed: ${e.message}")
            null
        }
    }
}
