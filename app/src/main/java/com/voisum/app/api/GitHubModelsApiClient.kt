package com.voisum.app.api

import com.google.gson.Gson
import com.voisum.app.debug.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * GitHub Copilot / Models provider.
 *
 * Two-step pipeline:
 *   1. Transcribe audio via OpenAI Whisper at api.openai.com (requires separate OpenAI key)
 *   2. Polish text via chat completions on GitHub Models (uses GitHub token)
 *
 * The user must enter BOTH a GitHub token (for chat) and an OpenAI API key
 * (for Whisper transcription). The GitHub Models endpoint does NOT support
 * /audio/transcriptions — multipart uploads return 400.
 */

class GitHubModelsApiClient {

    companion object {
        private const val TAG = "GitHubModelsApi"
        private const val OPENAI_BASE = "https://api.openai.com/v1/"
        private const val GITHUB_MODELS_BASE = "https://models.inference.ai.azure.com/"
    }

    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Two-step process:
     * 1. Transcribe audio with Whisper (OpenAI endpoint, using openAiApiKey)
     * 2. Polish transcript with chat model (GitHub Models endpoint, using GitHub token)
     *
     * @param githubToken  GitHub PAT for chat completions on models.inference.ai.azure.com
     * @param openAiApiKey Separate OpenAI key for Whisper transcription on api.openai.com
     */
    suspend fun processAudio(
        @Suppress("UNUSED_PARAMETER") context: android.content.Context,
        githubToken: String,
        audioFilePath: String,
        systemPrompt: String,
        chatModel: String = "gpt-4o-mini",
        openAiApiKey: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val audioFile = File(audioFilePath)

            // Determine which key to use for Whisper
            val whisperKey = openAiApiKey.ifBlank { githubToken }
            if (openAiApiKey.isBlank()) {
                DebugLogger.w(TAG, "No OpenAI API key set — Whisper will use GitHub token (likely to fail)")
            }

            // === Step 1: Transcribe with Whisper (OpenAI endpoint) ===
            DebugLogger.i(TAG, "Step 1: Sending audio to Whisper (${audioFile.length()} bytes)")
            val transcript = whisperTranscribe("Bearer $whisperKey", audioFile)

            if (transcript == null) {
                val hint = if (openAiApiKey.isBlank())
                    "Enter your OpenAI API key in the GitHub Copilot settings for voice transcription."
                else
                    "Check that your OpenAI API key is valid."
                return@withContext Result.failure(
                    AiError.Network("Whisper transcription failed. $hint")
                )
            }

            DebugLogger.i(TAG, "Transcript (${transcript.length} chars): ${transcript.take(100)}...")

            // === Step 2: Polish with selected chat model (GitHub Models endpoint) ===
            val githubAuth = "Bearer $githubToken"
            DebugLogger.i(TAG, "Step 2: Polishing with $chatModel")
            val polished = chatComplete(githubAuth, systemPrompt, transcript, chatModel)

            if (polished == null) {
                return@withContext Result.failure(AiError.EmptyResponse())
            }

            DebugLogger.i(TAG, "Polished (${polished.length} chars): ${polished.take(80)}...")
            Result.success(polished)
        } catch (e: java.net.UnknownHostException) {
            DebugLogger.e(TAG, "No internet: ${e.message}")
            Result.failure(AiError.Network("No internet connection"))
        } catch (e: java.net.SocketTimeoutException) {
            DebugLogger.e(TAG, "Timeout: ${e.message}")
            Result.failure(AiError.Network("Request timed out"))
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Error: ${e.javaClass.simpleName}: ${e.message}")
            Result.failure(AiError.Network(e.message ?: "Unknown error"))
        }
    }

    // ---- Step 1: Whisper transcription (always via api.openai.com) ----

    private fun whisperTranscribe(authHeader: String, audioFile: File): String? {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart(
                "file", audioFile.name,
                audioFile.asRequestBody("audio/mp4".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("${OPENAI_BASE}audio/transcriptions")
            .addHeader("Authorization", authHeader)
            .post(body)
            .build()

        return try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val parsed = gson.fromJson(responseBody, WhisperResponse::class.java)
                val text = parsed?.text
                if (!text.isNullOrBlank()) {
                    DebugLogger.i(TAG, "Whisper transcription succeeded")
                    text
                } else {
                    DebugLogger.e(TAG, "Whisper returned empty text")
                    null
                }
            } else {
                DebugLogger.e(TAG, "Whisper failed: HTTP ${response.code} — ${responseBody.take(300)}")
                handleHttpError(response.code, responseBody)
                null
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Whisper exception: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ---- Step 2: Chat completion (try GitHub Models first, fallback to OpenAI) ----

    private fun chatComplete(authHeader: String, systemPrompt: String, userText: String, chatModel: String = "gpt-4o-mini"): String? {
        val requestJson = gson.toJson(
            mapOf(
                "model" to chatModel,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to userText)
                )
            )
        )
        val jsonBody = requestJson.toRequestBody("application/json".toMediaType())

        // Try GitHub Models endpoint first
        val githubResult = tryChatRequest(
            authHeader, jsonBody, "${GITHUB_MODELS_BASE}chat/completions", "GitHub Models"
        )
        if (githubResult != null) return githubResult

        // Fallback to OpenAI endpoint
        val openaiBody = requestJson.toRequestBody("application/json".toMediaType())
        val openaiResult = tryChatRequest(
            authHeader, openaiBody, "${OPENAI_BASE}chat/completions", "OpenAI"
        )
        return openaiResult
    }

    private fun tryChatRequest(
        authHeader: String,
        body: okhttp3.RequestBody,
        url: String,
        label: String
    ): String? {
        DebugLogger.d(TAG, "Trying chat completion via $label")
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", authHeader)
            .post(body)
            .build()

        return try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val parsed = gson.fromJson(responseBody, ChatResponse::class.java)
                val text = parsed?.choices?.firstOrNull()?.message?.content
                if (!text.isNullOrBlank()) {
                    DebugLogger.i(TAG, "$label chat completion succeeded")
                    text.trim()
                } else {
                    DebugLogger.w(TAG, "$label returned empty text")
                    null
                }
            } else {
                DebugLogger.w(TAG, "$label chat failed: HTTP ${response.code} — ${responseBody.take(200)}")
                null
            }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "$label chat exception: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ---- Helpers ----

    @Suppress("UNUSED_PARAMETER")
    private fun handleHttpError(code: Int, body: String) {
        when (code) {
            401, 403 -> DebugLogger.e(TAG, "Invalid API key (HTTP $code)")
            429 -> DebugLogger.e(TAG, "Rate limited (HTTP 429)")
            else -> DebugLogger.e(TAG, "HTTP $code")
        }
    }

    // ---- Test Connection ----

    /**
     * Tests both the GitHub Models endpoint (chat) and optionally the OpenAI endpoint (Whisper).
     */
    suspend fun testConnection(
        githubToken: String,
        chatModel: String = "gpt-4o-mini",
        openAiApiKey: String = ""
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // 1. Test GitHub Models chat endpoint
            val githubAuth = "Bearer $githubToken"
            val requestJson = gson.toJson(
                mapOf(
                    "model" to chatModel,
                    "messages" to listOf(
                        mapOf("role" to "user", "content" to "Say OK")
                    )
                )
            )

            val githubBody = requestJson.toRequestBody("application/json".toMediaType())
            val githubReq = Request.Builder()
                .url("${GITHUB_MODELS_BASE}chat/completions")
                .addHeader("Authorization", githubAuth)
                .post(githubBody)
                .build()

            val githubResp = client.newCall(githubReq).execute()
            val githubCode = githubResp.code
            val githubRespBody = githubResp.body?.string() ?: ""
            githubResp.close()

            if (!githubResp.isSuccessful) {
                return@withContext if (githubCode == 401 || githubCode == 403) {
                    Result.failure(AiError.InvalidApiKey())
                } else {
                    Result.failure(AiError.Network("GitHub Models: HTTP $githubCode — ${githubRespBody.take(200)}"))
                }
            }

            // 2. If OpenAI key provided, verify it too
            if (openAiApiKey.isNotBlank()) {
                val openaiReq = Request.Builder()
                    .url("${OPENAI_BASE}models")
                    .addHeader("Authorization", "Bearer $openAiApiKey")
                    .get()
                    .build()
                val openaiResp = client.newCall(openaiReq).execute()
                val openaiCode = openaiResp.code
                openaiResp.close()

                if (openaiCode == 401 || openaiCode == 403) {
                    return@withContext Result.failure(
                        AiError.Network("GitHub token OK, but OpenAI API key is invalid. Voice transcription will fail.")
                    )
                }
            }

            Result.success(true)
        } catch (e: Exception) {
            Result.failure(AiError.Network(e.message ?: "Unknown error"))
        }
    }

    // ---- Response models ----

    private data class WhisperResponse(val text: String?)

    private data class ChatResponse(val choices: List<ChatChoice>?)
    private data class ChatChoice(val message: ChatMessage?)
    private data class ChatMessage(val role: String?, val content: String?)
}
