package com.openclaw.assistant.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Secure settings storage
 */
class SettingsRepository(private val appContext: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // HTTP Server URL (required)
    var httpUrl: String
        // Внешний конфиг (files/relay.json) главнее: на шлеме его кладут по adb,
        // потому что зашифрованные prefs снаружи не записать.
        get() = RelayConfig.url(appContext) ?: (prefs.getString(KEY_HTTP_URL, "") ?: "")
        set(value) {
            if (value != httpUrl) {
                prefs.edit().putString(KEY_HTTP_URL, value).apply()
                isVerified = false
            }
        }

    // Auth Token (optional)
    var authToken: String
        get() = RelayConfig.token(appContext).ifBlank { prefs.getString(KEY_AUTH_TOKEN, "") ?: "" }
        set(value) = prefs.edit().putString(KEY_AUTH_TOKEN, value).apply()

    // Session ID (auto-generated)
    var sessionId: String
        get() {
            val existing = prefs.getString(KEY_SESSION_ID, null)
            return existing ?: generateNewSessionId().also { sessionId = it }
        }
        set(value) = prefs.edit().putString(KEY_SESSION_ID, value).apply()

    // Timestamp of the last voice message sent (epoch millis, voice-only)
    var lastVoiceMessageTimestamp: Long
        get() = prefs.getLong(KEY_LAST_VOICE_MESSAGE_TIMESTAMP, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_VOICE_MESSAGE_TIMESTAMP, value).apply()

    // The session ID used by the voice flow (independent of the chat UI's selected session)
    var voiceSessionId: String
        get() {
            val existing = prefs.getString(KEY_VOICE_SESSION_ID, null)
            return existing ?: generateNewSessionId().also { voiceSessionId = it }
        }
        set(value) = prefs.edit().putString(KEY_VOICE_SESSION_ID, value).apply()

    // Session timeout in minutes (used only in TTL mode, voice only)
    var sessionTimeoutMinutes: Int
        get() = prefs.getInt(KEY_SESSION_TIMEOUT_MINUTES, DEFAULT_SESSION_TIMEOUT_MINUTES)
        set(value) = prefs.edit().putInt(KEY_SESSION_TIMEOUT_MINUTES, value).apply()

    // Session mode: "always_new", "ttl", "per_conversation", "sticky"
    var sessionMode: String
        get() = prefs.getString(KEY_SESSION_MODE, SESSION_MODE_TTL) ?: SESSION_MODE_TTL
        set(value) = prefs.edit().putString(KEY_SESSION_MODE, value).apply()

    /**
     * Resolves the voice session ID based on the configured mode.
     * Only used by voice activations — the chat UI uses its own local session ID.
     */
    fun resolveVoiceSessionId(): String {
        return when (sessionMode) {
            SESSION_MODE_ALWAYS_NEW -> generateNewSessionId().also { voiceSessionId = it }
            SESSION_MODE_STICKY -> voiceSessionId
            SESSION_MODE_PER_CONVERSATION -> generateNewSessionId().also { voiceSessionId = it }
            else -> { // TTL
                val now = System.currentTimeMillis()
                val timeout = sessionTimeoutMinutes
                val elapsed = now - lastVoiceMessageTimestamp
                val withinWindow = timeout > 0 && lastVoiceMessageTimestamp > 0 &&
                        elapsed < timeout * 60_000L
                if (withinWindow) voiceSessionId
                else generateNewSessionId().also { voiceSessionId = it }
            }
        }
    }

    fun stampVoiceMessageTime() {
        lastVoiceMessageTimestamp = System.currentTimeMillis()
    }



    // Hotword enabled
    var hotwordEnabled: Boolean
        get() = prefs.getBoolean(KEY_HOTWORD_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_HOTWORD_ENABLED, value).apply()

    // Wake word selection
    var wakeWordPreset: String
        get() = prefs.getString(KEY_WAKE_WORD_PRESET, WAKE_WORD_HEY_JARVIS) ?: WAKE_WORD_HEY_JARVIS
        set(value) = prefs.edit().putString(KEY_WAKE_WORD_PRESET, value).apply()

    // Wake word detection threshold (0.0 – 1.0). Lower = more sensitive = more false positives.
    var wakeWordThreshold: Float
        get() = prefs.getFloat(KEY_WAKE_WORD_THRESHOLD, 0.5f)
        set(value) = prefs.edit().putFloat(KEY_WAKE_WORD_THRESHOLD, value.coerceIn(0.1f, 0.95f)).apply()

    fun getWakeWordDisplayName(): String {
        return when (wakeWordPreset) {
            WAKE_WORD_HEY_JARVIS -> "Hey Jarvis"
            WAKE_WORD_ALEXA -> "Alexa"
            WAKE_WORD_HEY_MYCROFT -> "Hey Mycroft"
            else -> "Hey Jarvis"
        }
    }

    // TTS enabled
    var ttsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TTS_ENABLED, true) // Default true as per user request
        set(value) = prefs.edit().putBoolean(KEY_TTS_ENABLED, value).apply()

    // Continuous mode
    var continuousMode: Boolean
        get() = prefs.getBoolean(KEY_CONTINUOUS_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_CONTINUOUS_MODE, value).apply()

    // Resume Latest Session
    var resumeLatestSession: Boolean
        get() = prefs.getBoolean(KEY_RESUME_LATEST_SESSION, false)
        set(value) = prefs.edit().putBoolean(KEY_RESUME_LATEST_SESSION, value).apply()

    // TTS Speed
    var ttsSpeed: Float
        get() = prefs.getFloat(KEY_TTS_SPEED, 1.2f)
        set(value) = prefs.edit().putFloat(KEY_TTS_SPEED, value).apply()

    // TTS Engine
    var ttsEngine: String
        get() = prefs.getString(KEY_TTS_ENGINE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TTS_ENGINE, value).apply()
    
    // TTS Type (local, elevenlabs, openai, voicevox)
    var ttsType: String
        get() = prefs.getString(KEY_TTS_TYPE, TTS_TYPE_LOCAL) ?: TTS_TYPE_LOCAL
        set(value) = prefs.edit().putString(KEY_TTS_TYPE, value).apply()
    
    // ElevenLabs settings
    var elevenLabsApiKey: String
        get() = prefs.getString(KEY_ELEVENLABS_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ELEVENLABS_API_KEY, value).apply()
    
    var elevenLabsVoiceId: String
        get() = prefs.getString(KEY_ELEVENLABS_VOICE_ID, DEFAULT_ELEVENLABS_VOICE_ID) ?: DEFAULT_ELEVENLABS_VOICE_ID
        set(value) = prefs.edit().putString(KEY_ELEVENLABS_VOICE_ID, value).apply()
    
    var elevenLabsModel: String
        get() = prefs.getString(KEY_ELEVENLABS_MODEL, DEFAULT_ELEVENLABS_MODEL) ?: DEFAULT_ELEVENLABS_MODEL
        set(value) = prefs.edit().putString(KEY_ELEVENLABS_MODEL, value).apply()
    
    var elevenLabsSpeed: Float
        get() = prefs.getFloat(KEY_ELEVENLABS_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_ELEVENLABS_SPEED, value.coerceIn(0.7f, 1.2f)).apply()
    
    // OpenAI settings
    var openAiApiKey: String
        get() = prefs.getString(KEY_OPENAI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENAI_API_KEY, value).apply()
    
    var openAiVoice: String
        get() = prefs.getString(KEY_OPENAI_VOICE, DEFAULT_OPENAI_VOICE) ?: DEFAULT_OPENAI_VOICE
        set(value) = prefs.edit().putString(KEY_OPENAI_VOICE, value).apply()
    
    var openAiModel: String
        get() = prefs.getString(KEY_OPENAI_MODEL, DEFAULT_OPENAI_MODEL) ?: DEFAULT_OPENAI_MODEL
        set(value) = prefs.edit().putString(KEY_OPENAI_MODEL, value).apply()
    
    // VOICEVOX settings
    var voiceVoxSpeakerId: Int
        get() = prefs.getInt(KEY_VOICEVOX_SPEAKER_ID, DEFAULT_VOICEVOX_SPEAKER_ID)
        set(value) = prefs.edit().putInt(KEY_VOICEVOX_SPEAKER_ID, value).apply()
    
    var voiceVoxStyleId: Int
        get() = prefs.getInt(KEY_VOICEVOX_STYLE_ID, DEFAULT_VOICEVOX_STYLE_ID)
        set(value) = prefs.edit().putInt(KEY_VOICEVOX_STYLE_ID, value).apply()
    
    var voiceVoxTermsAccepted: Boolean
        get() = prefs.getBoolean(KEY_VOICEVOX_TERMS_ACCEPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_VOICEVOX_TERMS_ACCEPTED, value).apply()

    // Gateway Port for WebSocket agent list connection (default 18789)

    var gatewayPort: Int
        get() = prefs.getInt(KEY_GATEWAY_PORT, 18789)
        set(value) = prefs.edit().putInt(KEY_GATEWAY_PORT, value).apply()

    // Speech recognition silence timeout in ms (default 5000ms)
    var speechSilenceTimeout: Long
        get() = prefs.getLong(KEY_SPEECH_SILENCE_TIMEOUT, 5000L)
        set(value) = prefs.edit().putLong(KEY_SPEECH_SILENCE_TIMEOUT, value).apply()

    // Speech recognition language (BCP-47 tag, empty = system default)
    var speechLanguage: String
        get() = prefs.getString(KEY_SPEECH_LANGUAGE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SPEECH_LANGUAGE, value).apply()

    // App UI language (BCP-47 tag, empty = system default)
    var appLanguage: String
        get() = prefs.getString(KEY_APP_LANGUAGE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_APP_LANGUAGE, value).apply()

    // Thinking sound enabled
    var thinkingSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_THINKING_SOUND_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_THINKING_SOUND_ENABLED, value).apply()

    // Connection Verified
    var isVerified: Boolean
        get() = prefs.getBoolean(KEY_IS_VERIFIED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_VERIFIED, value).apply()

    // Has completed initial setup guide
    var hasCompletedSetup: Boolean
        get() = prefs.getBoolean(KEY_HAS_COMPLETED_SETUP, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_COMPLETED_SETUP, value).apply()

    // Default Agent ID
    var defaultAgentId: String
        get() = prefs.getString(KEY_DEFAULT_AGENT_ID, "main") ?: "main"
        set(value) = prefs.edit().putString(KEY_DEFAULT_AGENT_ID, value).apply()

    // Legacy: was used for gateway chat pipeline, now always false
    var useNodeChat: Boolean
        get() = prefs.getBoolean(KEY_USE_NODE_CHAT, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_NODE_CHAT, value).apply()

    // Connection Type (Gateway vs HTTP) - default to HTTP for generic webhook use
    var connectionType: String
        get() = prefs.getString(KEY_CONNECTION_TYPE, CONNECTION_TYPE_HTTP) ?: CONNECTION_TYPE_HTTP
        set(value) = prefs.edit().putString(KEY_CONNECTION_TYPE, value).apply()

    // Request format: "simple", "openai", or "custom"
    var requestFormat: String
        get() = prefs.getString(KEY_REQUEST_FORMAT, REQUEST_FORMAT_SIMPLE) ?: REQUEST_FORMAT_SIMPLE
        set(value) = prefs.edit().putString(KEY_REQUEST_FORMAT, value).apply()

    // Custom JSON template for the CUSTOM request format.
    // Use {{query}} and {{session_id}} as placeholders.
    var customJsonTemplate: String
        get() = prefs.getString(KEY_CUSTOM_JSON_TEMPLATE, DEFAULT_CUSTOM_JSON_TEMPLATE) ?: DEFAULT_CUSTOM_JSON_TEMPLATE
        set(value) = prefs.edit().putString(KEY_CUSTOM_JSON_TEMPLATE, value).apply()

    // Ignore SSL certificate errors for HTTPS connections (for self-signed certs)
    var httpIgnoreSslErrors: Boolean
        get() = prefs.getBoolean(KEY_HTTP_IGNORE_SSL_ERRORS, false)
        set(value) = prefs.edit().putBoolean(KEY_HTTP_IGNORE_SSL_ERRORS, value).apply()

    // Stop token the LLM appends when the conversation is complete
    var stopToken: String
        get() = prefs.getString(KEY_STOP_TOKEN, DEFAULT_STOP_TOKEN) ?: DEFAULT_STOP_TOKEN
        set(value) = prefs.edit().putString(KEY_STOP_TOKEN, value).apply()

    // Dismiss phrases — user says one of these to close the session instantly
    var dismissPhrases: Set<String>
        get() {
            val raw = prefs.getString(KEY_DISMISS_PHRASES, null)
            return if (raw.isNullOrBlank()) DEFAULT_DISMISS_PHRASES
            else raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
        set(value) = prefs.edit().putString(KEY_DISMISS_PHRASES, value.joinToString(",")).apply()

    // Connection type used by the voice session (wakeword / long-press home)
    // Defaults to the same as connectionType so existing setups behave identically
    var wakewordConnectionType: String
        get() = prefs.getString(KEY_WAKEWORD_CONNECTION_TYPE, connectionType) ?: connectionType
        set(value) = prefs.edit().putString(KEY_WAKEWORD_CONNECTION_TYPE, value).apply()

    /**
     * Get the webhook URL exactly as configured, with no path manipulation.
     */
    fun getWebhookUrl(): String {
        return httpUrl.trim().trimEnd('/')
    }

    @Deprecated("Use getWebhookUrl()", replaceWith = ReplaceWith("getWebhookUrl()"))
    fun getChatCompletionsUrl(): String = getWebhookUrl()

    fun getBaseUrl(): String {
        val url = httpUrl.trimEnd('/')
        val idx = url.indexOf("/v1/")
        return if (idx > 0) url.substring(0, idx) else url
    }

    // Check if configured
    fun isConfigured(): Boolean {
        if (RelayConfig.url(appContext) != null) return true   // конфиг задан снаружи
        return when (connectionType) {
            CONNECTION_TYPE_GATEWAY -> true
            else -> httpUrl.isNotBlank() && isVerified
        }
    }

    // Generate new session ID
    fun generateNewSessionId(): String {
        return UUID.randomUUID().toString()
    }

    // Reset session
    fun resetSession() {
        sessionId = generateNewSessionId()
    }

    companion object {
        private const val PREFS_NAME = "openclaw_secure_prefs"
        private const val KEY_HTTP_URL = "webhook_url"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_LAST_VOICE_MESSAGE_TIMESTAMP = "last_voice_message_timestamp"
        private const val KEY_VOICE_SESSION_ID = "voice_session_id"
        private const val KEY_SESSION_TIMEOUT_MINUTES = "session_timeout_minutes"
        private const val KEY_SESSION_MODE = "session_mode"
        private const val KEY_HOTWORD_ENABLED = "hotword_enabled"
        private const val KEY_WAKE_WORD_PRESET = "wake_word_preset"
        private const val KEY_WAKE_WORD_THRESHOLD = "wake_word_threshold"
        private const val KEY_IS_VERIFIED = "is_verified"
        private const val KEY_TTS_ENABLED = "tts_enabled"
        private const val KEY_CONTINUOUS_MODE = "continuous_mode"
        private const val KEY_RESUME_LATEST_SESSION = "resume_latest_session"
        private const val KEY_TTS_SPEED = "tts_speed"
        private const val KEY_TTS_ENGINE = "tts_engine"
        private const val KEY_GATEWAY_PORT = "gateway_port"
        private const val KEY_DEFAULT_AGENT_ID = "default_agent_id"
        private const val KEY_USE_NODE_CHAT = "use_node_chat"
        private const val KEY_CONNECTION_TYPE = "connection_type"
        private const val KEY_REQUEST_FORMAT = "request_format"
        private const val KEY_CUSTOM_JSON_TEMPLATE = "custom_json_template"
        private const val KEY_HTTP_IGNORE_SSL_ERRORS = "http_ignore_ssl_errors"
        private const val KEY_WAKEWORD_CONNECTION_TYPE = "wakeword_connection_type"
        private const val KEY_STOP_TOKEN = "stop_token"
        private const val KEY_DISMISS_PHRASES = "dismiss_phrases"
        private const val KEY_SPEECH_SILENCE_TIMEOUT = "speech_silence_timeout"
        private const val KEY_THINKING_SOUND_ENABLED = "thinking_sound_enabled"
        private const val KEY_SPEECH_LANGUAGE = "speech_language"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_HAS_COMPLETED_SETUP = "has_completed_setup"
        
        // TTS settings keys
        private const val KEY_TTS_TYPE = "tts_type"
        private const val KEY_ELEVENLABS_API_KEY = "elevenlabs_api_key"
        private const val KEY_ELEVENLABS_VOICE_ID = "elevenlabs_voice_id"
        private const val KEY_ELEVENLABS_MODEL = "elevenlabs_model"
        private const val KEY_ELEVENLABS_SPEED = "elevenlabs_speed"
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_OPENAI_VOICE = "openai_voice"
        private const val KEY_OPENAI_MODEL = "openai_model"
        private const val KEY_VOICEVOX_SPEAKER_ID = "voicevox_speaker_id"
        private const val KEY_VOICEVOX_STYLE_ID = "voicevox_style_id"
        private const val KEY_VOICEVOX_TERMS_ACCEPTED = "voicevox_terms_accepted"

        // Wake word presets (openWakeWord models)
        const val WAKE_WORD_HEY_JARVIS = "hey_jarvis"
        const val WAKE_WORD_ALEXA = "alexa"
        const val WAKE_WORD_HEY_MYCROFT = "hey_mycroft"
        
        const val CONNECTION_TYPE_GATEWAY = "gateway"
        const val CONNECTION_TYPE_HTTP = "http"

        const val REQUEST_FORMAT_SIMPLE = "simple"
        const val REQUEST_FORMAT_OPENAI = "openai"
        const val REQUEST_FORMAT_CUSTOM = "custom"
        const val DEFAULT_CUSTOM_JSON_TEMPLATE = """{"query": "{{query}}", "session_id": "{{session_id}}"}"""
        const val DEFAULT_SESSION_TIMEOUT_MINUTES = 10
        const val SESSION_MODE_ALWAYS_NEW = "always_new"
        const val SESSION_MODE_TTL = "ttl"
        const val SESSION_MODE_PER_CONVERSATION = "per_conversation"
        const val SESSION_MODE_STICKY = "sticky"
        const val DEFAULT_STOP_TOKEN = "[END]"
        val DEFAULT_DISMISS_PHRASES = setOf("stop", "cancel", "never mind", "goodbye", "that's all")
        
        const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
        
        // TTS Type constants
        const val TTS_TYPE_LOCAL = "local"
        const val TTS_TYPE_ELEVENLABS = "elevenlabs"
        const val TTS_TYPE_OPENAI = "openai"
        const val TTS_TYPE_VOICEVOX = "voicevox"
        
        // Default ElevenLabs voice ID (empty - user must set)
        const val DEFAULT_ELEVENLABS_VOICE_ID = ""
        const val DEFAULT_ELEVENLABS_MODEL = "eleven_multilingual_v2"
        
        // Default OpenAI voice
        const val DEFAULT_OPENAI_VOICE = "coral"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini-tts"
        
        // Default VOICEVOX speaker (none - user must select and download)
        const val DEFAULT_VOICEVOX_SPEAKER_ID = 0
        const val DEFAULT_VOICEVOX_STYLE_ID = 0  // 四国めたん あまあま ( Style ID 0 )

        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
