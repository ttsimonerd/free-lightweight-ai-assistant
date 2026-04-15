package com.homeai.assistant.audio

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * SpeechOutputManager – wraps Android TextToSpeech configured for Spanish (es-ES).
 *
 * Queues speech output on STREAM_MUSIC and notifies via [TtsCallback] when
 * utterances complete.
 */
class SpeechOutputManager(private val context: Context) {

    interface TtsCallback {
        /** Utterance started. */
        fun onSpeechStart(utteranceId: String)
        /** Utterance finished (or was cancelled). */
        fun onSpeechDone(utteranceId: String)
        /** TTS engine error. */
        fun onSpeechError(utteranceId: String)
    }

    companion object {
        private const val TAG = "SpeechOutputManager"
        private val SPANISH = Locale("es", "ES")
    }

    var callback: TtsCallback? = null
    var isSpeaking: Boolean = false
        private set

    private var tts: TextToSpeech? = null
    private var ready = false

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /** Initialise TTS engine. Provide an optional completion lambda. */
    fun init(onReady: (() -> Unit)? = null) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureTts()
                ready = true
                Log.d(TAG, "TTS ready (es-ES)")
                onReady?.invoke()
            } else {
                Log.e(TAG, "TTS init failed with status $status")
            }
        }
    }

    /**
     * Speak [text] in Spanish.
     * @param queueMode TextToSpeech.QUEUE_FLUSH (default) or QUEUE_ADD
     * @return utterance ID or null if TTS is not ready
     */
    fun speak(
        text: String,
        queueMode: Int = TextToSpeech.QUEUE_FLUSH
    ): String? {
        if (!ready) {
            Log.w(TAG, "TTS not ready, ignoring: $text")
            return null
        }
        val utteranceId = UUID.randomUUID().toString()
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        tts?.speak(text, queueMode, params, utteranceId)
        return utteranceId
    }

    /** Stop any current speech immediately. */
    fun stop() {
        tts?.stop()
        isSpeaking = false
    }

    /** Release TTS resources. */
    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    // ─────────────────────────────────────────────────────────────
    // Internal
    // ─────────────────────────────────────────────────────────────

    private fun configureTts() {
        val engine = tts ?: return

        // Set Spanish locale
        val result = engine.setLanguage(SPANISH)
        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            Log.w(TAG, "Spanish TTS language not fully supported on this device. " +
                    "Install es-ES TTS data via Settings > Language > Text-to-Speech.")
        }

        engine.setSpeechRate(0.9f)   // slightly slower for clarity
        engine.setPitch(1.0f)

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                isSpeaking = true
                callback?.onSpeechStart(utteranceId)
            }

            override fun onDone(utteranceId: String) {
                isSpeaking = false
                callback?.onSpeechDone(utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                isSpeaking = false
                callback?.onSpeechError(utteranceId)
            }
        })
    }
}
