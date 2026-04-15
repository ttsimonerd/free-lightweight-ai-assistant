package com.homeai.assistant.service

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.homeai.assistant.R
import com.homeai.assistant.audio.SpeechInputManager
import com.homeai.assistant.audio.SpeechOutputManager
import com.homeai.assistant.llm.LocalAssistant
import com.homeai.assistant.ui.MainActivity
import kotlinx.coroutines.*

/**
 * HomeAIService – foreground service that keeps the AI pipeline running 24/7.
 *
 * The camera is now owned by MainActivity so the live preview works correctly.
 * This service receives person-detection events from MainActivity via broadcast,
 * and broadcasts status + subtitle events back to the activity for display.
 *
 * Broadcast contract:
 *   Inbound  (MainActivity → Service)
 *     ACTION_PERSON_DETECTED   extra: EXTRA_PERSON_PRESENT (Boolean)
 *
 *   Outbound (Service → MainActivity)
 *     ACTION_AI_STATUS         extra: EXTRA_STATUS ("WAITING"|"LISTENING"|"THINKING"|"SPEAKING")
 *     ACTION_SUBTITLE          extras: EXTRA_SUBTITLE_TEXT (String), EXTRA_SUBTITLE_IS_USER (Boolean)
 */
class HomeAIService : Service() {

    companion object {
        private const val TAG            = "HomeAIService"
        private const val CHANNEL_ID     = "home_ai_channel"
        private const val NOTIFICATION_ID = 101

        // ── Inbound ──────────────────────────────────────────────
        const val ACTION_PERSON_DETECTED  = "com.homeai.assistant.PERSON_DETECTED"
        const val EXTRA_PERSON_PRESENT    = "present"

        // ── Outbound ─────────────────────────────────────────────
        const val ACTION_AI_STATUS        = "com.homeai.assistant.AI_STATUS"
        const val EXTRA_STATUS            = "status"

        const val ACTION_SUBTITLE         = "com.homeai.assistant.SUBTITLE"
        const val EXTRA_SUBTITLE_TEXT     = "text"
        const val EXTRA_SUBTITLE_IS_USER  = "is_user"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var wakeLock:     PowerManager.WakeLock
    private lateinit var speechInput:  SpeechInputManager
    private lateinit var speechOutput: SpeechOutputManager
    private lateinit var assistant:    LocalAssistant

    private val conversationHistory = mutableListOf<LocalAssistant.Message>().also {
        it.add(LocalAssistant.Message(LocalAssistant.Role.SYSTEM, LocalAssistant.SYSTEM_PROMPT))
    }

    private var isPersonPresent   = false
    private var hasGreeted        = false
    private var isProcessingInput = false

    // ─────────────────────────────────────────────────────────────
    // Person-detection receiver (from MainActivity camera)
    // ─────────────────────────────────────────────────────────────

    private val personReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val present = intent?.getBooleanExtra(EXTRA_PERSON_PRESENT, false) ?: return
            if (present == isPersonPresent) return
            isPersonPresent = present
            Log.d(TAG, "Person present: $present")
            if (present) onPersonArrived() else onPersonLeft()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()

        assistant    = LocalAssistant()
        speechOutput = SpeechOutputManager(this).also { it.init() }
        speechInput  = SpeechInputManager(this).also {
            it.callback = speechCallback
            it.init()
        }

        val filter = IntentFilter(ACTION_PERSON_DETECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(personReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(personReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        speechInput.destroy()
        speechOutput.destroy()
        assistant.close()
        releaseWakeLock()
        unregisterReceiver(personReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────────
    // Conversation logic
    // ─────────────────────────────────────────────────────────────

    private fun onPersonArrived() {
        if (!hasGreeted) {
            hasGreeted = true
            serviceScope.launch {
                delay(800)
                val greeting = "Hola, estoy aquí si necesitas algo."
                broadcastSubtitle(greeting, isUser = false)
                speechOutput.speak(greeting)
                appendToHistory(LocalAssistant.Role.ASSISTANT, greeting)
                delay(2200)
                startListening()
            }
        } else {
            serviceScope.launch {
                delay(500)
                startListening()
            }
        }
    }

    private fun onPersonLeft() {
        speechInput.stopListening()
        speechOutput.stop()
        isProcessingInput = false
        broadcastStatus("WAITING")
    }

    private val speechCallback = object : SpeechInputManager.SpeechCallback {

        override fun onSpeechResult(text: String, isFinal: Boolean) {
            if (!isFinal) return
            Log.d(TAG, "User said: $text")
            broadcastSubtitle(text, isUser = true)
            if (!isProcessingInput) {
                isProcessingInput = true
                handleUserInput(text)
            }
        }

        override fun onListeningStarted() { broadcastStatus("LISTENING") }

        override fun onListeningEnded() {
            if (!isProcessingInput) broadcastStatus("WAITING")
        }

        override fun onError(errorCode: Int) {
            Log.w(TAG, "STT error: $errorCode")
            isProcessingInput = false
            broadcastStatus("WAITING")
            serviceScope.launch {
                delay(1200)
                if (isPersonPresent) startListening()
            }
        }
    }

    private fun startListening() {
        broadcastStatus("LISTENING")
        speechInput.startListening()
    }

    private fun handleUserInput(userText: String) {
        appendToHistory(LocalAssistant.Role.USER, userText)
        broadcastStatus("THINKING")

        serviceScope.launch(Dispatchers.Default) {
            val reply = assistant.generateReply(conversationHistory)
            withContext(Dispatchers.Main) {
                Log.d(TAG, "Reply: $reply")
                appendToHistory(LocalAssistant.Role.ASSISTANT, reply)
                broadcastStatus("SPEAKING")
                broadcastSubtitle(reply, isUser = false)
                speechOutput.speak(reply)
                isProcessingInput = false
                delay(500L + reply.length * 50L)
                if (isPersonPresent) startListening()
            }
        }
    }

    private fun appendToHistory(role: LocalAssistant.Role, content: String) {
        conversationHistory.add(LocalAssistant.Message(role, content))
        while (conversationHistory.size > 21) conversationHistory.removeAt(1)
    }

    // ─────────────────────────────────────────────────────────────
    // Broadcasts to MainActivity
    // ─────────────────────────────────────────────────────────────

    private fun broadcastStatus(status: String) {
        sendBroadcast(Intent(ACTION_AI_STATUS).putExtra(EXTRA_STATUS, status))
    }

    private fun broadcastSubtitle(text: String, isUser: Boolean) {
        sendBroadcast(
            Intent(ACTION_SUBTITLE)
                .putExtra(EXTRA_SUBTITLE_TEXT, text)
                .putExtra(EXTRA_SUBTITLE_IS_USER, isUser)
        )
    }

    // ─────────────────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(
                CHANNEL_ID,
                "Asistente IA",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificación del asistente doméstico activo"
                setShowBadge(false)
                getSystemService(NotificationManager::class.java)?.createNotificationChannel(this)
            }
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Asistente IA")
            .setContentText("Escuchando y detectando personas…")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ─────────────────────────────────────────────────────────────
    // Wake lock
    // ─────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomeAI::WakeLock")
        wakeLock.acquire()
    }

    private fun releaseWakeLock() {
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
    }
}
