package com.webunime.tv.data

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.webunime.tv.data.api.CatalogPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.source
import java.io.File
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Katalog dari JSON publik repo WEBUNIME (GitHub raw / jsDelivr),
 * dengan fallback cache lokal + assets bawaan.
 */
class CatalogRepository(
    private val context: Context,
) {
    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val listType = Types.newParameterizedType(List::class.java, CatalogItem::class.java)
    private val listAdapter = moshi.adapter<List<CatalogItem>>(listType)
    private val shellListType =
        Types.newParameterizedType(List::class.java, CatalogItemShell::class.java)
    private val shellListAdapter = moshi.adapter<List<CatalogItemShell>>(shellListType)
    private val itemAdapter = moshi.adapter(CatalogItem::class.java)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .build()

    private val cacheDir: File
        get() = File(context.filesDir, "catalog").also { if (!it.exists()) it.mkdirs() }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val refreshMutex = Mutex()
    private val sectionMutex = Mutex()
    private val githubRefreshDone = AtomicBoolean(false)
    private val loadedSections = mutableSetOf<CatalogSection>()
    private val itemCache = ConcurrentHashMap<String, CatalogItem>()

    @Volatile
    var snapshot: CatalogSnapshot = CatalogSnapshot()
        private set

    @Volatile
    var heroItems: List<CatalogItem> = emptyList()
        private set

    @Volatile
    var homeLoaded: Boolean = false
        private set

    fun isSnapshotReady(): Boolean =
        homeLoaded ||
            heroItems.isNotEmpty() ||
            snapshot.movies.isNotEmpty() ||
            snapshot.indonesia.isNotEmpty() ||
            snapshot.horror.isNotEmpty() ||
            snapshot.marvel.isNotEmpty() ||
            snapshot.series.isNotEmpty() ||
            snapshot.seriesLatest.isNotEmpty() ||
            snapshot.anime.isNotEmpty() ||
            snapshot.animeMovies.isNotEmpty() ||
            snapshot.animeLatest.isNotEmpty()

    fun isSectionLoaded(section: CatalogSection): Boolean = section in loadedSections

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

    /** True jika belum sync sukses hari ini, atau cache unduhan belum ada. */
    fun needsGithubRefreshToday(): Boolean {
        if (!hasDownloadedCache()) return true
        return prefs.getString(KEY_LAST_SYNC_DAY, null) != todayKey()
    }

    /**
     * Cold start UI: sync GitHub (jika perlu) + shell ringan + hero lokal.
     */
    suspend fun loadHome(): CatalogSnapshot = refreshMutex.withLock {
        loadHomeUnlocked()
    }

    private suspend fun loadHomeUnlocked(): CatalogSnapshot {
        if (needsGithubRefreshToday() && !githubRefreshDone.get()) {
            val ok = withContext(Dispatchers.IO) { downloadCatalogFiles(cacheBust = false) }
            if (ok > 0) {
                prefs.edit().putString(KEY_LAST_SYNC_DAY, todayKey()).apply()
                githubRefreshDone.set(true)
                snapshot = CatalogSnapshot()
                loadedSections.clear()
                itemCache.clear()
            }
        }
        loadStartupShell()
        rebuildHero()
        homeLoaded = true
        return snapshot
    }

    suspend fun loadStartupShell(): CatalogSnapshot {
        for (section in CatalogSection.STARTUP) {
            ensureSection(section)
        }
        rebuildHero()
        return snapshot
    }

    suspend fun loadInitial(): CatalogSnapshot {
        loadStartupShell()
        return ensureAllSections()
    }

    /** @deprecated gunakan [loadStartupShell] */
    suspend fun loadBrowseFirst(): CatalogSnapshot = loadStartupShell()

    suspend fun loadHeavyCatalog(): CatalogSnapshot {
        ensureSection(CatalogSection.SERIES)
        ensureSection(CatalogSection.ANIME)
        return snapshot
    }

    suspend fun ensureSection(section: CatalogSection): CatalogSnapshot = sectionMutex.withLock {
        withContext(Dispatchers.IO) {
            if (section in loadedSections) return@withContext snapshot
            // Anime/Series browse pakai *-index.json (~1MB). Fallback ke file penuh (shell) bila index belum ada.
            var list = readList(section.fileName)
            if (list.isEmpty()) {
                list = when (section) {
                    CatalogSection.ANIME -> readList("anime.json", lightweight = true)
                    CatalogSection.SERIES -> readList("series.json", lightweight = true)
                    else -> emptyList()
                }
            }
            if (list.isEmpty()) {
                val file = File(cacheDir, section.fileName)
                if (file.exists() && file.length() > 50_000L) {
                    file.delete()
                    list = readList(section.fileName)
                    if (list.isEmpty()) {
                        list = when (section) {
                            CatalogSection.ANIME -> readList("anime.json", lightweight = true)
                            CatalogSection.SERIES -> readList("series.json", lightweight = true)
                            else -> emptyList()
                        }
                    }
                }
            }
            // Kosong pun ditandai loaded agar tidak retry parse berkali-kali.
            loadedSections.add(section)
            if (list.isEmpty()) return@withContext snapshot
            snapshot = applySection(section, list)
            snapshot = enrichThumbnails(snapshot)
            for (item in list) remember(item, section.apiName)
            snapshot
        }
    }

    private fun applySection(section: CatalogSection, list: List<CatalogItem>): CatalogSnapshot =
        when (section) {
            CatalogSection.MOVIES -> snapshot.copy(movies = list)
            CatalogSection.INDONESIA -> snapshot.copy(indonesia = list)
            CatalogSection.HORROR -> snapshot.copy(horror = list)
            CatalogSection.MARVEL -> snapshot.copy(marvel = list)
            CatalogSection.SERIES_LATEST -> snapshot.copy(seriesLatest = list)
            CatalogSection.SERIES -> snapshot.copy(series = list)
            CatalogSection.ANIME_LATEST -> snapshot.copy(animeLatest = list)
            CatalogSection.ANIME -> snapshot.copy(anime = list)
            CatalogSection.ANIME_MOVIES -> snapshot.copy(animeMovies = list)
        }

    suspend fun ensureSections(sections: Collection<CatalogSection>): CatalogSnapshot {
        for (section in sections) ensureSection(section)
        return snapshot
    }

    suspend fun ensureAllSections(): CatalogSnapshot =
        ensureSections(CatalogSection.ALL)

    /**
     * Halaman koleksi dari JSON lokal (kompatibel dengan browse API-style).
     */
    suspend fun listCollectionPage(
        collection: String,
        page: Int = 1,
        limit: Int = PAGE_LIMIT,
        q: String = "",
        genre: String = "",
        sort: String = "",
    ): CatalogPage {
        val section = sectionFor(collection) ?: return CatalogPage(collection = collection)
        ensureSection(section)
        when (section) {
            CatalogSection.SERIES_LATEST -> ensureSection(CatalogSection.SERIES)
            CatalogSection.ANIME_LATEST -> ensureSection(CatalogSection.ANIME)
            else -> Unit
        }

        var items = itemsFor(section).map { remember(it, section.apiName) }
        val query = q.trim()
        if (query.length >= 2) {
            val needle = query.lowercase()
            items = items.filter { item ->
                item.displayTitle().lowercase().contains(needle) ||
                    (item.slug ?: item.anime_slug ?: "").lowercase().contains(needle)
            }
        }
        if (genre.isNotBlank()) {
            val wanted = genre.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (wanted.isNotEmpty()) {
                items = items.filter { item ->
                    item.genre.orEmpty().any { g ->
                        wanted.any { w -> g.equals(w, ignoreCase = true) }
                    }
                }
            }
        }
        items = when (sort.trim().lowercase()) {
            "rating" -> items.sortedByDescending { ratingValue(it) }
            "hot" -> items.sortedByDescending { hotScore(it) }
            // Random N film rating > 8 dari koleksi (Top Film / Top Horor).
            "top_random" ->
                items
                    .filter { ratingValue(it) > TOP_RANDOM_MIN_RATING }
                    .shuffled()
                    .take(TOP_RANDOM_LIMIT)
            else -> if (section == CatalogSection.INDONESIA) {
                items.sortedWith(
                    compareByDescending<CatalogItem> { it.releaseSortKey() }
                        .thenByDescending { it.tahun?.toIntOrNull() ?: 0 }
                        .thenBy { it.displayTitle() },
                )
            } else {
                items
            }
        }

        val pageNum = page.coerceAtLeast(1)
        val lim = limit.coerceIn(1, 80)
        val from = (pageNum - 1) * lim
        val slice = if (from >= items.size) emptyList() else items.drop(from).take(lim)
        return CatalogPage(
            collection = section.apiName,
            page = pageNum,
            limit = lim,
            total = items.size,
            items = slice,
        )
    }

    suspend fun fetchHero(limit: Int = HERO_LIMIT): List<CatalogItem> {
        ensureSections(CatalogSection.STARTUP)
        rebuildHero(limit)
        return heroItems
    }

    suspend fun search(query: String, limit: Int = 40): List<CatalogItem> {
        ensureAllSections()
        return snapshot.search(query, limit).map {
            remember(it, it.catalog ?: it.detailCollection())
        }
    }

    suspend fun getItem(collection: String, slug: String): CatalogItem? {
        val col = collection.trim()
        val s = slug.trim()
        if (col.isBlank() || s.isBlank()) return null
        val cached = itemCache["$col:${s.lowercase()}"]
        sectionFor(col)?.let { ensureSection(it) }
        val found = snapshot.findBySlug(s)?.let { remember(it, col) }
        val seed = found ?: cached
        if (cached != null && cached.isHydrated() && !needsEpisodeRefresh(cached, seed ?: cached)) {
            return cached
        }
        return hydrateIfNeeded(seed, col)
    }

    suspend fun findBySlugEnsured(
        slug: String,
        collectionHint: String? = null,
    ): CatalogItem? {
        if (slug.isBlank()) return null
        val key = slug.trim()
        val hint = collectionHint?.takeIf { it.isNotBlank() }
        if (hint != null) {
            sectionFor(hint)?.let { ensureSection(it) }
            snapshot.findBySlug(key)?.let {
                return hydrateIfNeeded(remember(it, hint), hint)
            }
            if (hint == "anime-latest" || hint == "anime") {
                ensureSection(CatalogSection.ANIME)
                ensureSection(CatalogSection.ANIME_LATEST)
                snapshot.findBySlug(key)?.let {
                    return hydrateIfNeeded(remember(it, "anime"), "anime")
                }
                return hydrateIfNeeded(
                    CatalogItem(slug = key, anime_slug = key, catalog = "anime", type = "anime"),
                    "anime",
                )
            }
            if (hint == "series-latest" || hint == "series") {
                ensureSection(CatalogSection.SERIES)
                ensureSection(CatalogSection.SERIES_LATEST)
                snapshot.findBySlug(key)?.let {
                    return hydrateIfNeeded(remember(it, "series"), "series")
                }
                return hydrateIfNeeded(
                    CatalogItem(slug = key, series_slug = key, catalog = "series", type = "series"),
                    "series",
                )
            }
        }
        snapshot.findBySlug(key)?.let {
            return hydrateIfNeeded(it, it.detailCollection())
        }
        val order = listOf(
            CatalogSection.MOVIES,
            CatalogSection.INDONESIA,
            CatalogSection.HORROR,
            CatalogSection.SERIES,
            CatalogSection.ANIME,
            CatalogSection.ANIME_MOVIES,
            CatalogSection.SERIES_LATEST,
            CatalogSection.ANIME_LATEST,
        )
        for (section in order) {
            if (section in loadedSections) continue
            ensureSection(section)
            snapshot.findBySlug(key)?.let {
                return hydrateIfNeeded(remember(it, section.apiName), section.apiName)
            }
        }
        hydrateFromFile("anime.json", key)?.let { return remember(it, "anime") }
        hydrateFromFile("series.json", key)?.let { return remember(it, "series") }
        return snapshot.findBySlug(key)?.let { hydrateIfNeeded(it, it.detailCollection()) }
    }

    private suspend fun hydrateIfNeeded(item: CatalogItem?, collection: String?): CatalogItem? {
        if (item == null) return null
        val slug = item.slug?.takeIf { it.isNotBlank() }
            ?: item.anime_slug?.takeIf { it.isNotBlank() }
            ?: item.series_slug?.takeIf { it.isNotBlank() }
            ?: return item
        val col = (collection ?: item.detailCollection()).lowercase()
        val file = when {
            col.contains("anime") && !col.contains("movie") -> "anime.json"
            col.contains("series") -> "series.json"
            else -> return item
        }
        if (item.isHydrated() && !needsEpisodeRefresh(item, item)) {
            return item
        }
        if (file == "anime.json") {
            ensureSection(CatalogSection.ANIME)
            ensureSection(CatalogSection.ANIME_LATEST)
        } else if (file == "series.json") {
            ensureSection(CatalogSection.SERIES)
            ensureSection(CatalogSection.SERIES_LATEST)
        }
        return withContext(Dispatchers.IO) {
            ensureHeavyFile(file, force = false)
            var hydrated = hydrateFromFile(file, slug)
            if (hydrated != null && needsEpisodeRefresh(hydrated, item)) {
                // Cache anime.json/series.json sering tertinggal dari anime-latest (mis. kartu E12, detail masih 4).
                ensureHeavyFile(file, force = true)
                hydrated = hydrateFromFile(file, slug) ?: hydrated
            }
            hydrated?.let { remember(it, col) } ?: item
        }
    }

    /** Feed/index bilang lebih banyak episode daripada yang sudah di-hydrate. */
    private fun needsEpisodeRefresh(hydrated: CatalogItem, seed: CatalogItem): Boolean {
        val got = hydrated.episodes?.size ?: 0
        if (got <= 0 && hydrated.players.isNullOrEmpty()) return true
        val expected = expectedEpisodeFloor(seed, hydrated)
        return expected > 0 && got > 0 && got < expected
    }

    private fun expectedEpisodeFloor(vararg items: CatalogItem): Int {
        var floor = 0
        for (item in items) {
            floor = maxOf(
                floor,
                item.episodes_count ?: 0,
                item.episode ?: 0,
                item.episodes?.size ?: 0,
            )
            val key = item.detailSlug().takeIf { it.isNotBlank() } ?: continue
            snapshot.findBySlug(key)?.let { snap ->
                floor = maxOf(floor, snap.episodes_count ?: 0, snap.episode ?: 0)
            }
            for (latest in snapshot.animeLatest) {
                if (latest.anime_slug.equals(key, ignoreCase = true) ||
                    latest.slug.equals(key, ignoreCase = true)
                ) {
                    floor = maxOf(floor, latest.episode ?: 0, latest.episodes_count ?: 0)
                }
            }
            for (latest in snapshot.seriesLatest) {
                if (latest.series_slug.equals(key, ignoreCase = true) ||
                    latest.slug.equals(key, ignoreCase = true)
                ) {
                    floor = maxOf(floor, latest.episode ?: 0, latest.episodes_count ?: 0)
                }
            }
        }
        return floor
    }

    /** Pastikan anime.json / series.json ada di cache (unduh on-demand untuk detail). */
    private fun ensureHeavyFile(fileName: String, force: Boolean = false) {
        val cached = File(cacheDir, fileName)
        if (!force && cached.exists() && cached.length() > 2) return
        runCatching { downloadAndCache(fileName, cacheBust = force) }
    }

    suspend fun ensureLocalLoaded(): CatalogSnapshot {
        if (isSnapshotReady()) return snapshot
        return loadStartupShell()
    }

    /**
     * Unduh katalog dari GitHub paling banyak sekali per hari.
     * @return jumlah file OK; -1 = dilewati; 0 = gagal total.
     */
    suspend fun refreshFromGithubOnce(): Int {
        if (githubRefreshDone.get()) return -1
        return refreshMutex.withLock {
            if (githubRefreshDone.get()) return@withLock -1
            if (!needsGithubRefreshToday()) {
                githubRefreshDone.set(true)
                return@withLock -1
            }
            val ok = refreshFromGithub()
            if (ok > 0) {
                prefs.edit().putString(KEY_LAST_SYNC_DAY, todayKey()).apply()
                githubRefreshDone.set(true)
            }
            ok
        }
    }

    suspend fun refreshFromGithub(): Int = withContext(Dispatchers.IO) {
        val ok = downloadCatalogFiles(cacheBust = false)
        snapshot = CatalogSnapshot()
        loadedSections.clear()
        itemCache.clear()
        homeLoaded = false
        loadStartupShell()
        ok
    }

    suspend fun forceRefreshFromGithub(): Int = refreshMutex.withLock {
        val ok = withContext(Dispatchers.IO) {
            downloadCatalogFiles(cacheBust = true)
        }
        snapshot = CatalogSnapshot()
        loadedSections.clear()
        itemCache.clear()
        homeLoaded = false
        loadStartupShell()
        rebuildHero()
        homeLoaded = true
        if (ok > 0) {
            prefs.edit()
                .putString(KEY_LAST_SYNC_DAY, todayKey())
                .putBoolean(KEY_RELOAD_BROWSE, true)
                .apply()
            githubRefreshDone.set(true)
        }
        ok
    }

    /** Alias Settings lama yang menyebut API. */
    suspend fun forceRefreshFromApi(): Int = forceRefreshFromGithub()

    fun consumeBrowseReloadRequest(): Boolean {
        if (!prefs.getBoolean(KEY_RELOAD_BROWSE, false)) return false
        prefs.edit().putBoolean(KEY_RELOAD_BROWSE, false).apply()
        return true
    }

    fun cachedItem(collection: String, slug: String): CatalogItem? =
        itemCache["$collection:${slug.lowercase()}"]
            ?: snapshot.findBySlug(slug)

    /** Baca sync-status.json publik (tanpa token), dengan cache-bust. */
    suspend fun fetchSyncStatus(): CatalogSyncStatus? = withContext(Dispatchers.IO) {
        val bust = System.currentTimeMillis()
        val urls = listOf(
            "$GITHUB_RAW_BASE$SYNC_STATUS_FILE?t=$bust",
            "$GITHUB_JSDELIVR_BASE$SYNC_STATUS_FILE?t=$bust",
        )
        for (url in urls) {
            val status = runCatching { fetchSyncStatusFrom(url) }.getOrNull()
            if (status != null) return@withContext status
        }
        null
    }

    private fun fetchSyncStatusFrom(url: String): CatalogSyncStatus? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "WEBUNIME-TV/1.0")
            .header("Cache-Control", "no-cache, no-store, must-revalidate")
            .header("Pragma", "no-cache")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            return moshi.adapter(CatalogSyncStatus::class.java).fromJson(body)
                ?: runCatching {
                    val o = org.json.JSONObject(body.trim().removePrefix("\uFEFF"))
                    CatalogSyncStatus(
                        state = o.optString("state").takeIf { it.isNotBlank() },
                        startedAt = o.optString("startedAt").takeIf { it.isNotBlank() && it != "null" },
                        finishedAt = o.optString("finishedAt").takeIf { it.isNotBlank() && it != "null" },
                        runId = if (o.has("runId") && !o.isNull("runId")) o.optLong("runId") else null,
                        message = o.optString("message").takeIf { it.isNotBlank() },
                    )
                }.getOrNull()
        }
    }

    private fun downloadCatalogFiles(cacheBust: Boolean): Int {
        var ok = 0
        for (name in CATALOG_FILES) {
            if (runCatching { downloadAndCache(name, cacheBust) }.isSuccess) ok++
        }
        return ok
    }

    private fun hasDownloadedCache(): Boolean =
        CATALOG_FILES.any { name ->
            val f = File(cacheDir, name)
            f.exists() && f.length() > 2
        }

    private fun todayKey(): String {
        val c = Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH),
        )
    }

    private fun enrichThumbnails(snap: CatalogSnapshot): CatalogSnapshot {
        val animeBySlug = HashMap<String, CatalogItem>(snap.anime.size * 2)
        for (item in snap.anime) {
            item.slug?.takeIf { it.isNotBlank() }?.let { animeBySlug[it] = item }
        }
        val seriesBySlug = HashMap<String, CatalogItem>(snap.series.size * 2)
        for (item in snap.series) {
            item.slug?.takeIf { it.isNotBlank() }?.let { seriesBySlug[it] = item }
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

        val enrichedSeriesLatest = snap.seriesLatest.map { feed ->
            val parent = feed.series_slug?.let { seriesBySlug[it] }
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

        return snap.copy(
            anime = enrichedAnime,
            animeLatest = enrichedLatest,
            seriesLatest = enrichedSeriesLatest,
        )
    }

    private fun readList(fileName: String, lightweight: Boolean = false): List<CatalogItem> {
        val cached = File(cacheDir, fileName)
        val fromCache = if (cached.exists() && cached.length() > 2) {
            parseListFile(cached, lightweight)
        } else {
            emptyList()
        }
        if (fromCache.isNotEmpty()) return fromCache

        return runCatching {
            context.assets.open("data/$fileName").use { input ->
                parseListStream(input, lightweight)
            }
        }.getOrDefault(emptyList())
    }

    private fun parseListFile(file: File, lightweight: Boolean): List<CatalogItem> =
        runCatching {
            file.inputStream().use { parseListStream(it, lightweight) }
        }.getOrDefault(emptyList())

    private fun parseListStream(input: java.io.InputStream, lightweight: Boolean): List<CatalogItem> {
        val source = input.source().buffer()
        return source.use {
            if (lightweight) {
                shellListAdapter.fromJson(it).orEmpty().map { shell ->
                    normalizeCatalogUrls(shell.toCatalogItem())
                }
            } else {
                listAdapter.fromJson(it).orEmpty().map { normalizeCatalogUrls(it) }
            }
        }
    }

    /** Baca satu judul penuh (termasuk episodes/players) dari file besar tanpa load semua. */
    private fun hydrateFromFile(fileName: String, slug: String): CatalogItem? {
        val key = slug.trim()
        if (key.isBlank()) return null
        val cached = File(cacheDir, fileName)
        if (cached.exists() && cached.length() > 2) {
            findItemInFile(cached, key)?.let { return it }
        }
        return runCatching {
            context.assets.open("data/$fileName").use { input ->
                findItemInStream(input, key)
            }
        }.getOrNull()
    }

    private fun findItemInFile(file: File, slug: String): CatalogItem? =
        runCatching {
            file.inputStream().use { findItemInStream(it, slug) }
        }.getOrNull()

    private fun findItemInStream(input: java.io.InputStream, slug: String): CatalogItem? {
        val source = input.source().buffer()
        source.use {
            val reader = com.squareup.moshi.JsonReader.of(it)
            reader.beginArray()
            while (reader.hasNext()) {
                val item = itemAdapter.fromJson(reader) ?: continue
                if (item.slug.equals(slug, ignoreCase = true) ||
                    item.anime_slug.equals(slug, ignoreCase = true) ||
                    item.series_slug.equals(slug, ignoreCase = true)
                ) {
                    return normalizeCatalogUrls(item)
                }
            }
            reader.endArray()
        }
        return null
    }

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
            // Anoboy: domain lama mati/timeout → quest
            .replace(
                Regex("""(?i)https?://(?:www\.)?anoboy\.xyz"""),
                "https://anoboy.quest",
            )
    }

    private val playerHostAliases = listOf(
        "playeriframe.sbs" to "videonode.de",
    )

    private fun rewritePlayerHost(url: String?): String? {
        if (url.isNullOrBlank()) return url
        var out: String = url
        for ((from, to) in playerHostAliases) {
            if (from.isBlank() || to.isBlank() || from.equals(to, ignoreCase = true)) continue
            out = out.replace(
                Regex("""(?i)https?://${Regex.escape(from)}"""),
                "https://$to",
            )
        }
        return out
    }

    private fun downloadAndCache(fileName: String, cacheBust: Boolean = false) {
        val bust = if (cacheBust) "?t=${System.currentTimeMillis()}" else ""
        val urls = buildList {
            add("$GITHUB_RAW_BASE$fileName$bust")
            if (cacheBust) add("$GITHUB_JSDELIVR_BASE$fileName$bust")
        }
        var lastError: Throwable? = null
        for (url in urls) {
            val result = runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "WEBUNIME-TV/1.0")
                    .apply {
                        if (cacheBust) {
                            header("Cache-Control", "no-cache, no-store, must-revalidate")
                            header("Pragma", "no-cache")
                        }
                    }
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code} for $fileName")
                    val body = response.body ?: error("Empty body $fileName")
                    val tmp = File(cacheDir, "$fileName.part")
                    tmp.outputStream().use { out ->
                        body.byteStream().use { input -> input.copyTo(out) }
                    }
                    if (tmp.length() < 2L) {
                        tmp.delete()
                        error("Empty body $fileName")
                    }
                    // Validasi root JSON array tanpa load seluruh file ke RAM.
                    tmp.inputStream().buffered().use { raw ->
                        var c = raw.read()
                        while (c != -1 && c.toChar().isWhitespace()) {
                            c = raw.read()
                        }
                        if (c.toChar() != '[') {
                            tmp.delete()
                            error("Invalid JSON root $fileName")
                        }
                    }
                    val dest = File(cacheDir, fileName)
                    if (dest.exists()) dest.delete()
                    if (!tmp.renameTo(dest)) {
                        tmp.copyTo(dest, overwrite = true)
                        tmp.delete()
                    }
                }
            }
            if (result.isSuccess) return
            lastError = result.exceptionOrNull()
        }
        throw lastError ?: error("Download failed $fileName")
    }

    private fun rebuildHero(limit: Int = HERO_LIMIT) {
        val cap = limit.coerceIn(1, HERO_LIMIT)
        val pool = (snapshot.movies + snapshot.series + snapshot.indonesia + snapshot.horror)
            .asSequence()
            .filter { ratingValue(it) > FEATURED_MIN_RATING }
            .filter { !it.thumbnail.isNullOrBlank() || !it.thumbnail_landscape.isNullOrBlank() }
            .distinctBy { it.slug?.takeIf { s -> s.isNotBlank() } ?: it.displayTitle() }
            .toList()
        val source = if (pool.size > cap) pool else pool
        heroItems = source.shuffled().take(cap).map {
            remember(it, it.catalog ?: it.detailCollection())
        }
    }

    private fun remember(item: CatalogItem, collection: String): CatalogItem {
        val normalized = item.copy(catalog = item.catalog ?: collection)
        fun put(key: String, value: CatalogItem) {
            val prev = itemCache[key]
            // Jangan timpa entri hydrated (punya episodes) dengan feed ringan.
            if (prev != null && prev.isHydrated() && !value.isHydrated()) return
            // Prefer katalog yang punya lebih banyak episode (hindari cache stale 4 eps vs feed E12).
            val prevEps = prev?.episodes?.size ?: 0
            val nextEps = value.episodes?.size ?: 0
            if (prev != null && prev.isHydrated() && value.isHydrated() && nextEps < prevEps) return
            itemCache[key] = value
        }
        val slug = normalized.slug?.lowercase()
        if (!slug.isNullOrBlank()) put("$collection:$slug", normalized)
        normalized.anime_slug?.lowercase()?.takeIf { it.isNotBlank() }?.let {
            put("anime:$it", normalized)
        }
        normalized.series_slug?.lowercase()?.takeIf { it.isNotBlank() }?.let {
            put("series:$it", normalized)
        }
        return normalized
    }

    private fun sectionFor(collection: String): CatalogSection? {
        val key = collection.trim().lowercase()
        return CatalogSection.entries.firstOrNull { it.apiName == key }
    }

    private fun itemsFor(section: CatalogSection): List<CatalogItem> =
        when (section) {
            CatalogSection.MOVIES -> snapshot.movies
            CatalogSection.INDONESIA -> snapshot.indonesia
            CatalogSection.HORROR -> snapshot.horror
            CatalogSection.MARVEL -> snapshot.marvel
            CatalogSection.SERIES_LATEST -> snapshot.seriesLatest
            CatalogSection.SERIES -> snapshot.series
            CatalogSection.ANIME_LATEST -> snapshot.animeLatest
            CatalogSection.ANIME -> snapshot.anime
            CatalogSection.ANIME_MOVIES -> snapshot.animeMovies
        }

    private fun ratingValue(item: CatalogItem): Double {
        val raw = item.rating?.trim()?.replace(',', '.') ?: return 0.0
        if (raw.none { it.isDigit() }) return 0.0
        return raw.toDoubleOrNull() ?: 0.0
    }

    private fun hotScore(item: CatalogItem): Double {
        val eps = item.episodes_count?.toDouble() ?: item.episodes?.size?.toDouble() ?: 0.0
        return ratingValue(item) * 1000.0 + eps
    }

    companion object {
        const val GITHUB_RAW_BASE =
            "https://raw.githubusercontent.com/gitgitmiko/WEBUNIME/main/public/data/"

        const val GITHUB_JSDELIVR_BASE =
            "https://cdn.jsdelivr.net/gh/gitgitmiko/WEBUNIME@main/public/data/"

        const val SYNC_STATUS_FILE = "sync-status.json"

        private const val PREFS_NAME = "catalog_sync"
        private const val KEY_LAST_SYNC_DAY = "last_github_sync_day"
        private const val KEY_RELOAD_BROWSE = "reload_browse_after_sync"

        const val PAGE_LIMIT = 12
        const val HERO_LIMIT = 10
        private const val FEATURED_MIN_RATING = 7.0
        private const val TOP_RANDOM_MIN_RATING = 8.0
        private const val TOP_RANDOM_LIMIT = 10

        private val CATALOG_FILES = listOf(
            "movies.json",
            "series-index.json",
            "series-latest.json",
            "horror.json",
            "marvel.json",
            "indonesia.json",
            "anime-index.json",
            "anime-movies.json",
            "anime-latest.json",
        )
    }
}
