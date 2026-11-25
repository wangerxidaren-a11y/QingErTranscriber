package com.erxi.qingertranscriber

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig

/**
 * 共享离线转写引擎（SenseVoice），供 TranscriberService 与 RecordingService 共用。
 *
 * 配置与 v1.2 的 TranscriberService.ensureOfflineRecognizer() 完全一致。
 * 只保证单线程使用（调用方都是单线程 worker）。
 */
class OfflineTranscriber(context: Context) {

    private val appContext = context.applicationContext
    private var recognizer: OfflineRecognizer? = null

    /** 模型是否已加载 */
    val isLoaded: Boolean
        get() = recognizer != null

    /**
     * 首次调用时加载模型；已加载时直接返回。
     * 模型未下载时抛 IllegalStateException（中文提示，与 v1.2 文案一致）。
     */
    @Throws(IllegalStateException::class)
    fun ensureLoaded() {
        if (recognizer != null) return
        if (!ModelManager.isModelReady(appContext)) {
            throw IllegalStateException("离线模型尚未下载，请先点击顶部「下载离线模型」")
        }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = ModelManager.getModelFile(appContext).absolutePath,
                    language = "",
                    useInverseTextNormalization = true
                ),
                tokens = ModelManager.getTokensFile(appContext).absolutePath,
                numThreads = threads,
                provider = "cpu"
            )
        )
        recognizer = OfflineRecognizer(assetManager = null, config = config)
    }

    /**
     * 转写一段 16kHz 单声道音频，返回去空白后的文本（可为空串）。
     * 调用前须先 ensureLoaded()，否则抛 IllegalStateException。
     */
    fun transcribe(samples: FloatArray): String {
        val rec = recognizer
            ?: throw IllegalStateException("离线引擎尚未加载，请先调用 ensureLoaded()")
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, 16000)
            rec.decode(stream)
            return rec.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    /** 释放模型（幂等） */
    fun release() {
        recognizer?.release()
        recognizer = null
    }
}
