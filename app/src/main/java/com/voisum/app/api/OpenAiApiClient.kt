package com.voisum.app.api

import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
// CRITICAL BUG FIX #7: import retrofit2.http.Part explicitly.
// Never use import retrofit2.http.* — it conflicts with Kotlin's own Part annotation.
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

// --- Request/Response models ---

data class WhisperResponse(
    val text: String?
)

data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>
)

data class ChatMessage(
    val role: String,
    val content: String
)

data class ChatCompletionResponse(
    val choices: List<ChatChoice>?
)

data class ChatChoice(
    val message: ChatMessage?
)

// --- Retrofit interface ---

interface OpenAiApi {
    @Multipart
    @POST("v1/audio/transcriptions")
    suspend fun transcribe(
        @Header("Authorization") auth: String,
        @Part file: MultipartBody.Part,
        @Part("model") model: okhttp3.RequestBody
    ): WhisperResponse

    @POST("v1/chat/completions")
    suspend fun chatCompletion(
        @Header("Authorization") auth: String,
        @Body request: ChatCompletionRequest
    ): ChatCompletionResponse
}

// --- Client ---

class OpenAiApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val api: OpenAiApi = Retrofit.Builder()
        .baseUrl("https://api.openai.com/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(OpenAiApi::class.java)

    /**
     * Two-step process:
     * 1. Transcribe audio with Whisper
     * 2. Polish transcript with GPT-4o-mini
     */
    suspend fun processAudio(
        apiKey: String,
        audioFile: File,
        systemPrompt: String,
        chatModel: String = "gpt-4o-mini",
        whisperModel: String = "whisper-1"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val authHeader = "Bearer $apiKey"

            // Step 1: Transcribe with Whisper
            val requestBody = audioFile.asRequestBody("audio/m4a".toMediaType())
            val filePart = MultipartBody.Part.createFormData("file", audioFile.name, requestBody)
            val modelBody = whisperModel.toRequestBody("text/plain".toMediaType())

            val whisperResponse = api.transcribe(authHeader, filePart, modelBody)
            val transcript = whisperResponse.text

            if (transcript.isNullOrBlank()) {
                return@withContext Result.failure(AiError.EmptyResponse())
            }

            // Step 2: Polish with GPT
            val chatRequest = ChatCompletionRequest(
                model = chatModel,
                messages = listOf(
                    ChatMessage(role = "system", content = systemPrompt),
                    ChatMessage(role = "user", content = transcript)
                )
            )

            val chatResponse = api.chatCompletion(authHeader, chatRequest)
            val text = chatResponse.choices?.firstOrNull()?.message?.content

            if (text.isNullOrBlank()) {
                Result.failure(AiError.EmptyResponse())
            } else {
                Result.success(text.trim())
            }
        } catch (e: retrofit2.HttpException) {
            val code = e.code()
            val body = e.response()?.errorBody()?.string() ?: ""
            when {
                code == 401 || code == 403 ->
                    Result.failure(AiError.InvalidApiKey())
                code == 429 || body.contains("rate_limit", ignoreCase = true) ->
                    Result.failure(AiError.QuotaExceeded("ChatGPT"))
                else -> Result.failure(AiError.Network(e.message ?: "HTTP $code"))
            }
        } catch (e: java.net.UnknownHostException) {
            Result.failure(AiError.Network("No internet connection"))
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(AiError.Network("Request timed out"))
        } catch (e: Exception) {
            Result.failure(AiError.Network(e.message ?: "Unknown error"))
        }
    }

    suspend fun testConnection(apiKey: String, chatModel: String = "gpt-4o-mini"): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val authHeader = "Bearer $apiKey"
            val request = ChatCompletionRequest(
                model = chatModel,
                messages = listOf(ChatMessage(role = "user", content = "Say OK"))
            )
            api.chatCompletion(authHeader, request)
            Result.success(true)
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 401 || e.code() == 403) {
                Result.failure(AiError.InvalidApiKey())
            } else {
                Result.failure(AiError.Network(e.message ?: "HTTP ${e.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(AiError.Network(e.message ?: "Unknown error"))
        }
    }
}
