package com.erxi.qingertranscriber

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TranscriberState(
    val isRunning: Boolean = false,
    val statusMessage: String = "准备就绪",
    val progress: Int = 0,
    val title: String = "",
    val transcript: String = "",
    val savedFilePath: String? = null,
    val errorMessage: String? = null
)

class TranscriberService : Service() {

    companion object {
        private const val CHANNEL_ID = "qinger_transcribe_channel"
        private const val NOTIF_ID = 1001

        const val ACTION_START_URL = "ACTION_START_URL"
        const val ACTION_START_URI = "ACTION_START_URI"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_URL = "EXTRA_URL"
        const val EXTRA_URI = "EXTRA_URI"
        const val EXTRA_DISPLAY_NAME = "EXTRA_DISPLAY_NAME"

        private val _state = MutableStateFlow(TranscriberState())
        val state: StateFlow<TranscriberState> = _state.asStateFlow()

        fun startWithUrl(context: Context, rawUrl: String) {
            val intent = Intent(context, TranscriberService::class.java).apply {
                action = ACTION_START_URL
                putExtra(EXTRA_URL, rawUrl)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startWithLocalUri(context: Context, uri: Uri, displayName: String) {
            val intent = Intent(context, TranscriberService::class.java).apply {
                action = ACTION_START_URI
                putExtra(EXTRA_URI, uri.toString())
                putExtra(EXTRA_DISPLAY_NAME, displayName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopTask(context: Context) {
            val intent = Intent(context, TranscriberService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun getTranscriptsDir(context: Context): File =
            File(context.filesDir, "transcripts").apply { mkdirs() }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null
    @Volatile
    private var cancelled = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                cancelled = true
                currentJob?.cancel()
                _state.value = _state.value.copy(
                    isRunning = false,
                    statusMessage = "已手动停止（已保留当前识别内容）"
                )
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START_URL -> {
                val rawUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
                beginForeground("正在解析链接…")
                runTranscription(rawUrl = rawUrl, localUri = null, localName = null)
            }
            ACTION_START_URI -> {
                val uriStr = intent.getStringExtra(EXTRA_URI).orEmpty()
                val name = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty().ifBlank { "本地音频" }
                beginForeground("正在准备本地音视频…")
                runTranscription(rawUrl = null, localUri = Uri.parse(uriStr), localName = name)
            }
        }
        return START_NOT_STICKY
    }

    private fun beginForeground(text: String) {
        val notif = buildNotification(text, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        acquireWakeLock()
    }

    private fun runTranscription(rawUrl: String?, localUri: Uri?, localName: String?) {
        currentJob?.cancel()
        cancelled = false
        val sb = StringBuilder()

        currentJob = serviceScope.launch {
            var tempAudioFile: File? = null
            // 离线引擎按需加载：纯在线任务不加载模型；在线失败回退时才加载
            val offlineTranscriber = OfflineTranscriber(applicationContext)
            try {
                val onlineEnabled = TranscribeSettings.isOnlineEnabled(applicationContext)
                val onlineApiKey = TranscribeSettings.getApiKey(applicationContext)
                if (!onlineEnabled && !ModelManager.isModelReady(applicationContext)) {
                    throw IllegalStateException("离线模型尚未下载，请先点击顶部「下载离线模型」；也可以在「在线设置」里填写 API Key 后使用在线转写")
                }

                var title = localName ?: "未命名音频"
                if (rawUrl != null) {
                    updateState(isRunning = true, status = "正在解析链接…", progress = 2, title = "", transcript = "", error = null)
                    val parsed = LinkParser.parse(rawUrl)
                    title = parsed.title
                    updateState(isRunning = true, status = "已识别[${parsed.sourceType}]：$title，正在下载音频…", progress = 5, title = title)

                    tempAudioFile = File(cacheDir, "audio_${System.currentTimeMillis()}.tmp")
                    LinkParser.downloadAudio(parsed, tempAudioFile) { pct, doneMb, totalMb ->
                        if (cancelled) throw InterruptedException("已取消")
                        val dlStatus = if (totalMb > 0) {
                            "正在下载音频：%.1f / %.1f MB".format(doneMb, totalMb)
                        } else {
                            "正在下载音频：%.1f MB".format(doneMb)
                        }
                        val overallPct = 5 + (pct * 0.20f).toInt()
                        updateState(isRunning = true, status = dlStatus, progress = overallPct)
                    }
                } else {
                    updateState(isRunning = true, status = "正在加载本地文件：$title", progress = 10, title = title, transcript = "", error = null)
                }

                if (cancelled) return@launch

                var useOnline = onlineEnabled
                var fellBack = false

                if (useOnline) {
                    updateState(isRunning = true, status = "正在准备在线转写…", progress = 26)
                }

                sb.append("# ").append(title).append("\n")
                if (!rawUrl.isNullOrBlank()) {
                    LinkParser.extractFirstUrl(rawUrl)?.let { cleanUrl ->
                        sb.append("> 来源：").append(cleanUrl).append("\n")
                    }
                }
                sb.append("\n")

                var segmentCount = 0
                // 在线按约 3 分钟静音点分段上传（单段 WAV 约 6 MB，远小于 Groq 免费版 25 MB 单文件上限）；
                // 离线沿用 16~26 秒小分段，适配本机模型
                val minChunkSec = if (onlineEnabled) 150 else 16
                val maxChunkSec = if (onlineEnabled) 200 else 26
                AudioDecoder.decodeInChunks(
                    context = applicationContext,
                    file = tempAudioFile,
                    uri = localUri,
                    isCancelled = { cancelled },
                    minChunkSec = minChunkSec,
                    maxChunkSec = maxChunkSec
                ) { samples, startSec, fraction ->
                    // 在线优先：在线成功则用在线结果；一旦失败（密钥无效/限流/网络问题）
                    // 就记一次回退，本段及之后全部改走本地离线引擎
                    if (useOnline) {
                        try {
                            val segments = GroqTranscriber.transcribeChunk(samples, onlineApiKey)
                            for (seg in segments) {
                                segmentCount++
                                sb.append("[").append(formatTime(startSec + seg.start)).append("] ")
                                    .append(seg.text).append("\n\n")
                            }
                            val overallPct = 28 + (fraction * 70f).toInt()
                            updateState(
                                isRunning = true,
                                status = "在线极速转写中（已处理至 ${formatTime(startSec)}，共 $segmentCount 段）…",
                                progress = overallPct.coerceIn(28, 99),
                                transcript = sb.toString()
                            )
                            return@decodeInChunks
                        } catch (e: GroqFallbackException) {
                            useOnline = false
                            fellBack = true
                            sb.append("\n> ℹ️ ${e.message}，从此处起改用本地离线引擎继续转写\n\n")
                            updateState(
                                isRunning = true,
                                status = "在线转写不可用，正在切换为本地离线转写…",
                                transcript = sb.toString()
                            )
                        }
                    }

                    // 首次加载前提示一次（与 v1.2 行为一致：模型未就绪时不提示，直接抛错）
                    if (!offlineTranscriber.isLoaded && ModelManager.isModelReady(applicationContext)) {
                        updateState(isRunning = true, status = "正在加载离线语音大模型（SenseVoice）…")
                    }
                    offlineTranscriber.ensureLoaded()
                    val text = offlineTranscriber.transcribe(samples)
                    if (text.isNotEmpty()) {
                        segmentCount++
                        sb.append("[").append(formatTime(startSec)).append("] ").append(text).append("\n\n")
                    }
                    val overallPct = 28 + (fraction * 70f).toInt()
                    val engineLabel = if (fellBack) "本地离线转写中（在线不可用，已处理至" else "本地离线转写中（已处理至"
                    updateState(
                        isRunning = true,
                        status = "$engineLabel ${formatTime(startSec)}，共 $segmentCount 段）…",
                        progress = overallPct.coerceIn(28, 99),
                        transcript = sb.toString()
                    )
                }

                val finalText = sb.toString()
                val savedFile = saveTranscript(title, finalText)
                val engineNote = when {
                    onlineEnabled && !fellBack -> "（在线极速）"
                    onlineEnabled && fellBack -> "（在线+本地混合）"
                    else -> "（本地离线）"
                }
                updateState(
                    isRunning = false,
                    status = "转写完成$engineNote！共 $segmentCount 段，已自动保存文稿",
                    progress = 100,
                    transcript = finalText,
                    savedFilePath = savedFile.absolutePath
                )
            } catch (e: Throwable) {
                if (!cancelled) {
                    updateState(
                        isRunning = false,
                        status = "转写中断：${e.message ?: "未知错误"}",
                        transcript = sb.toString(),
                        error = e.message ?: e.toString()
                    )
                }
            } finally {
                offlineTranscriber.release()
                tempAudioFile?.delete()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun saveTranscript(title: String, content: String): File {
        val safeTitle = title.replace(Regex("""[\\/:*?"<>|\r\n]"""), "_").take(40).ifBlank { "文稿" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        val outFile = File(getTranscriptsDir(applicationContext), "${stamp}_${safeTitle}.md")
        outFile.writeText(content, Charsets.UTF_8)
        return outFile
    }

    private fun updateState(
        isRunning: Boolean = _state.value.isRunning,
        status: String = _state.value.statusMessage,
        progress: Int = _state.value.progress,
        title: String = _state.value.title,
        transcript: String = _state.value.transcript,
        savedFilePath: String? = _state.value.savedFilePath,
        error: String? = null
    ) {
        _state.value = TranscriberState(
            isRunning = isRunning,
            statusMessage = status,
            progress = progress,
            title = title,
            transcript = transcript,
            savedFilePath = savedFilePath,
            errorMessage = error
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(status, progress))
    }

    /** 时间戳统一为 [HH:MM:SS] 格式 */
    private fun formatTime(seconds: Double): String {
        val totalSec = seconds.toInt().coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "语音转录进度",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("轻耳")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(progress < 100)
            .setProgress(100, progress, progress <= 5)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QingEr::TranscribeWakeLock")
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

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        serviceScope.cancel()
    }
}
