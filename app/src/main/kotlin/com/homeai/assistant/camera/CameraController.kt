package com.homeai.assistant.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraController – wraps CameraX with both a live Preview and ImageAnalysis.
 *
 * Bound to the caller's [LifecycleOwner] (the Activity), so CameraX manages
 * the camera lifecycle automatically and the preview surface is valid.
 *
 * @param previewView Optional [PreviewView] to display the camera feed.
 *                    When provided, a Preview use case is added automatically.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val callback: CameraCallback,
    private val previewView: PreviewView? = null
) {

    interface CameraCallback {
        fun onLensFacingChanged(isFront: Boolean)
        fun onPersonPresenceChanged(personPresent: Boolean)
    }

    companion object {
        private const val TAG = "CameraController"
    }

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var currentLensFacing: Int = CameraSelector.LENS_FACING_FRONT
    private val personDetector = PersonDetector()
    private var cameraProvider: ProcessCameraProvider? = null

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(context))
    }

    /** Toggle between front and back camera. */
    fun toggleCamera() {
        currentLensFacing = if (currentLensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        bindCamera()
    }

    fun stop() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
        personDetector.close()
    }

    // ─────────────────────────────────────────────────────────────
    // Internal
    // ─────────────────────────────────────────────────────────────

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val selector = CameraSelector.Builder()
            .requireLensFacing(currentLensFacing)
            .build()

        // ImageAnalysis – person detection runs on background thread
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(320, 240))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, personAnalyzer) }

        try {
            if (previewView != null) {
                // Preview + ImageAnalysis bound together to the same lifecycle
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                provider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis)
            } else {
                provider.bindToLifecycle(lifecycleOwner, selector, imageAnalysis)
            }

            val isFront = currentLensFacing == CameraSelector.LENS_FACING_FRONT
            callback.onLensFacingChanged(isFront)
            Log.d(TAG, "Camera bound: ${if (isFront) "FRONT" else "BACK"}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera", e)
        }
    }

    private val personAnalyzer = ImageAnalysis.Analyzer { imageProxy ->
        val personPresent = personDetector.analyze(imageProxy)
        callback.onPersonPresenceChanged(personPresent)
        imageProxy.close()
    }
}
