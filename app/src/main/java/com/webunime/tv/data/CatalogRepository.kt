package com.webunime.tv.data

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class CatalogRepository(private val context: Context) {

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val listType = Types.newParameterizedType(List::class.java, CatalogItem::class.java)
    private val listAdapter = moshi.adapter<List<CatalogItem>>(listType)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val cacheDir: File
        get() = File(context.filesDir, "catalog").also { if (!it.exists()) it.mkdirs() }

    @Volatile
    var snapshot: CatalogSnapshot = CatalogSnapshot()
        private set

    suspend fun loadInitial(): CatalogSnapshot = withContext(Dispatchers.IO) {
        snapshot = enrichThumbnails(
            CatalogSnapshot(
                movies = readList("movies.json"),
                series = readList("series.json"),
                horror = readList("horror.json"),
                anime = readList("anime.json"),
                animeMovies = readList("anime-movies.json"),
                animeLatest = readList("anime-latest.json"),
            )
        )
        snapshot
    }

    /**
     * Unduh katalog dari GitHub dulu, lalu muat snapshot.
     * @return jumlah file yang berhasil diunduh (0 = gagal total → pakai lokal).
     */
    suspend fun refreshFromGithub(): Int = withContext(Dispatchers.IO) {
        val files = listOf(
            "movies.json",
            "series.json",
            "horror.json",
            "anime.json",
            "anime-movies.json",
            "anime-latest.json",
        )
        var ok = 0
        for (name in files) {
            if (runCatching { downloadAndCache(name) }.isSuccess) ok++
        }
        loadInitial()
        ok
    }

    /**
     * Anime Terbaru = feed per episode → thumbnail episode tetap primary.
     * Poster katalog hanya cadangan jika screenshot episode gagal load (404/SSL).
     */
    private fun enrichThumbnails(snap: CatalogSnapshot): CatalogSnapshot {
        val animeBySlug = HashMap<String, CatalogItem>(snap.anime.size * 2)
        for (item in snap.anime) {
            item.slug?.takeIf { it.isNotBlank() }?.let { animeBySlug[it] = item }
        }

        val feedThumbBySlug = HashMap<String, String>(snap.animeLatest.size)
        for (feed in snap.animeLatest) {
            val slug = feed.anime_slug?.takeIf { it.isNotBlank() } ?: continue
            val thumb = feed.thumbnail?.takeIf { it.isNotBlank() } ?: continue
            feedThumbBySlug.putIfAbsent(slug, thumb)
        }

        val enrichedLatest = snap.animeLatest.map { feed ->
            val parentThumb = feed.anime_slug
                ?.let { animeBySlug[it]?.thumbnail }
                ?.takeIf { it.isNotBlank() }
            val feedThumb = feed.thumbnail?.takeIf { it.isNotBlank() }
            feed.copy(
                thumbnail = feedThumb ?: parentThumb,
                thumbnailAlt = parentThumb?.takeIf { it != feedThumb },
            )
        }

        val enrichedAnime = snap.anime.map { item ->
            val slug = item.slug?.takeIf { it.isNotBlank() } ?: return@map item
            val alt = feedThumbBySlug[slug]?.takeIf { it != item.thumbnail } ?: return@map item
            item.copy(thumbnailAlt = alt)
        }

        return snap.copy(anime = enrichedAnime, animeLatest = enrichedLatest)
    }

    private fun readList(fileName: String): List<CatalogItem> {
        val cached = File(cacheDir, fileName)
        val json = when {
            cached.exists() && cached.length() > 2 -> cached.readText(Charsets.UTF_8)
            else -> runCatching {
                context.assets.open("data/$fileName").bufferedReader().use { it.readText() }
            }.getOrNull()
        } ?: return emptyList()

        return runCatching { listAdapter.fromJson(json).orEmpty() }.getOrDefault(emptyList())
    }

    private fun downloadAndCache(fileName: String) {
        val url = "$GITHUB_RAW_BASE$fileName"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "WEBUNIME-TV/1.0")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $fileName")
            val body = response.body?.string().orEmpty()
            if (body.length < 2) error("Empty body $fileName")
            // Validate JSON parses before overwriting cache
            listAdapter.fromJson(body) ?: error("Invalid JSON $fileName")
            File(cacheDir, fileName).writeText(body, Charsets.UTF_8)
        }
    }

    companion object {
        const val GITHUB_RAW_BASE =
            "https://raw.githubusercontent.com/gitgitmiko/WEBUNIME/main/public/data/"
    }
}
