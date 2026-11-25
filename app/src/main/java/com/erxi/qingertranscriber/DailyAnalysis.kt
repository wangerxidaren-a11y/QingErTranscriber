package com.erxi.qingertranscriber

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 日分析编排：用同一个 Groq API Key（在线设置里已保存的那个）调
 * GroqChatClient，对录音文稿做分析。
 *
 * 输出形状借鉴 OPPO AI 心力球的「今日结构化摘要」：重要观点、待办事项、
 * 做出的决定、值得记住的事，条列清晰。
 * 跨天主题归集/项目时间线（心力球的 PersonaX 记忆引擎）不在 v1.3 做。
 */
object DailyAnalysis {

    private const val SYSTEM_PROMPT = """你是一位细心的日常记录助手。只依据下面提供的文稿内容分析，不要编造文稿里没有的事。
输出 Markdown，结构固定为四个部分，每部分下用条列写清楚，没有对应内容的部分写"无"：
## 重要观点
## 待办事项
## 做出的决定
## 值得记住的事
语言简洁务实，不要用口号式标题或程度副词。"""

    private const val CHUNK_SUMMARY_PROMPT =
        "提炼下面这段文稿里的关键内容，只列事实要点，不要发挥：重要观点、待办事项、做出的决定、值得记住的事。"

    private const val COMBINE_PROMPT =
        "把下面多段摘要合并成一份完整的日分析，按四个部分输出：## 重要观点、## 待办事项、## 做出的决定、## 值得记住的事。合并重复项，语言简洁务实。"

    /** v1.3.2 周总结：四部分 + 本周重点 */
    private const val WEEK_SYSTEM_PROMPT = """你是一位细心的日常记录助手。只依据下面提供的文稿内容分析，不要编造文稿里没有的事。
输出 Markdown，结构固定为五个部分，每部分下用条列写清楚，没有对应内容的部分写"无"：
## 本周重点
## 重要观点
## 待办事项
## 做出的决定
## 值得记住的事
语言简洁务实，不要用口号式标题或程度副词。"""

    /** v1.3.2 月总结：四部分 + 本月重点 */
    private const val MONTH_SYSTEM_PROMPT = """你是一位细心的日常记录助手。只依据下面提供的文稿内容分析，不要编造文稿里没有的事。
输出 Markdown，结构固定为五个部分，每部分下用条列写清楚，没有对应内容的部分写"无"：
## 本月重点
## 重要观点
## 待办事项
## 做出的决定
## 值得记住的事
语言简洁务实，不要用口号式标题或程度副词。"""

    private const val WEEK_COMBINE_PROMPT =
        "把下面多段摘要合并成一份完整的周总结，按五个部分输出：## 本周重点、## 重要观点、## 待办事项、## 做出的决定、## 值得记住的事。合并重复项，语言简洁务实。"

    private const val MONTH_COMBINE_PROMPT =
        "把下面多段摘要合并成一份完整的月总结，按五个部分输出：## 本月重点、## 重要观点、## 待办事项、## 做出的决定、## 值得记住的事。合并重复项，语言简洁务实。"

    /** 分析单条录音的文稿，返回 Markdown 并保存到该录音目录的 analysis.md */
    fun analyzeRecording(context: Context, recordingId: String, onProgress: (Int, String) -> Unit): String {
        val apiKey = TranscribeSettings.getApiKey(context)
        if (apiKey.isBlank()) {
            throw IllegalStateException("未填写 Groq API Key，请先在「在线设置」里填写（免费领取）")
        }
        val transcript = RecordingService.readTranscript(context, recordingId)
        if (transcript.isBlank()) {
            throw IllegalStateException("暂无可分析的文稿")
        }
        val result = GroqChatClient.analyzeLongText(
            apiKey, SYSTEM_PROMPT, transcript,
            CHUNK_SUMMARY_PROMPT, COMBINE_PROMPT, onProgress
        )
        val dir = File(RecordingService.getRecordingsDir(context), recordingId)
        File(dir, "analysis.md").writeText(result, Charsets.UTF_8)
        RecordingService.setHasAnalysis(context, recordingId, true)
        return result
    }

    /** 分析今天所有录音的文稿（合并），返回 Markdown 并保存到文稿目录的 日分析_yyyyMMdd.md */
    fun analyzeToday(context: Context, onProgress: (Int, String) -> Unit): String {
        val apiKey = TranscribeSettings.getApiKey(context)
        if (apiKey.isBlank()) {
            throw IllegalStateException("未填写 Groq API Key，请先在「在线设置」里填写（免费领取）")
        }
        val merged = collectTodayTranscript(context)
        if (merged.isBlank()) {
            throw IllegalStateException("暂无可分析的文稿")
        }
        val result = GroqChatClient.analyzeLongText(
            apiKey, SYSTEM_PROMPT, merged,
            CHUNK_SUMMARY_PROMPT, COMBINE_PROMPT, onProgress
        )
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val outFile = File(TranscriberService.getTranscriptsDir(context), "日分析_${stamp}.md")
        outFile.writeText(result, Charsets.UTF_8)
        return result
    }

    /** 收集今天所有录音的文稿（按时间顺序拼接，带录音标题分隔），无录音时返回 "" */
    fun collectTodayTranscript(context: Context): String {
        val recordings = RecordingService.listRecordings(context)
        if (recordings.isEmpty()) return ""
        val startOfToday = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val sb = StringBuilder()
        for (meta in recordings.filter { it.startTimeMillis >= startOfToday }.sortedBy { it.startTimeMillis }) {
            val text = RecordingService.readTranscript(context, meta.id)
            if (text.isBlank()) continue
            sb.append("## ").append(meta.title).append("\n\n")
                .append(text).append("\n\n")
        }
        return sb.toString()
    }

    /** 读取今天已保存的日分析，不存在返回 null */
    fun readTodayAnalysis(context: Context): String? {
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val f = File(TranscriberService.getTranscriptsDir(context), "日分析_${stamp}.md")
        return if (f.exists()) f.readText(Charsets.UTF_8).ifBlank { null } else null
    }

    /** 收集指定时间段内所有录音的文稿（按时间顺序拼接，带录音标题分隔），无录音时返回 "" */
    fun collectTranscriptInRange(context: Context, startMillis: Long, endMillis: Long): String {
        val recordings = RecordingService.listRecordings(context)
        if (recordings.isEmpty()) return ""
        val sb = StringBuilder()
        for (meta in recordings.filter { it.startTimeMillis in startMillis until endMillis }.sortedBy { it.startTimeMillis }) {
            val text = RecordingService.readTranscript(context, meta.id)
            if (text.isBlank()) continue
            sb.append("## ").append(meta.title).append("\n\n")
                .append(text).append("\n\n")
        }
        return sb.toString()
    }

    /** 收集指定时间段内已保存的日分析文件内容（按日期顺序拼接），无文件时返回 "" */
    fun collectDailyAnalysesInRange(context: Context, startMillis: Long, endMillis: Long): String {
        val dir = TranscriberService.getTranscriptsDir(context)
        val wanted = dateStringsInRange(startMillis, endMillis).toSet()
        val files = dir.listFiles { f -> f.name.startsWith("日分析_") && f.name.endsWith(".md") }.orEmpty()
        val sb = StringBuilder()
        for (f in files.sortedBy { it.name }) {
            val dateStr = f.name.removePrefix("日分析_").removeSuffix(".md")
            if (dateStr !in wanted) continue
            val text = f.readText(Charsets.UTF_8)
            if (text.isBlank()) continue
            sb.append("## ").append(dateStr).append("\n\n").append(text).append("\n\n")
        }
        return sb.toString()
    }

    /** 指定时间段内是否有可总结的内容（已有日分析，或有录音文稿） */
    fun hasContentInRange(context: Context, startMillis: Long, endMillis: Long): Boolean =
        collectDailyAnalysesInRange(context, startMillis, endMillis).isNotBlank() ||
            collectTranscriptInRange(context, startMillis, endMillis).isNotBlank()

    /** 本周范围：6 天前 0 点到现在（共 7 天） */
    fun weekRangeMillis(): Pair<Long, Long> {
        val endMillis = java.util.Calendar.getInstance().timeInMillis
        val start = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, -6)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return start.timeInMillis to endMillis
    }

    /** 本月范围：本月 1 号 0 点到现在 */
    fun monthRangeMillis(): Pair<Long, Long> {
        val endMillis = java.util.Calendar.getInstance().timeInMillis
        val start = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return start.timeInMillis to endMillis
    }

    /** 生成本周总结（过去 7 天）：优先用已保存的日分析合并，没有时退回用原始文稿；保存到 周总结_yyyyMMdd.md（以今天日期结尾） */
    fun aggregateWeek(context: Context, onProgress: (Int, String) -> Unit): String {
        val apiKey = TranscribeSettings.getApiKey(context)
        if (apiKey.isBlank()) {
            throw IllegalStateException("未填写 Groq API Key，请先在「在线设置」里填写（免费领取）")
        }
        val (startMillis, endMillis) = weekRangeMillis()
        val input = collectDailyAnalysesInRange(context, startMillis, endMillis)
            .ifBlank { collectTranscriptInRange(context, startMillis, endMillis) }
        if (input.isBlank()) {
            throw IllegalStateException("这段时间没有录音可总结")
        }
        val result = GroqChatClient.analyzeLongText(
            apiKey, WEEK_SYSTEM_PROMPT, input,
            CHUNK_SUMMARY_PROMPT, WEEK_COMBINE_PROMPT, onProgress
        )
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val outFile = File(TranscriberService.getTranscriptsDir(context), "周总结_${stamp}.md")
        outFile.writeText(result, Charsets.UTF_8)
        return result
    }

    /** 生成本月总结（本月 1 号到今天）：优先用已保存的日分析合并，没有时退回用原始文稿；保存到 月总结_yyyyMM.md */
    fun aggregateMonth(context: Context, onProgress: (Int, String) -> Unit): String {
        val apiKey = TranscribeSettings.getApiKey(context)
        if (apiKey.isBlank()) {
            throw IllegalStateException("未填写 Groq API Key，请先在「在线设置」里填写（免费领取）")
        }
        val (startMillis, endMillis) = monthRangeMillis()
        val input = collectDailyAnalysesInRange(context, startMillis, endMillis)
            .ifBlank { collectTranscriptInRange(context, startMillis, endMillis) }
        if (input.isBlank()) {
            throw IllegalStateException("这段时间没有录音可总结")
        }
        val result = GroqChatClient.analyzeLongText(
            apiKey, MONTH_SYSTEM_PROMPT, input,
            CHUNK_SUMMARY_PROMPT, MONTH_COMBINE_PROMPT, onProgress
        )
        val stamp = SimpleDateFormat("yyyyMM", Locale.getDefault()).format(Date())
        val outFile = File(TranscriberService.getTranscriptsDir(context), "月总结_${stamp}.md")
        outFile.writeText(result, Charsets.UTF_8)
        return result
    }

    /** 列出 [startMillis, endMillis) 内每一天的 yyyyMMdd 字符串 */
    private fun dateStringsInRange(startMillis: Long, endMillis: Long): List<String> {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val out = ArrayList<String>()
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = startMillis }
        while (cal.timeInMillis < endMillis) {
            out.add(fmt.format(cal.time))
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return out
    }
}
