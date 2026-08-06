package com.webunime.tv.data

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CatalogRepository(private val context: Context) {

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val listType = Types.newParameterizedType(List::class.java, CatalogItem::class.java)
    private val listAdapter = moshi.adapter<List<CatalogItem>>(listType)

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    private val cacheDir: File
        get() = File(context.filesDir, "catalog").also { if (!it.exists()) it.mkdirs() }

    private val refreshMutex = Mutex()
    private val githubRefreshDone = AtomicBoolean(false)

    @Volatile
    var snapshot: CatalogSnapshot = CatalogSnapshot()
        private set

    fun isSnapshotReady(): Boolean =
        snapshot.movies.isNotEmpty() ||
            snapshot.series.isNotEmpty() ||
            snapshot.horror.isNotEmpty() ||
            snapshot.indonesia.isNotEmpty() ||
            snapshot.anime.isNotEmpty() ||
            snapshot.animeMovies.isNotEmpty() ||
            snapshot.animeLatest.isNotEmpty()

    /** Ada cache lokal atau assets bawaan — boleh tampil browse tanpa tunggu GitHub. */
    fun hasLocalCatalog(): Boolean {
        return CATALOG_FILES.any { name ->
            val cached = File(cacheDir, name)
            if (cached.exists() && cached.length() > 2) return@any true
            runCatching {
                context.assets.open("data/$name").use { it.available() > 2 }
            }.getOrDefault(false)
        }
    }

    suspend fun loadInitial(): CatalogSnapshot {
        loadBrowseFirst()
        return loadHeavyCatalog()
    }

    /** Film / horror / indonesia / anime terbaru — cepat, cukup untuk isi layar pertama. */
    suspend fun loadBrowseFirst(): CatalogSnapshot = withContext(Dispatchers.IO) {
        if (snapshot.movies.isNotEmpty() || snapshot.horror.isNotEmpty() ||
            snapshot.indonesia.isNotEmpty() ||
            snapshot.animeMovies.isNotEmpty() || snapshot.animeLatest.isNotEmpty()
        ) {
            return@withContext snapshot
        }
        val movies = readList("movies.json")
        val horror = readList("horror.json")
        val indonesia = readList("indonesia.json")
        val animeMovies = readList("anime-movies.json")
        val animeLatest = readList("anime-latest.json")
        snapshot = enrichThumbnails(
            snapshot.copy(
                movies = movies,
                horror = horror,
                indonesia = indonesia,
                animeMovies = animeMovies,
                animeLatest = animeLatest,
            )
        )
        snapshot
    }

    /** Series + anime penuh (~20MB) — setelah browse sudah tampil. */
    suspend fun loadHeavyCatalog(): CatalogSnapshot = withContext(Dispatchers.IO) {
        if (snapshot.series.isNotEmpty() && snapshot.anime.isNotEmpty()) {
            return@withContext snapshot
        }
        val series = if (snapshot.series.isEmpty()) readList("series.json") else snapshot.series
        val anime = if (snapshot.anime.isEmpty()) readList("anime.json") else snapshot.anime
        snapshot = enrichThumbnails(snapshot.copy(series = series, anime = anime))
        snapshot
    }

    /**
     * Pastikan snapshot terisi dari cache/assets (cepat).
     * Tidak mengunduh GitHub.
     */
    suspend fun ensureLocalLoaded(): CatalogSnapshot {
        if (isSnapshotReady()) return snapshot
        return loadInitial()
    }

    /**
     * Unduh katalog dari GitHub paling banyak sekali per proses app.
     * @return jumlah file OK; -1 = sudah pernah sync di proses ini; 0 = gagal total.
     */
    suspend fun refreshFromGithubOnce(): Int {
        if (githubRefreshDone.get()) return -1
        return refreshMutex.withLock {
            if (githubRefreshDone.get()) return@withLock -1
            val ok = refreshFromGithub()
            githubRefreshDone.set(true)
            ok
        }
    }

    /**
     * Unduh katalog dari GitHub dulu, lalu muat snapshot.
     * @return jumlah file yang berhasil diunduh (0 = gagal total → pakai lokal).
     */
    suspend fun refreshFromGithub(): Int = withContext(Dispatchers.IO) {
        var ok = 0
        for (name in CATALOG_FILES) {
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
            val parent = feed.anime_slug?.let { animeBySlug[it] }
            val parentThumb = parent?.thumbnail?.takeIf { it.isNotBlank() }
            val parentLand = parent?.thumbnail_landscape?.takeIf { it.isNotBlank() }
            val feedThumb = feed.thumbnail?.takeIf { it.isNotBlank() }
            feed.copy(
                thumbnail = feedThumb ?: parentThumb,
                thumbnailAlt = parentThumb?.takeIf { it != feedThumb },
                thumbnail_landscape = feed.thumbnail_landscape?.takeIf { it.isNotBlank() }
                    ?: parentLand,
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

        return runCatching {
            listAdapter.fromJson(json).orEmpty().map { normalizeCatalogUrls(it) }
        }.getOrDefault(emptyList())
    }

    /**
     * Normalisasi URL katalog:
     * - poster/image.showcdnx → poster.lk21official (cover.showcdnx tetap)
     * - playeriframe.sbs → videonode.de (domain wrapper lama 404)
     */
    private fun normalizeCatalogUrls(item: CatalogItem): CatalogItem {
        val thumb = rewriteDeadPosterHost(item.thumbnail)
        val land = rewriteDeadPosterHost(item.thumbnail_landscape)
        val players = item.players?.map { p ->
            val u = rewritePlayerHost(p.url)
            if (u == p.url) p else p.copy(url = u)
        }
        val episodes = item.episodes?.map { ep ->
            val epsPlayers = ep.players?.map { p ->
                val u = rewritePlayerHost(p.url)
                if (u == p.url) p else p.copy(url = u)
            }
            if (epsPlayers == ep.players) ep else ep.copy(players = epsPlayers)
        }
        if (thumb == item.thumbnail &&
            land == item.thumbnail_landscape &&
            players == item.players &&
            episodes == item.episodes
        ) {
            return item
        }
        return item.copy(
            thumbnail = thumb,
            thumbnail_landscape = land,
            players = players,
            episodes = episodes,
        )
    }

    private fun rewriteDeadPosterHost(url: String?): String? {
        if (url.isNullOrBlank()) return url
        return url
            .replace(
                Regex("""(?i)https?://poster\.showcdnx\.com"""),
                "https://poster.lk21official.cc",
            )
            .replace(
                Regex("""(?i)https?://image\.showcdnx\.com"""),
                "https://poster.lk21official.cc",
            )
    }

    private fun rewritePlayerHost(url: String?): String? {
        if (url.isNullOrBlank()) return url
        return url.replace(
            Regex("""(?i)https?://playeriframe\.sbs"""),
            "https://videonode.de",
        )
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
            // Validasi ringan saja — jangan parse penuh di sini (anime/series ~10MB,
            // double-parse di emulator membuat UI stuck hitam lama).
            val trimmed = body.trimStart()
            if (!trimmed.startsWith("[")) error("Invalid JSON root $fileName")
            File(cacheDir, fileName).writeText(body, Charsets.UTF_8)
        }
    }

    companion object {
        const val GITHUB_RAW_BASE =
            "https://raw.githubusercontent.com/gitgitmiko/WEBUNIME/main/public/data/"

        private val CATALOG_FILES = listOf(
            "movies.json",
            "series.json",
            "horror.json",
            "indonesia.json",
            "anime.json",
            "anime-movies.json",
            "anime-latest.json",
        )
    }
}
