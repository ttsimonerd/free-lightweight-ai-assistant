package com.homeai.assistant.llm

import android.util.Log
import com.homeai.assistant.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * LocalAssistant – generates Spanish replies via the Groq API (free tier).
 *
 * Model: llama-3.1-8b-instant
 * API:   https://api.groq.com/openai/v1/chat/completions  (OpenAI-compatible)
 *
 * The API key is injected at build time via BuildConfig.GROQ_API_KEY,
 * which is populated from a GitHub Actions Secret — it never appears in source.
 *
 * generateReply() is always called from a background thread (Dispatchers.Default
 * in HomeAIService), so blocking HTTP here is safe.
 */
class LocalAssistant {

    companion object {
        private const val TAG          = "LocalAssistant"
        private const val GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val MODEL        = "llama-3.1-8b-instant"
        private const val TIMEOUT_MS   = 15_000

        const val SYSTEM_PROMPT =
            "Eres un asistente doméstico fijo en un punto de la habitación. " +
            "Detectas cuando hay una persona delante y hablas en frases cortas, amables y claras. " +
            "Evitas explicaciones largas a menos que te las pidan. " +
            "Mantienes un tono tranquilo, útil y conciso. " +
            "Si no estás seguro de algo, haces una pregunta breve para aclararlo. " +
            "Responde SIEMPRE en español. " +
            "Mantén la mayoría de respuestas en menos de 4 frases."
    }

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Generate a Spanish reply using Groq.
     * Blocking – must be called from a background thread.
     * Falls back to a simple offline reply if the API is unreachable.
     */
    fun generateReply(history: List<Message>): String {
        val apiKey = BuildConfig.GROQ_API_KEY
        if (apiKey.isBlank()) {
            Log.w(TAG, "GROQ_API_KEY not set – using offline fallback")
            return offlineFallback(history)
        }
        return try {
            callGroqApi(history, apiKey)
        } catch (e: Exception) {
            Log.w(TAG, "Groq API error: ${e.message} – using offline fallback")
            offlineFallback(history)
        }
    }

    fun close() { /* nothing to release */ }

    // ─────────────────────────────────────────────────────────────
    // Groq API call
    // ─────────────────────────────────────────────────────────────

    private fun callGroqApi(history: List<Message>, apiKey: String): String {
        // Build messages array
        val messages = JSONArray()

        // System prompt always first
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", SYSTEM_PROMPT)
        })

        // Conversation history (skip any SYSTEM entries already stored)
        for (msg in history) {
            if (msg.role == Role.SYSTEM) continue
            messages.put(JSONObject().apply {
                put("role", if (msg.role == Role.USER) "user" else "assistant")
                put("content", msg.content)
            })
        }

        // Request body
        val body = JSONObject().apply {
            put("model", MODEL)
            put("messages", messages)
            put("temperature", 0.7)
            put("max_tokens", 200)
            put("stream", false)
        }

        // HTTP POST
        val conn = (URL(GROQ_API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            connectTimeout = TIMEOUT_MS
            readTimeout    = TIMEOUT_MS
            doOutput       = true
        }

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }

        val code   = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw    = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }

        if (code !in 200..299) {
            Log.e(TAG, "Groq HTTP $code: $raw")
            throw Exception("HTTP $code")
        }

        val reply = JSONObject(raw)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()

        Log.d(TAG, "Groq reply: $reply")
        return reply
    }

    // ─────────────────────────────────────────────────────────────
    // Offline fallback (used when WiFi is off or API unreachable)
    // ─────────────────────────────────────────────────────────────

    private fun offlineFallback(history: List<Message>): String {
        val last = history.lastOrNull { it.role == Role.USER }?.content?.lowercase() ?: ""
        return when {
            last.contains("hola") || last.contains("buenos")
                -> "Hola. Ahora mismo no tengo conexión, pero aquí estoy."
            last.contains("gracias")
                -> "De nada. Aunque estoy sin conexión, intento ayudarte."
            last.contains("adiós") || last.contains("hasta luego")
                -> "Hasta luego. Vuelve cuando quieras."
            else
                -> "Sin conexión en este momento. Inténtalo en un momento."
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Data model
    // ─────────────────────────────────────────────────────────────

    enum class Role { SYSTEM, USER, ASSISTANT }

    data class Message(val role: Role, val content: String)
}
