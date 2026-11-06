package com.webunime.tv.data

import android.content.Context
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.webunime.tv.data.api.ApiClient
import com.webunime.tv.data.api.ApiConfig
import com.webunime.tv.data.api.LibraryEntry
import com.webunime.tv.data.api.WatchedEpisode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Favorit + riwayat tonton. Selalu disimpan di SharedPreferences (cache device);
 * saat login, server (`/api/v1/me/...`) jadi sumber utama dan perubahan ikut dikirim
 * supaya sama dengan web.
 */
class LibraryRepository(
    context: Context,
    private val api: ApiClient,
    private val auth: AuthRepository,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val entryListType = Types.newParameterizedType(List::class.java, LibraryEntry::class.java)
    private val entryAdapter = moshi.adapter<List<LibraryEntry>>(entryListType)
    private val watchedListType = Types.newParameterizedType(List::class.java, WatchedEpisode::class.java)
    private val watchedAdapter = moshi.adapter<List<WatchedEpisode>>(watchedListType)

    private val watchedByTitle = ConcurrentHashMap<String, List<WatchedEpisode>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Kirim riwayat ke server per judul, di-debounce agar player tidak spam. */
    private val pendingHistory = ConcurrentHashMap<String, Job>()

    @Volatile
    var favorites: List<LibraryEntry> = emptyList()
        private set

    @Volatile
    var history: List<LibraryEntry> = emptyList()
        private set

    init {
        favorites = loadEntries(KEY_FAVORITES)
        history = loadEntries(KEY_HISTORY)
        loadAllWatchedIntoMemory()
    }

    suspend fun refresh(): Unit = withContext(Dispatchers.IO) {
        favorites = loadEntries(KEY_FAVORITES)
        history = loadEntries(KEY_HISTORY)
        loadAllWatchedIntoMemory()
        if (!auth.isLoggedIn()) return@withContext
        // Riwayat yang masih antre dikirim dulu supaya tidak tertimpa data server lama.
        withTimeoutOrNull(2_500) { pendingHistory.values.toList().forEach { it.join() } }
        // Entri koleksi yang tidak dikenal server tetap disimpan di device.
        runCatching { parseItems(JSONObject(api.get("/api/v1/me/favorites"))) }
            .onSuccess { remote ->
                favorites = remote + favorites.filterNot { isSyncable(it.collection, it.slug) }
                persistFavorites()
            }
            .onFailure { Log.w(TAG, "favorit server gagal: ${it.message}") }
        runCatching { parseItems(JSONObject(api.get("/api/v1/me/history"))) }
            .onSuccess { remote ->
                // Server menyimpan baris per episode; Lanjutkan cukup satu per judul (terbaru).
                history = (remote + history.filterNot { isSyncable(it.collection, it.slug) })
                    .sortedByDescending { it.lastWatchedAt.orEmpty() }
                    .distinctBy { "${it.collection}:${it.slug.lowercase()}" }
                    .take(MAX_HISTORY)
                persistHistory()
            }
            .onFailure { Log.w(TAG, "riwayat server gagal: ${it.message}") }
    }

    /**
     * Setelah login: kirim favorit/riwayat device yang belum ada di akun,
     * lalu tarik versi server.
     */
    suspend fun mergeLocalIntoAccount(): Unit = withContext(Dispatchers.IO) {
        if (!auth.isLoggedIn()) return@withContext
        val localFavorites = loadEntries(KEY_FAVORITES)
        val localHistory = loadEntries(KEY_HISTORY)
        val serverFavorites = runCatching {
            parseItems(JSONObject(api.get("/api/v1/me/favorites")))
        }.getOrNull() ?: return@withContext
        val serverHistory = runCatching {
            parseItems(JSONObject(api.get("/api/v1/me/history")))
        }.getOrNull() ?: return@withContext
        fun List<LibraryEntry>.has(e: LibraryEntry) =
            any { it.collection == e.collection && it.slug.equals(e.slug, true) }
        // Terlama dulu → urutan "terbaru" di server tetap sama dengan device.
        localFavorites.filter { isSyncable(it.collection, it.slug) && !serverFavorites.has(it) }
            .take(MERGE_LIMIT).asReversed()
            .forEach { e ->
                runCatching {
                    api.post("/api/v1/me/favorites", libraryBody(e.collection, e.slug, e.title, e.thumbnail))
                }
            }
        localHistory.filter { isSyncable(it.collection, it.slug) && !serverHistory.has(it) }
            .take(MERGE_LIMIT).asReversed()
            .forEach { e ->
                runCatching {
                    api.post(
                        "/api/v1/me/history",
                        libraryBody(
                            e.collection, e.slug, e.title, e.thumbnail,
                            e.episodeSlug, e.episodeNum, e.progressSeconds,
                        ),
                    )
                }
            }
        refresh()
    }

    suspend fun fetchFavorites(): List<LibraryEntry> = withContext(Dispatchers.IO) {
        loadEntries(KEY_FAVORITES).also { favorites = it }
    }

    suspend fun fetchHistory(): List<LibraryEntry> = withContext(Dispatchers.IO) {
        loadEntries(KEY_HISTORY).also { history = it }
    }

    suspend fun fetchWatchedEpisodes(collection: String, slug: String): List<WatchedEpisode> =
        withContext(Dispatchers.IO) {
            val col = collection.trim()
            val s = slug.trim().lowercase()
            if (col.isBlank() || s.isBlank()) return@withContext emptyList()
            val key = watchedKey(col, s)
            var list = loadWatched(key)
            if (isSyncable(col, s)) {
                runCatching {
                    parseWatchedEpisodes(
                        JSONObject(api.get("/api/v1/me/history/${enc(col)}/${enc(s)}/episodes")),
                    )
                }.onSuccess { remote ->
                    val extra = list.filterNot { local ->
                        remote.any { it.matches(local.episodeSlug, local.episodeNum) }
                    }
                    list = remote + extra
                    persistWatched(key, list)
                }
            }
            watchedByTitle[key] = list
            list
        }

    fun cachedWatchedEpisodes(collection: String, slug: String): List<WatchedEpisode> =
        watchedByTitle[watchedKey(collection, slug)].orEmpty()

    fun isEpisodeWatched(
        collection: String,
        slug: String,
        episodeSlug: String?,
        episodeNum: Int?,
    ): Boolean = cachedWatchedEpisodes(collection, slug).any { it.matches(episodeSlug, episodeNum) }

    suspend fun isFavorite(collection: String, slug: String): Boolean = withContext(Dispatchers.IO) {
        val col = collection.trim()
        val s = slug.trim().lowercase()
        if (col.isBlank() || s.isBlank()) return@withContext false
        favorites.any { it.collection == col && it.slug.equals(s, true) }
    }

    suspend fun addFavorite(
        collection: String,
        slug: String,
        title: String?,
        thumbnail: String?,
    ) = withContext(Dispatchers.IO) {
        val entry = LibraryEntry(
            collection = collection,
            slug = slug.lowercase(),
            title = title,
            thumbnail = thumbnail,
            createdAt = nowIso(),
        )
        favorites = listOf(entry) + favorites.filterNot {
            it.collection == collection && it.slug.equals(slug, true)
        }.take(MAX_FAVORITES - 1)
        persistFavorites()
        if (isSyncable(collection, slug)) {
            remote("favorit +") {
                api.post("/api/v1/me/favorites", libraryBody(collection, slug, title, thumbnail))
            }
        }
    }

    suspend fun removeFavorite(collection: String, slug: String) = withContext(Dispatchers.IO) {
        val col = collection.trim()
        val s = slug.trim().lowercase()
        favorites = favorites.filterNot { it.collection == col && it.slug.equals(s, true) }
        persistFavorites()
        if (isSyncable(col, s)) {
            remote("favorit -") { api.delete("/api/v1/me/favorites/${enc(col)}/${enc(s)}") }
        }
    }

    suspend fun upsertHistory(
        collection: String,
        slug: String,
        title: String?,
        thumbnail: String?,
        episodeSlug: String?,
        episodeNum: Int?,
        progressSeconds: Long,
    ) = withContext(Dispatchers.IO) {
        applyHistoryLocal(
            collection = collection,
            slug = slug,
            title = title,
            thumbnail = thumbnail,
            episodeSlug = episodeSlug,
            episodeNum = episodeNum,
            progressSeconds = progressSeconds,
        )
        queueHistorySync(collection, slug, title, thumbnail, episodeSlug, episodeNum, progressSeconds, true)
    }

    suspend fun removeHistory(collection: String, slug: String) = withContext(Dispatchers.IO) {
        val col = collection.trim()
        val s = slug.trim().lowercase()
        history = history.filterNot { it.collection == col && it.slug.equals(s, true) }
        persistHistory()
        val key = watchedKey(col, s)
        watchedByTitle.remove(key)
        prefs.edit().remove(watchedPrefsKey(key)).apply()
        pendingHistory.remove(key)?.cancel()
        if (isSyncable(col, s)) {
            remote("riwayat -") { api.delete("/api/v1/me/history/${enc(col)}/${enc(s)}") }
        }
    }

    /** Logout: buang salinan library akun dari device. */
    fun clear() {
        pendingHistory.values.forEach { it.cancel() }
        pendingHistory.clear()
        favorites = emptyList()
        history = emptyList()
        watchedByTitle.clear()
        prefs.edit().clear().apply()
    }

    /**
     * Tulis riwayat lokal (sinkron cepat untuk UI; persist ke disk),
     * lalu kirim ke akun bila login.
     */
    fun scheduleHistoryUpsert(
        collection: String?,
        slug: String,
        title: String?,
        thumbnail: String?,
        episodeSlug: String?,
        episodeNum: Int?,
        progressSeconds: Long,
        flushNow: Boolean = false,
    ) {
        val col = collection?.takeIf { it.isNotBlank() } ?: return
        if (slug.isBlank() || progressSeconds < 5) return
        applyHistoryLocal(
            collection = col,
            slug = slug,
            title = title,
            thumbnail = thumbnail,
            episodeSlug = episodeSlug,
            episodeNum = episodeNum,
            progressSeconds = progressSeconds,
        )
        queueHistorySync(col, slug, title, thumbnail, episodeSlug, episodeNum, progressSeconds, flushNow)
    }

    private fun queueHistorySync(
        collection: String,
        slug: String,
        title: String?,
        thumbnail: String?,
        episodeSlug: String?,
        episodeNum: Int?,
        progressSeconds: Long,
        flushNow: Boolean,
    ) {
        if (!isSyncable(collection, slug)) return
        val key = watchedKey(collection, slug)
        val epNum = LibraryEntry.parseEpisodeNum(episodeNum, episodeSlug)
        val body = libraryBody(collection, slug, title, thumbnail, episodeSlug, epNum, progressSeconds)
        pendingHistory.remove(key)?.cancel()
        val job = scope.launch {
            if (!flushNow) delay(HISTORY_DEBOUNCE_MS)
            runCatching { api.post("/api/v1/me/history", body) }
                .onFailure { Log.w(TAG, "riwayat + gagal: ${it.message}") }
        }
        pendingHistory[key] = job
        job.invokeOnCompletion { pendingHistory.remove(key, job) }
    }

    /** Hanya koleksi API yang dikenal server yang dikirim ke akun. */
    private fun isSyncable(collection: String, slug: String): Boolean =
        auth.isLoggedIn() &&
            collection.trim().lowercase() in ApiConfig.ITEM_COLLECTIONS &&
            slug.isNotBlank()

    private fun remote(label: String, block: () -> Unit) {
        scope.launch {
            runCatching(block).onFailure { Log.w(TAG, "$label gagal: ${it.message}") }
        }
    }

    private fun libraryBody(
        collection: String,
        slug: String,
        title: String?,
        thumbnail: String?,
        episodeSlug: String? = null,
        episodeNum: Int? = null,
        progressSeconds: Long? = null,
    ): JSONObject {
        val json = JSONObject()
            .put("collection", collection.trim().lowercase())
            .put("slug", slug.lowercase())
        if (!title.isNullOrBlank()) json.put("title", title)
        if (!thumbnail.isNullOrBlank()) json.put("thumbnail", thumbnail)
        if (!episodeSlug.isNullOrBlank()) json.put("episodeSlug", episodeSlug)
        if (episodeNum != null && episodeNum > 0) json.put("episodeNum", episodeNum)
        if (progressSeconds != null) json.put("progressSeconds", progressSeconds.coerceAtLeast(0L))
        return json
    }

    private fun parseItems(root: JSONObject): List<LibraryEntry> {
        val arr = root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val collection = o.optString("collection")
                val slug = o.optString("slug")
                if (collection.isBlank() || slug.isBlank()) continue
                val episodeSlug = o.optString("episodeSlug").takeIf { it.isNotBlank() }
                add(
                    LibraryEntry(
                        collection = collection,
                        slug = slug,
                        title = o.optString("title").takeIf { it.isNotBlank() },
                        thumbnail = o.optString("thumbnail").takeIf { it.isNotBlank() },
                        episodeSlug = episodeSlug,
                        episodeNum = LibraryEntry.parseEpisodeNum(optPositiveInt(o, "episodeNum"), episodeSlug),
                        progressSeconds = o.optLong("progressSeconds"),
                        lastWatchedAt = o.optString("lastWatchedAt").takeIf { it.isNotBlank() },
                        createdAt = o.optString("createdAt").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    private fun parseWatchedEpisodes(root: JSONObject): List<WatchedEpisode> {
        val arr = root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val episodeSlug = o.optString("episodeSlug").takeIf { it.isNotBlank() }
                val episodeNum = LibraryEntry.parseEpisodeNum(optPositiveInt(o, "episodeNum"), episodeSlug)
                if (episodeSlug.isNullOrBlank() && episodeNum == null) continue
                add(
                    WatchedEpisode(
                        episodeSlug = episodeSlug,
                        episodeNum = episodeNum,
                        watchedAt = o.optString("watchedAt").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    private fun optPositiveInt(o: JSONObject, key: String): Int? {
        if (!o.has(key) || o.isNull(key)) return null
        val n = o.optInt(key, 0)
        if (n > 0) return n
        return o.optString(key).toIntOrNull()?.takeIf { it > 0 }
    }

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun applyHistoryLocal(
        collection: String,
        slug: String,
        title: String?,
        thumbnail: String?,
        episodeSlug: String?,
        episodeNum: Int?,
        progressSeconds: Long,
    ) {
        val epNum = LibraryEntry.parseEpisodeNum(episodeNum, episodeSlug)
        val entry = LibraryEntry(
            collection = collection,
            slug = slug.lowercase(),
            title = title,
            thumbnail = thumbnail,
            episodeSlug = episodeSlug,
            episodeNum = epNum,
            progressSeconds = progressSeconds.coerceAtLeast(0L),
            lastWatchedAt = nowIso(),
        )
        history = listOf(entry) + history.filterNot {
            it.collection == collection && it.slug.equals(slug, true)
        }.take(MAX_HISTORY - 1)
        persistHistory()
        rememberWatchedEpisode(collection, slug, episodeSlug, epNum)
    }

    private fun rememberWatchedEpisode(
        collection: String,
        slug: String,
        episodeSlug: String?,
        episodeNum: Int?,
    ) {
        if (episodeSlug.isNullOrBlank() && (episodeNum == null || episodeNum <= 0)) return
        val key = watchedKey(collection, slug)
        val current = watchedByTitle[key].orEmpty().ifEmpty { loadWatched(key) }
        if (current.any { it.matches(episodeSlug, episodeNum) }) {
            watchedByTitle[key] = current
            return
        }
        val next = listOf(
            WatchedEpisode(
                episodeSlug = episodeSlug,
                episodeNum = episodeNum,
                watchedAt = nowIso(),
            ),
        ) + current
        watchedByTitle[key] = next
        persistWatched(key, next)
    }

    private fun loadEntries(key: String): List<LibraryEntry> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching { entryAdapter.fromJson(raw).orEmpty() }
            .getOrDefault(emptyList())
    }

    private fun persistFavorites() {
        prefs.edit().putString(KEY_FAVORITES, entryAdapter.toJson(favorites)).apply()
    }

    private fun persistHistory() {
        prefs.edit().putString(KEY_HISTORY, entryAdapter.toJson(history)).apply()
    }

    private fun loadWatched(key: String): List<WatchedEpisode> {
        val raw = prefs.getString(watchedPrefsKey(key), null) ?: return emptyList()
        return runCatching { watchedAdapter.fromJson(raw).orEmpty() }
            .getOrDefault(emptyList())
    }

    private fun persistWatched(key: String, list: List<WatchedEpisode>) {
        prefs.edit().putString(watchedPrefsKey(key), watchedAdapter.toJson(list)).apply()
    }

    private fun loadAllWatchedIntoMemory() {
        watchedByTitle.clear()
        for ((prefsKey, value) in prefs.all) {
            if (prefsKey !is String || !prefsKey.startsWith(KEY_WATCHED_PREFIX)) continue
            if (value !is String) continue
            val logical = prefsKey.removePrefix(KEY_WATCHED_PREFIX)
            val list = runCatching { watchedAdapter.fromJson(value).orEmpty() }
                .getOrDefault(emptyList())
            if (list.isNotEmpty()) watchedByTitle[logical] = list
        }
    }

    private fun watchedKey(collection: String, slug: String): String =
        "${collection.trim().lowercase()}\t${slug.trim().lowercase()}"

    private fun watchedPrefsKey(logicalKey: String): String =
        KEY_WATCHED_PREFIX + logicalKey

    private fun nowIso(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

    companion object {
        private const val TAG = "Library"
        private const val HISTORY_DEBOUNCE_MS = 1_500L
        /** Batas entri device yang diunggah saat pertama login. */
        private const val MERGE_LIMIT = 50
        private const val PREFS = "library_local"
        private const val KEY_FAVORITES = "favorites_v1"
        private const val KEY_HISTORY = "history_v1"
        private const val KEY_WATCHED_PREFIX = "watched_v1:"
        private const val MAX_FAVORITES = 200
        private const val MAX_HISTORY = 100
    }
}
