package com.erxi.qingertranscriber

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordingState(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val elapsedSec: Int = 0,
    val micSource: MicSource = MicSource.PHONE,
    val statusMessage: String = "未开始录音",
    val liveTranscript: String = "",
    val pendingChunks: Int = 0,
    val transcribedSegments: Int = 0,
    val highlightCount: Int = 0,
    val currentRecordingId: String? = null,
    val errorMessage: String? = null
)

data class RecordingMeta(
    val id: String,
    val title: String,
    val startTimeMillis: Long,
    val durationSec: Int,
    val status: String, // "recording" | "transcribing" | "done" | "partial"
    val micSource: String, // "phone" | "bluetooth"
    val segmentCount: Int,
    val pendingChunks: Int,
    val hasAnalysis: Boolean,
    val highlights: List<Int> = emptyList(), // 重点标记点的秒数
    val audioDeleted: Boolean = false // 转写后是否已删除原录音（只留文字）
)

/**
 * 录音前台服务：应用内录音 + 边录边转。
 *
 * 设计要点（与 TranscriberService 的在线优先/回退策略一致）：
 * - 录音和识别分开：AudioRecorder 只负责采音切段，转写 worker 单协程顺序消费；
 *   转写失败绝不打断录音，失败分段以 chunk 文件形式留在磁盘，可稍后补转
 * - 在线优先：Groq 额度用完（429）/密钥无效（401）时锁死回退离线；网络等临时问题
 *   只让本段排队，下一段继续尝试在线（直播场景下网络可能恢复）
 * - 离线模型未下载时也不打断录音，分段排队，之后补转
 */
class RecordingService : Service() {

    companion object {
        private const val CHANNEL_ID = "qinger_record_channel"
        private const val NOTIF_ID = 1002

        const val ACTION_START = "ACTION_START_RECORD"
        const val ACTION_PAUSE = "ACTION_PAUSE_RECORD"
        const val ACTION_RESUME = "ACTION_RESUME_RECORD"
        const val ACTION_STOP = "ACTION_STOP_RECORD"
        const val ACTION_MARK = "ACTION_MARK_HIGHLIGHT"
        const val ACTION_RETRY = "ACTION_RETRY_PENDING"
        const val EXTRA_RECORDING_ID = "EXTRA_RECORDING_ID"

        private val _state = MutableStateFlow(RecordingState())
        val state: StateFlow<RecordingState> = _state.asStateFlow()

        fun startRecording(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pauseRecording(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply { action = ACTION_PAUSE }
            context.startService(intent)
        }

        fun resumeRecording(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply { action = ACTION_RESUME }
            context.startService(intent)
        }

        fun stopRecording(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        fun markHighlight(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply { action = ACTION_MARK }
            context.startService(intent)
        }

        fun retryPending(context: Context, recordingId: String) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_RETRY
                putExtra(EXTRA_RECORDING_ID, recordingId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun getRecordingsDir(context: Context): File =
            File(context.filesDir, "recordings").apply { mkdirs() }

        fun listRecordings(context: Context): List<RecordingMeta> {
            val dir = getRecordingsDir(context)
            val subdirs = dir.listFiles { f -> f.isDirectory }.orEmpty()
            val out = ArrayList<RecordingMeta>()
            for (sub in subdirs) {
                val meta = readMeta(File(sub, "meta.json")) ?: continue
                out.add(meta)
            }
            return out.sortedByDescending { it.startTimeMillis }
        }

        fun readTranscript(context: Context, recordingId: String): String {
            val f = File(File(getRecordingsDir(context), recordingId), "transcript.md")
            return if (f.exists()) f.readText(Charsets.UTF_8) else ""
        }

        fun readAnalysis(context: Context, recordingId: String): String? {
            val f = File(File(getRecordingsDir(context), recordingId), "analysis.md")
            return if (f.exists()) f.readText(Charsets.UTF_8) else null
        }

        fun deleteRecording(context: Context, recordingId: String): Boolean {
            val dir = File(getRecordingsDir(context), recordingId)
            return if (dir.exists()) dir.deleteRecursively() else false
        }

        /** 标记某条录音已有 AI 分析（DailyAnalysis 保存后调用） */
        fun setHasAnalysis(context: Context, recordingId: String, value: Boolean) {
            val metaFile = File(File(getRecordingsDir(context), recordingId), "meta.json")
            val meta = readMeta(metaFile) ?: return
            writeMetaFile(metaFile, meta.copy(hasAnalysis = value))
        }

        private fun readMeta(file: File): RecordingMeta? {
            if (!file.exists()) return null
            return try {
                val obj = JSONObject(file.readText(Charsets.UTF_8))
                val highlights = ArrayList<Int>()
                val arr = obj.optJSONArray("highlights")
                if (arr != null) {
                    for (i in 0 until arr.length()) highlights.add(arr.optInt(i))
                }
                RecordingMeta(
                    id = obj.optString("id"),
                    title = obj.optString("title"),
                    startTimeMillis = obj.optLong("startTimeMillis"),
                    durationSec = obj.optInt("durationSec"),
                    status = obj.optString("status"),
                    micSource = obj.optString("micSource"),
                    segmentCount = obj.optInt("segmentCount"),
                    pendingChunks = obj.optInt("pendingChunks"),
                    hasAnalysis = obj.optBoolean("hasAnalysis"),
                    highlights = highlights,
                    audioDeleted = obj.optBoolean("audioDeleted")
                )
            } catch (_: Throwable) {
                null
            }
        }

        private fun writeMetaFile(file: File, meta: RecordingMeta) {
            val obj = JSONObject().apply {
                put("id", meta.id)
                put("title", meta.title)
                put("startTimeMillis", meta.startTimeMillis)
                put("durationSec", meta.durationSec)
                put("status", meta.status)
                put("micSource", meta.micSource)
                put("segmentCount", meta.segmentCount)
                put("pendingChunks", meta.pendingChunks)
                put("hasAnalysis", meta.hasAnalysis)
                put("highlights", JSONArray(meta.highlights))
                put("audioDeleted", meta.audioDeleted)
            }
            file.writeText(obj.toString(), Charsets.UTF_8)
        }
    }

    /** 待转写分段：文件已落盘，只传路径与起点秒数，内存里不攒音频 */
    private data class PendingChunk(val file: File, val startSec: Double)

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recorder: AudioRecorder? = null
    private var offlineTranscriber: OfflineTranscriber? = null
    private var workerJob: Job? = null
    private var timerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var transcribeChannel = Channel<PendingChunk>(Channel.UNLIMITED)

    /** 所有文稿与 meta 修改都经这把锁串行化（转写 worker 与重点标记可能同时写） */
    private val transcriptMutex = Mutex()

    private var currentDir: File? = null
    private var currentMeta: RecordingMeta? = null
    private var transcriptBuilder = StringBuilder()
    private var segmentCount = 0
    private var chunkIndex = 0
    private var useOnline = false
    private var onlineApiKey = ""
    private var offlineUnavailable = false
    private var starting = false
    private var stopping = false
    private var retrying = false
    private var pcmOut: FileOutputStream? = null
    private var bluetoothFallbackNoted = false
    private var finalDurationSec = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (_state.value.isRecording || starting || stopping || retrying) return START_NOT_STICKY
                beginForeground("正在开始录音…")
                serviceScope.launch { beginRecordingInternal() }
            }
            ACTION_PAUSE -> {
                recorder?.pause()
                if (_state.value.isRecording) {
                    updateState(isPaused = true, status = "已暂停，点继续可恢复录音")
                }
            }
            ACTION_RESUME -> {
                recorder?.resume()
                if (_state.value.isRecording) {
                    updateState(isPaused = false, status = "正在录音…")
                }
            }
            ACTION_STOP -> {
                serviceScope.launch { stopRecordingInternal() }
            }
            ACTION_MARK -> {
                markHighlightInternal()
            }
            ACTION_RETRY -> {
                val id = intent.getStringExtra(EXTRA_RECORDING_ID).orEmpty()
                if (id.isBlank() || _state.value.isRecording || starting || stopping || retrying) {
                    return START_NOT_STICKY
                }
                beginForeground("正在补转录音分段…")
                serviceScope.launch { retryPendingInternal(id) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { recorder?.release() } catch (_: Throwable) {}
        try { offlineTranscriber?.release() } catch (_: Throwable) {}
        releaseWakeLock()
        serviceScope.cancel()
    }

    private fun beginForeground(text: String) {
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        acquireWakeLock()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "录音",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("轻耳·录音")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QingEr::RecordWakeLock")
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(4 * 60 * 60 * 1000L) // 最长 4 小时保活
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    // ---------- 录音 ----------

    private suspend fun beginRecordingInternal() {
        if (_state.value.isRecording || starting) return
        starting = true
        stopping = false
        try {
            val now = Date()
            val id = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
            val title = "录音 " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(now)
            val dir = File(getRecordingsDir(this), id).apply { mkdirs() }
            currentDir = dir
            transcriptBuilder = StringBuilder()
            segmentCount = 0
            chunkIndex = 0
            useOnline = TranscribeSettings.isOnlineEnabled(applicationContext)
            onlineApiKey = TranscribeSettings.getApiKey(applicationContext)
            offlineUnavailable = false
            bluetoothFallbackNoted = false
            finalDurationSec = 0
            transcribeChannel = Channel(Channel.UNLIMITED)
            currentMeta = RecordingMeta(id, title, now.time, 0, "recording", "phone", 0, 0, false)
            writeMeta(currentMeta!!)
            pcmOut = FileOutputStream(File(dir, "audio.pcm"))

            updateState(
                isRecording = true, isPaused = false, elapsedSec = 0,
                micSource = MicSource.PHONE, status = "正在开始录音…",
                liveTranscript = "", pendingChunks = 0, transcribedSegments = 0,
                highlightCount = 0, currentRecordingId = id, error = null
            )

            val listener = object : AudioRecorderListener {
                override fun onChunk(chunk: AudioChunk) {
                    handleChunk(chunk)
                }

                override fun onMicSourceChanged(source: MicSource) {
                    if (source == MicSource.PHONE && !bluetoothFallbackNoted) {
                        bluetoothFallbackNoted = true
                        updateState(
                            micSource = MicSource.PHONE,
                            status = "蓝牙连接失败，已改用手机麦克风继续录音"
                        )
                    } else {
                        updateState(micSource = source)
                    }
                }

                override fun onPausedByCall() {
                    updateState(isPaused = true, status = "来电已自动暂停，通话结束点继续可恢复录音")
                }

                override fun onError(message: String) {
                    updateState(status = "录音出错：$message", error = message)
                    serviceScope.launch { stopRecordingInternal() }
                }
            }
            val rec = AudioRecorder(applicationContext, listener)
            recorder = rec
            try {
                // 阻塞：蓝牙 SCO 最多等 4 秒（在 IO 线程，无 ANR 风险）
                rec.start()
            } catch (e: IllegalStateException) {
                updateState(isRecording = false, status = "录音启动失败：${e.message}", error = e.message)
                cleanupAfterFailedStart()
                return
            }
            // 成功：按实际来源写 meta
            val actualSource = if (rec.micSource == MicSource.BLUETOOTH) "bluetooth" else "phone"
            currentMeta = currentMeta?.copy(micSource = actualSource)
            currentMeta?.let { writeMeta(it) }
            updateState(
                status = if (rec.micSource == MicSource.BLUETOOTH) "正在录音（蓝牙耳机）…" else "正在录音…",
                micSource = rec.micSource
            )

            // 转写 worker + 计时（计时不刷通知，只更新界面）
            workerJob = serviceScope.launch { runTranscribeWorker() }
            timerJob = serviceScope.launch {
                while (isActive) {
                    val sec = rec.recordedDurationSec
                    if (_state.value.elapsedSec != sec) {
                        _state.value = _state.value.copy(elapsedSec = sec)
                    }
                    delay(1000)
                }
            }
        } finally {
            starting = false
        }
    }

    /** 启动失败清理：释放录音器、删掉空录音目录、停前台 */
    private fun cleanupAfterFailedStart() {
        try { recorder?.release() } catch (_: Throwable) {}
        recorder = null
        try { pcmOut?.close() } catch (_: Throwable) {}
        pcmOut = null
        try { currentDir?.deleteRecursively() } catch (_: Throwable) {}
        currentDir = null
        currentMeta = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 分段回调（录音线程）：落盘分段 WAV、追加完整录音 PCM、入队转写 */
    private fun handleChunk(chunk: AudioChunk) {
        val dir = currentDir ?: return
        val index = chunkIndex++
        val file = chunkFile(dir, index, chunk.startSec)
        try {
            file.writeBytes(GroqTranscriber.encodeWav16kMono(chunk.samples))
        } catch (e: Throwable) {
            updateState(status = "保存录音分段失败：${e.message}", error = e.message)
            return
        }
        try {
            val out = pcmOut ?: return
            val buf = ByteBuffer.allocate(chunk.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in chunk.samples) {
                buf.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            }
            out.write(buf.array())
            out.flush()
        } catch (e: Throwable) {
            updateState(status = "保存完整录音失败：${e.message}")
        }
        transcribeChannel.trySend(PendingChunk(file, chunk.startSec))
        updateState(pendingChunks = countPendingChunks())
    }

    /** 转写 worker：单协程顺序消费，失败的分段留在磁盘待补转 */
    private suspend fun runTranscribeWorker() {
        try {
            for (pending in transcribeChannel) {
                if (!isActive) break
                transcribeOneChunk(pending)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            updateState(status = "转写异常：${e.message}")
        }
    }

    private suspend fun transcribeOneChunk(pending: PendingChunk) {
        val samples = try {
            readWav16kMono(pending.file)
        } catch (e: Throwable) {
            updateState(pendingChunks = countPendingChunks())
            return
        }
        var handled = false
        if (useOnline) {
            try {
                val segments = GroqTranscriber.transcribeChunk(samples, onlineApiKey)
                appendSegments(pending.startSec, segments)
                handled = true
            } catch (e: GroqFallbackException) {
                val msg = e.message.orEmpty()
                if (msg.contains("额度") || msg.contains("密钥无效")) {
                    // 429/401 不会自己恢复：本段及之后全部改走本地离线
                    useOnline = false
                    appendNote("\n> ℹ️ ${e.message}，从此处起改用本地离线引擎继续转写\n\n")
                } else {
                    // 网络等临时问题：本段留待补转，下一段继续尝试在线
                    updateState(
                        status = "在线转写暂时不可用，本段已排队（${countPendingChunks()} 段待补转）",
                        pendingChunks = countPendingChunks()
                    )
                    return
                }
            }
        }
        if (!handled) {
            // 离线路径（在线不可用、或用户选了离线模式）
            if (offlineUnavailable) {
                updateState(pendingChunks = countPendingChunks())
                return
            }
            try {
                val rec = offlineTranscriber
                    ?: OfflineTranscriber(applicationContext).also { offlineTranscriber = it }
                if (!rec.isLoaded) {
                    updateState(status = "正在加载离线语音大模型（SenseVoice）…")
                }
                rec.ensureLoaded()
                val text = rec.transcribe(samples)
                if (text.isNotEmpty()) {
                    appendTextSegment(pending.startSec, text)
                }
                handled = true
            } catch (e: IllegalStateException) {
                // 离线模型未下载：本段留待补转，之后的分段不再尝试离线
                offlineUnavailable = true
                updateState(
                    status = "离线模型未下载且在线不可用，本段已排队，可稍后补转",
                    pendingChunks = countPendingChunks()
                )
                return
            } catch (e: Throwable) {
                updateState(
                    status = "本段转写失败，已排队（${countPendingChunks()} 段待补转）",
                    pendingChunks = countPendingChunks()
                )
                return
            }
        }
        // 成功：删分段文件
        try { pending.file.delete() } catch (_: Throwable) {}
        updateState(pendingChunks = countPendingChunks())
    }

    /** 追加在线转写结果（时间戳 = 段起点 + 段内偏移） */
    private suspend fun appendSegments(startSec: Double, segments: List<GroqSegment>) {
        if (segments.isEmpty()) return
        transcriptMutex.withLock {
            for (seg in segments) {
                transcriptBuilder.append("[").append(formatTime(startSec + seg.start)).append("] ")
                    .append(seg.text).append("\n\n")
                segmentCount++
            }
            persistTranscriptLocked()
        }
    }

    /** 追加离线转写结果（整段一个时间戳） */
    private suspend fun appendTextSegment(startSec: Double, text: String) {
        transcriptMutex.withLock {
            transcriptBuilder.append("[").append(formatTime(startSec)).append("] ")
                .append(text).append("\n\n")
            segmentCount++
            persistTranscriptLocked()
        }
    }

    /** 追加提示行（回退提示、重点标记等，不计入段数） */
    private suspend fun appendNote(text: String) {
        transcriptMutex.withLock {
            transcriptBuilder.append(text)
            persistTranscriptLocked()
        }
    }

    /** 必须在 transcriptMutex 内调用：写 transcript.md、更新 meta 与 state */
    private fun persistTranscriptLocked() {
        val full = transcriptBuilder.toString()
        writeTranscriptFile(full)
        val meta = currentMeta?.copy(segmentCount = segmentCount, pendingChunks = countPendingChunks())
        if (meta != null) {
            currentMeta = meta
            writeMeta(meta)
        }
        _state.value = _state.value.copy(
            liveTranscript = full,
            transcribedSegments = segmentCount,
            pendingChunks = countPendingChunks()
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(_state.value.statusMessage))
    }

    private fun writeTranscriptFile(content: String) {
        val dir = currentDir ?: return
        try {
            File(dir, "transcript.md").writeText(content, Charsets.UTF_8)
        } catch (_: Throwable) {
        }
    }

    private fun writeMeta(meta: RecordingMeta) {
        val dir = currentDir ?: return
        try {
            writeMetaFile(File(dir, "meta.json"), meta)
        } catch (_: Throwable) {
        }
    }

    private fun countPendingChunks(): Int {
        val dir = currentDir ?: return 0
        return dir.listFiles { f -> f.isFile && f.name.startsWith("chunk_") && f.name.endsWith(".wav") }
            ?.size ?: 0
    }

    /** 分段文件名带起点毫秒，补转时可从文件名还原时间戳 */
    private fun chunkFile(dir: File, index: Int, startSec: Double): File {
        val startMs = (startSec * 1000).toLong()
        return File(dir, "chunk_%03d_%d.wav".format(index, startMs))
    }

    private fun parseChunkStartSec(file: File): Double {
        val match = Regex("""chunk_\d+_(\d+)\.wav""").find(file.name)
        val ms = match?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return 0.0
        return ms / 1000.0
    }

    private fun readWav16kMono(file: File): FloatArray {
        val bytes = file.readBytes()
        if (bytes.size < 44) throw IllegalStateException("分段文件损坏")
        val count = (bytes.size - 44) / 2
        val out = FloatArray(count)
        val buf = ByteBuffer.wrap(bytes, 44, count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            out[i] = buf.short / 32768f
        }
        return out
    }

    private fun updateState(
        isRecording: Boolean = _state.value.isRecording,
        isPaused: Boolean = _state.value.isPaused,
        elapsedSec: Int = _state.value.elapsedSec,
        micSource: MicSource = _state.value.micSource,
        status: String = _state.value.statusMessage,
        liveTranscript: String = _state.value.liveTranscript,
        pendingChunks: Int = _state.value.pendingChunks,
        transcribedSegments: Int = _state.value.transcribedSegments,
        highlightCount: Int = _state.value.highlightCount,
        currentRecordingId: String? = _state.value.currentRecordingId,
        error: String? = _state.value.errorMessage,
        notify: Boolean = true
    ) {
        _state.value = RecordingState(
            isRecording = isRecording,
            isPaused = isPaused,
            elapsedSec = elapsedSec,
            micSource = micSource,
            statusMessage = status,
            liveTranscript = liveTranscript,
            pendingChunks = pendingChunks,
            transcribedSegments = transcribedSegments,
            highlightCount = highlightCount,
            currentRecordingId = currentRecordingId,
            errorMessage = error
        )
        if (notify) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(status))
        }
    }

    /** 时间戳统一为 [HH:MM:SS] 格式 */
    private fun formatTime(seconds: Double): String {
        val totalSec = seconds.toInt().coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    // ---------- 停止与收尾 ----------

    private suspend fun stopRecordingInternal() {
        if (!_state.value.isRecording && !stopping) return
        if (stopping) {
            // 停止过程中再次点停止：取消剩余补转，按部分完成收尾
            workerJob?.cancel()
            return
        }
        stopping = true
        try {
            // 等启动完成（极少数情况：刚点开始就点停止，SCO 最多等 4 秒）
            var waited = 0
            while (starting && waited < 5000) {
                delay(100)
                waited += 100
            }
            updateState(status = "正在停止录音…")
            // 1. 停录音器（刷出最后一段到通道）
            try { recorder?.stop() } catch (_: Throwable) {}
            finalDurationSec = recorder?.recordedDurationSec ?: _state.value.elapsedSec
            // 2. 关通道，让 worker 排空后退出
            transcribeChannel.close()
            try {
                workerJob?.join()
            } catch (_: CancellationException) {
                // 取消补转时直接收尾，剩余分段留在磁盘
            }
            // 3. 收尾
            finishCurrentSession(isRetry = false)
        } finally {
            stopping = false
        }
    }

    /** 重点标记：给当前时间点打标记，写进文稿时间线与 meta */
    private fun markHighlightInternal() {
        if (!_state.value.isRecording) return
        val sec = recorder?.recordedDurationSec ?: _state.value.elapsedSec
        serviceScope.launch {
            transcriptMutex.withLock {
                val mark = "⭐ 重点标记 [${formatTime(sec.toDouble())}]\n\n"
                transcriptBuilder.append(mark)
                val meta = currentMeta?.copy(
                    highlights = (currentMeta?.highlights ?: emptyList()) + sec
                )
                if (meta != null) {
                    currentMeta = meta
                    writeMeta(meta)
                }
                val full = transcriptBuilder.toString()
                writeTranscriptFile(full)
                _state.value = _state.value.copy(
                    liveTranscript = full,
                    highlightCount = (currentMeta?.highlights?.size ?: 0)
                )
            }
        }
    }

    /** 补转某条录音的未转写分段（录音进行中不允许） */
    private suspend fun retryPendingInternal(recordingId: String) {
        if (_state.value.isRecording || starting || stopping || retrying) return
        retrying = true
        try {
            val dir = File(getRecordingsDir(this), recordingId)
            val metaFile = File(dir, "meta.json")
            if (!metaFile.exists()) return
            val meta = readMeta(metaFile) ?: return
            transcriptBuilder = StringBuilder(readTranscript(this, recordingId))
            segmentCount = meta.segmentCount
            currentDir = dir
            currentMeta = meta
            useOnline = TranscribeSettings.isOnlineEnabled(applicationContext)
            onlineApiKey = TranscribeSettings.getApiKey(applicationContext)
            offlineUnavailable = false
            transcribeChannel = Channel(Channel.UNLIMITED)
            updateState(
                isRecording = false,
                status = "正在补转「${meta.title}」…",
                liveTranscript = transcriptBuilder.toString(),
                pendingChunks = countPendingChunks(),
                transcribedSegments = segmentCount,
                highlightCount = meta.highlights.size,
                currentRecordingId = recordingId,
                error = null
            )
            workerJob = serviceScope.launch { runTranscribeWorker() }
            val chunks = dir.listFiles { f ->
                f.isFile && f.name.startsWith("chunk_") && f.name.endsWith(".wav")
            }?.sortedBy { it.name }.orEmpty()
            for (f in chunks) {
                transcribeChannel.trySend(PendingChunk(f, parseChunkStartSec(f)))
            }
            transcribeChannel.close()
            try {
                workerJob?.join()
            } catch (_: CancellationException) {
            }
            finishCurrentSession(isRetry = true)
        } finally {
            retrying = false
        }
    }

    /**
     * 收尾：生成完整录音 WAV、按设置决定是否删除原录音、写 meta、停前台。
     * 录音停止与补转共用；补转时跳过 PCM 与录音器步骤（audio.wav 已存在）。
     */
    private suspend fun finishCurrentSession(isRetry: Boolean) {
        timerJob?.cancel()
        workerJob = null
        timerJob = null
        val dir = currentDir
        if (!isRetry) {
            try { pcmOut?.flush(); pcmOut?.close() } catch (_: Throwable) {}
            pcmOut = null
            if (dir != null) {
                val pcmFile = File(dir, "audio.pcm")
                val wavFile = File(dir, "audio.wav")
                if (pcmFile.exists() && pcmFile.length() > 0) {
                    try {
                        pcmToWav(pcmFile, wavFile)
                        pcmFile.delete()
                    } catch (e: Throwable) {
                        updateState(status = "保存完整录音失败：${e.message}")
                    }
                } else {
                    pcmFile.delete()
                }
            }
        }
        val pending = countPendingChunks()
        val status = if (pending == 0) "done" else "partial"
        var audioDeleted = currentMeta?.audioDeleted ?: false
        // 转写后删除原录音（只留文字）：仅当全部转写完成时执行
        if (status == "done" && TranscribeSettings.isDeleteAudioAfterTranscribe(applicationContext)) {
            val wavFile = dir?.let { File(it, "audio.wav") }
            if (wavFile?.exists() == true) {
                if (wavFile.delete()) audioDeleted = true
            } else {
                audioDeleted = true
            }
        }
        val durationSec = if (isRetry) (currentMeta?.durationSec ?: 0) else finalDurationSec
        val meta = currentMeta?.copy(
            durationSec = durationSec,
            status = status,
            segmentCount = segmentCount,
            pendingChunks = pending,
            audioDeleted = audioDeleted
        )
        if (meta != null) {
            currentMeta = meta
            writeMeta(meta)
        }
        if (!isRetry) {
            try { recorder?.release() } catch (_: Throwable) {}
            recorder = null
        }
        try { offlineTranscriber?.release() } catch (_: Throwable) {}
        offlineTranscriber = null
        val doneMsg = if (isRetry) {
            if (pending == 0) "补转完成，共 $segmentCount 段" else "补转完成，仍有 $pending 段待补转"
        } else {
            if (pending == 0) "录音完成，共 $segmentCount 段，已保存文稿" else "录音完成，共 $segmentCount 段，$pending 段待补转"
        }
        updateState(
            isRecording = false, isPaused = false,
            status = doneMsg, pendingChunks = pending,
            transcribedSegments = segmentCount
        )
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** PCM 文件流式转 WAV（不把整个录音读进内存） */
    private fun pcmToWav(pcmFile: File, wavFile: File) {
        val dataSize = pcmFile.length().toInt()
        FileOutputStream(wavFile).use { out ->
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
            pcmFile.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    if (n > 0) out.write(buf, 0, n)
                }
            }
        }
    }
}
