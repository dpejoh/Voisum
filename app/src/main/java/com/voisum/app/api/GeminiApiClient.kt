package com.voisum.app.api

import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

// --- Request/Response models ---

data class GeminiRequest(
    val contents: List<GeminiContent>
)

data class GeminiContent(
    val parts: List<GeminiPart>
)

data class GeminiPart(
    val text: String? = null,
    @SerializedName("inline_data")
    val inlineData: GeminiInlineData? = null
)

data class GeminiInlineData(
    @SerializedName("mime_type")
    val mimeType: String,
    val data: String
)

data class GeminiResponse(
    val candidates: List<GeminiCandidate>?
)

data class GeminiCandidate(
    val content: GeminiCandidateContent?
)

data class GeminiCandidateContent(
    val parts: List<GeminiResponsePart>?
)

data class GeminiResponsePart(
    val text: String?
)

// --- Retrofit interface ---
// Always use v1beta. v1 has limit=0 on the free tier (CRITICAL BUG FIX #5).
// Dynamic model: URL is built at call site, so we use @Url.

interface GeminiApi {
    @POST
    suspend fun generateContent(
        @retrofit2.http.Url url: String,
        @Query("key") apiKey: String,
        @Body request: GeminiRequest
    ): GeminiResponse
}

// --- Client ---

class GeminiApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val api: GeminiApi = Retrofit.Builder()
        .baseUrl("https://generativelanguage.googleapis.com/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(GeminiApi::class.java)

    /**
     * Send audio file to Gemini for transcription and polishing.
     *
     * CRITICAL BUG FIX #4: Do NOT use systemInstruction field.
     * It causes HTTP 400 "Unknown name systemInstruction".
     * Instead, embed the system prompt as the first text part inside the user content array.
     */
    suspend fun processAudio(
        apiKey: String,
        audioFile: File,
        systemPrompt: String,
        modelId: String = "gemini-2.0-flash"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val audioBytes = audioFile.readBytes()
            val base64Audio = Base64.getEncoder().encodeToString(audioBytes)

            val request = GeminiRequest(
                contents = listOf(
                    GeminiContent(
                        parts = listOf(
                            GeminiPart(text = "$systemPrompt\n\nNow process this voice message:"),
                            GeminiPart(
                                inlineData = GeminiInlineData(
                                    mimeType = "audio/m4a",
                                    data = base64Audio
                                )
                            )
                        )
                    )
                )
            )

            val url = "v1beta/models/$modelId:generateContent"
            val response = api.generateContent(url, apiKey, request)
            val text = response.candidates
                ?.firstOrNull()
                ?.content
                ?.parts
                ?.firstOrNull()
                ?.text

            if (text.isNullOrBlank()) {
                Result.failure(AiError.EmptyResponse())
            } else {
                Result.success(text.trim())
            }
        } catch (e: retrofit2.HttpException) {
            val code = e.code()
            val body = e.response()?.errorBody()?.string() ?: ""
            android.util.Log.e("VA_GeminiAPI", "HTTP $code: $body")
            when {
                code == 400 && body.contains("API_KEY", ignoreCase = true) ->
                    Result.failure(AiError.InvalidApiKey())
                code == 401 || code == 403 ->
                    Result.failure(AiError.InvalidApiKey())
                code == 404 ->
                    Result.failure(AiError.Network("Model not found (HTTP 404). Check API key region."))
                code == 429 || body.contains("quota", ignoreCase = true) ->
                    Result.failure(AiError.QuotaExceeded("Gemini"))
                else -> Result.failure(AiError.Network("HTTP $code"))
            }
        } catch (e: java.net.UnknownHostException) {
            Result.failure(AiError.Network("No internet connection"))
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(AiError.Network("Request timed out"))
        } catch (e: Exception) {
            Result.failure(AiError.Network(e.message ?: "Unknown error"))
        }
    }

    /**
     * Quick test to verify the API key works.
     */
    suspend fun testConnection(apiKey: String, modelId: String = "gemini-2.0-flash"): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = GeminiRequest(
                contents = listOf(
                    GeminiContent(
                        parts = listOf(GeminiPart(text = "Say OK"))
                    )
                )
            )
            val url = "v1beta/models/$modelId:generateContent"
            api.generateContent(url, apiKey, request)
            Result.success(true)
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 400 || e.code() == 403) {
                Result.failure(AiError.InvalidApiKey())
            } else {
                Result.failure(AiError.Network(e.message ?: "HTTP ${e.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(AiError.Network(e.message ?: "Unknown error"))
        }
    }
}
