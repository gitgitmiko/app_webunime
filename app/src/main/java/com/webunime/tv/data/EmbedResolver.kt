package com.webunime.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Resolve URL wrapper / embed → URL yang bisa diputar.
 */
object EmbedResolver {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val iframeSrc = Pattern.compile(
        """(?i)<(?:iframe|frame|embed)\b[^>]*\bsrc\s*=\s*["']([^"']+)["']"""
    )
    private val absHttp = Pattern.compile("""(?i)https?://[^\s"'<>]+""")

    /**
     * Wrapper LK21 (playeriframe.sbs dan mirror-nya, mis. videonode.de) memakai pola
     * path /iframe/<server>/<id>. Nama domain berganti-ganti, jadi deteksi utama
     * memakai pola path, bukan daftar host.
     */
    private val wrapperHostName = Regex("(playeriframe|videonode)", RegexOption.IGNORE_CASE)
    private val wrapperPath = Regex("""^/iframe/[a-z0-9_-]+/.+""", RegexOption.IGNORE_CASE)

    data class ResolveResult(
        val url: String,
        /** true = MKV / tidak bisa Exo — tampilkan pesan */
        val unsupported: Boolean = false,
        val message: String? = null,
    )

    suspend fun resolve(sourceUrl: String): ResolveResult = withContext(Dispatchers.IO) {
        when {
            needsPlayeriframeResolve(sourceUrl) ->
                ResolveResult(resolvePlayeriframe(sourceUrl))
            needsWibufileEmbedResolve(sourceUrl) ->
                ResolveResult(resolveWibufileEmbed(sourceUrl) ?: sourceUrl)
            needsFiledonResolve(sourceUrl) -> resolveFiledon(sourceUrl)
            needsPixeldrainResolve(sourceUrl) ->
                ResolveResult(resolvePixeldrain(sourceUrl))
            else -> ResolveResult(sourceUrl)
        }
    }

    fun needsPlayeriframeResolve(url: String): Boolean = isWrapperEmbed(url)

    fun isWrapperEmbed(url: String): Boolean {
        if (wrapperHostName.containsMatchIn(url)) return true
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (isBlockedNavigation(url)) return false
        if (isKnownPlayerHost(host)) return false
        return wrapperPath.containsMatchIn(uri.path.orEmpty())
    }

    fun needsWibufileEmbedResolve(url: String): Boolean =
        url.contains("api.wibufile.com/embed", ignoreCase = true) ||
            url.contains("login.wibufile.com/v/", ignoreCase = true)

    fun needsFiledonResolve(url: String): Boolean =
        url.contains("filedon.co/embed", ignoreCase = true)

    fun needsPixeldrainResolve(url: String): Boolean =
        url.contains("pixeldrain.com", ignoreCase = true)

    /**
     * Mega file share → embed player (UI lebih bersih, tombol play lebih mudah di-otomasi).
     * Contoh: /file/ID#KEY → /embed/ID#KEY
     */
    fun megaEmbedUrl(sourceUrl: String): String? {
        if (!sourceUrl.contains("mega.nz", ignoreCase = true) &&
            !sourceUrl.contains("mega.co.nz", ignoreCase = true)
        ) {
            return null
        }
        if (sourceUrl.contains("/embed/", ignoreCase = true)) return sourceUrl
        // Modern: https://mega.nz/file/ID#KEY
        Regex(
            """(?i)https?://(?:www\.)?mega\.(?:nz|co\.nz)/file/([^#?/\s]+)(?:#([^\s?#]+))?"""
        ).find(sourceUrl)?.let { m ->
            val id = m.groupValues[1]
            val key = m.groupValues.getOrNull(2).orEmpty()
            return if (key.isNotBlank()) "https://mega.nz/embed/$id#$key"
            else "https://mega.nz/embed/$id"
        }
        // Legacy: https://mega.nz/#!ID!KEY
        Regex(
            """(?i)https?://(?:www\.)?mega\.(?:nz|co\.nz)/#!([^!#?\s]+)!([^\s?#]+)"""
        ).find(sourceUrl)?.let { m ->
            return "https://mega.nz/embed/${m.groupValues[1]}#${m.groupValues[2]}"
        }
        return null
    }

    fun isBlockedNavigation(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host.contains("lk21") ||
            host.contains("nontondrama") ||
            host.contains("showcdnx") ||
            host.contains("lk21official") ||
            host.endsWith("tv12.lk21official.cc") ||
            host.contains("dunia21") ||
            host.contains("layarkaca") ||
            host.contains("samehadaku")
    }

    fun isAllowedPlayerHost(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        return wrapperHostName.containsMatchIn(host) || isKnownPlayerHost(host)
    }

    private fun isKnownPlayerHost(host: String): Boolean {
        return host.contains("turbo") ||
            host.contains("emturbovid") ||
            host.contains("turbovid") ||
            host.contains("gn1r5n") ||
            host.contains("abyss") ||
            host.contains("short.icu") ||
            host.contains("iamcdn") ||
            host.contains("blogger.com") ||
            host.contains("wibufile") ||
            host.contains("filedon") ||
            host.contains("mega.nz") ||
            host.contains("pixeldrain") ||
            host.contains("googleapis") ||
            host.contains("googleusercontent") ||
            host.contains("r2.cloudflarestorage") ||
            host.contains("p2pplay") ||
            host.contains("playcdn")
    }

    private fun resolvePlayeriframe(sourceUrl: String): String =
        runCatching {
            val html = fetch(sourceUrl, referer = "https://tv12.lk21official.cc/")
            extractPlayerUrl(html, sourceUrl) ?: sourceUrl
        }.getOrDefault(sourceUrl)

    private fun resolveWibufileEmbed(sourceUrl: String): String? {
        val html = fetch(sourceUrl, referer = "https://api.wibufile.com/")
        return extractMp4(html)
    }

    /**
     * Filedon/Pucuk/VIP: ambil props.url (signed R2) utuh — jangan potong di `&`.
     */
    private fun resolveFiledon(sourceUrl: String): ResolveResult {
        return runCatching {
            val html = fetch(sourceUrl, referer = "https://v2.samehadaku.how/")
            val page = decodeInertiaPage(html)
            val props = page?.optJSONObject("props")
            val ext = props?.optJSONObject("files")?.optString("extension").orEmpty()
                .ifBlank { props?.optJSONObject("files")?.optString("mime_type").orEmpty() }
                .lowercase()
            val hls = props?.optJSONObject("media")?.optString("hls_url")
                ?.takeIf { it.startsWith("http") }
            val direct = props?.optString("url")
                ?.takeIf { it.contains("r2.cloudflarestorage") || it.contains(".mp4") || it.contains(".mkv") }

            when {
                !hls.isNullOrBlank() -> ResolveResult(hls)
                !direct.isNullOrBlank() && (ext.contains("mkv") || direct.contains(".mkv")) ->
                    ResolveResult(
                        url = sourceUrl,
                        unsupported = true,
                        message = "Server Pucuk/VIP ini berkas MKV — browser/TV tidak bisa memutar. Pilih Blogspot atau Mega."
                    )
                !direct.isNullOrBlank() -> ResolveResult(direct)
                else -> {
                    // fallback regex lama (tanpa potong &)
                    extractM3u8(html)?.let { return@runCatching ResolveResult(it) }
                    extractFiledonDirectFull(html)?.let { url ->
                        if (url.contains(".mkv", ignoreCase = true)) {
                            return@runCatching ResolveResult(
                                sourceUrl,
                                unsupported = true,
                                message = "Berkas MKV tidak bisa diputar. Pilih Blogspot atau Mega."
                            )
                        }
                        return@runCatching ResolveResult(url)
                    }
                    ResolveResult(sourceUrl)
                }
            }
        }.getOrElse { ResolveResult(sourceUrl) }
    }

    /** Pixeldrain/Nakama → stream API (Exo) atau ?embed (WebView bersih). */
    private fun resolvePixeldrain(sourceUrl: String): String {
        val id = extractPixeldrainId(sourceUrl) ?: return sourceUrl
        // API stream langsung untuk ExoPlayer
        return "https://pixeldrain.com/api/file/$id"
    }

    /** Embed URL Pixeldrain (UI minimal) — dipakai jika Exo gagal. */
    fun pixeldrainEmbedUrl(sourceUrl: String): String? {
        val id = extractPixeldrainId(sourceUrl) ?: return null
        return "https://pixeldrain.com/u/$id?embed"
    }

    fun extractPixeldrainId(url: String): String? {
        Regex("""pixeldrain\.com/(?:u|api/file)/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)?.let { return it }
        return null
    }

    private fun decodeInertiaPage(html: String): JSONObject? {
        val m = Regex("""data-page=(["'])([\s\S]*?)\1""", RegexOption.IGNORE_CASE).find(html)
            ?: return null
        val raw = m.groupValues[2]
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun fetch(url: String, referer: String): String {
        val req = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"
            )
            .header("Referer", referer)
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("HTTP ${res.code}")
            return res.body?.string().orEmpty()
        }
    }

    private fun extractMp4(html: String): String? {
        val patterns = listOf(
            Regex(""""file"\s*:\s*"((?:https?:)?\\/\\/[^"]+\.mp4[^"]*)"""", RegexOption.IGNORE_CASE),
            Regex(""""file"\s*:\s*"((?:https?://)[^"]+\.mp4[^"]*)"""", RegexOption.IGNORE_CASE),
            Regex("""file\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""(https?://[^\s"'<>]+\.mp4(?:\?[^\s"'<>]*)?)""", RegexOption.IGNORE_CASE),
        )
        for (re in patterns) {
            val m = re.find(html) ?: continue
            var url = m.groupValues[1].replace("\\/", "/")
            if (url.startsWith("//")) url = "https:$url"
            runCatching { return java.net.URI(url).toString() }
        }
        return null
    }

    private fun extractM3u8(html: String): String? {
        val patterns = listOf(
            Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""", RegexOption.IGNORE_CASE),
            Regex("""&quot;(https?://[^&"]*\.m3u8[^&"]*)&quot;""", RegexOption.IGNORE_CASE),
        )
        for (re in patterns) {
            val m = re.find(html) ?: continue
            val url = m.groupValues[1].replace("\\/", "/").replace("&amp;", "&")
            if (url.contains("r2.cloudflarestorage") || url.contains(".m3u8")) {
                return url
            }
        }
        Regex(""""hls_url"\s*:\s*"(https?://[^"]+)"""").find(html)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.let { return it }
        return null
    }

    /** Ambil URL R2 lengkap termasuk query (&amp; / &). */
    private fun extractFiledonDirectFull(html: String): String? {
        Regex(
            """&quot;(https?://[^&"]*r2\.cloudflarestorage\.com[^&"]*)&quot;""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)?.let { raw ->
            return raw.replace("\\/", "/").replace("&amp;", "&").replace("\\u0026", "&")
        }
        Regex(
            """"(https?://[^"]*r2\.cloudflarestorage\.com[^"]+)"""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)?.let { raw ->
            return raw.replace("\\/", "/").replace("\\u0026", "&")
        }
        return null
    }

    private fun extractPlayerUrl(html: String, base: String): String? {
        val candidates = mutableListOf<String>()
        val m = iframeSrc.matcher(html)
        while (m.find()) {
            val raw = m.group(1) ?: continue
            val abs = toAbsolute(raw, base) ?: continue
            if (isBlockedNavigation(abs)) continue
            candidates += abs
        }
        if (candidates.isEmpty()) {
            val am = absHttp.matcher(html)
            while (am.find()) {
                val u = am.group().trimEnd(')', ',', ';', '"', '\'')
                if (isAllowedPlayerHost(u) && !isBlockedNavigation(u) && !isWrapperEmbed(u)) {
                    candidates += u
                }
            }
        }
        val prefer = listOf(
            "turbo", "emturbovid", "gn1r5n", "cast", "abyss", "hydrax",
            "playcdn", "p2pplay", "wibufile", "blogger",
        )
        for (key in prefer) {
            candidates.firstOrNull { it.contains(key, ignoreCase = true) }?.let { return it }
        }
        return candidates.firstOrNull { !isWrapperEmbed(it) }
            ?: candidates.firstOrNull()
    }

    private fun toAbsolute(raw: String, base: String): String? = try {
        java.net.URI(base).resolve(raw).toString()
    } catch (_: Exception) {
        null
    }
}
