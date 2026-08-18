package com.webunime.tv.data

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.webunime.tv.data.api.ApiClient
import com.webunime.tv.data.api.ApiConfig
import com.webunime.tv.data.api.CatalogPage
import com.webunime.tv.data.api.UnauthorizedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class CatalogRepository(
    private val context: Context,
    private val api: ApiClient,
) {
    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val itemAdapter = moshi.adapter(CatalogItem::class.java)

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val refreshMutex = Mutex()
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
            snapshot.indonesia.isNotEmpty()

    fun isSectionLoaded(section: CatalogSection): Boolean =
        when (section) {
            CatalogSection.MOVIES -> snapshot.movies.isNotEmpty()
            CatalogSection.INDONESIA -> snapshot.indonesia.isNotEmpty()
            CatalogSection.HORROR -> snapshot.horror.isNotEmpty()
            CatalogSection.SERIES_LATEST -> snapshot.seriesLatest.isNotEmpty()
            CatalogSection.SERIES -> snapshot.series.isNotEmpty()
            CatalogSection.ANIME_LATEST -> snapshot.animeLatest.isNotEmpty()
            CatalogSection.ANIME -> snapshot.anime.isNotEmpty()
            CatalogSection.ANIME_MOVIES -> snapshot.animeMovies.isNotEmpty()
        }

    suspend fun loadHome(): CatalogSnapshot = refreshMutex.withLock {
        loadHomeUnlocked()
    }

    private suspend fun loadHomeUnlocked(): CatalogSnapshot = withContext(Dispatchers.IO) {
        api.get("/api/v1")
        heroItems = try {
            fetchHero(HERO_LIMIT).take(HERO_LIMIT)
        } catch (err: UnauthorizedException) {
            throw err
        } catch (_: Exception) {
            emptyList()
        }
        homeLoaded = true
        snapshot
    }

    suspend fun loadStartupShell(): CatalogSnapshot = loadHome()

    suspend fun ensureSection(section: CatalogSection): CatalogSnapshot {
        if (isSectionLoaded(section)) return snapshot
        val page = listCollectionPage(section.apiName, page = 1, limit = 40)
        mergeSection(section, page.items)
        return snapshot
    }

    suspend fun ensureSections(sections: Collection<CatalogSection>): CatalogSnapshot {
        for (section in sections) ensureSection(section)
        return snapshot
    }

    suspend fun ensureAllSections(): CatalogSnapshot =
        ensureSections(CatalogSection.ALL)

    suspend fun listCollectionPage(
        collection: String,
        page: Int = 1,
        limit: Int = PAGE_LIMIT,
        q: String = "",
        genre: String = "",
        sort: String = "",
    ): CatalogPage = withContext(Dispatchers.IO) {
        val params = LinkedHashMap<String, String>()
        params["page"] = page.coerceAtLeast(1).toString()
        params["limit"] = limit.coerceIn(1, 80).toString()
        if (q.isNotBlank()) params["q"] = q
        if (genre.isNotBlank()) params["genre"] = genre
        if (sort.isNotBlank()) params["sort"] = sort
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        val raw = api.get("/api/v1/catalog/${enc(collection)}?$query")
        val obj = JSONObject(raw)
        val items = parseItemArray(obj.optJSONArray("items")).map {
            remember(it.copy(catalog = it.catalog ?: collection), collection)
        }
        CatalogPage(
            collection = obj.optString("collection").ifBlank { collection },
            page = obj.optInt("page", page),
            limit = obj.optInt("limit", limit),
            total = obj.optInt("total", items.size),
            items = items,
        )
    }

    suspend fun fetchHero(limit: Int = HERO_LIMIT): List<CatalogItem> = withContext(Dispatchers.IO) {
        val cap = limit.coerceIn(1, HERO_LIMIT)
        val raw = api.get("/api/v1/hero?limit=$cap")
        val parsed = parseItemArray(JSONObject(raw).optJSONArray("items"))
            .map { remember(it, it.detailCollection()) }
        takeHeroItems(parsed, cap)
    }

    suspend fun search(query: String, limit: Int = 40): List<CatalogItem> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()
        val raw = api.get(
            "/api/v1/search?q=${java.net.URLEncoder.encode(q, "UTF-8")}&limit=${limit.coerceIn(1, 80)}",
        )
        parseItemArray(JSONObject(raw).optJSONArray("items")).map {
            remember(it, it.catalog ?: it.detailCollection())
        }
    }

    suspend fun getItem(collection: String, slug: String): CatalogItem? = withContext(Dispatchers.IO) {
        val col = collection.trim()
        val s = slug.trim()
        if (col.isBlank() || s.isBlank()) return@withContext null
        val cached = itemCache["$col:${s.lowercase()}"]
        if (cached != null && cached.isHydrated()) return@withContext cached
        val raw = api.get("/api/v1/catalog/${enc(col)}/${enc(s)}")
        val item = parseItem(raw)?.copy(catalog = col) ?: return@withContext cached
        remember(item, col)
    }

    suspend fun findBySlugEnsured(
        slug: String,
        collectionHint: String? = null,
    ): CatalogItem? {
        if (slug.isBlank()) return null
        val hint = collectionHint?.takeIf { it.isNotBlank() }
        if (hint != null) {
            runCatching { getItem(hint, slug) }.getOrNull()?.let { return it }
            if (hint == "anime-latest") {
                runCatching { getItem("anime", slug) }.getOrNull()?.let { return it }
            }
            if (hint == "series-latest") {
                runCatching { getItem("series", slug) }.getOrNull()?.let { return it }
            }
        }
        snapshot.findBySlug(slug)?.let { cached ->
            if (cached.isHydrated()) return cached
            return runCatching { getItem(cached.detailCollection(), cached.detailSlug()) }.getOrNull()
                ?: cached
        }
        val order = listOf(
            "movies", "indonesia", "horror", "series", "anime", "anime-movies",
        )
        for (col in order) {
            val found = runCatching { getItem(col, slug) }.getOrNull()
            if (found != null) return found
        }
        return snapshot.findBySlug(slug)
    }

    suspend fun fetchDoc(name: String): JSONObject? = withContext(Dispatchers.IO) {
        if (name !in ApiConfig.DOC_NAMES) return@withContext null
        val raw = api.get("/api/v1/docs/${enc(name)}")
        runCatching { JSONObject(raw) }.getOrNull()
    }

    suspend fun fetchSyncStatus(): CatalogSyncStatus? = withContext(Dispatchers.IO) {
        runCatching {
            val doc = fetchDoc("sync-status") ?: return@runCatching null
            CatalogSyncStatus(
                state = doc.optString("state").takeIf { it.isNotBlank() },
                startedAt = doc.optString("startedAt").takeIf { it.isNotBlank() && it != "null" },
                finishedAt = doc.optString("finishedAt").takeIf { it.isNotBlank() && it != "null" },
                runId = if (doc.has("runId") && !doc.isNull("runId")) doc.optLong("runId") else null,
                message = doc.optString("message").takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }

    suspend fun forceRefreshFromApi(): Int = refreshMutex.withLock {
        itemCache.clear()
        snapshot = CatalogSnapshot()
        heroItems = emptyList()
        homeLoaded = false
        loadHomeUnlocked()
        prefs.edit().putBoolean(KEY_RELOAD_BROWSE, true).apply()
        if (heroItems.isNotEmpty() || homeLoaded) 1 else 0
    }

    /** @deprecated alias Settings/Main lama */
    suspend fun forceRefreshFromGithub(): Int = forceRefreshFromApi()

    suspend fun refreshFromGithubOnce(): Int = forceRefreshFromApi()

    fun consumeBrowseReloadRequest(): Boolean {
        if (!prefs.getBoolean(KEY_RELOAD_BROWSE, false)) return false
        prefs.edit().putBoolean(KEY_RELOAD_BROWSE, false).apply()
        return true
    }

    fun cachedItem(collection: String, slug: String): CatalogItem? =
        itemCache["$collection:${slug.lowercase()}"]

    private fun mergeSection(section: CatalogSection, items: List<CatalogItem>) {
        snapshot = when (section) {
            CatalogSection.MOVIES -> snapshot.copy(movies = mergeList(snapshot.movies, items))
            CatalogSection.INDONESIA -> snapshot.copy(indonesia = mergeList(snapshot.indonesia, items))
            CatalogSection.HORROR -> snapshot.copy(horror = mergeList(snapshot.horror, items))
            CatalogSection.SERIES_LATEST -> snapshot.copy(seriesLatest = mergeList(snapshot.seriesLatest, items))
            CatalogSection.SERIES -> snapshot.copy(series = mergeList(snapshot.series, items))
            CatalogSection.ANIME_LATEST -> snapshot.copy(animeLatest = mergeList(snapshot.animeLatest, items))
            CatalogSection.ANIME -> snapshot.copy(anime = mergeList(snapshot.anime, items))
            CatalogSection.ANIME_MOVIES -> snapshot.copy(animeMovies = mergeList(snapshot.animeMovies, items))
        }
    }

    private fun mergeList(current: List<CatalogItem>, incoming: List<CatalogItem>): List<CatalogItem> {
        if (current.isEmpty()) return incoming
        val seen = current.mapNotNull { it.slug ?: it.anime_slug }.toMutableSet()
        return current + incoming.filter { item ->
            val key = item.slug ?: item.anime_slug ?: return@filter true
            seen.add(key)
        }
    }

    private fun remember(item: CatalogItem, collection: String): CatalogItem {
        val normalized = normalizeCatalogUrls(item).let { it.copy(catalog = it.catalog ?: collection) }
        val slug = normalized.slug?.lowercase()
        if (!slug.isNullOrBlank()) itemCache["$collection:$slug"] = normalized
        normalized.anime_slug?.lowercase()?.takeIf { it.isNotBlank() }?.let {
            itemCache["anime:$it"] = normalized
        }
        normalized.series_slug?.lowercase()?.takeIf { it.isNotBlank() }?.let {
            itemCache["series:$it"] = normalized
        }
        return normalized
    }

    private fun parseItemArray(arr: JSONArray?): List<CatalogItem> =
        CatalogJson.parseItemList(arr)

    private fun parseItem(raw: String): CatalogItem? =
        CatalogJson.parseItem(raw)
            ?: runCatching { itemAdapter.fromJson(raw) }.getOrNull()

    private fun enc(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

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

    private fun takeHeroItems(items: List<CatalogItem>, limit: Int): List<CatalogItem> {
        val cap = limit.coerceIn(1, HERO_LIMIT)
        val seen = LinkedHashSet<String>()
        return items.filter { item ->
            val key = item.slug?.lowercase()?.takeIf { it.isNotBlank() } ?: item.displayTitle()
            seen.add(key)
        }.take(cap)
    }

    companion object {
        private const val PREFS_NAME = "catalog_sync"
        private const val KEY_RELOAD_BROWSE = "reload_browse_after_sync"
        const val PAGE_LIMIT = 12
        const val HERO_LIMIT = 10
    }
}
