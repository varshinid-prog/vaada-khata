package com.vaada.models

/**
 * Single source of truth for the Assistant lifecycle and audio states.
 */
sealed interface AssistantState {
    data object Stopped : AssistantState
    data object Standby : AssistantState
    data class Listening(val amplitudeRms: Float = 0f) : AssistantState
    data class Captured(val metadata: AudioCaptureMetadata) : AssistantState
    /** Gateway call in progress; audio already captured and released. */
    data object Processing : AssistantState
    /** Gateway returned a reply. Shown until next wake-word cycle. */
    data class Responding(
        val transcript: String,
        val replyText: String,
        val replyKind: String,
        val dialogueState: String?,
        val ledgerCommitted: Boolean,
        val duplicate: Boolean,
        /** What is waiting for an answer: "confirmation", "clarification" or null. */
        val awaiting: String? = null,
        /** The pending entry as the gateway understood it, so the merchant can verify customer, amount and direction. */
        val pendingCustomer: String? = null,
        val pendingAmountPaise: Long? = null,
        val pendingType: String? = null,            // "CREDIT" | "PAYMENT" | null (not decided yet)
        /** The reply is being spoken right now. */
        val speaking: Boolean = false,
        /** UiCatalog key explaining why the reply could not be spoken (the text is always shown). */
        val audioNote: String? = null
    ) : AssistantState
    /** [message] is already in the merchant's language. [canRetry]: the same upload can be retried safely, or a new recording started. */
    data class Error(val message: String, val canRetry: Boolean = true) : AssistantState
}

/**
 * Metadata recorded during an utterance capture session.
 */
data class AudioCaptureMetadata(
    val sampleRate: Int = 16000,
    val channels: Int = 1,
    val audioFormat: String = "PCM_16BIT",
    val durationMs: Long,
    val totalBytes: Int,
    val maxAmplitude: Int = 0,
    val averageRms: Float = 0f,
    val timestamp: Long = System.currentTimeMillis(),
    /** SILENCE, MAX_DURATION, NO_SPEECH, STOPPED or ERROR (see EndReason). */
    val endReason: String = "UNKNOWN",
    val speechDetected: Boolean = true,
    val speechStartMs: Long = -1,
    val noiseFloor: Float = 0f,
    val speechThreshold: Float = 0f
)
