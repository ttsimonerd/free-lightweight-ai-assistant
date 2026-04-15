package com.homeai.assistant.service

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.homeai.assistant.R
import com.homeai.assistant.audio.SpeechInputManager
import com.homeai.assistant.audio.SpeechOutputManager
import com.homeai.assistant.camera.CameraController
import com.homeai.assistant.llm.LocalAssistant
import com.homeai.assistant.ui.MainActivity
import kotlinx.coroutines.*

/**
 * HomeAIService – foreground service that keeps the AI assistant running 24/7.
 *
 * Responsibilities:
 *  - Acquires a partial wake lock to prevent CPU sleep.
 *  - Manages the full assistant pipeline: camera → person detection → STT → LLM → TTS.
 *  - Broadcasts camera state changes back to MainActivity.
 *  - Responds to ACTION_TOGGLE_CAMERA intents from the UI.
 */
class HomeAIService : Service() {

    companion object {
        private const val TAG = "HomeAIService"

        /** Notification channel ID */
        private const val CHANNEL_ID = "home_ai_channel"

        /** Notification ID for the persistent foreground notification */
        private const val NOTIFICATION_ID = 101

        /** Broadcast: request camera toggle */
        const val ACTION_TOGGLE_CAMERA = "com.homeai.assistant.TOGGLE_CAMERA"

        /** Broadcast: camera facing changed */
        const val ACTION_CAMERA_FACING = "com.homeai.assistant.CAMERA_FACING"
        const val EXTRA_IS_FRONT       = "is_front"
    }

    // ─────────────────────────────────────────────────────────────
    // Fields
    // ─────────────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var wakeLock: PowerManager.WakeLock

    private lateinit var speechInput: SpeechInputManager
    private lateinit var speechOutput: SpeechOutputManager
    private lateinit var cameraController: CameraController
    private lateinit var assistant: LocalAssistant

    /** Conversation history (SYSTEM prompt + alternating USER/ASSISTANT messages) */
    private val conversationHistory = mutableListOf<LocalAssistant.Message>().also {
        it.add(
            LocalAssistant.Message(
                role = LocalAssistant.Role.SYSTEM,
                content = LocalAssistant.SYSTEM_PROMPT
            )
        )
    }

    private var isPersonPresent = false
    private var hasGreeted = false
    private var isProcessingInput = false

    // ─────────────────────────────────────────────────────────────
    // Broadcast receiver for camera toggle
    // ─────────────────────────────────────────────────────────────

    private val toggleCameraReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TOGGLE_CAMERA) {
                cameraController.toggleCamera()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Service lifecycle
    // ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "HomeAIService created")

        createNotificationChannel()
        acquireWakeLock()
        registerCameraToggleReceiver()

        // Initialise modules
        assistant      = LocalAssistant()
        speechOutput   = SpeechOutputManager(this).also { it.init() }
        speechInput    = SpeechInputManager(this).also {
            it.callback = speechCallback
            it.init()
        }

        // CameraController needs a LifecycleOwner – we use a custom owner
        cameraController = CameraController(
            context      = this,
            lifecycleOwner = ServiceLifecycleOwner(),
            callback     = cameraCallback
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "HomeAIService onStartCommand")

        // Start as foreground service immediately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        cameraController.start()

        return START_STICKY   // restart automatically if killed
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "HomeAIService destroyed")

        serviceScope.cancel()
        cameraController.stop()
        speechInput.destroy()
        speechOutput.destroy()
        assistant.close()
        releaseWakeLock()
        unregisterReceiver(toggleCameraReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────────
    // Camera callback
    // ─────────────────────────────────────────────────────────────

    private val cameraCallback = object : CameraController.CameraCallback {

        override fun onLensFacingChanged(isFront: Boolean) {
            Log.d(TAG, "Lens facing changed: ${if (isFront) "FRONT" else "BACK"}")
            // Notify MainActivity to update the UI label
            sendBroadcast(Intent(ACTION_CAMERA_FACING).apply {
                putExtra(EXTRA_IS_FRONT, isFront)
            })
        }

        override fun onPersonPresenceChanged(personPresent: Boolean) {
            if (personPresent == isPersonPresent) return   // no change

            isPersonPresent = personPresent
            Log.d(TAG, "Person present: $personPresent")

            if (personPresent) {
                onPersonArrived()
            } else {
                onPersonLeft()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Speech callback
    // ─────────────────────────────────────────────────────────────

    private val speechCallback = object : SpeechInputManager.SpeechCallback {

        override fun onSpeechResult(text: String, isFinal: Boolean) {
            if (!isFinal) return

            Log.d(TAG, "User said: $text")
            if (!isProcessingInput) {
                isProcessingInput = true
                handleUserInput(text)
            }
        }

        override fun onListeningStarted() {
            Log.d(TAG, "Listening started")
        }

        override fun onListeningEnded() {
            Log.d(TAG, "Listening ended")
        }

        override fun onError(errorCode: Int) {
            Log.w(TAG, "STT error: $errorCode")
            isProcessingInput = false
            // Restart listening after a short delay
            serviceScope.launch {
                delay(1000)
                if (isPersonPresent) speechInput.startListening()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Conversation logic
    // ─────────────────────────────────────────────────────────────

    private fun onPersonArrived() {
        if (!hasGreeted) {
            hasGreeted = true
            serviceScope.launch {
                delay(800)    // brief pause before greeting
                val greeting = "Hola, estoy aquí si necesitas algo."
                speechOutput.speak(greeting)
                appendToHistory(LocalAssistant.Role.ASSISTANT, greeting)
                delay(2000)
                speechInput.startListening()
            }
        } else {
            serviceScope.launch {
                delay(500)
                speechInput.startListening()
            }
        }
    }

    private fun onPersonLeft() {
        speechInput.stopListening()
        speechOutput.stop()
        isProcessingInput = false
    }

    private fun handleUserInput(userText: String) {
        appendToHistory(LocalAssistant.Role.USER, userText)

        serviceScope.launch(Dispatchers.Default) {
            val reply = assistant.generateReply(conversationHistory)
            withContext(Dispatchers.Main) {
                Log.d(TAG, "Assistant reply: $reply")
                appendToHistory(LocalAssistant.Role.ASSISTANT, reply)
                speechOutput.speak(reply)
                isProcessingInput = false
                // Wait for TTS to finish, then listen again
                delay(500 + reply.length * 50L)
                if (isPersonPresent) speechInput.startListening()
            }
        }
    }

    private fun appendToHistory(role: LocalAssistant.Role, content: String) {
        conversationHistory.add(LocalAssistant.Message(role, content))
        // Keep history at a manageable size (system prompt + last 20 turns)
        if (conversationHistory.size > 21) {
            // Remove oldest non-system messages
            val systemMsg = conversationHistory.first()
            while (conversationHistory.size > 21) {
                conversationHistory.removeAt(1)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Asistente IA",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificación del asistente doméstico activo"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Asistente IA")
            .setContentText("Escuchando y detectando personas…")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    // ─────────────────────────────────────────────────────────────
    // Wake lock
    // ─────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HomeAI::WakeLock"
        )
        wakeLock.acquire(/* indefinite – released in onDestroy */)
    }

    private fun releaseWakeLock() {
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Toggle camera receiver
    // ─────────────────────────────────────────────────────────────

    private fun registerCameraToggleReceiver() {
        val filter = IntentFilter(ACTION_TOGGLE_CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(toggleCameraReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(toggleCameraReceiver, filter)
        }
    }
}
