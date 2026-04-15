package com.homeai.assistant.llm

import android.util.Log

/**
 * LocalAssistant – handles local text generation for the AI assistant.
 *
 * The current implementation is a structured placeholder that generates
 * context-aware Spanish responses without requiring any model file. It is
 * deliberately designed so you can swap [generateWithModel] for a real tiny
 * local LLM (e.g. Llama.cpp, MLC-LLM, or a quantised GGUF model via JNI)
 * without changing the public API.
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  Integration notes for real LLM:                                    │
 * │  - Put your .gguf / .tflite model in app/src/main/assets/          │
 * │  - Initialise the JNI/library binding in init{}                    │
 * │  - Replace generateWithModel() with real inference                 │
 * │  - Keep the public generateReply() contract identical              │
 * └─────────────────────────────────────────────────────────────────────┘
 */
class LocalAssistant {

    companion object {
        private const val TAG = "LocalAssistant"

        /**
         * System prompt injected before every conversation.
         * Instructs the model to respond always in Spanish, concisely.
         */
        const val SYSTEM_PROMPT = """
Eres un asistente doméstico fijo en un punto de la habitación. Detectas cuando hay una persona delante y hablas en frases cortas, amables y claras. Evitas explicaciones largas a menos que te las pidan. Mantienes un tono tranquilo, útil y conciso. Si no estás seguro de algo, haces una pregunta breve para aclararlo. Responde SIEMPRE en español. Mantén la mayoría de respuestas en menos de 4 frases.
"""
    }

    // ── Real-model initialisation (uncomment when integrating) ────
    // private var llamaContext: Long = 0L
    // init {
    //     System.loadLibrary("llama")
    //     llamaContext = nativeInit(modelPath)
    //     Log.d(TAG, "LLM loaded from $modelPath")
    // }

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Generate a reply given the conversation history.
     *
     * @param history List of [Message] objects (role + content), newest last.
     * @return A Spanish reply string.
     */
    fun generateReply(history: List<Message>): String {
        Log.d(TAG, "Generating reply for ${history.size} message(s) in history")
        return generateWithModel(history)
    }

    /** Release any native resources held by the model. */
    fun close() {
        // nativeDestroy(llamaContext)
    }

    // ─────────────────────────────────────────────────────────────
    // Model inference (placeholder – replace with real LLM call)
    // ─────────────────────────────────────────────────────────────

    /**
     * Placeholder inference function.
     *
     * When you integrate a real local model, replace the body of this
     * function with native inference (JNI, TFLite, etc.) while keeping the
     * signature intact.
     */
    private fun generateWithModel(history: List<Message>): String {
        val lastUserMessage = history
            .lastOrNull { it.role == Role.USER }
            ?.content
            ?.lowercase()
            ?: return "¿En qué puedo ayudarte?"

        // Simple keyword-based responses for demonstration
        return when {
            containsAny(lastUserMessage, "hola", "buenos días", "buenas tardes", "buenas noches") ->
                "¡Hola! Aquí estoy. ¿En qué puedo ayudarte?"

            containsAny(lastUserMessage, "cómo estás", "qué tal", "todo bien") ->
                "Estoy bien, gracias por preguntar. ¿Necesitas algo?"

            containsAny(lastUserMessage, "hora", "qué hora") ->
                "Lo siento, no tengo acceso al reloj del sistema. ¿Puedo ayudarte con otra cosa?"

            containsAny(lastUserMessage, "tiempo", "clima", "temperatura", "lluvia") ->
                "No tengo acceso a información del tiempo en este momento, ya que funciono sin conexión."

            containsAny(lastUserMessage, "música", "canción", "poner música") ->
                "Podría ayudarte con eso si estuviera conectado a un sistema de audio. ¿Necesitas algo más?"

            containsAny(lastUserMessage, "luz", "apaga", "enciende", "lámpara") ->
                "Por ahora no controlo los dispositivos del hogar. ¿Hay algo más en lo que pueda ayudarte?"

            containsAny(lastUserMessage, "gracias", "muchas gracias") ->
                "De nada. ¡Estoy aquí cuando me necesites!"

            containsAny(lastUserMessage, "adiós", "hasta luego", "chao", "nos vemos") ->
                "¡Hasta luego! Aquí estaré si me necesitas."

            containsAny(lastUserMessage, "ayuda", "qué puedes hacer", "para qué sirves") ->
                "Puedo responder preguntas, recordarte cosas o simplemente hacerte compañía. ¿Qué necesitas?"

            containsAny(lastUserMessage, "quién eres", "cómo te llamas", "qué eres") ->
                "Soy tu asistente doméstico. Estoy aquí para ayudarte."

            containsAny(lastUserMessage, "repite", "di de nuevo", "no entendí") ->
                "Lo siento, ¿puedes reformular tu pregunta? Estoy aquí para escucharte."

            containsAny(lastUserMessage, "silencio", "calla", "para") ->
                "Entendido, me quedo callado. Avísame si me necesitas."

            else -> buildContextualReply(lastUserMessage)
        }
    }

    private fun buildContextualReply(input: String): String {
        // Generic fallback – acknowledge and invite clarification
        val wordCount = input.trim().split("\\s+".toRegex()).size
        return if (wordCount <= 3) {
            "Entiendo. ¿Puedes contarme más sobre lo que necesitas?"
        } else {
            "Interesante. No estoy completamente seguro de cómo ayudarte con eso. ¿Puedes ser más específico?"
        }
    }

    private fun containsAny(text: String, vararg keywords: String): Boolean =
        keywords.any { text.contains(it) }

    // ─────────────────────────────────────────────────────────────
    // Data model
    // ─────────────────────────────────────────────────────────────

    enum class Role { SYSTEM, USER, ASSISTANT }

    data class Message(
        val role: Role,
        val content: String
    )
}
