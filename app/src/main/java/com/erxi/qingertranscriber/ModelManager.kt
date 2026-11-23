package com.erxi.qingertranscriber

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object ModelManager {

    private const val MODEL_FILE_NAME = "model.int8.onnx"
    private const val TOKENS_FILE_NAME = "tokens.txt"
    private const val MIN_MODEL_BYTES = 100_000_000L

    private val MIRRORS = listOf(
        "魔搭国内高速源 1" to "https://www.modelscope.cn/models/fengge2024/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/master/",
        "魔搭国内高速源 2" to "https://www.modelscope.cn/models/mariolux/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/master/",
        "HF 国内镜像源" to "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/",
        "HuggingFace 官方源" to "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun getModelDir(context: Context): File =
        File(context.filesDir, "sensevoice").apply { mkdirs() }

    fun getModelFile(context: Context): File =
        File(getModelDir(context), MODEL_FILE_NAME)

    fun getTokensFile(context: Context): File =
        File(getModelDir(context), TOKENS_FILE_NAME)

    fun isModelReady(context: Context): Boolean {
        val model = getModelFile(context)
        val tokens = getTokensFile(context)
        return model.exists() && model.length() > MIN_MODEL_BYTES &&
            tokens.exists() && tokens.length() > 1000L
    }

    suspend fun downloadModel(
        context: Context,
        onProgress: (status: String, percent: Int) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val tokensFile = getTokensFile(context)
        val modelFile = getModelFile(context)

        var lastError: Throwable? = null
        for ((mirrorName, baseUrl) in MIRRORS) {
            try {
                if (!tokensFile.exists() || tokensFile.length() < 1000L) {
                    onProgress("正在从 $mirrorName 下载词表…", 2)
                    downloadFile(baseUrl + TOKENS_FILE_NAME, tokensFile) { _, _, _ -> }
                }

                if (!modelFile.exists() || modelFile.length() < MIN_MODEL_BYTES) {
                    onProgress("正在从 $mirrorName 下载 SenseVoice 离线模型（约 228 MB）…", 5)
                    downloadFile(baseUrl + MODEL_FILE_NAME, modelFile) { pct, doneMb, totalMb ->
                        val label = if (totalMb > 0) {
                            "正在下载模型（$mirrorName）：%.1f / %.1f MB".format(doneMb, totalMb)
                        } else {
                            "正在下载模型（$mirrorName）：%.1f MB".format(doneMb)
                        }
                        onProgress(label, pct.coerceIn(5, 99))
                    }
                }

                if (isModelReady(context)) {
                    onProgress("离线模型已就绪（SenseVoice 多语种）", 100)
                    return@withContext Result.success(Unit)
                }
            } catch (e: Throwable) {
                lastError = e
                onProgress("$mirrorName 连接失败，正在自动切换备用源…", 3)
            }
        }
        Result.failure(lastError ?: IllegalStateException("所有模型源均下载失败，请检查网络连接"))
    }

    private fun downloadFile(
        url: String,
        target: File,
        onProgress: (percent: Int, doneMb: Double, totalMb: Double) -> Unit
    ) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} ($url)")
            }
            val body = resp.body ?: throw IllegalStateException("响应内容为空")
            val total = body.contentLength()
            var downloaded = 0L
            var lastEmit = 0L

            body.byteStream().use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 250) {
                            lastEmit = now
                            val pct = if (total > 0) ((downloaded * 100) / total).toInt() else 10
                            onProgress(pct, downloaded / 1048576.0, if (total > 0) total / 1048576.0 else -1.0)
                        }
                    }
                    output.flush()
                }
            }
        }

        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
