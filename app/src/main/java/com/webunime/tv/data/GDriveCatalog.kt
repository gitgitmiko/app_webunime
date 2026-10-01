package com.webunime.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Baris "Film Gdrives": daftar video di folder Drive publik, diputar lewat ExoPlayer.
 */
object GDriveCatalog {

    const val FOLDER_ID = "1wVY66-iublVb8gIwo_S4hTmBSiyRx_3D"
    const val COLLECTION = "gdrive"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val entryRe = Regex(
        """id="entry-([A-Za-z0-9_-]+)"[\s\S]*?type/video/[\s\S]*?<div class="flip-entry-title">([^<]+)</div>""",
    )
    private val yearRe = Regex("""(?:^|\.)((?:19|20)\d{2})(?:\.|$)""")
    private val qualityRe = Regex("""(?i)(\d{3,4})p""")
    private val releaseToken = Regex(
        """(?i)(?:\d{3,4}p|web[-.]?dl|bluray|blu-ray|hdrip|dvdrip|x264|x265|h\.?264|h\.?265|hevc|10bit)""",
    )

    @Volatile
    private var cache: List<CatalogItem> = emptyList()

    @Volatile
    private var fetchedAtMs: Long = 0L

    suspend fun listFilms(maxAgeMs: Long = 5 * 60_000L): List<CatalogItem> {
        val now = System.currentTimeMillis()
        val fresh = cache
        if (fresh.isNotEmpty() && now - fetchedAtMs < maxAgeMs) return fresh
        val loaded = withContext(Dispatchers.IO) { fetchFilms() }
        if (loaded != null) {
            cache = loaded
            fetchedAtMs = now
            return loaded
        }
        return fresh
    }

    fun playUrl(fileId: String): String =
        "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"

    private fun fetchFilms(): List<CatalogItem>? {
        val html = runCatching {
            val request = Request.Builder()
                .url("https://drive.google.com/embeddedfolderview?id=$FOLDER_ID")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
                )
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        }.getOrNull() ?: return null

        return entryRe.findAll(html).map { match ->
            val fileId = match.groupValues[1]
            val rawName = unescape(match.groupValues[2]).trim()
            val parsed = prettyName(rawName)
            CatalogItem(
                type = "movie",
                nama = parsed.title,
                judul = parsed.title,
                tahun = parsed.year,
                quality = parsed.quality,
                thumbnail = "https://drive.google.com/thumbnail?id=$fileId&sz=w800",
                slug = "gdrive-$fileId",
                catalog = COLLECTION,
                source = "https://drive.google.com/file/d/$fileId/view",
                players = listOf(
                    PlayerServer(
                        no = 1,
                        server = "gdrive",
                        label = "GDrive",
                        url = playUrl(fileId),
                        isDefault = true,
                    ),
                ),
            )
        }.distinctBy { it.slug }.toList()
    }

    private data class PrettyName(val title: String, val year: String?, val quality: String?)

    private fun prettyName(fileName: String): PrettyName {
        val base = fileName.substringBeforeLast('.').trim()
        val year = yearRe.find(base)?.groupValues?.get(1)
        val quality = qualityRe.find(base)?.groupValues?.get(1)?.let { "${it}p" }
        val cut = when {
            year != null -> base.substring(0, yearRe.find(base)!!.range.first)
            else -> {
                val token = releaseToken.find(base)
                if (token != null) base.substring(0, token.range.first) else base
            }
        }
        val title = cut.trim('.', ' ', '-', '_')
            .replace('.', ' ')
            .replace('_', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { base.replace('.', ' ') }
        return PrettyName(title, year, quality?.lowercase())
    }

    private fun unescape(text: String): String =
        text.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
}
