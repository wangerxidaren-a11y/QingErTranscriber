package com.erxi.qingertranscriber

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.launch
import java.io.File

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
            cm.setPrimaryClip(ClipData.newPlainText("清耳转录文稿", content))
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

        lifecycleScope.launch {
            TranscriberService.state.collect { st ->
                tvTaskStatus.text = st.statusMessage
                pbTask.progress = st.progress
                pbTask.visibility = if (st.isRunning || st.progress in 1..99) View.VISIBLE else View.GONE
                btnStop.visibility = if (st.isRunning) View.VISIBLE else View.GONE
                btnStartUrl.isEnabled = !st.isRunning
                btnPickFile.isEnabled = !st.isRunning
                if (st.transcript.isNotBlank()) {
                    tvTranscript.text = st.transcript
                }
            }
        }
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
