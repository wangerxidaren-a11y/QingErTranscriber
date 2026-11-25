package com.erxi.qingertranscriber

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

data class GroqSegment(
    val start: Double,
    val end: Double,
    val text: String
)

/**
 * Groq 在线转写失败时抛出的异常。
 * 调用方收到此异常后应回退到本地离线引擎继续转写（额度用完 429、密钥无效 401、网络/服务端错误都走同一条回退路径）。
 */
class GroqFallbackException(message: String) : IOException(message)

/**
 * Groq Whisper 在线转写客户端（OpenAI 兼容接口）。
 * 模型 whisper-large-v3-turbo；免费额度：20 请求/分钟、每天 8 小时音频（28,800 音频秒/天）；
 * 单文件上限 25 MB（免费版）。见 https://console.groq.com/docs/speech-to-text 与 /docs/rate-limits。
 * 注册只需邮箱或 Google 账号，无需实名。
 */
object GroqTranscriber {

    private const val ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val MODEL = "whisper-large-v3-turbo"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /**
     * 转写一段 16kHz 单声道 PCM 采样，返回带时间戳的片段（时间相对于本段起点）。
     * 服务端不返回分段时退化为整段一条（时间戳取段起点）。
     * 失败时抛出 GroqFallbackException，调用方回退离线。
     */
    fun transcribeChunk(samples: FloatArray, apiKey: String): List<GroqSegment> {
        val wav = encodeWav16kMono(samples)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", MODEL)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("file", "chunk.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    when (resp.code) {
                        429 -> throw GroqFallbackException("Groq 免费额度已用完（每天 8 小时音频）")
                        401 -> throw GroqFallbackException("Groq API 密钥无效，请检查在线设置中的密钥")
                        else -> throw GroqFallbackException("Groq 在线服务暂不可用（HTTP ${resp.code}）")
                    }
                }
                return parseSegments(text, samples.size / 16000.0)
            }
        } catch (e: GroqFallbackException) {
            throw e
        } catch (e: IOException) {
            throw GroqFallbackException("无法连接 Groq 在线服务（${e.message}）")
        }
    }

    private fun parseSegments(json: String, chunkDurationSec: Double): List<GroqSegment> {
        val out = ArrayList<GroqSegment>()
        val obj = JSONObject(json)
        val arr = obj.optJSONArray("segments")
        if (arr != null && arr.length() > 0) {
            for (i in 0 until arr.length()) {
                val seg = arr.optJSONObject(i) ?: continue
                val text = seg.optString("text", "").trim()
                if (text.isNotEmpty()) {
                    out.add(GroqSegment(seg.optDouble("start", 0.0), seg.optDouble("end", 0.0), text))
                }
            }
            if (out.isNotEmpty()) return out
        }
        // 服务端只返回全文时，整段记一条，时间戳取段起点
        val fullText = obj.optString("text", "").trim()
        if (fullText.isNotEmpty()) {
            out.add(GroqSegment(0.0, chunkDurationSec, fullText))
        }
        return out
    }

    /** Float 采样（16kHz 单声道，取值 -1..1）编码为 WAV 字节，供 Groq 上传使用。 */
    fun encodeWav16kMono(samples: FloatArray): ByteArray {
        val dataSize = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(1) // 单声道
        header.putInt(16000)
        header.putInt(16000 * 2) // 每秒字节数
        header.putShort(2) // 块对齐
        header.putShort(16) // 位深
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)
        out.write(header.array())
        val pcm = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            val v = (s.coerceIn(-1f, 1f) * 32767f).toInt().coerceIn(-32768, 32767)
            pcm.putShort(v.toShort())
        }
        out.write(pcm.array())
        return out.toByteArray()
    }
}
