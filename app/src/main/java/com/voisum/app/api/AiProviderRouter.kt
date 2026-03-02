package com.voisum.app.api

import android.content.Context
import com.voisum.app.settings.AiProvider
import com.voisum.app.settings.PreferencesManager
import java.io.File

/**
 * Routes AI processing requests to the active provider.
 * Handles provider selection, key retrieval, and error normalization.
 */
class AiProviderRouter(private val context: Context) {

    private val prefs = PreferencesManager(context)
    private val geminiClient = GeminiApiClient()
    private val openAiClient = OpenAiApiClient()
    private val githubClient = GitHubModelsApiClient()

    data class ProcessingResult(
        val text: String,
        val detectedLanguage: String,
        val providerUsed: String
    )

    /**
     * Process an audio file through the currently active AI provider.
     * Returns the polished text and detected language.
     */
    suspend fun processAudio(
        audioFile: File,
        systemPrompt: String
    ): Result<ProcessingResult> {
        val provider = prefs.aiProvider
        val apiKey = prefs.getApiKeyForProvider(provider)

        if (apiKey.isBlank()) {
            return Result.failure(AiError.InvalidApiKey())
        }

        // Check file size before upload (20MB limit)
        if (audioFile.length() > 20 * 1024 * 1024) {
            return Result.failure(AiError.RecordingTooLarge())
        }

        val result = when (provider) {
            AiProvider.GEMINI -> {
                val model = prefs.getModelForProvider(provider)
                geminiClient.processAudio(apiKey, audioFile, systemPrompt, modelId = model)
            }
            AiProvider.CHATGPT -> {
                val model = prefs.getModelForProvider(provider)
                openAiClient.processAudio(apiKey, audioFile, systemPrompt, chatModel = model)
            }
            AiProvider.GITHUB_COPILOT -> {
                val model = prefs.getModelForProvider(provider)
                val openAiKey = prefs.openAiApiKey  // separate key for Whisper
                githubClient.processAudio(
                    context, apiKey, audioFile.absolutePath, systemPrompt,
                    chatModel = model, openAiApiKey = openAiKey
                )
            }
        }

        return result.map { text ->
            val language = detectLanguageFromText(text)
            ProcessingResult(
                text = text,
                detectedLanguage = language,
                providerUsed = provider.label
            )
        }
    }

    /**
     * Test the connection for a specific provider and API key.
     */
    suspend fun testConnection(
        provider: AiProvider = prefs.aiProvider,
        apiKey: String = prefs.getApiKeyForProvider(provider)
    ): Result<Boolean> {
        if (apiKey.isBlank()) {
            return Result.failure(AiError.InvalidApiKey())
        }
        return when (provider) {
            AiProvider.GEMINI -> {
                val model = prefs.getModelForProvider(provider)
                geminiClient.testConnection(apiKey, modelId = model)
            }
            AiProvider.CHATGPT -> {
                val model = prefs.getModelForProvider(provider)
                openAiClient.testConnection(apiKey, chatModel = model)
            }
            AiProvider.GITHUB_COPILOT -> {
                val model = prefs.getModelForProvider(provider)
                val openAiKey = prefs.openAiApiKey  // separate key for Whisper
                githubClient.testConnection(apiKey, chatModel = model, openAiApiKey = openAiKey)
            }
        }
    }

    /**
     * Basic language detection from output text.
     * Uses character range analysis and keyword matching.
     */
    private fun detectLanguageFromText(text: String): String {
        val stripped = text.replace("\\s+".toRegex(), "")
        if (stripped.isEmpty()) return "unknown"

        // Count character types
        var arabicCount = 0
        var latinCount = 0
        var cjkCount = 0

        for (char in stripped) {
            when {
                char.code in 0x0600..0x06FF || char.code in 0xFE70..0xFEFF -> arabicCount++
                char.code in 0x0041..0x024F -> latinCount++
                char.code in 0x4E00..0x9FFF || char.code in 0x3040..0x30FF -> cjkCount++
            }
        }

        val total = stripped.length.toFloat()

        // Arabic script dominates
        if (arabicCount / total > 0.3f) {
            val lowerText = text.lowercase()
            // Darija-specific keywords
            val darijaKeywords = listOf("wach", "bghit", "khoya", "hna", "dyal", "kayn",
                "machi", "bzzaf", "wash", "3lach", "mnin", "kifach", "labas")
            for (kw in darijaKeywords) {
                if (lowerText.contains(kw)) return "darija"
            }
            return "arabic"
        }

        // Latin script dominates
        if (latinCount / total > 0.5f) {
            val lowerText = text.lowercase()
            // French detection
            val frenchWords = listOf(" je ", " tu ", " nous ", " vous ", " les ", " des ",
                " est ", " sont ", " dans ", " pour ", " avec ", " cette ", " c'est ")
            val frenchHits = frenchWords.count { lowerText.contains(it) }
            if (frenchHits >= 2) return "french"

            // Spanish detection
            val spanishWords = listOf(" el ", " los ", " las ", " una ", " pero ",
                " como ", " está ", " tiene ", " para ")
            val spanishHits = spanishWords.count { lowerText.contains(it) }
            if (spanishHits >= 2) return "spanish"

            // German detection
            val germanWords = listOf(" der ", " die ", " das ", " und ", " ist ",
                " nicht ", " ein ", " eine ")
            val germanHits = germanWords.count { lowerText.contains(it) }
            if (germanHits >= 2) return "german"

            // Default Latin script to English
            return "english"
        }

        return "unknown"
    }
}
