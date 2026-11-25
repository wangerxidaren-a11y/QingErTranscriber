package com.erxi.qingertranscriber

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** 录音麦克风来源 */
enum class MicSource { PHONE, BLUETOOTH }

/** 一段待转写的 16kHz 单声道音频，startSec 为段起点相对录音开始的秒数（不含暂停时段） */
data class AudioChunk(val samples: FloatArray, val startSec: Double)

/**
 * 录音器回调。
 * 注意：回调均在录音读取线程触发（onPausedByCall 在电话状态回调线程触发），
 * 调用方如需更新 UI 或做耗时操作请自行切线程。
 */
interface AudioRecorderListener {
    /** 一段音频已就绪，可送去转写 */
    fun onChunk(chunk: AudioChunk)
    /** 麦克风来源变化（蓝牙 SCO 失败回退手机麦克风时触发） */
    fun onMicSourceChanged(source: MicSource)
    /** 来电导致自动暂停（通话结束不自动恢复，等用户手动点继续） */
    fun onPausedByCall()
    /** 录音出错，出错后录音已停止 */
    fun onError(message: String)
}

/**
 * 应用内录音器：16kHz 单声道 PCM，边录边按 30~60 秒切分交付。
 *
 * - 优先蓝牙耳机：有已连接耳机时走 SCO（窄带 8kHz，线性重采样到 16kHz）；
 *   SCO 超时/失败时回退手机麦克风，并通过 onMicSourceChanged 告知
 * - 手机麦克风用 VOICE_RECOGNITION，16kHz 直采
 * - 暂停时丢弃新采样、不丢已缓存的未发出采样；已录时长暂停时不增长
 * - 已授予 READ_PHONE_STATE 时，来电自动暂停
 *
 * pause()/resume()/stop()/release() 均幂等且线程安全。
 */
class AudioRecorder(
    private val context: Context,
    private val listener: AudioRecorderListener
) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_MAX_SEC = 60
        const val CHUNK_MIN_SEC = 30

        private const val SCO_SAMPLE_RATE = 8000 // 蓝牙 SCO 窄带采样率
        private const val SCO_WAIT_TIMEOUT_SEC = 4L // 等 SCO 连接的最长时间
        private const val MIN_FINAL_SEC = 0.5 // stop 时剩余音频不少于 0.5 秒才作为最后一段发出
        // 静音判定与 AudioDecoder 保持一致：尾部 200ms 的 RMS 低于阈值视为静音
        private const val SILENCE_RMS_THRESHOLD = 0.018f
        private const val SILENCE_WINDOW = SAMPLE_RATE / 5
    }

    /** 当前麦克风来源（start 后有效；蓝牙回退时会变为 PHONE） */
    @Volatile
    var micSource: MicSource = MicSource.PHONE
        private set

    /** 是否已授予 READ_PHONE_STATE（来电自动暂停可用），start() 时确定 */
    @Volatile
    var callAutoPauseAvailable: Boolean = false
        private set

    /** 已录时长（秒，暂停时不增长），录音线程内更新 */
    @Volatile
    var recordedDurationSec: Int = 0

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lock = Any()

    @Volatile
    private var started = false
    @Volatile
    private var keepReading = false
    @Volatile
    private var paused = false
    /** resume 后首次读取前需排空缓冲里的旧采样 */
    @Volatile
    private var drainOnResume = false

    private var audioRecord: AudioRecord? = null
    private var readThread: Thread? = null
    private var isBluetoothMode = false
    /** SCO 是否由本次录音建立（是才由我们负责关闭） */
    private var scoOwned = false

    // 来电监听（API 31+ 用 TelephonyCallback，以下用 PhoneStateListener）
    private var telephonyManager: TelephonyManager? = null
    private var telephonyCallback: TelephonyCallback? = null
    private var phoneStateListener: PhoneStateListener? = null

    // 16kHz 累计缓冲：仅录音线程写入；stop 时在读取线程结束后刷出
    private var chunkBuf = FloatArray(CHUNK_MAX_SEC * SAMPLE_RATE + SAMPLE_RATE * 4)
    private var chunkLen = 0
    private var totalRecordedSamples = 0L
    // 蓝牙 8kHz→16kHz 线性重采样状态（中点插值）
    private var btPrevSample = 0f
    private var btHasPrev = false

    /**
     * 开始录音。
     * 失败时抛 IllegalStateException，message 为可直接展示给用户的中文；
     * 失败后内部资源已清理，可直接重试。
     */
    @Throws(IllegalStateException::class)
    fun start() {
        var notifyFallback = false
        synchronized(lock) {
            if (started) throw IllegalStateException("录音已在进行中")
            // 重置本次录音会话状态
            chunkLen = 0
            totalRecordedSamples = 0L
            btPrevSample = 0f
            btHasPrev = false
            recordedDurationSec = 0
            paused = false
            drainOnResume = false
            micSource = MicSource.PHONE
            isBluetoothMode = false
            scoOwned = false

            try {
                // 1. 有已连接蓝牙耳机时优先走 SCO，失败回退手机麦克风
                var useBluetooth = false
                if (hasConnectedHeadset()) {
                    if (tryStartSco()) {
                        useBluetooth = true
                        isBluetoothMode = true
                        micSource = MicSource.BLUETOOTH
                    } else {
                        restoreAudioMode()
                        notifyFallback = true
                    }
                }

                // 2. 创建并启动 AudioRecord
                val source = if (useBluetooth) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    else MediaRecorder.AudioSource.VOICE_RECOGNITION
                val rate = if (useBluetooth) SCO_SAMPLE_RATE else SAMPLE_RATE
                val minBuf = AudioRecord.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) throw IllegalStateException("当前设备不支持录音")
                val bufferSize = (minBuf * 2).coerceAtLeast(rate * 2) // 至少 1 秒缓冲
                val record = try {
                    AudioRecord(
                        source, rate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufferSize
                    )
                } catch (e: SecurityException) {
                    throw IllegalStateException("未获得录音权限，请在设置中允许清耳转录使用麦克风")
                } catch (e: IllegalArgumentException) {
                    throw IllegalStateException("当前设备不支持录音（录音参数无效）")
                }
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    throw IllegalStateException("麦克风被其他应用占用，请关闭其他录音应用后重试")
                }
                try {
                    record.startRecording()
                } catch (e: IllegalStateException) {
                    record.release()
                    throw IllegalStateException("麦克风被其他应用占用，请关闭其他录音应用后重试")
                }
                audioRecord = record

                // 3. 来电自动暂停（需 READ_PHONE_STATE，未授予时跳过）
                callAutoPauseAvailable =
                    ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_PHONE_STATE) ==
                        PackageManager.PERMISSION_GRANTED
                if (callAutoPauseAvailable) {
                    try {
                        registerCallListener()
                    } catch (e: SecurityException) {
                        callAutoPauseAvailable = false
                    }
                }

                // 4. 启动读取线程
                keepReading = true
                val thread = Thread({ readLoop() }, "AudioRecorder-Read")
                readThread = thread
                started = true
                thread.start()
            } catch (e: IllegalStateException) {
                // 启动失败：释放已占用的部分资源（SCO、AudioRecord、来电监听），保证可重试
                cleanupLocked()
                started = false
                keepReading = false
                throw e
            }
        }
        // 在锁外回调，避免监听方反向调用录音器时死锁
        if (notifyFallback) {
            listener.onMicSourceChanged(MicSource.PHONE)
        }
    }

    /** 暂停录音（幂等）：已缓存的未发出采样保留，已录时长不再增长 */
    fun pause() {
        pauseInternal()
    }

    private fun pauseInternal(): Boolean {
        synchronized(lock) {
            if (!started || paused) return false
            paused = true
            return true
        }
    }

    /** 继续录音（幂等） */
    fun resume() {
        synchronized(lock) {
            if (!started || !paused) return
            // 先置排空标志再解除暂停：读取线程看到 paused=false 时必能看到排空标志
            drainOnResume = true
            paused = false
        }
    }

    /**
     * 停止录音：刷出剩余音频（>=0.5 秒才作为最后一段发出），释放全部资源。
     * 幂等且线程安全。
     */
    fun stop() {
        stopInternal(fromReadThread = false)
    }

    /** 与 stop 相同作用的兜底释放（幂等） */
    fun release() {
        stopInternal(fromReadThread = false)
    }

    private fun stopInternal(fromReadThread: Boolean) {
        val threadToJoin: Thread?
        synchronized(lock) {
            if (!started) return
            keepReading = false
            paused = false
            threadToJoin = readThread
        }
        // 在锁外等读取线程结束，避免与读取线程内的回调死锁
        if (!fromReadThread && threadToJoin != null && Thread.currentThread() !== threadToJoin) {
            try {
                threadToJoin.join(2000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (threadToJoin.isAlive) {
                threadToJoin.interrupt()
                try {
                    threadToJoin.join(500)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        synchronized(lock) {
            if (!started) return
            flushRemaining()
            cleanupLocked()
            started = false
        }
    }

    // ---------- 读取线程 ----------

    private fun readLoop() {
        val record = audioRecord ?: return
        val shortBuf = ShortArray(4096)
        try {
            while (keepReading) {
                if (paused) {
                    // 暂停：继续读取并丢弃，避免缓冲积压；已缓存的未发出采样保留
                    record.read(shortBuf, 0, shortBuf.size, AudioRecord.READ_NON_BLOCKING)
                    Thread.sleep(20)
                    continue
                }
                if (drainOnResume) {
                    // 排空暂停期间残留的旧采样，避免把暂停时段的声音录进去
                    while (record.read(shortBuf, 0, shortBuf.size, AudioRecord.READ_NON_BLOCKING) > 0) {
                        // 丢弃
                    }
                    drainOnResume = false
                    continue
                }
                val n = record.read(shortBuf, 0, shortBuf.size, AudioRecord.READ_NON_BLOCKING)
                when {
                    n > 0 -> appendSamples(shortBuf, n)
                    n == 0 -> Thread.sleep(10)
                    else -> {
                        // ERROR_INVALID_OPERATION / ERROR_BAD_VALUE / ERROR_DEAD_OBJECT
                        if (keepReading) {
                            keepReading = false
                            flushRemaining() // 先把已录的残留发出
                            listener.onError("录音读取失败，麦克风可能被其他应用占用")
                            stopInternal(fromReadThread = true)
                        }
                        return
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            if (keepReading) {
                keepReading = false
                try {
                    flushRemaining()
                } catch (_: Throwable) {
                }
                listener.onError("录音异常停止：${e.message ?: "未知错误"}")
                stopInternal(fromReadThread = true)
            }
        }
    }

    /** 把一批原始采样转为 16kHz Float 并累计，满足条件时切分发出 */
    private fun appendSamples(shortBuf: ShortArray, count: Int) {
        if (isBluetoothMode) {
            // 8kHz→16kHz 线性重采样：每个采样本身 + 与下一采样的中点
            ensureCapacity(chunkLen + count * 2)
            for (i in 0 until count) {
                val s = shortBuf[i] / 32768f
                if (btHasPrev) {
                    chunkBuf[chunkLen++] = (btPrevSample + s) / 2f
                    totalRecordedSamples++
                }
                chunkBuf[chunkLen++] = s
                totalRecordedSamples++
                btPrevSample = s
                btHasPrev = true
            }
        } else {
            ensureCapacity(chunkLen + count)
            for (i in 0 until count) {
                chunkBuf[chunkLen++] = shortBuf[i] / 32768f
                totalRecordedSamples++
            }
        }
        recordedDurationSec = (totalRecordedSamples / SAMPLE_RATE).toInt()
        maybeFlushChunk()
    }

    private fun ensureCapacity(needed: Int) {
        if (needed > chunkBuf.size) {
            chunkBuf = chunkBuf.copyOf(maxOf(needed, chunkBuf.size * 2))
        }
    }

    /** 达到 60 秒强制切分，或达到 30 秒且尾部 200ms 静音时切分 */
    private fun maybeFlushChunk() {
        val maxSamples = CHUNK_MAX_SEC * SAMPLE_RATE
        val minSamples = CHUNK_MIN_SEC * SAMPLE_RATE
        if (chunkLen >= maxSamples || (chunkLen >= minSamples && isTrailingSilence(chunkBuf, chunkLen))) {
            emitChunk()
        }
    }

    private fun emitChunk() {
        if (chunkLen <= 0) return
        val samples = chunkBuf.copyOf(chunkLen)
        val startSec = (totalRecordedSamples - chunkLen).toDouble() / SAMPLE_RATE
        chunkLen = 0
        listener.onChunk(AudioChunk(samples, startSec))
    }

    /** 刷出剩余音频：>=0.5 秒才作为最后一段发出，否则丢弃 */
    private fun flushRemaining() {
        if (chunkLen >= (SAMPLE_RATE * MIN_FINAL_SEC).toInt()) {
            emitChunk()
        } else {
            chunkLen = 0
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

    // ---------- 蓝牙 ----------

    /** 是否有已连接的蓝牙耳机（无权限或查询失败时视为无耳机） */
    private fun hasConnectedHeadset(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
        if (ContextCompat.checkSelfPermission(appContext, permission) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return try {
            val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter: BluetoothAdapter? = bluetoothManager?.adapter
            if (adapter == null || !adapter.isEnabled) return false
            adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * 尝试建立蓝牙 SCO 连接，最多等 4 秒；成功返回 true。
     * 失败时内部已停止 SCO，调用方直接回退手机麦克风即可。
     */
    private fun tryStartSco(): Boolean {
        if (audioManager.isBluetoothScoOn) {
            // SCO 已由其他方建立，直接用，不由我们关闭
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            return true
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val latch = CountDownLatch(1)
        val connected = AtomicBoolean(false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.getIntExtra(
                    AudioManager.EXTRA_SCO_AUDIO_STATE,
                    AudioManager.SCO_AUDIO_STATE_ERROR
                )) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        connected.set(true)
                        latch.countDown()
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
                    AudioManager.SCO_AUDIO_STATE_ERROR -> latch.countDown()
                }
            }
        }
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(receiver, filter)
            }
        } catch (e: SecurityException) {
            return false
        }
        try {
            audioManager.startBluetoothSco()
            scoOwned = true
            val done = try {
                latch.await(SCO_WAIT_TIMEOUT_SEC, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (done && connected.get()) {
                audioManager.isBluetoothScoOn = true
                return true
            }
            // 超时或失败：停止 SCO，调用方回退手机麦克风
            try {
                audioManager.stopBluetoothSco()
            } catch (_: Throwable) {
            }
            scoOwned = false
            return false
        } catch (e: Exception) {
            try {
                audioManager.stopBluetoothSco()
            } catch (_: Throwable) {
            }
            scoOwned = false
            return false
        } finally {
            try {
                appContext.unregisterReceiver(receiver)
            } catch (_: Throwable) {
            }
        }
    }

    private fun restoreAudioMode() {
        try {
            if (audioManager.mode != AudioManager.MODE_NORMAL) {
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        } catch (_: Throwable) {
        }
    }

    // ---------- 来电监听 ----------

    private fun registerCallListener() {
        val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager = tm
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallState(state)
                }
            }
            telephonyCallback = callback
            tm.registerTelephonyCallback(appContext.mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            val stateListener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallState(state)
                }
            }
            phoneStateListener = stateListener
            @Suppress("DEPRECATION")
            tm.listen(stateListener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun unregisterCallListener() {
        val tm = telephonyManager
        if (tm != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    telephonyCallback?.let { tm.unregisterTelephonyCallback(it) }
                } else {
                    @Suppress("DEPRECATION")
                    phoneStateListener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
                }
            } catch (_: Throwable) {
            }
        }
        telephonyManager = null
        telephonyCallback = null
        phoneStateListener = null
    }

    private fun handleCallState(state: Int) {
        if (state == TelephonyManager.CALL_STATE_RINGING ||
            state == TelephonyManager.CALL_STATE_OFFHOOK
        ) {
            if (pauseInternal()) {
                listener.onPausedByCall()
            }
        }
        // IDLE（通话结束）不自动恢复，等用户手动点继续
    }

    // ---------- 资源清理 ----------

    /** 释放 AudioRecord、反注册监听、恢复音频模式（需在 lock 内调用） */
    private fun cleanupLocked() {
        unregisterCallListener()
        audioRecord?.let { rec ->
            try {
                rec.stop()
            } catch (_: Throwable) {
            }
            try {
                rec.release()
            } catch (_: Throwable) {
            }
        }
        audioRecord = null
        readThread = null
        if (scoOwned) {
            try {
                audioManager.isBluetoothScoOn = false
            } catch (_: Throwable) {
            }
            try {
                audioManager.stopBluetoothSco()
            } catch (_: Throwable) {
            }
            scoOwned = false
        }
        restoreAudioMode()
        isBluetoothMode = false
        micSource = MicSource.PHONE
    }
}
