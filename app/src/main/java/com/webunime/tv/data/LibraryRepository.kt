package com.webunime.tv.data

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.webunime.tv.data.api.LibraryEntry
import com.webunime.tv.data.api.WatchedEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Favorit + riwayat tonton disimpan di SharedPreferences (cache lokal device).
 * Tidak membutuhkan login / API akun.
 */
class LibraryRepository(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val entryListType = Types.newParameterizedType(List::class.java, LibraryEntry::class.java)
    private val entryAdapter = moshi.adapter<List<LibraryEntry>>(entryListType)
    private val watchedListType = Types.newParameterizedType(List::class.java, WatchedEpisode::class.java)
    private val watchedAdapter = moshi.adapter<List<WatchedEpisode>>(watchedListType)

    private val watchedByTitle = ConcurrentHashMap<String, List<WatchedEpisode>>()

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
            val list = loadWatched(key)
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
    }

    suspend fun removeFavorite(collection: String, slug: String) = withContext(Dispatchers.IO) {
        val col = collection.trim()
        val s = slug.trim().lowercase()
        favorites = favorites.filterNot { it.collection == col && it.slug.equals(s, true) }
        persistFavorites()
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
    }

    suspend fun removeHistory(collection: String, slug: String) = withContext(Dispatchers.IO) {
        val col = collection.trim()
        val s = slug.trim().lowercase()
        history = history.filterNot { it.collection == col && it.slug.equals(s, true) }
        persistHistory()
        val key = watchedKey(col, s)
        watchedByTitle.remove(key)
        prefs.edit().remove(watchedPrefsKey(key)).apply()
    }

    fun clear() {
        favorites = emptyList()
        history = emptyList()
        watchedByTitle.clear()
        prefs.edit().clear().apply()
    }

    /**
     * Tulis riwayat lokal (sinkron cepat untuk UI; persist ke disk).
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
        // flushNow dibiarkan untuk kompatibilitas pemanggil lama
        @Suppress("UNUSED_EXPRESSION")
        flushNow
    }

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
        private const val PREFS = "library_local"
        private const val KEY_FAVORITES = "favorites_v1"
        private const val KEY_HISTORY = "history_v1"
        private const val KEY_WATCHED_PREFIX = "watched_v1:"
        private const val MAX_FAVORITES = 200
        private const val MAX_HISTORY = 100
    }
}
