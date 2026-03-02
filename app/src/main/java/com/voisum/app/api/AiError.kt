package com.voisum.app.api

/**
 * Sealed hierarchy for AI provider errors.
 * Each error type maps to a specific user-facing message and action.
 */
sealed class AiError(message: String) : Exception(message) {
    class Network(msg: String = "No connection") : AiError(msg)
    class InvalidApiKey : AiError("API key rejected")
    class QuotaExceeded(val provider: String) : AiError("Quota exceeded for $provider")
    class EmptyResponse : AiError("No output returned")
    class TranscriptionUnavailable : AiError("On-device transcription unavailable")
    class RecordingTooLarge : AiError("Recording too large")
}
