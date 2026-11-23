package com.erxi.qingertranscriber

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class ParsedMedia(
    val title: String,
    val audioUrl: String,
    val sourceType: String,
    val headers: Map<String, String> = emptyMap()
)

object LinkParser {

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun extractFirstUrl(rawText: String): String? {
        val matcher = Pattern.compile("https?://[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+").matcher(rawText)
        return if (matcher.find()) matcher.group() else null
    }

    fun parse(rawInput: String): ParsedMedia {
        val url = extractFirstUrl(rawInput.trim())
            ?: throw IllegalArgumentException("未在输入内容中识别到有效链接，请粘贴小宇宙、B站或音频直链")

        return when {
            url.contains("xiaoyuzhoufm.com", ignoreCase = true) -> parseXiaoyuzhou(url)
            url.contains("bilibili.com", ignoreCase = true) ||
                url.contains("b23.tv", ignoreCase = true) -> parseBilibili(url)
            isDirectMediaUrl(url) -> ParsedMedia(
                title = url.substringAfterLast('/').substringBefore('?').ifBlank { "在线音频" },
                audioUrl = url,
                sourceType = "直链音频"
            )
            else -> parseGenericOrRss(url)
        }
    }

    private fun isDirectMediaUrl(url: String): Boolean {
        val lower = url.substringBefore('?').lowercase()
        return lower.endsWith(".m4a") || lower.endsWith(".mp3") ||
            lower.endsWith(".wav") || lower.endsWith(".aac") ||
            lower.endsWith(".mp4") || lower.endsWith(".ogg")
    }

    private fun parseXiaoyuzhou(url: String): ParsedMedia {
        val html = fetchText(url, mapOf("User-Agent" to DESKTOP_UA))

        val title = Regex("""<meta\s+property=["']og:title["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?: Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
            ?: "小宇宙播客"

        val audioUrl = Regex("""<meta\s+property=["']og:audio["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?: Regex(""""enclosure"\s*:\s*\{\s*"url"\s*:\s*"([^"]+)"""")
                .find(html)?.groupValues?.get(1)
            ?: Regex("""https?://[^"'\\\s]+\.(?:m4a|mp3)(?:\?[^"'\\\s]*)?""", RegexOption.IGNORE_CASE)
                .find(html)?.value
            ?: throw IllegalStateException("未能从小宇宙页面提取音频地址，请确认链接为单集页面（/episode/...）")

        return ParsedMedia(
            title = decodeHtmlEntities(title).replace(" - 小宇宙", "").trim(),
            audioUrl = audioUrl.replace("\\u002F", "/"),
            sourceType = "小宇宙播客",
            headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "https://www.xiaoyuzhoufm.com/")
        )
    }

    private fun parseBilibili(inputUrl: String): ParsedMedia {
        val resolvedUrl = if (inputUrl.contains("b23.tv", ignoreCase = true)) {
            resolveFinalUrl(inputUrl)
        } else {
            inputUrl
        }

        val bvMatch = Regex("""(BV[0-9A-Za-z]{10})""").find(resolvedUrl)
            ?: throw IllegalArgumentException("未识别到 B 站 BV 号，请复制视频详情页或分享链接")
        val bvid = bvMatch.groupValues[1]

        val pageIndex = Regex("""[?&]p=(\d+)""").find(resolvedUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val biliHeaders = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "https://www.bilibili.com/video/$bvid/"
        )

        val viewJson = JSONObject(fetchText("https://api.bilibili.com/x/web-interface/view?bvid=$bvid", biliHeaders))
        if (viewJson.optInt("code", -1) != 0) {
            throw IllegalStateException("获取 B 站视频信息失败：${viewJson.optString("message", "接口受限")}")
        }
        val viewData = viewJson.getJSONObject("data")
        var title = viewData.optString("title", bvid)
        var cid = viewData.optLong("cid", 0L)

        val pages = viewData.optJSONArray("pages")
        if (pages != null && pageIndex >= 1 && pageIndex <= pages.length()) {
            val pageObj = pages.getJSONObject(pageIndex - 1)
            cid = pageObj.optLong("cid", cid)
            val part = pageObj.optString("part", "")
            if (pages.length() > 1 && part.isNotBlank()) {
                title = "$title - P$pageIndex $part"
            }
        }

        if (cid == 0L) {
            throw IllegalStateException("未能获取该 B 站视频的 cid")
        }

        // 优先尝试 DASH 纯音频流（体积小、下载快）
        val dashUrl = "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&fnval=16&fourk=0"
        val dashJson = JSONObject(fetchText(dashUrl, biliHeaders))
        val dashAudioUrl = dashJson.optJSONObject("data")
            ?.optJSONObject("dash")
            ?.optJSONArray("audio")
            ?.let { arr -> if (arr.length() > 0) arr.getJSONObject(0).optString("baseUrl") else null }

        val finalMediaUrl = if (!dashAudioUrl.isNullOrBlank()) {
            dashAudioUrl
        } else {
            // 回退到普通 MP4 流
            val mp4Url = "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&qn=32&fnval=1"
            val mp4Json = JSONObject(fetchText(mp4Url, biliHeaders))
            mp4Json.optJSONObject("data")
                ?.optJSONArray("durl")
                ?.let { arr -> if (arr.length() > 0) arr.getJSONObject(0).optString("url") else null }
                ?: throw IllegalStateException("B 站音频流获取受限（可能需要登录或为付费/番剧视频），可下载音频后通过「选择本地文件」离线转写")
        }

        return ParsedMedia(
            title = title.trim(),
            audioUrl = finalMediaUrl,
            sourceType = "B站视频",
            headers = biliHeaders
        )
    }

    private fun parseGenericOrRss(url: String): ParsedMedia {
        val text = fetchText(url, mapOf("User-Agent" to DESKTOP_UA))
        val enclosure = Regex("""<enclosure[^>]+url=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)
            ?: Regex("""<meta\s+property=["']og:audio["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)
            ?: Regex("""https?://[^"'\\\s]+\.(?:m4a|mp3)(?:\?[^"'\\\s]*)?""", RegexOption.IGNORE_CASE)
                .find(text)?.value
            ?: throw IllegalStateException("未能从该网页解析出音频地址，请尝试小宇宙/B站链接或直接上传本地音视频")

        val title = Regex("""<title>(?:<!\[CDATA\[)?([^<\]]+)(?:\]\]>)?</title>""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.trim() ?: "播客音频"

        return ParsedMedia(
            title = decodeHtmlEntities(title),
            audioUrl = enclosure,
            sourceType = "泛用播客",
            headers = mapOf("User-Agent" to DESKTOP_UA)
        )
    }

    fun downloadAudio(
        media: ParsedMedia,
        destFile: File,
        onProgress: (percent: Int, doneMb: Double, totalMb: Double) -> Unit
    ) {
        val builder = Request.Builder().url(media.audioUrl)
        media.headers.forEach { (k, v) -> builder.header(k, v) }

        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("音频下载失败（HTTP ${resp.code}）")
            }
            val body = resp.body ?: throw IllegalStateException("音频流为空")
            val total = body.contentLength()
            var downloaded = 0L
            var lastEmit = 0L

            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 250) {
                            lastEmit = now
                            val pct = if (total > 0) ((downloaded * 100) / total).toInt() else 50
                            onProgress(pct, downloaded / 1048576.0, if (total > 0) total / 1048576.0 else -1.0)
                        }
                    }
                    output.flush()
                }
            }
        }
    }

    private fun resolveFinalUrl(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", DESKTOP_UA).build()
        client.newCall(req).execute().use { resp ->
            return resp.request.url.toString()
        }
    }

    private fun fetchText(url: String, headers: Map<String, String>): String {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("页面请求失败（HTTP ${resp.code}）")
            }
            return resp.body?.string() ?: ""
        }
    }

    private fun decodeHtmlEntities(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
    }
}
