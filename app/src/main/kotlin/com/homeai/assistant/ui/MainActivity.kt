package com.homeai.assistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.homeai.assistant.databinding.ActivityMainBinding
import com.homeai.assistant.service.HomeAIService

/**
 * MainActivity – fullscreen kiosk-like activity.
 *
 * Responsibilities:
 *  - Immersive sticky fullscreen (no status/nav bar)
 *  - Keep screen on while charging
 *  - Permission requests (Camera, Audio)
 *  - Start HomeAIService
 *  - Host EyesView and camera overlay UI
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    // ─────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen always on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupImmersiveMode()
        setupCameraToggle()

        if (allPermissionsGranted()) {
            startAIService()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setupImmersiveMode()
    }

    override fun onResume() {
        super.onResume()
        setupImmersiveMode()
        // Resume eye animations
        binding.eyesView.resumeAnimations()
    }

    override fun onPause() {
        super.onPause()
        // Pause animations to save CPU when app is backgrounded
        binding.eyesView.pauseAnimations()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                startAIService()
            }
            // If denied we still start service – it will handle gracefully
            else {
                startAIService()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Immersive / fullscreen
    // ─────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun setupImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Camera indicator & toggle
    // ─────────────────────────────────────────────────────────────

    private fun setupCameraToggle() {
        binding.btnToggleCamera.setOnClickListener {
            // Notify the service to switch cameras
            val intent = Intent(HomeAIService.ACTION_TOGGLE_CAMERA)
            sendBroadcast(intent)
        }

        // Observe camera facing state changes from the service via broadcast
        // (registered in onStart / unregistered in onStop)
    }

    /**
     * Called by HomeAIService (via a LocalBroadcast) to update the camera label.
     */
    fun updateCameraLabel(isFront: Boolean) {
        runOnUiThread {
            binding.tvCameraLabel.text = if (isFront) "Cámara frontal" else "Cámara trasera"
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Service
    // ─────────────────────────────────────────────────────────────

    private fun startAIService() {
        val intent = Intent(this, HomeAIService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
