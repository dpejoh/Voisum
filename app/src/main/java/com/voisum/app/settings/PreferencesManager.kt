package com.voisum.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

enum class AiProvider(val label: String) {
    GEMINI("Gemini"),
    CHATGPT("ChatGPT"),
    GITHUB_COPILOT("GitHub Copilot")
}

/**
 * Available model versions for each provider.
 * id   = what we send to the API
 * label = what the user sees in the picker
 */
data class ModelOption(val id: String, val label: String)

object AvailableModels {
    val gemini = listOf(
        ModelOption("gemini-2.0-flash",      "Gemini 2.0 Flash"),
        ModelOption("gemini-2.0-flash-lite", "Gemini 2.0 Flash‑Lite"),
        ModelOption("gemini-2.5-flash-preview-05-20", "Gemini 2.5 Flash (Preview)"),
        ModelOption("gemini-2.5-pro-preview-05-06",   "Gemini 2.5 Pro (Preview)"),
        ModelOption("gemini-1.5-pro",        "Gemini 1.5 Pro"),
    )

    val chatgpt = listOf(
        ModelOption("gpt-4o-mini",   "GPT‑4o Mini"),
        ModelOption("gpt-4o",        "GPT‑4o"),
        ModelOption("gpt-4-turbo",   "GPT‑4 Turbo"),
        ModelOption("gpt-3.5-turbo", "GPT‑3.5 Turbo"),
    )

    /** Whisper models (used for Step 1 transcription by ChatGPT & GitHub) */
    val whisper = listOf(
        ModelOption("whisper-1", "Whisper‑1"),
    )

    val github = listOf(
        ModelOption("gpt-4o-mini",            "GPT‑4o Mini"),
        ModelOption("gpt-4o",                 "GPT‑4o"),
        ModelOption("gpt-4.1",                "GPT‑4.1"),
        ModelOption("gpt-4.1-mini",           "GPT‑4.1 Mini"),
        ModelOption("gpt-4.1-nano",           "GPT‑4.1 Nano"),
        ModelOption("o4-mini",                "o4‑mini"),
        ModelOption("o3",                     "o3"),
        ModelOption("o3-mini",                "o3‑mini"),
        ModelOption("gpt-5-mini",             "GPT‑5 Mini"),
        ModelOption("claude-3.5-sonnet",      "Claude 3.5 Sonnet"),
        ModelOption("claude-sonnet-4",        "Claude Sonnet 4"),
        ModelOption("claude-opus-4",          "Claude Opus 4"),
        ModelOption("claude-3.5-haiku",       "Claude 3.5 Haiku"),
        ModelOption("claude-haiku-4",         "Claude Haiku 4"),
    )

    fun forProvider(provider: AiProvider): List<ModelOption> = when (provider) {
        AiProvider.GEMINI        -> gemini
        AiProvider.CHATGPT       -> chatgpt
        AiProvider.GITHUB_COPILOT -> github
    }

    fun defaultFor(provider: AiProvider): String = forProvider(provider).first().id
}

data class PromptPreset(
    val id: String,
    val name: String,
    val colorHex: String,
    val promptBody: String
)

data class PerAppRule(
    val packageName: String,
    val presetId: String
)

data class LanguageFlagEntry(
    val keyword: String,
    val flag: String
)

class PreferencesManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "voice_assist_prefs"
        private const val SECURE_PREFS_NAME = "voice_assist_secure"

        // Keys - general
        private const val KEY_PROVIDER = "ai_provider"
        private const val KEY_BUBBLE_SIDE = "bubble_side"
        private const val KEY_AUTO_DISMISS = "auto_dismiss_after_paste"
        private const val KEY_MAX_DURATION = "max_recording_duration_seconds"
        private const val KEY_ACTIVE_PRESET = "active_preset_id"
        private const val KEY_PER_APP_ENABLED = "per_app_rules_enabled"
        private const val KEY_PER_APP_RULES = "per_app_rules_json"
        private const val KEY_PRESETS = "presets_json"
        private const val KEY_LANGUAGE_FLAGS = "language_flags_json"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_BUBBLE_Y_OFFSET = "bubble_y_offset"
        private const val KEY_BUBBLE_SNAPPED_SIDE = "bubble_snapped_side"

        // Keys - model versions
        private const val KEY_MODEL_GEMINI = "model_gemini"
        private const val KEY_MODEL_CHATGPT = "model_chatgpt"
        private const val KEY_MODEL_GITHUB = "model_github"

        // Keys - secure
        private const val KEY_GEMINI_KEY = "gemini_api_key"
        private const val KEY_OPENAI_KEY = "openai_api_key"
        private const val KEY_GITHUB_TOKEN = "github_token"

        const val DEFAULT_SYSTEM_PROMPT = """You are a messaging assistant. The user recorded a voice message.
Your job is to turn it into a clean written message they can send as-is.

LANGUAGE: Write in the exact same language or dialect you hear.
Algerian Darija in, Darija out. English in, English out. French in, French out. Arabic in, Arabic out. Never translate or switch languages.

LENGTH:
- Short message (under ~60 words): just clean it. Remove filler words, stutters, false starts. Keep everything else untouched.
- Long message (over ~60 words): strip it down to the point. Cut repeated ideas, unnecessary context, over-explanation. The output should be much shorter but lose nothing that matters.

TONE: match the speaker. Casual stays casual. Formal stays formal.

WRITING STYLE:
- No em dashes. Use commas or short sentences instead.
- No AI filler words: seamless, straightforward, valuable, leverage, ensure, utilize, comprehensive, robust, streamline, cutting-edge, game-changer, dive into, at the end of the day, it is worth noting, in today's world, I hope this helps.
- Never start with: Certainly, Of course, Great question, Absolutely, Sure, or any variation.
- No filler affirmations.
- Write like a human texting another human. Short sentences. Direct. No fluff.

Output ONLY the final message text. Nothing else."""

        val DEFAULT_PRESETS = listOf(
            PromptPreset("casual_dm", "Casual DM", "#34C759", DEFAULT_SYSTEM_PROMPT),
            PromptPreset("professional", "Professional", "#007AFF",
                DEFAULT_SYSTEM_PROMPT.replace("Casual stays casual. Formal stays formal.",
                    "Use a professional, polished tone regardless of input.")),
            PromptPreset("summarize", "Summarize", "#FF9500",
                "You are a summarization assistant. The user recorded a voice message. Summarize it into 1-3 concise bullet points. Keep the same language. Output ONLY the summary."),
            PromptPreset("translate_en", "Translate → EN", "#AF52DE",
                "You are a translation assistant. The user recorded a voice message. Translate it to English. Output ONLY the translated text."),
            PromptPreset("custom_1", "Custom 1", "#FF2D55", DEFAULT_SYSTEM_PROMPT)
        )

        val DEFAULT_PER_APP_RULES = listOf(
            PerAppRule("com.whatsapp", "casual_dm"),
            PerAppRule("com.google.android.gm", "professional"),
            PerAppRule("com.linkedin.android", "professional"),
            PerAppRule("com.instagram.android", "casual_dm")
        )

        val DEFAULT_LANGUAGE_FLAGS = listOf(
            LanguageFlagEntry("darija", "\uD83C\uDDE9\uD83C\uDDFF"),     // 🇩🇿
            LanguageFlagEntry("algerian", "\uD83C\uDDE9\uD83C\uDDFF"),   // 🇩🇿
            LanguageFlagEntry("arabic", "\uD83C\uDDF8\uD83C\uDDE6"),     // 🇸🇦
            LanguageFlagEntry("english", "\uD83C\uDDEC\uD83C\uDDE7"),    // 🇬🇧
            LanguageFlagEntry("french", "\uD83C\uDDEB\uD83C\uDDF7"),     // 🇫🇷
            LanguageFlagEntry("spanish", "\uD83C\uDDEA\uD83C\uDDF8"),    // 🇪🇸
            LanguageFlagEntry("german", "\uD83C\uDDE9\uD83C\uDDEA"),     // 🇩🇪
            LanguageFlagEntry("italian", "\uD83C\uDDEE\uD83C\uDDF9"),    // 🇮🇹
            LanguageFlagEntry("portuguese", "\uD83C\uDDF5\uD83C\uDDF9"), // 🇵🇹
            LanguageFlagEntry("turkish", "\uD83C\uDDF9\uD83C\uDDF7"),    // 🇹🇷
        )

        private const val DEFAULT_GLOBE = "\uD83C\uDF10" // 🌐
    }

    private val gson = Gson()

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val securePrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // --- AI Provider ---

    var aiProvider: AiProvider
        get() = AiProvider.valueOf(prefs.getString(KEY_PROVIDER, AiProvider.GEMINI.name)!!)
        set(value) = prefs.edit().putString(KEY_PROVIDER, value.name).apply()

    // --- Model versions ---

    fun getModelForProvider(provider: AiProvider = aiProvider): String {
        val key = when (provider) {
            AiProvider.GEMINI -> KEY_MODEL_GEMINI
            AiProvider.CHATGPT -> KEY_MODEL_CHATGPT
            AiProvider.GITHUB_COPILOT -> KEY_MODEL_GITHUB
        }
        val saved = prefs.getString(key, null)
        // Validate that saved model still exists in the list
        val available = AvailableModels.forProvider(provider)
        return if (saved != null && available.any { it.id == saved }) saved
               else AvailableModels.defaultFor(provider)
    }

    fun setModelForProvider(modelId: String, provider: AiProvider = aiProvider) {
        val key = when (provider) {
            AiProvider.GEMINI -> KEY_MODEL_GEMINI
            AiProvider.CHATGPT -> KEY_MODEL_CHATGPT
            AiProvider.GITHUB_COPILOT -> KEY_MODEL_GITHUB
        }
        prefs.edit().putString(key, modelId).apply()
    }

    // --- API Keys (secure) ---

    var geminiApiKey: String
        get() = securePrefs.getString(KEY_GEMINI_KEY, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_GEMINI_KEY, value).apply()

    var openAiApiKey: String
        get() = securePrefs.getString(KEY_OPENAI_KEY, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_OPENAI_KEY, value).apply()

    var githubToken: String
        get() = securePrefs.getString(KEY_GITHUB_TOKEN, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_GITHUB_TOKEN, value).apply()

    fun getApiKeyForProvider(provider: AiProvider = aiProvider): String = when (provider) {
        AiProvider.GEMINI -> geminiApiKey
        AiProvider.CHATGPT -> openAiApiKey
        AiProvider.GITHUB_COPILOT -> githubToken
    }

    fun setApiKeyForProvider(key: String, provider: AiProvider = aiProvider) = when (provider) {
        AiProvider.GEMINI -> { geminiApiKey = key }
        AiProvider.CHATGPT -> { openAiApiKey = key }
        AiProvider.GITHUB_COPILOT -> { githubToken = key }
    }

    // --- Bubble ---

    var bubbleSide: String
        get() = prefs.getString(KEY_BUBBLE_SIDE, "right") ?: "right"
        set(value) = prefs.edit().putString(KEY_BUBBLE_SIDE, value).apply()

    var autoDismissAfterPaste: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DISMISS, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DISMISS, value).apply()

    var maxRecordingDurationSeconds: Int
        get() = prefs.getInt(KEY_MAX_DURATION, 180)
        set(value) = prefs.edit().putInt(KEY_MAX_DURATION, value).apply()

    var bubbleYOffset: Int
        get() = prefs.getInt(KEY_BUBBLE_Y_OFFSET, -1)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_Y_OFFSET, value).apply()

    var bubbleSnappedSide: String
        get() = prefs.getString(KEY_BUBBLE_SNAPPED_SIDE, "right") ?: "right"
        set(value) = prefs.edit().putString(KEY_BUBBLE_SNAPPED_SIDE, value).apply()

    // --- Presets ---

    var activePresetId: String
        get() = prefs.getString(KEY_ACTIVE_PRESET, "casual_dm") ?: "casual_dm"
        set(value) = prefs.edit().putString(KEY_ACTIVE_PRESET, value).apply()

    var presets: List<PromptPreset>
        get() {
            val json = prefs.getString(KEY_PRESETS, null) ?: return DEFAULT_PRESETS
            return try {
                val type = object : TypeToken<List<PromptPreset>>() {}.type
                gson.fromJson(json, type)
            } catch (e: Exception) {
                DEFAULT_PRESETS
            }
        }
        set(value) = prefs.edit().putString(KEY_PRESETS, gson.toJson(value)).apply()

    fun getActivePreset(): PromptPreset {
        return presets.find { it.id == activePresetId } ?: presets.first()
    }

    fun getPresetById(id: String): PromptPreset? {
        return presets.find { it.id == id }
    }

    fun resetPresetToDefault(presetId: String) {
        val defaultPreset = DEFAULT_PRESETS.find { it.id == presetId } ?: return
        val currentPresets = presets.toMutableList()
        val index = currentPresets.indexOfFirst { it.id == presetId }
        if (index >= 0) {
            currentPresets[index] = defaultPreset
            presets = currentPresets
        }
    }

    // --- System Prompt ---

    var systemPrompt: String
        get() = prefs.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
        set(value) = prefs.edit().putString(KEY_SYSTEM_PROMPT, value).apply()

    // --- Per-App Rules ---

    var perAppRulesEnabled: Boolean
        get() = prefs.getBoolean(KEY_PER_APP_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_PER_APP_ENABLED, value).apply()

    var perAppRules: List<PerAppRule>
        get() {
            val json = prefs.getString(KEY_PER_APP_RULES, null) ?: return DEFAULT_PER_APP_RULES
            return try {
                val type = object : TypeToken<List<PerAppRule>>() {}.type
                gson.fromJson(json, type)
            } catch (e: Exception) {
                DEFAULT_PER_APP_RULES
            }
        }
        set(value) = prefs.edit().putString(KEY_PER_APP_RULES, gson.toJson(value)).apply()

    fun getPresetForApp(packageName: String): PromptPreset? {
        if (!perAppRulesEnabled) return null
        val rule = perAppRules.find { it.packageName == packageName } ?: return null
        return getPresetById(rule.presetId)
    }

    fun addPerAppRule(rule: PerAppRule) {
        val current = perAppRules.toMutableList()
        current.removeAll { it.packageName == rule.packageName }
        current.add(rule)
        perAppRules = current
    }

    fun removePerAppRule(packageName: String) {
        perAppRules = perAppRules.filter { it.packageName != packageName }
    }

    // --- Language Flags ---

    var languageFlags: List<LanguageFlagEntry>
        get() {
            val json = prefs.getString(KEY_LANGUAGE_FLAGS, null) ?: return DEFAULT_LANGUAGE_FLAGS
            return try {
                val type = object : TypeToken<List<LanguageFlagEntry>>() {}.type
                gson.fromJson(json, type)
            } catch (e: Exception) {
                DEFAULT_LANGUAGE_FLAGS
            }
        }
        set(value) = prefs.edit().putString(KEY_LANGUAGE_FLAGS, gson.toJson(value)).apply()

    fun getFlagForLanguage(detectedLanguage: String): String {
        val lower = detectedLanguage.lowercase()
        for (entry in languageFlags) {
            if (lower.contains(entry.keyword.lowercase())) {
                return entry.flag
            }
        }
        return DEFAULT_GLOBE
    }

    fun addLanguageFlag(entry: LanguageFlagEntry) {
        val current = languageFlags.toMutableList()
        current.removeAll { it.keyword.equals(entry.keyword, ignoreCase = true) }
        current.add(entry)
        languageFlags = current
    }

    fun removeLanguageFlag(keyword: String) {
        languageFlags = languageFlags.filter { !it.keyword.equals(keyword, ignoreCase = true) }
    }
}
