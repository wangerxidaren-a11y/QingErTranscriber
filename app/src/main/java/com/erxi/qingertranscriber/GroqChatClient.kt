package com.erxi.qingertranscriber

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Groq 聊天接口失败时抛出的异常，message 为可直接展示给用户的中文。 */
class GroqChatException(message: String) : IOException(message)

/**
 * Groq 聊天接口客户端（OpenAI 兼容接口），供 v1.3 的 AI 日分析使用，
 * 复用转写用的同一个 Groq API Key。
 *
 * 模型 openai/gpt-oss-120b（Production 状态；免费 tier：30 RPM / 1K RPD /
 * 8K TPM / 200K TPD；上下文 131K tokens；约 500 tps）。
 * 核实来源（2026-11-25）：https://console.groq.com/docs/models 与
 * https://console.groq.com/docs/rate-limits。
 * 未选 llama-3.1/3.3（Enterprise 专属，不在免费 tier 内），未选
 * qwen/qwen3.8-27b（Preview 状态，可能随时下线）。
 */
object GroqChatClient {

    const val CHAT_MODEL = "openai/gpt-oss-120b"

    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

    /** 免费 tier 每分钟 token 上限 8000；单次调用按 7000 预算，留余量给输出波动 */
    private const val FREE_TPM = 8000
    private const val SINGLE_CALL_TOKEN_BUDGET = 7000

    /** 长文稿分段：每段约 6000 字符（约 4000 tokens 输入 + 800 输出 ≈ 4800 tokens） */
    private const val CHUNK_CHAR_BUDGET = 6000

    /** 分段调用间隔 50 秒：约 4800 tokens / 50 秒 ≈ 5.8K tokens/分钟 < 8K 上限 */
    private const val CHUNK_PAUSE_MS = 50_000L

    /** 429 后等待 65 秒重试一次（超过 1 分钟的 TPM 窗口） */
    private const val RETRY_AFTER_429_MS = 65_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * 单次聊天，返回助手回复全文。
     * 失败时抛 GroqChatException（中文、可直接展示）。
     */
    fun chat(apiKey: String, systemPrompt: String, userPrompt: String, maxTokens: Int = 1500): String {
        val body = JSONObject().apply {
            put("model", CHAT_MODEL)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            })
            put("temperature", 0.3)
            put("max_tokens", maxTokens)
        }
        val req = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    when (resp.code) {
                        429 -> throw GroqChatException("Groq 免费额度已用完（聊天接口每天 1000 次请求），请稍后再试")
                        401 -> throw GroqChatException("Groq API 密钥无效，请检查在线设置中的密钥")
                        else -> throw GroqChatException("Groq 聊天服务暂不可用（HTTP ${resp.code}）")
                    }
                }
                val content = JSONObject(text)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    .orEmpty().trim()
                if (content.isEmpty()) {
                    throw GroqChatException("Groq 返回了空回复，请重试")
                }
                return content
            }
        } catch (e: GroqChatException) {
            throw e
        } catch (e: IOException) {
            throw GroqChatException("无法连接 Groq 服务（${e.message}）")
        }
    }

    /**
     * 长文稿分析：短文稿一次调用；长文稿自动分段摘要再合并。
     * onProgress(0..100, 状态文案)，各阶段回调；调用方可在回调里抛
     * CancellationException 来取消（下一个分段边界生效）。
     */
    fun analyzeLongText(
        apiKey: String,
        systemPrompt: String,
        text: String,
        chunkSummaryPrompt: String,
        combinePrompt: String,
        onProgress: (Int, String) -> Unit
    ): String {
        val singleEstimate = estimateTokens(systemPrompt.length + text.length) + 1500
        if (singleEstimate <= SINGLE_CALL_TOKEN_BUDGET) {
            onProgress(10, "正在分析文稿…")
            val result = chat(apiKey, systemPrompt, text)
            onProgress(100, "分析完成")
            return result
        }

        val chunks = splitIntoChunks(text, CHUNK_CHAR_BUDGET)
        val summaries = ArrayList<String>()
        for ((index, chunk) in chunks.withIndex()) {
            onProgress(
                (index * 80 / chunks.size).coerceIn(0, 80),
                "正在分析第 ${index + 1}/${chunks.size} 段…"
            )
            val summary = chatWith429Retry(
                apiKey, systemPrompt,
                "$chunkSummaryPrompt\n\n$chunk",
                maxTokens = 800
            )
            summaries.add(summary)
            if (index < chunks.size - 1) {
                // 先回调一次，让调用方的取消检查在 sleep 之前生效
                onProgress(
                    ((index + 1) * 80 / chunks.size).coerceIn(0, 80),
                    "已完成第 ${index + 1}/${chunks.size} 段，稍候继续…"
                )
                Thread.sleep(CHUNK_PAUSE_MS)
            }
        }
        onProgress(90, "正在合并分析结果…")
        val combined = chat(
            apiKey, systemPrompt,
            "$combinePrompt\n\n" + summaries.joinToString("\n\n---\n\n"),
            maxTokens = 1500
        )
        onProgress(100, "分析完成")
        return combined
    }

    /** 429 时等 65 秒重试一次；仍失败则把异常抛给调用方 */
    private fun chatWith429Retry(
        apiKey: String,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int
    ): String {
        try {
            return chat(apiKey, systemPrompt, userPrompt, maxTokens)
        } catch (e: GroqChatException) {
            if (e.message?.contains("额度已用完") == true) {
                Thread.sleep(RETRY_AFTER_429_MS)
                return chat(apiKey, systemPrompt, userPrompt, maxTokens)
            }
            throw e
        }
    }

    /** token 粗估：中文约 1.5 字符/token，再加 100 余量 */
    private fun estimateTokens(chars: Int): Int = (chars / 1.5).toInt() + 100

    /** 按行切分，尽量在换行处断开；单行超长时硬切 */
    private fun splitIntoChunks(text: String, maxChars: Int): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (line in text.split("\n")) {
            if (line.length > maxChars) {
                if (current.isNotEmpty()) {
                    out.add(current.toString())
                    current.clear()
                }
                var rest = line
                while (rest.length > maxChars) {
                    out.add(rest.substring(0, maxChars))
                    rest = rest.substring(maxChars)
                }
                current.append(rest)
                continue
            }
            if (current.isNotEmpty() && current.length + line.length + 1 > maxChars) {
                out.add(current.toString())
                current.clear()
            }
            if (current.isNotEmpty()) current.append("\n")
            current.append(line)
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out.ifEmpty { listOf(text) }
    }
}
