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
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
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
            var recognizer: OfflineRecognizer? = null
            try {
                if (!ModelManager.isModelReady(applicationContext)) {
                    throw IllegalStateException("离线模型尚未下载，请先点击顶部「下载离线模型」")
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

                updateState(isRunning = true, status = "正在加载离线语音大模型（SenseVoice）…", progress = 26)
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = ModelManager.getModelFile(applicationContext).absolutePath,
                            language = "",
                            useInverseTextNormalization = true
                        ),
                        tokens = ModelManager.getTokensFile(applicationContext).absolutePath,
                        numThreads = threads,
                        provider = "cpu"
                    )
                )
                recognizer = OfflineRecognizer(assetManager = null, config = config)

                sb.append("# ").append(title).append("\n")
                if (!rawUrl.isNullOrBlank()) {
                    LinkParser.extractFirstUrl(rawUrl)?.let { cleanUrl ->
                        sb.append("> 来源：").append(cleanUrl).append("\n")
                    }
                }
                sb.append("\n")

                var segmentCount = 0
                AudioDecoder.decodeInChunks(
                    context = applicationContext,
                    file = tempAudioFile,
                    uri = localUri,
                    isCancelled = { cancelled }
                ) { samples, startSec, fraction ->
                    val stream = recognizer.createStream()
                    try {
                        stream.acceptWaveform(samples, 16000)
                        recognizer.decode(stream)
                        val text = recognizer.getResult(stream).text.trim()
                        if (text.isNotEmpty()) {
                            segmentCount++
                            sb.append("[").append(formatTime(startSec)).append("] ").append(text).append("\n\n")
                        }
                    } finally {
                        stream.release()
                    }
                    val overallPct = 28 + (fraction * 70f).toInt()
                    updateState(
                        isRunning = true,
                        status = "离线转写中（已处理至 ${formatTime(startSec)}，共 $segmentCount 段）…",
                        progress = overallPct.coerceIn(28, 99),
                        transcript = sb.toString()
                    )
                }

                val finalText = sb.toString()
                val savedFile = saveTranscript(title, finalText)
                updateState(
                    isRunning = false,
                    status = "转写完成！共 $segmentCount 段，已自动保存文稿",
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
                recognizer?.release()
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

    private fun formatTime(seconds: Double): String {
        val totalSec = seconds.toInt().coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
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
            .setContentTitle("清耳转录")
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
