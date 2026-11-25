package com.erxi.qingertranscriber

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
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

    // 内存 CookieJar：拉笔记页时拿到的 Cookie 需要带到后续的 CDN 下载请求上，
    // 否则小红书 CDN 可能直接 403。本客户端只拉媒体站页面、只下载媒体文件。
    private val cookieJar = object : CookieJar {
        private val cookies = mutableListOf<Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            this.cookies.removeAll { old -> cookies.any { it.name == old.name } }
            this.cookies.addAll(cookies)
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.toList()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(cookieJar)
        .build()

    fun extractFirstUrl(rawText: String): String? {
        val matcher = Pattern.compile("https?://[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+").matcher(rawText)
        return if (matcher.find()) matcher.group() else null
    }

    fun parse(rawInput: String): ParsedMedia {
        val url = extractFirstUrl(rawInput.trim())
            ?: throw IllegalArgumentException("未在输入内容中识别到有效链接，请粘贴小宇宙、B站、小红书链接或分享口令")

        return when {
            url.contains("xiaoyuzhoufm.com", ignoreCase = true) -> parseXiaoyuzhou(url)
            url.contains("bilibili.com", ignoreCase = true) ||
                url.contains("b23.tv", ignoreCase = true) -> parseBilibili(url)
            url.contains("xiaohongshu.com", ignoreCase = true) ||
                url.contains("xhslink.com", ignoreCase = true) ||
                url.contains("xhslink.cn", ignoreCase = true) -> parseXiaohongshu(url)
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

    private fun parseXiaohongshu(inputUrl: String): ParsedMedia {
        val resolvedUrl = if (inputUrl.contains("xhslink.com", ignoreCase = true) ||
            inputUrl.contains("xhslink.cn", ignoreCase = true)
        ) {
            resolveFinalUrl(inputUrl)
        } else {
            inputUrl
        }

        val noteId = Regex("""/(?:explore|discovery/item)/([A-Za-z0-9]+)""")
            .find(resolvedUrl)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("未识别到小红书笔记链接，请复制笔记详情页、分享口令或短链")

        val pageUrl = "https://www.xiaohongshu.com/explore/$noteId"
        // 下载时用笔记详情页作 Referer（不用首页），否则 CDN 可能拦。
        val xhsHeaders = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to pageUrl
        )
        val html = try {
            fetchText(pageUrl, xhsHeaders)
        } catch (e: IllegalStateException) {
            throw IllegalStateException(
                "小红书页面请求受限（可能触发风控），请在小红书里把视频保存到相册，然后用「选择本地文件」转录"
            )
        }

        // 登录墙检测：无登录态时小红书返回登录页（通用标题、无 __INITIAL_STATE__，
        // 页面里没有任何视频地址）。不拦下来，后面的兜底正则会把登录页 JS 里的
        // 裸 CDN 域名当成视频地址，下载时 CDN 回 403。
        val pageTitle = Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim().orEmpty()
        val isLoginWall = !html.contains("__INITIAL_STATE__") &&
            (pageTitle == "小红书 - 你的生活兴趣社区" ||
                (html.contains("login", ignoreCase = true) &&
                    (html.contains("登录") || html.contains("扫码"))))
        if (isLoginWall) {
            throw IllegalStateException(
                "小红书页面需要登录或受限，无法直接解析：请在小红书里把视频保存到相册，再用「本地文件」转录"
            )
        }

        val title = Regex("""<meta\s+property=["']og:title["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?: Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
            ?: "小红书笔记"

        // 多层解析视频流地址：
        // 1) window.__INITIAL_STATE__ 里的 masterUrl（无水印 CDN 直链）
        // 2) originVideoKey：取 key 后在页面里找包含它的完整 CDN 地址
        // 3) og:video
        // 4) xhscdn.com 的 mp4 直链 / sns-video CDN
        val masterUrl = Regex(""""masterUrl"\s*:\s*"([^"]+)"""")
            .find(html)?.groupValues?.get(1)
        val originKey = Regex(""""originVideoKey"\s*:\s*"([^"]+)"""")
            .find(html)?.groupValues?.get(1)
        val keyUrl = originKey?.let { key ->
            Regex("""https://[^"'\\\s]*""" + Regex.escape(key) + """[^"'\\\s]*""")
                .find(html)?.value
        }
        val videoUrl = masterUrl
            ?: keyUrl
            ?: Regex("""<meta\s+property=["']og:video["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
            ?: Regex("""https://[^"'\\\s]*xhscdn\.com[^"'\\\s]*\.mp4[^"'\\\s]*""").find(html)?.value
            // 兜底：域名后必须有路径，裸域名（如登录页 JS 里的 https://sns-video-qc.xhscdn.com）
            // 绝不能被当成视频地址。
            ?: Regex("""https://sns-video-[^"'\\\s]+/[^"'\\\s]+""").find(html)?.value
            ?: throw IllegalStateException(
                "该笔记无法解析出视频地址（图文笔记没有视频，或触发了小红书风控），" +
                    "请在小红书里把视频保存到相册，然后用「选择本地文件」转录"
            )

        return ParsedMedia(
            title = decodeHtmlEntities(title).replace(" - 小红书", "").trim(),
            audioUrl = videoUrl.replace("\\u002F", "/").replace("&amp;", "&"),
            sourceType = "小红书视频",
            headers = xhsHeaders
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
            ?: throw IllegalStateException("未能从该网页解析出音频地址，请尝试小宇宙/B站/小红书链接或直接上传本地音视频")

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
