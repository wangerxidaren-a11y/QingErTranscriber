package com.erxi.qingertranscriber

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteOrder
import kotlin.math.sqrt

object AudioDecoder {

    private const val TARGET_SAMPLE_RATE = 16000
    private const val DEFAULT_MIN_CHUNK_SEC = 16 // 离线默认：最短 16 秒寻找静音切点
    private const val DEFAULT_MAX_CHUNK_SEC = 26 // 离线默认：最长 26 秒强制切分
    private const val SILENCE_RMS_THRESHOLD = 0.018f
    private const val SILENCE_WINDOW = TARGET_SAMPLE_RATE / 5 // 200ms 静音检测窗

    /**
     * 解码为 16kHz 单声道分段回调。
     * @param minChunkSec 达到该时长后遇静音即切分（在线约 3 分钟：150 秒）
     * @param maxChunkSec 达到该时长强制切分（在线约 3 分钟：200 秒）
     */
    fun decodeInChunks(
        context: Context,
        file: File?,
        uri: Uri?,
        isCancelled: () -> Boolean,
        minChunkSec: Int = DEFAULT_MIN_CHUNK_SEC,
        maxChunkSec: Int = DEFAULT_MAX_CHUNK_SEC,
        onChunk: (samples: FloatArray, startSec: Double, progressFraction: Float) -> Unit
    ) {
        val minChunkSamples = minChunkSec * TARGET_SAMPLE_RATE
        val maxChunkSamples = maxChunkSec * TARGET_SAMPLE_RATE
        val extractor = MediaExtractor()
        try {
            if (file != null) {
                extractor.setDataSource(file.absolutePath)
            } else if (uri != null) {
                extractor.setDataSource(context, uri, null)
            } else {
                throw IllegalArgumentException("未提供音频源")
            }

            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = fmt
                    break
                }
            }

            if (audioTrackIndex < 0 || inputFormat == null) {
                throw IllegalStateException("该文件中未找到音轨")
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }

            var srcSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var srcChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(inputFormat, null, null, 0)
                codec.start()

                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false

                // 累计 16kHz 单声道 float 缓冲
                var chunkBuf = FloatArray(maxChunkSamples + TARGET_SAMPLE_RATE * 4)
                var chunkLen = 0
                var totalEmittedSamples = 0L

                // 跨帧线性重采样状态
                var resamplePos = 0.0
                var prevMonoSample = 0f

                while (!outputDone && !isCancelled()) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)!!
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFmt = codec.outputFormat
                            if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                srcSampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                srcChannels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                        }
                        outIdx >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null && bufferInfo.size > 0) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                val shortBuf = outBuf.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                val totalShorts = shortBuf.remaining()
                                val frameCount = totalShorts / srcChannels

                                if (frameCount > 0) {
                                    val mono = FloatArray(frameCount)
                                    for (f in 0 until frameCount) {
                                        var sum = 0f
                                        for (c in 0 until srcChannels) {
                                            sum += shortBuf.get() / 32768f
                                        }
                                        mono[f] = sum / srcChannels
                                    }

                                    // 线性重采样至 16kHz
                                    val step = srcSampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
                                    while (resamplePos < frameCount) {
                                        val idx0 = resamplePos.toInt()
                                        val frac = (resamplePos - idx0).toFloat()
                                        val s0 = if (idx0 == 0 && resamplePos < 0.0) prevMonoSample else mono[idx0]
                                        val s1 = if (idx0 + 1 < frameCount) mono[idx0 + 1] else mono[frameCount - 1]
                                        val sample = s0 + (s1 - s0) * frac

                                        if (chunkLen >= chunkBuf.size) {
                                            chunkBuf = chunkBuf.copyOf(chunkBuf.size * 2)
                                        }
                                        chunkBuf[chunkLen++] = sample
                                        resamplePos += step
                                    }
                                    resamplePos -= frameCount
                                    prevMonoSample = mono[frameCount - 1]

                                    // 检查是否可以智能切分并送出识别
                                    val shouldFlush = chunkLen >= maxChunkSamples ||
                                        (chunkLen >= minChunkSamples && isTrailingSilence(chunkBuf, chunkLen))

                                    if (shouldFlush) {
                                        val startSec = totalEmittedSamples.toDouble() / TARGET_SAMPLE_RATE
                                        val progress = if (durationUs > 0L) {
                                            (bufferInfo.presentationTimeUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 0.99f)
                                        } else {
                                            0.5f
                                        }
                                        val emitArr = chunkBuf.copyOf(chunkLen)
                                        totalEmittedSamples += chunkLen
                                        chunkLen = 0
                                        onChunk(emitArr, startSec, progress)
                                    }
                                }
                            }

                            codec.releaseOutputBuffer(outIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                    }
                }

                // 处理最后一段残留音频
                if (!isCancelled() && chunkLen > TARGET_SAMPLE_RATE / 4) {
                    val startSec = totalEmittedSamples.toDouble() / TARGET_SAMPLE_RATE
                    onChunk(chunkBuf.copyOf(chunkLen), startSec, 1.0f)
                }
            } finally {
                try { codec.stop() } catch (_: Throwable) {}
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun isTrailingSilence(buf: FloatArray, len: Int): Boolean {
        if (len < SILENCE_WINDOW) return false
        var sumSq = 0.0
        for (i in (len - SILENCE_WINDOW) until len) {
            val v = buf[i].toDouble()
            sumSq += v * v
        }
        val rms = sqrt(sumSq / SILENCE_WINDOW).toFloat()
        return rms < SILENCE_RMS_THRESHOLD
    }
}
