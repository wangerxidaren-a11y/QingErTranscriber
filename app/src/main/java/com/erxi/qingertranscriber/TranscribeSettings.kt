package com.erxi.qingertranscriber

import android.content.Context

object TranscribeSettings {

    private const val PREFS = "qinger_transcribe_prefs"
    private const val KEY_MODE = "transcribe_mode"
    private const val KEY_API_KEY = "online_api_key"
    private const val KEY_BASE_URL = "online_base_url"
    private const val KEY_MODEL = "online_model"

    /** 在线极速模式（默认） */
    const val MODE_ONLINE = "online"

    /** 本地离线模式 */
    const val MODE_OFFLINE = "offline"

    /** 默认接口：硅基流动（OpenAI 兼容格式） */
    const val DEFAULT_BASE_URL = "https://api.siliconflow.cn/v1"

    /** 免费推荐模型预设 */
    const val MODEL_SENSE_VOICE = "FunAudioLLM/SenseVoiceSmall"
    const val MODEL_TELE_SPEECH = "TeleAI/TeleSpeechASR"
    const val DEFAULT_MODEL = MODEL_SENSE_VOICE

    /** 硅基流动免费领 Key 入口 */
    const val SILICONFLOW_KEY_URL = "https://cloud.siliconflow.cn/account/ak"

    fun getMode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, MODE_ONLINE) ?: MODE_ONLINE

    fun setMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode).apply()
    }

    /** 是否在线模式：默认在线，只有明确设为离线时才离线 */
    fun isOnlineMode(context: Context): Boolean = getMode(context) != MODE_OFFLINE

    fun getApiKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, "").orEmpty()

    fun setApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun getBaseUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BASE_URL, DEFAULT_BASE_URL)
            ?.ifBlank { DEFAULT_BASE_URL } ?: DEFAULT_BASE_URL

    fun setBaseUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_BASE_URL, url.trim().ifBlank { DEFAULT_BASE_URL }).apply()
    }

    fun getModel(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODEL, DEFAULT_MODEL)
            ?.ifBlank { DEFAULT_MODEL } ?: DEFAULT_MODEL

    fun setModel(context: Context, model: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODEL, model.trim().ifBlank { DEFAULT_MODEL }).apply()
    }

    /** 在线转写是否可用：在线模式且已填写密钥 */
    fun isOnlineEnabled(context: Context): Boolean =
        isOnlineMode(context) && getApiKey(context).isNotBlank()
}
