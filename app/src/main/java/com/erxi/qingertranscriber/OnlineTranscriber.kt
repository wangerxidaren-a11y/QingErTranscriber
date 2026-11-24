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

data class OnlineSegment(
    val start: Double,
    val end: Double,
    val text: String
)

/**
 * 在线转写失败时抛出的异常。
 * 调用方收到此异常后应回退到本地离线引擎继续转写（密钥无效 401、限流/额度用完 429、网络/服务端错误都走同一条回退路径）。
 */
class OnlineFallbackException(message: String) : IOException(message)

/**
 * 通用在线转写客户端，兼容 OpenAI `/v1/audio/transcriptions` 格式接口。
 * 默认接硅基流动（https://api.siliconflow.cn/v1），也可在设置里改成 Groq 等其他兼容接口。
 */
object OnlineTranscriber {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /**
     * 转写一段 16kHz 单声道 PCM 采样，返回带时间戳的片段（时间相对于本段起点）。
     * 服务端不返回分段时退化为整段一条（时间戳取段起点）。
     * 失败时抛出 OnlineFallbackException，调用方回退离线。
     */
    fun transcribeChunk(
        samples: FloatArray,
        baseUrl: String,
        apiKey: String,
        model: String
    ): List<OnlineSegment> {
        val endpoint = baseUrl.trim().trimEnd('/') + "/audio/transcriptions"
        val wav = encodeWav16kMono(samples)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("file", "chunk.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    when (resp.code) {
                        401 -> throw OnlineFallbackException("在线 API 密钥无效（401），请检查在线设置中的密钥")
                        429 -> throw OnlineFallbackException("在线服务请求过于频繁或额度已用完（429）")
                        else -> throw OnlineFallbackException("在线服务暂不可用（HTTP ${resp.code}）")
                    }
                }
                return parseSegments(text, samples.size / 16000.0)
            }
        } catch (e: OnlineFallbackException) {
            throw e
        } catch (e: IOException) {
            throw OnlineFallbackException("无法连接在线服务（${e.message}）")
        }
    }

    private fun parseSegments(json: String, chunkDurationSec: Double): List<OnlineSegment> {
        val out = ArrayList<OnlineSegment>()
        val obj = JSONObject(json)
        val arr = obj.optJSONArray("segments")
        if (arr != null && arr.length() > 0) {
            for (i in 0 until arr.length()) {
                val seg = arr.optJSONObject(i) ?: continue
                val text = seg.optString("text", "").trim()
                if (text.isNotEmpty()) {
                    out.add(OnlineSegment(seg.optDouble("start", 0.0), seg.optDouble("end", 0.0), text))
                }
            }
            if (out.isNotEmpty()) return out
        }
        // 服务端只返回全文时，整段记一条，时间戳取段起点
        val fullText = obj.optString("text", "").trim()
        if (fullText.isNotEmpty()) {
            out.add(OnlineSegment(0.0, chunkDurationSec, fullText))
        }
        return out
    }

    /** Float 采样（16kHz 单声道，取值 -1..1）编码为 WAV 字节，供在线接口上传使用。 */
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
