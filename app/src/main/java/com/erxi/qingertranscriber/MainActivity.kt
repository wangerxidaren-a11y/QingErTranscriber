package com.erxi.qingertranscriber

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvModelStatus: TextView
    private lateinit var btnDownloadModel: Button
    private lateinit var pbModel: ProgressBar
    private lateinit var toggleEngine: MaterialButtonToggleGroup
    private lateinit var btnOnlineSettings: Button
    private lateinit var tvEngineHint: TextView
    private lateinit var etUrl: EditText
    private lateinit var btnPaste: Button
    private lateinit var btnStartUrl: Button
    private lateinit var btnPickFile: Button
    private lateinit var btnStop: Button
    private lateinit var tvTaskStatus: TextView
    private lateinit var pbTask: ProgressBar
    private lateinit var tvTranscript: TextView
    private lateinit var btnCopy: Button
    private lateinit var btnShare: Button
    private lateinit var btnHistory: Button

    // v1.3 录音
    private lateinit var btnRecord: Button
    private lateinit var btnPauseResume: Button
    private lateinit var btnMarkHighlight: Button
    private lateinit var btnRecordings: Button
    private lateinit var btnTodaySummary: Button
    private lateinit var tvRecordTimer: TextView
    private lateinit var tvMicSource: TextView
    private lateinit var tvRecordStatus: TextView
    private lateinit var tvLiveTranscript: TextView
    private lateinit var svLiveTranscript: ScrollView
    private lateinit var cbDeleteAudio: CheckBox

    // v1.3.2 待补转直显 + 总结卡片
    private lateinit var llMarkRow: LinearLayout
    private lateinit var llPendingSection: LinearLayout
    private lateinit var llPendingList: LinearLayout
    private lateinit var tvSummaryTitle: TextView
    private lateinit var tvSummaryPreview: TextView
    private lateinit var svSummaryPreview: ScrollView
    private lateinit var btnWeekSummary: Button
    private lateinit var btnMonthSummary: Button

    private var transcribeRunning = false
    private var recordingActive = false
    private var lastRecordError: String? = null
    private var startRecordingRequested = false
    private var audioPermAsked = false
    private var btPermAsked = false
    private var phonePermAsked = false
    private var analysisJob: Job? = null

    // v1.3.2 自动补转/自动总结：每个录音每打开一次 App 最多自动补转一次；今日总结每会话最多自动生成一次
    private val autoRetriedIds = mutableSetOf<String>()
    private var autoSummaryAttempted = false
    private var autoSummaryJob: Job? = null

    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val name = queryDisplayName(uri)
            TranscriberService.startWithLocalUri(this, uri, name)
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // v1.3 录音权限链：结果回来后继续往下走（见 continueStartRecordingFlow）
    private val recordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { if (startRecordingRequested) continueStartRecordingFlow() }

    private val bluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { if (startRecordingRequested) continueStartRecordingFlow() }

    private val phoneStateLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { if (startRecordingRequested) continueStartRecordingFlow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvModelStatus = findViewById(R.id.tvModelStatus)
        btnDownloadModel = findViewById(R.id.btnDownloadModel)
        pbModel = findViewById(R.id.pbModel)
        toggleEngine = findViewById(R.id.toggleEngine)
        btnOnlineSettings = findViewById(R.id.btnOnlineSettings)
        tvEngineHint = findViewById(R.id.tvEngineHint)
        etUrl = findViewById(R.id.etUrl)
        btnPaste = findViewById(R.id.btnPaste)
        btnStartUrl = findViewById(R.id.btnStartUrl)
        btnPickFile = findViewById(R.id.btnPickFile)
        btnStop = findViewById(R.id.btnStop)
        tvTaskStatus = findViewById(R.id.tvTaskStatus)
        pbTask = findViewById(R.id.pbTask)
        tvTranscript = findViewById(R.id.tvTranscript)
        btnCopy = findViewById(R.id.btnCopy)
        btnShare = findViewById(R.id.btnShare)
        btnHistory = findViewById(R.id.btnHistory)

        requestNotificationPermissionIfNeeded()
        // v1.3.5：排期后台任务（每天 21:00 自动总结 + 录音看门狗；KEEP 策略，重复调用不重复排期）
        QingErWorkers.scheduleAll(this)
        refreshModelUi()
        refreshEngineUi()
        handleIncomingShareIntent(intent)

        btnDownloadModel.setOnClickListener { startModelDownload() }

        toggleEngine.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.btnModeOnline -> TranscribeSettings.MODE_ONLINE
                R.id.btnModeOffline -> TranscribeSettings.MODE_OFFLINE
                else -> return@addOnButtonCheckedListener
            }
            TranscribeSettings.setMode(this, mode)
            refreshEngineUi()
        }

        btnOnlineSettings.setOnClickListener { showOnlineSettingsDialog() }

        btnPaste.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            if (text.isNotBlank()) {
                etUrl.setText(text)
                Toast.makeText(this, "已粘贴剪贴板内容", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
            }
        }

        btnStartUrl.setOnClickListener {
            if (!canStartTranscribe()) {
                Toast.makeText(this, "请先下载离线模型，或在「在线设置」里填写 API Key 后使用在线转写", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val raw = etUrl.text.toString().trim()
            if (raw.isEmpty()) {
                Toast.makeText(this, "请先粘贴小宇宙、B站或小红书链接", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            TranscriberService.startWithUrl(this, raw)
        }

        btnPickFile.setOnClickListener {
            if (!canStartTranscribe()) {
                Toast.makeText(this, "请先下载离线模型，或在「在线设置」里填写 API Key 后使用在线转写", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            pickAudioLauncher.launch(arrayOf("audio/*", "video/*"))
        }

        btnStop.setOnClickListener {
            TranscriberService.stopTask(this)
        }

        btnCopy.setOnClickListener {
            val content = tvTranscript.text.toString()
            if (content.isBlank()) {
                Toast.makeText(this, "暂无可复制的文稿", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("轻耳文稿", content))
            Toast.makeText(this, "已复制全文（${content.length} 字）", Toast.LENGTH_SHORT).show()
        }

        btnShare.setOnClickListener {
            val state = TranscriberService.state.value
            val savedPath = state.savedFilePath
            if (savedPath != null && File(savedPath).exists()) {
                shareMarkdownFile(File(savedPath))
            } else if (tvTranscript.text.isNotBlank()) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, tvTranscript.text.toString())
                }
                startActivity(Intent.createChooser(shareIntent, "分享文稿"))
            } else {
                Toast.makeText(this, "暂无可导出的文稿", Toast.LENGTH_SHORT).show()
            }
        }

        btnHistory.setOnClickListener { showHistoryDialog() }

        // v1.3 录音
        btnRecord = findViewById(R.id.btnRecord)
        btnPauseResume = findViewById(R.id.btnPauseResume)
        btnMarkHighlight = findViewById(R.id.btnMarkHighlight)
        btnRecordings = findViewById(R.id.btnRecordings)
        btnTodaySummary = findViewById(R.id.btnTodaySummary)
        llMarkRow = findViewById(R.id.llMarkRow)
        llPendingSection = findViewById(R.id.llPendingSection)
        llPendingList = findViewById(R.id.llPendingList)
        tvSummaryTitle = findViewById(R.id.tvSummaryTitle)
        tvSummaryPreview = findViewById(R.id.tvSummaryPreview)
        svSummaryPreview = findViewById(R.id.svSummaryPreview)
        btnWeekSummary = findViewById(R.id.btnWeekSummary)
        btnMonthSummary = findViewById(R.id.btnMonthSummary)
        tvRecordTimer = findViewById(R.id.tvRecordTimer)
        tvMicSource = findViewById(R.id.tvMicSource)
        tvRecordStatus = findViewById(R.id.tvRecordStatus)
        tvLiveTranscript = findViewById(R.id.tvLiveTranscript)
        svLiveTranscript = findViewById(R.id.svLiveTranscript)
        cbDeleteAudio = findViewById(R.id.cbDeleteAudio)

        cbDeleteAudio.isChecked = TranscribeSettings.isDeleteAudioAfterTranscribe(this)
        cbDeleteAudio.setOnCheckedChangeListener { _, checked ->
            TranscribeSettings.setDeleteAudioAfterTranscribe(this, checked)
            Toast.makeText(
                this,
                if (checked) "已开启：新录音转写完成后自动删除原录音，只保留文字" else "已关闭：转写后保留原录音",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnRecord.setOnClickListener {
            if (RecordingService.state.value.isRecording) {
                RecordingService.stopRecording(this)
            } else {
                startRecordingRequested = true
                audioPermAsked = false
                btPermAsked = false
                phonePermAsked = false
                continueStartRecordingFlow()
            }
        }

        btnPauseResume.setOnClickListener {
            val st = RecordingService.state.value
            if (st.isPaused) RecordingService.resumeRecording(this) else RecordingService.pauseRecording(this)
        }

        btnMarkHighlight.setOnClickListener {
            RecordingService.markHighlight(this)
            Toast.makeText(this, "已标记重点", Toast.LENGTH_SHORT).show()
        }

        btnRecordings.setOnClickListener { showRecordingsDialog() }

        btnTodaySummary.setOnClickListener { runTodaySummary() }
        btnWeekSummary.setOnClickListener { runWeekSummary() }
        btnMonthSummary.setOnClickListener { runMonthSummary() }

        // v1.3.2：进主界面先展示已有的今日总结（没有则由 onResume 自动生成）
        DailyAnalysis.readTodayAnalysis(this)?.let { showSummaryPreview("今日总结", it) }
        refreshPendingSection()

        lifecycleScope.launch {
            TranscriberService.state.collect { st ->
                tvTaskStatus.text = st.statusMessage
                pbTask.progress = st.progress
                pbTask.visibility = if (st.isRunning || st.progress in 1..99) View.VISIBLE else View.GONE
                btnStop.visibility = if (st.isRunning) View.VISIBLE else View.GONE
                transcribeRunning = st.isRunning
                refreshActionButtons()
                if (st.transcript.isNotBlank()) {
                    tvTranscript.text = st.transcript
                }
            }
        }

        // v1.3：录音状态
        lifecycleScope.launch {
            RecordingService.state.collect { st ->
                recordingActive = st.isRecording
                refreshActionButtons()
                tvRecordTimer.text = formatElapsed(st.elapsedSec)
                tvMicSource.text = if (st.micSource == MicSource.BLUETOOTH) "麦克风：蓝牙耳机" else "麦克风：手机"
                tvRecordStatus.text = if (st.pendingChunks > 0) {
                    "${st.statusMessage}（${st.pendingChunks} 段待补转）"
                } else {
                    st.statusMessage
                }
                if (st.liveTranscript.isNotBlank()) {
                    tvLiveTranscript.text = st.liveTranscript
                    svLiveTranscript.post { svLiveTranscript.fullScroll(View.FOCUS_DOWN) }
                } else {
                    tvLiveTranscript.text = "录音时转出的文字会实时显示在这里"
                }
                btnRecord.text = if (st.isRecording) "停止" else "开始录音"
                btnPauseResume.visibility = if (st.isRecording) View.VISIBLE else View.GONE
                btnPauseResume.text = if (st.isPaused) "继续" else "暂停"
                llMarkRow.visibility = if (st.isRecording) View.VISIBLE else View.GONE
                // v1.3.2：待补转区随录音状态刷新（内部有签名 guard，内容没变不重建）
                refreshPendingSection()
                val err = st.errorMessage
                if (!err.isNullOrBlank() && err != lastRecordError) {
                    lastRecordError = err
                    Toast.makeText(this@MainActivity, err, Toast.LENGTH_LONG).show()
                } else if (err.isNullOrBlank()) {
                    lastRecordError = null
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // v1.3.2：打开 App 就自动补转待补转分段、自动生成今日总结（失败不打扰）
        refreshPendingSection()
        maybeAutoRetryPending()
        maybeAutoGenerateTodaySummary()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!shared.isNullOrBlank()) {
                etUrl.setText(shared)
                Toast.makeText(this, "已接收分享链接，点击「开始转录」即可", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 离线模型就绪，或在线模式已配好密钥，都可以开始转录 */
    private fun canStartTranscribe(): Boolean =
        ModelManager.isModelReady(this) || TranscribeSettings.isOnlineEnabled(this)

    private fun refreshEngineUi() {
        val onlineMode = TranscribeSettings.isOnlineMode(this)
        val key = TranscribeSettings.getApiKey(this)
        toggleEngine.check(if (onlineMode) R.id.btnModeOnline else R.id.btnModeOffline)
        tvEngineHint.text = when {
            !onlineMode ->
                "当前为本地离线模式：全部在本机转写，需要先下载离线模型"
            key.isBlank() ->
                "在线极速模式：请点「在线设置」填写 API Key（免费领取），否则无法在线转写"
            else ->
                "在线极速模式已启用：优先用在线接口，断网或不可用时自动转本地离线"
        }
    }

    /** 「在线设置」弹窗：Groq API Key */
    private fun showOnlineSettingsDialog() {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }

        val tvIntro = TextView(this).apply {
            text = "在线转写使用 Groq 免费 Whisper 接口（whisper-large-v3-turbo），速度比本地离线快很多，且无需下载 228 MB 模型。免费额度每天 8 小时音频；注册只需邮箱或 Google 账号，无需实名。"
            textSize = 13f
            setTextColor(0xFF4B5563.toInt())
        }
        val etApiKey = EditText(this).apply {
            hint = "粘贴 Groq API Key（gsk- 开头）"
            setText(TranscribeSettings.getApiKey(this@MainActivity))
            setSingleLine(true)
            textSize = 14f
        }
        val btnGetKey = Button(this).apply {
            text = "免费领取 Key（Groq）"
            textSize = 13f
        }
        btnGetKey.setOnClickListener {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(TranscribeSettings.GROQ_KEY_URL))
            )
        }

        container.addView(tvIntro)
        container.addView(etApiKey)
        container.addView(btnGetKey)

        AlertDialog.Builder(this)
            .setTitle("在线设置")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val key = etApiKey.text.toString().trim()
                TranscribeSettings.setApiKey(this, key)
                refreshEngineUi()
                if (key.isEmpty()) {
                    Toast.makeText(this, "已保存，未填 API Key 时在线模式不可用", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "在线设置已保存", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshModelUi() {
        if (ModelManager.isModelReady(this)) {
            tvModelStatus.text = "离线引擎已就绪：SenseVoice 多语种（零广告·完全本地运行）"
            btnDownloadModel.text = "已就绪"
            btnDownloadModel.isEnabled = false
            pbModel.visibility = View.GONE
        } else {
            tvModelStatus.text = "首次使用需下载一次开源模型（约 228 MB，国内直连高速源）"
            btnDownloadModel.text = "一键下载离线模型"
            btnDownloadModel.isEnabled = true
            pbModel.visibility = View.GONE
        }
    }

    private fun startModelDownload() {
        btnDownloadModel.isEnabled = false
        pbModel.visibility = View.VISIBLE
        pbModel.progress = 1
        lifecycleScope.launch {
            val result = ModelManager.downloadModel(this@MainActivity) { status, pct ->
                runOnUiThread {
                    tvModelStatus.text = status
                    pbModel.progress = pct
                }
            }
            if (result.isSuccess) {
                refreshModelUi()
                Toast.makeText(this@MainActivity, "离线模型下载完成，可以开始转录了！", Toast.LENGTH_LONG).show()
            } else {
                btnDownloadModel.isEnabled = true
                pbModel.visibility = View.GONE
                tvModelStatus.text = "下载失败：${result.exceptionOrNull()?.message ?: "请检查网络后重试"}"
            }
        }
    }

    private fun showHistoryDialog() {
        val files = TranscriberService.getTranscriptsDir(this)
            .listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

        if (files.isEmpty()) {
            Toast.makeText(this, "暂无历史文稿记录", Toast.LENGTH_SHORT).show()
            return
        }

        val names = files.map { it.name.removeSuffix(".md") }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("历史文稿（点击查看）")
            .setItems(names) { _, which ->
                val f = files[which]
                tvTranscript.text = f.readText(Charsets.UTF_8)
                tvTaskStatus.text = "已打开历史文稿：${f.name}"
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun shareMarkdownFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "导出/分享 Markdown 文稿"))
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return "本地音频"
    }

    // ---------- v1.3 录音 ----------

    /** 两个前台任务（链接转录 / 录音）不同时跑，按钮状态统一在这里算 */
    private fun refreshActionButtons() {
        val busy = transcribeRunning || recordingActive
        btnStartUrl.isEnabled = !busy
        btnPickFile.isEnabled = !busy
        btnRecord.isEnabled = !transcribeRunning
    }

    private fun formatElapsed(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    /**
     * 录音启动前的权限链：录音（必需）→ 蓝牙（可选，仅检测耳机）→ 电话状态（可选，仅来电暂停）。
     * 每项每点一次最多请求一次；蓝牙和电话状态被拒也不影响录音，继续往下走。
     * 注意：Android 14 要求先拿到录音权限才能启动 microphone 类型的前台服务，所以权限流程必须在启动服务之前完成。
     */
    private fun continueStartRecordingFlow() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            if (!audioPermAsked) {
                audioPermAsked = true
                AlertDialog.Builder(this)
                    .setTitle("需要麦克风权限")
                    .setMessage("应用内录音需要使用麦克风，录音仅用于转写你自己的说话内容。蓝牙耳机录音需要蓝牙权限来检测已连接的耳机，来电自动暂停需要电话状态权限（这两项拒绝也不影响录音）。")
                    .setPositiveButton("去授权") { _, _ -> recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                    .setNegativeButton("取消") { _, _ -> startRecordingRequested = false }
                    .show()
            } else {
                startRecordingRequested = false
                Toast.makeText(this, "没有麦克风权限无法录音，请在系统设置中授予后重试", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED && isBluetoothOn() && !btPermAsked
        ) {
            btPermAsked = true
            bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED && !phonePermAsked
        ) {
            phonePermAsked = true
            phoneStateLauncher.launch(Manifest.permission.READ_PHONE_STATE)
            return
        }
        startRecordingRequested = false
        RecordingService.startRecording(this)
    }

    private fun isBluetoothOn(): Boolean {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        return adapter?.isEnabled == true
    }

    private fun showRecordingsDialog() {
        val recordings = RecordingService.listRecordings(this)
        if (recordings.isEmpty()) {
            Toast.makeText(this, "暂无录音", Toast.LENGTH_SHORT).show()
            return
        }
        val items = recordings.map { meta ->
            val statusText = when (meta.status) {
                "recording" -> "录音中"
                "transcribing" -> "转写中"
                "done" -> "已完成"
                "partial" -> "部分待补转"
                else -> meta.status
            }
            val micText = if (meta.micSource == "bluetooth") "蓝牙" else "手机"
            val marks = if (meta.highlights.isNotEmpty()) " · ${meta.highlights.size} 个重点" else ""
            val audioNote = if (meta.audioDeleted) " · 原录音已删" else ""
            "${meta.title}\n${formatElapsed(meta.durationSec)} · $statusText · $micText$marks$audioNote › 点击查看"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("录音列表（点击查看）")
            .setItems(items) { _, which -> showRecordingDetailDialog(recordings[which]) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showRecordingDetailDialog(meta: RecordingMeta) {
        val transcript = RecordingService.readTranscript(this, meta.id)
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val tvDetail = TextView(this).apply {
            text = transcript.ifBlank { "暂无文稿（可能还在转写中，或录音无有效语音）" }
            textSize = 14f
            setTextColor(0xFF374151.toInt())
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            addView(tvDetail)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (320 * resources.displayMetrics.density).toInt()
            )
        }
        container.addView(scroll)

        var detailDialog: AlertDialog? = null

        if (meta.pendingChunks > 0) {
            val btnRetry = Button(this).apply {
                text = "补转待转分段（${meta.pendingChunks} 段）"
                textSize = 13f
            }
            btnRetry.setOnClickListener {
                if (RecordingService.state.value.isRecording) {
                    Toast.makeText(this, "正在录音中，请停止录音后再补转", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                RecordingService.retryPending(this, meta.id)
                Toast.makeText(this, "开始补转，完成后可在文稿中查看", Toast.LENGTH_SHORT).show()
            }
            container.addView(btnRetry)
        }
        if (meta.hasAnalysis) {
            val btnViewAnalysis = Button(this).apply {
                text = "查看已有分析"
                textSize = 13f
            }
            btnViewAnalysis.setOnClickListener {
                val analysis = RecordingService.readAnalysis(this@MainActivity, meta.id)
                showTextDialog("AI 分析", analysis.orEmpty())
            }
            container.addView(btnViewAnalysis)
        }
        val btnDelete = Button(this).apply {
            text = "删除这条录音"
            textSize = 13f
            setTextColor(0xFFDC2626.toInt())
        }
        btnDelete.setOnClickListener {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("删除录音")
                .setMessage("确定删除「${meta.title}」吗？录音、文稿和分析都会一起删除。")
                .setPositiveButton("删除") { _, _ ->
                    if (RecordingService.deleteRecording(this@MainActivity, meta.id)) {
                        detailDialog?.dismiss()
                        Toast.makeText(this@MainActivity, "已删除", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "删除失败", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
        container.addView(btnDelete)

        detailDialog = AlertDialog.Builder(this)
            .setTitle(meta.title)
            .setView(container)
            .setPositiveButton("AI 分析") { _, _ -> runRecordingAnalysis(meta) }
            .setNeutralButton("分享") { _, _ ->
                if (transcript.isBlank()) {
                    Toast.makeText(this, "暂无可分享的文稿", Toast.LENGTH_SHORT).show()
                } else {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, transcript)
                    }
                    startActivity(Intent.createChooser(shareIntent, "分享文稿"))
                }
            }
            .setNegativeButton("关闭", null)
            .create()
        detailDialog.show()
    }

    private fun runRecordingAnalysis(meta: RecordingMeta) {
        if (TranscribeSettings.getApiKey(this).isBlank()) {
            Toast.makeText(this, "请先在「在线设置」里填写 Groq API Key（免费领取）", Toast.LENGTH_LONG).show()
            showOnlineSettingsDialog()
            return
        }
        runAnalysisWithProgress("正在分析录音…") { onProgress ->
            DailyAnalysis.analyzeRecording(this, meta.id, onProgress)
        }
    }

    private fun runTodaySummary() {
        if (TranscribeSettings.getApiKey(this).isBlank()) {
            Toast.makeText(this, "请先在「在线设置」里填写 Groq API Key（免费领取）", Toast.LENGTH_LONG).show()
            showOnlineSettingsDialog()
            return
        }
        if (DailyAnalysis.collectTodayTranscript(this).isBlank()) {
            Toast.makeText(this, "今天还没有录音文稿", Toast.LENGTH_SHORT).show()
            return
        }
        autoSummaryJob?.cancel()
        runAnalysisWithProgress("正在生成今日总结…", onSuccess = { result ->
            showSummaryPreview("今日总结", result)
        }) { onProgress ->
            DailyAnalysis.analyzeToday(this, onProgress)
        }
    }

    /** v1.3.2：本周总结（过去 7 天，优先用已保存的日分析合并） */
    private fun runWeekSummary() {
        if (TranscribeSettings.getApiKey(this).isBlank()) {
            Toast.makeText(this, "请先在「在线设置」里填写 Groq API Key（免费领取）", Toast.LENGTH_LONG).show()
            showOnlineSettingsDialog()
            return
        }
        val (startMillis, endMillis) = DailyAnalysis.weekRangeMillis()
        if (!DailyAnalysis.hasContentInRange(this, startMillis, endMillis)) {
            Toast.makeText(this, "这段时间没有录音可总结", Toast.LENGTH_SHORT).show()
            return
        }
        autoSummaryJob?.cancel()
        runAnalysisWithProgress("正在生成本周总结…", onSuccess = { result ->
            showSummaryPreview("本周总结", result)
        }) { onProgress ->
            DailyAnalysis.aggregateWeek(this, onProgress)
        }
    }

    /** v1.3.2：本月总结（本月 1 号到今天，优先用已保存的日分析合并） */
    private fun runMonthSummary() {
        if (TranscribeSettings.getApiKey(this).isBlank()) {
            Toast.makeText(this, "请先在「在线设置」里填写 Groq API Key（免费领取）", Toast.LENGTH_LONG).show()
            showOnlineSettingsDialog()
            return
        }
        val (startMillis, endMillis) = DailyAnalysis.monthRangeMillis()
        if (!DailyAnalysis.hasContentInRange(this, startMillis, endMillis)) {
            Toast.makeText(this, "这段时间没有录音可总结", Toast.LENGTH_SHORT).show()
            return
        }
        autoSummaryJob?.cancel()
        runAnalysisWithProgress("正在生成本月总结…", onSuccess = { result ->
            showSummaryPreview("本月总结", result)
        }) { onProgress ->
            DailyAnalysis.aggregateMonth(this, onProgress)
        }
    }

    /** v1.3.2：总结卡片预览显示哪份总结（标题 + 内容，回到顶部） */
    private fun showSummaryPreview(title: String, content: String) {
        tvSummaryTitle.text = title
        tvSummaryPreview.text = content
        svSummaryPreview.post { svSummaryPreview.fullScroll(View.FOCUS_UP) }
    }

    private var lastPendingSignature: String? = null

    /** v1.3.2：刷新主界面待补转区；内容没变时不重建（录音状态流每秒都可能推一次） */
    private fun refreshPendingSection() {
        val pending = try {
            RecordingService.listRecordings(this).filter { it.pendingChunks > 0 }
        } catch (e: Exception) {
            emptyList()
        }
        val signature = pending.joinToString(";") { "${it.id}:${it.pendingChunks}" }
        val shouldShow = pending.isNotEmpty()
        if (signature == lastPendingSignature &&
            llPendingSection.visibility == (if (shouldShow) View.VISIBLE else View.GONE)
        ) return
        lastPendingSignature = signature
        llPendingList.removeAllViews()
        if (!shouldShow) {
            llPendingSection.visibility = View.GONE
            return
        }
        llPendingSection.visibility = View.VISIBLE
        val density = resources.displayMetrics.density
        for (meta in pending) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pad = (6 * density).toInt()
                setPadding(0, pad, 0, pad)
            }
            val tv = TextView(this).apply {
                text = "${meta.title} · ${meta.pendingChunks} 段待补转"
                textSize = 13f
                setTextColor(0xFF374151.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btn = Button(this).apply {
                text = "补转"
                textSize = 12f
            }
            btn.setOnClickListener {
                if (RecordingService.state.value.isRecording) {
                    Toast.makeText(this, "正在录音中，请停止录音后再补转", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                RecordingService.retryPending(this, meta.id)
                Toast.makeText(this, "开始补转，完成后可在文稿中查看", Toast.LENGTH_SHORT).show()
            }
            row.addView(tv)
            row.addView(btn)
            llPendingList.addView(row)
        }
    }

    /** v1.3.2：打开 App 时自动补转待补转分段（仅在线模式、未录音、已填 Key；每打开一次每个录音最多一次；失败保留待补转状态，可手动补转） */
    private fun maybeAutoRetryPending() {
        if (RecordingService.state.value.isRecording) return
        if (!TranscribeSettings.isOnlineMode(this)) return
        if (TranscribeSettings.getApiKey(this).isBlank()) return
        val pending = try {
            RecordingService.listRecordings(this).filter { it.pendingChunks > 0 }
        } catch (e: Exception) {
            return
        }
        for (meta in pending) {
            if (meta.id in autoRetriedIds) continue
            autoRetriedIds.add(meta.id)
            RecordingService.retryPending(this, meta.id)
        }
    }

    /** v1.3.2：打开 App 时自动生成今日总结（今天有文稿、还没日分析时才生成；每会话最多尝试一次；失败只留提示，不打扰） */
    private fun maybeAutoGenerateTodaySummary() {
        if (autoSummaryAttempted) return
        if (DailyAnalysis.readTodayAnalysis(this) != null) return
        if (DailyAnalysis.collectTodayTranscript(this).isBlank()) return
        if (TranscribeSettings.getApiKey(this).isBlank()) return
        autoSummaryAttempted = true
        tvSummaryTitle.text = "今日总结"
        tvSummaryPreview.text = "正在生成今日总结…"
        autoSummaryJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = DailyAnalysis.analyzeToday(this@MainActivity) { _, _ -> }
                withContext(Dispatchers.Main) {
                    showSummaryPreview("今日总结", result)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvSummaryTitle.text = "今日总结"
                    tvSummaryPreview.text = "生成失败，点今日总结重试"
                }
            }
        }
    }

    /**
     * 带进度弹窗的分析执行器。分析是阻塞式网络请求，取消时靠 onProgress 回调里的
     * analysisCancelled 检查在下一个分段边界停下（最长等一次分段调用完成）。
     */
    private var analysisCancelled = false

    private fun runAnalysisWithProgress(
        startMessage: String,
        onSuccess: ((String) -> Unit)? = null,
        task: (onProgress: (Int, String) -> Unit) -> String
    ) {
        analysisCancelled = false
        val dialog = AlertDialog.Builder(this)
            .setTitle("AI 分析")
            .setMessage(startMessage)
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ ->
                analysisCancelled = true
                analysisJob?.cancel()
            }
            .create()
        dialog.show()
        analysisJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = task { _, status ->
                    if (analysisCancelled) throw kotlinx.coroutines.CancellationException("已取消")
                    runOnUiThread { if (dialog.isShowing) dialog.setMessage(status) }
                }
                withContext(Dispatchers.Main) {
                    if (dialog.isShowing) dialog.dismiss()
                    if (analysisCancelled) return@withContext
                    onSuccess?.invoke(result)
                    showTextDialog("AI 分析", result)
                    Toast.makeText(this@MainActivity, "分析已保存", Toast.LENGTH_SHORT).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户点了取消：预期行为，不提示
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (dialog.isShowing) dialog.dismiss()
                    val msg = e.message ?: "分析失败"
                    val extra = if (msg.contains("网络") || msg.contains("额度") || msg.contains("不可用")) {
                        "也可以把文稿发给小墨帮你分析。"
                    } else ""
                    Toast.makeText(this@MainActivity, msg + extra, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showTextDialog(title: String, content: String) {
        val tv = TextView(this).apply {
            text = content.ifBlank { "暂无内容" }
            textSize = 14f
            setTextColor(0xFF374151.toInt())
            setTextIsSelectable(true)
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("分享") { _, _ ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, content)
                }
                startActivity(Intent.createChooser(shareIntent, "分享"))
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
