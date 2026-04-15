package com.homeai.assistant.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.homeai.assistant.R
import com.homeai.assistant.camera.CameraController
import com.homeai.assistant.databinding.ActivityMainBinding
import com.homeai.assistant.service.HomeAIService

/**
 * MainActivity – fullscreen kiosk activity.
 *
 * Owns the camera (Preview + ImageAnalysis) so the mini preview window works
 * correctly and the toggle button operates without any broadcast round-trip.
 *
 * Communicates with HomeAIService via local broadcasts:
 *  ← ACTION_AI_STATUS  : service tells us its current state
 *  ← ACTION_SUBTITLE   : service sends text to display as subtitles
 *  → ACTION_PERSON_DETECTED : we forward person-detection results to the service
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var cameraController: CameraController? = null

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    // ─────────────────────────────────────────────────────────────
    // Subtitle auto-hide
    // ─────────────────────────────────────────────────────────────

    private val hideSubtitleRunnable = Runnable {
        binding.tvSubtitle.visibility = View.GONE
    }

    // ─────────────────────────────────────────────────────────────
    // Broadcast receiver – listens to service events
    // ─────────────────────────────────────────────────────────────

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                HomeAIService.ACTION_AI_STATUS -> {
                    val status = intent.getStringExtra(HomeAIService.EXTRA_STATUS) ?: "WAITING"
                    updateStatus(status)
                }
                HomeAIService.ACTION_SUBTITLE -> {
                    val text   = intent.getStringExtra(HomeAIService.EXTRA_SUBTITLE_TEXT) ?: return
                    val isUser = intent.getBooleanExtra(HomeAIService.EXTRA_SUBTITLE_IS_USER, false)
                    showSubtitle(text, isUser)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupImmersiveMode()

        // Toggle button calls the camera controller directly – no broadcast needed
        binding.btnToggleCamera.setOnClickListener {
            cameraController?.toggleCamera()
        }

        if (allPermissionsGranted()) {
            startCamera()
            startAIService()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(HomeAIService.ACTION_AI_STATUS)
            addAction(HomeAIService.ACTION_SUBTITLE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(serviceReceiver)
    }

    override fun onResume() {
        super.onResume()
        setupImmersiveMode()
        binding.eyesView.resumeAnimations()
    }

    override fun onPause() {
        super.onPause()
        binding.eyesView.pauseAnimations()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraController?.stop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setupImmersiveMode()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            startCamera()
            startAIService()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Camera
    // ─────────────────────────────────────────────────────────────

    private fun startCamera() {
        cameraController = CameraController(
            context        = this,
            lifecycleOwner = this,
            previewView    = binding.previewView,
            callback       = object : CameraController.CameraCallback {

                override fun onLensFacingChanged(isFront: Boolean) {
                    runOnUiThread {
                        binding.tvCameraLabel.text =
                            if (isFront) getString(R.string.camera_front)
                            else         getString(R.string.camera_back)
                    }
                }

                override fun onPersonPresenceChanged(personPresent: Boolean) {
                    // Forward detection result to the service
                    sendBroadcast(
                        Intent(HomeAIService.ACTION_PERSON_DETECTED).apply {
                            putExtra(HomeAIService.EXTRA_PERSON_PRESENT, personPresent)
                        }
                    )
                }
            }
        ).also { it.start() }
    }

    // ─────────────────────────────────────────────────────────────
    // Status indicator
    // ─────────────────────────────────────────────────────────────

    private fun updateStatus(status: String) {
        val (text, colorHex) = when (status) {
            "LISTENING" -> "Escuchando..." to "#4499FF"
            "THINKING"  -> "Pensando..."   to "#FFAA33"
            "SPEAKING"  -> "Hablando..."   to "#00FF99"
            else        -> "Esperando..."  to "#888888"
        }
        binding.tvStatus.text = text
        binding.tvStatus.setTextColor(Color.parseColor(colorHex))
    }

    // ─────────────────────────────────────────────────────────────
    // Subtitles
    // ─────────────────────────────────────────────────────────────

    private fun showSubtitle(text: String, isUser: Boolean) {
        val prefix = if (isUser) "Tú: " else "IA: "
        val color  = if (isUser) Color.WHITE else Color.parseColor("#00FF99")
        binding.tvSubtitle.text = "$prefix$text"
        binding.tvSubtitle.setTextColor(color)
        binding.tvSubtitle.visibility = View.VISIBLE

        // Auto-hide: longer for AI speech, shorter for user input
        binding.tvSubtitle.removeCallbacks(hideSubtitleRunnable)
        val ms = if (isUser) 4000L else (2500L + text.length * 55L).coerceAtMost(12000L)
        binding.tvSubtitle.postDelayed(hideSubtitleRunnable, ms)
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
    // Immersive mode
    // ─────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun setupImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
