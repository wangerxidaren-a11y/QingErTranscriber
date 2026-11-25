package com.erxi.qingertranscriber

import android.content.Context

object TranscribeSettings {

    private const val PREFS = "qinger_transcribe_prefs"
    private const val KEY_MODE = "transcribe_mode"
    private const val KEY_API_KEY = "online_api_key"
    private const val KEY_DELETE_AUDIO = "delete_audio_after_transcribe"

    /** 在线极速模式（默认） */
    const val MODE_ONLINE = "online"

    /** 本地离线模式 */
    const val MODE_OFFLINE = "offline"

    /** 在线引擎：Groq Whisper（whisper-large-v3-turbo），免费额度每天 8 小时音频 */
    const val ONLINE_MODEL = "whisper-large-v3-turbo"

    /** Groq 免费领 Key 入口：邮箱或 Google 注册即可，无需实名 */
    const val GROQ_KEY_URL = "https://console.groq.com"

    // 注：v1.1 留下的 online_base_url / online_model 偏好在 v1.2 起不再读取（在线引擎固定为 Groq）。

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

    /** 在线转写是否可用：在线模式且已填写密钥 */
    fun isOnlineEnabled(context: Context): Boolean =
        isOnlineMode(context) && getApiKey(context).isNotBlank()

    /**
     * v1.3：转写后删除原录音（只留文字），默认关闭。
     * 借鉴 OPPO AI 心力球“只留文字、不留原音”的隐私做法：开启后，录音转写全部完成后
     * 自动删除原录音文件，只保留文稿和分析。
     */
    fun isDeleteAudioAfterTranscribe(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DELETE_AUDIO, false)

    fun setDeleteAudioAfterTranscribe(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DELETE_AUDIO, enabled).apply()
    }
}
