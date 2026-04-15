package com.homeai.assistant.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * SpeechInputManager – wraps Android SpeechRecognizer configured for Spanish (es-ES).
 *
 * Usage:
 *  1. Construct with a Context.
 *  2. Set a [SpeechCallback].
 *  3. Call [startListening] when you want to capture speech.
 *  4. Call [destroy] when done.
 */
class SpeechInputManager(private val context: Context) {

    interface SpeechCallback {
        /** Called when a partial or final result is recognised. */
        fun onSpeechResult(text: String, isFinal: Boolean)
        /** Called when listening becomes active. */
        fun onListeningStarted()
        /** Called when listening ends (result or error). */
        fun onListeningEnded()
        /** Called on a recogniser error. */
        fun onError(errorCode: Int)
    }

    companion object {
        private const val TAG = "SpeechInputManager"
        private const val LANGUAGE = "es-ES"
    }

    var callback: SpeechCallback? = null

    private var recognizer: SpeechRecognizer? = null
    private var isListening = false

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /** Initialise the recogniser. Must be called from the main thread. */
    fun init() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available on this device.")
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(recognitionListener)
        }
        Log.d(TAG, "SpeechRecognizer initialised (es-ES)")
    }

    /** Start listening for speech. */
    fun startListening() {
        if (isListening) return
        val recognizerInstance = recognizer ?: run {
            Log.w(TAG, "Recognizer not initialised – calling init() first")
            init()
            recognizer
        } ?: return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, LANGUAGE)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Longer speech segments allowed
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
        }

        recognizerInstance.startListening(intent)
        isListening = true
        callback?.onListeningStarted()
        Log.d(TAG, "Listening started")
    }

    /** Stop the current listening session. */
    fun stopListening() {
        recognizer?.stopListening()
        isListening = false
    }

    /** Release all resources. */
    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        isListening = false
    }

    // ─────────────────────────────────────────────────────────────
    // RecognitionListener
    // ─────────────────────────────────────────────────────────────

    private val recognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech begun")
        }

        override fun onRmsChanged(rmsdB: Float) { /* unused */ }
        override fun onBufferReceived(buffer: ByteArray?) { /* unused */ }

        override fun onEndOfSpeech() {
            Log.d(TAG, "End of speech")
            isListening = false
            callback?.onListeningEnded()
        }

        override fun onError(error: Int) {
            Log.w(TAG, "Recognition error: $error")
            isListening = false
            callback?.onError(error)
            callback?.onListeningEnded()
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?: return
            Log.d(TAG, "Final result: $text")
            isListening = false
            callback?.onSpeechResult(text, isFinal = true)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?: return
            Log.d(TAG, "Partial result: $text")
            callback?.onSpeechResult(text, isFinal = false)
        }

        override fun onEvent(eventType: Int, params: Bundle?) { /* unused */ }
    }
}
