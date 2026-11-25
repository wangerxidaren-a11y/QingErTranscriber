package com.erxi.qingertranscriber

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * v1.3.5 后台任务（WorkManager，无需精确闹钟权限；WorkManager 自带 Doze 适配，
 * 任务持久化保存，重启后自动恢复调度）：
 * - NightSummaryWorker：每天 21:00 自动生成今日总结
 * - RecordingWatchdogWorker：每 15 分钟检查录音是否被系统杀掉
 * - RecordingRestartWorker：看门狗发现中断后，用加急任务恢复录音
 */
object QingErWorkers {

    private const val NIGHT_SUMMARY_WORK_NAME = "night_summary"
    private const val WATCHDOG_WORK_NAME = "recording_watchdog"
    const val RESTART_WORK_NAME = "recording_restart"

    /** 提醒通知渠道（夜间总结 / 看门狗恢复提醒共用；点通知打开 App） */
    private const val NOTIFY_CHANNEL_ID = "qinger_notify_channel"
    private const val SUMMARY_NOTIF_ID = 1003
    private const val WATCHDOG_NOTIF_ID = 1004

    /** App 启动时调用一次（KEEP 策略，重复调用不重复排期） */
    fun scheduleAll(context: Context) {
        scheduleNightSummary(context)
        scheduleWatchdog(context)
    }

    /** 每天 21:00 跑一次：首跑延迟到下一个 21:00，之后每 24 小时一次（需要联网） */
    fun scheduleNightSummary(context: Context) {
        val request = PeriodicWorkRequestBuilder<NightSummaryWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(millisUntilNext21(), TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            NIGHT_SUMMARY_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    /** 录音看门狗：每 15 分钟检查一次（WorkManager 周期任务的最短间隔） */
    fun scheduleWatchdog(context: Context) {
        val request = PeriodicWorkRequestBuilder<RecordingWatchdogWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WATCHDOG_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    /** 距下一个 21:00 的毫秒数（已过 21:00 则算到明天） */
    private fun millisUntilNext21(): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 21)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!target.after(now)) target.add(Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis - now.timeInMillis
    }

    /** 提醒通知渠道（IMPORTANCE_DEFAULT，保证用户能看到；与录音前台通知渠道分开） */
    fun ensureNotifyChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(NOTIFY_CHANNEL_ID, "提醒", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    /** 点通知打开主界面 */
    fun openAppPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** 给前台任务用的 ForegroundInfo（录音渠道，低打扰） */
    fun foregroundInfo(context: Context, text: String): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    "qinger_record_channel",
                    "录音",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val notif = NotificationCompat.Builder(context, "qinger_record_channel")
            .setContentTitle("轻耳")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppPendingIntent(context))
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 后台数据任务（生成总结 / 恢复录音），用 dataSync 类型
            ForegroundInfo(1005, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(1005, notif)
        }
    }

    fun postSummaryNotification(context: Context, todoCount: Int) {
        ensureNotifyChannel(context)
        val text = if (todoCount > 0) "今日总结好了，有 $todoCount 个待办" else "今日总结好了，去看一下吧"
        val notif = NotificationCompat.Builder(context, NOTIFY_CHANNEL_ID)
            .setContentTitle("轻耳")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppPendingIntent(context))
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(SUMMARY_NOTIF_ID, notif)
    }

    fun postWatchdogNotification(context: Context, text: String) {
        ensureNotifyChannel(context)
        val notif = NotificationCompat.Builder(context, NOTIFY_CHANNEL_ID)
            .setContentTitle("轻耳")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppPendingIntent(context))
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(WATCHDOG_NOTIF_ID, notif)
    }

    /** 数出"## 待办事项"一节里的条目数（"无"时为 0） */
    private val NUMBERED_BULLET = Regex("""\d+[.、]\s+.*""")

    fun countTodos(analysis: String): Int {
        var inTodos = false
        var count = 0
        for (raw in analysis.lines()) {
            val line = raw.trim()
            if (line.startsWith("## ")) {
                if (inTodos) break
                inTodos = line.removePrefix("## ").trim() == "待办事项"
                continue
            }
            if (inTodos && (line.startsWith("- ") || line.startsWith("• ") || NUMBERED_BULLET.matches(line))) count++
        }
        return count
    }
}

/** 每天 21:00 自动生成今日总结（复用 DailyAnalysis + Groq 免费模型，格式不变）。 */
class NightSummaryWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        return try {
            // 前台任务：长文稿分析可能耗时数分钟，避免被 WorkManager 10 分钟后台限额掐断
            try {
                setForegroundAsync(QingErWorkers.foregroundInfo(ctx, "正在生成今日总结…")).get(10, TimeUnit.SECONDS)
            } catch (_: Throwable) {
            }
            // 用户当天已手动生成过（或已自动生成过）就跳过
            if (DailyAnalysis.readTodayAnalysis(ctx) != null) return Result.success()
            if (DailyAnalysis.collectTodayTranscript(ctx).isBlank()) return Result.success()
            if (TranscribeSettings.getApiKey(ctx).isBlank()) return Result.success()
            val result = DailyAnalysis.analyzeToday(ctx) { _, _ -> }
            QingErWorkers.postSummaryNotification(ctx, QingErWorkers.countTodos(result))
            Result.success()
        } catch (_: Throwable) {
            // 失败不重试、不打扰：用户下次打开 App 时会自动生成今日总结
            Result.success()
        }
    }
}

/** 录音看门狗：每 15 分钟跑一次，发现"标志说在录音、服务却没在跑"就交给加急任务恢复。 */
class RecordingWatchdogWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        // 标志未置位 = 用户没在录音（或已主动停止），绝不重启
        if (!RecordingService.isRecordingActive(ctx)) return Result.success()
        // 服务还在跑，正常
        if (RecordingService.state.value.isRecording) return Result.success()
        // 被系统杀掉了：用加急一次性任务恢复（加急任务自带前台执行环境）
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            QingErWorkers.RESTART_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RecordingRestartWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        )
        return Result.success()
    }
}

/** 看门狗的恢复执行者：先升为前台再启动录音服务，绕开后台启动前台服务的限制。 */
class RecordingRestartWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        if (!RecordingService.isRecordingActive(ctx)) return Result.success()
        if (RecordingService.state.value.isRecording) return Result.success()
        var started = false
        try {
            try {
                setForegroundAsync(QingErWorkers.foregroundInfo(ctx, "正在恢复录音…")).get(10, TimeUnit.SECONDS)
            } catch (_: Throwable) {
            }
            RecordingService.startRecording(ctx)
            started = true
        } catch (_: Throwable) {
            started = false
        }
        if (started) {
            QingErWorkers.postWatchdogNotification(ctx, "录音被系统中断，已自动恢复（中间可能漏了一段）")
        } else {
            // 实在起不来（极少数系统限制）：通知用户点开 App 手动恢复
            QingErWorkers.postWatchdogNotification(ctx, "录音被系统中断了，点我打开轻耳恢复录音")
        }
        return Result.success()
    }
}
