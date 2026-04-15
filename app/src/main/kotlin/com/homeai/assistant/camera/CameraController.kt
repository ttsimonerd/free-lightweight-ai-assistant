package com.homeai.assistant.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraController – wraps CameraX to provide camera preview and image analysis.
 *
 * Supports toggling between front and back cameras.
 * Notifies callers via [CameraCallback] when the lens facing changes or a
 * person is detected.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val callback: CameraCallback
) {

    interface CameraCallback {
        fun onLensFacingChanged(isFront: Boolean)
        fun onPersonPresenceChanged(personPresent: Boolean)
    }

    companion object {
        private const val TAG = "CameraController"
    }

    /** Background thread for image analysis */
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** Currently selected lens */
    private var currentLensFacing: Int = CameraSelector.LENS_FACING_FRONT

    /** PersonDetector instance */
    private val personDetector = PersonDetector()

    private var cameraProvider: ProcessCameraProvider? = null

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /** Bind the camera to the provided lifecycle. */
    fun start() {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
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

    /** Release camera resources. */
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

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(currentLensFacing)
            .build()

        // Image analysis use case – feeds frames to PersonDetector
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(320, 240))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    val personPresent = personDetector.analyze(imageProxy)
                    callback.onPersonPresenceChanged(personPresent)
                    imageProxy.close()
                }
            }

        try {
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                imageAnalysis
            )
            val isFront = currentLensFacing == CameraSelector.LENS_FACING_FRONT
            callback.onLensFacingChanged(isFront)
            Log.d(TAG, "Camera bound: ${if (isFront) "FRONT" else "BACK"}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera", e)
        }
    }
}
