package com.erxi.qingertranscriber

import android.content.Context

object TranscribeSettings {

    private const val PREFS = "qinger_transcribe_prefs"
    private const val KEY_GROQ_API_KEY = "groq_api_key"
    private const val KEY_MODE = "transcribe_mode"

    /** 在线优先：有 Groq 密钥时先走在线，额度用完/不可用时自动转离线 */
    const val MODE_AUTO = "auto"

    /** 仅离线：不使用在线服务 */
    const val MODE_OFFLINE = "offline"

    fun getGroqApiKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GROQ_API_KEY, "").orEmpty()

    fun setGroqApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_GROQ_API_KEY, key.trim()).apply()
    }

    fun getMode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, MODE_AUTO) ?: MODE_AUTO

    fun setMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode).apply()
    }

    /** 是否启用在线转写：模式为在线优先且已填写密钥 */
    fun isOnlineEnabled(context: Context): Boolean =
        getMode(context) == MODE_AUTO && getGroqApiKey(context).isNotBlank()
}
