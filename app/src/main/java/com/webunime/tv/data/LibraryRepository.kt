package com.webunime.tv.data

import com.webunime.tv.data.api.ApiClient
import com.webunime.tv.data.api.LibraryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class LibraryRepository(
    private val api: ApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val historyDirty = AtomicBoolean(false)

    @Volatile
    var favorites: List<LibraryEntry> = emptyList()
        private set

    @Volatile
    var history: List<LibraryEntry> = emptyList()
        private set

    suspend fun refresh(): Unit = withContext(Dispatchers.IO) {
        favorites = runCatching { fetchFavorites() }.getOrDefault(favorites)
        history = runCatching { fetchHistory() }.getOrDefault(history)
    }

    suspend fun fetchFavorites(): List<LibraryEntry> = withContext(Dispatchers.IO) {
        parseItems(JSONObject(api.get("/api/v1/me/favorites"))).also { favorites = it }
    }

    suspend fun fetchHistory(): List<LibraryEntry> = withContext(Dispatchers.IO) {
        parseItems(JSONObject(api.get("/api/v1/me/history"))).also { history = it }
    }

    suspend fun isFavorite(collection: String, slug: String): Boolean = withContext(Dispatchers.IO) {
        val col = collection.trim()
        val s = slug.trim().lowercase()
        if (col.isBlank() || s.isBlank()) return@withContext false
        val cached = favorites.any { it.collection == col && it.slug.equals(s, true) }
        if (cached) return@withContext true
        val body = JSONObject(api.get("/api/v1/me/favorites/check?collection=$col&slug=$s"))
        body.optBoolean("favorite", false)
    }

    suspend fun addFavorite(
        collection: String,
        slug: String,
        title: String?,
        thumbnail: String?,
    ) = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("collection", collection)
            .put("slug", slug.lowercase())
            .put("title", title)
            .put("thumbnail", thumbnail)
        api.post("/api/v1/me/favorites", payload)
        val entry = LibraryEntry(collection, slug.lowercase(), title, thumbnail)
        favorites = listOf(entry) + favorites.filterNot {
            it.collection == collection && it.slug.equals(slug, true)
        }
    }

    suspend fun removeFavorite(collection: String, slug: String) = withContext(Dispatchers.IO) {
        val col = collection.trim()
        val s = slug.trim().lowercase()
        api.delete("/api/v1/me/favorites/$col/$s")
        favorites = favorites.filterNot { it.collection == col && it.slug.equals(s, true) }
    }

    suspend fun upsertHistory(
        collection: String,
        slug: String,
        title: String?,
        thumbnail: String?,
        episodeSlug: String?,
        progressSeconds: Long,
    ) = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("collection", collection)
            .put("slug", slug.lowercase())
            .put("title", title)
            .put("thumbnail", thumbnail)
            .put("episodeSlug", episodeSlug)
            .put("progressSeconds", progressSeconds.coerceAtLeast(0L))
        api.post("/api/v1/me/history", payload)
        val entry = LibraryEntry(
            collection = collection,
            slug = slug.lowercase(),
            title = title,
            thumbnail = thumbnail,
            episodeSlug = episodeSlug,
            progressSeconds = progressSeconds.coerceAtLeast(0L),
        )
        history = listOf(entry) + history.filterNot {
            it.collection == collection && it.slug.equals(slug, true)
        }
    }

    suspend fun removeHistory(collection: String, slug: String) = withContext(Dispatchers.IO) {
        val col = collection.trim()
        val s = slug.trim().lowercase()
        api.delete("/api/v1/me/history/$col/$s")
        history = history.filterNot { it.collection == col && it.slug.equals(s, true) }
    }

    fun clear() {
        favorites = emptyList()
        history = emptyList()
    }

    /**
     * Tulis riwayat ke API dengan jeda, agar player tidak spam STB.
     */
    fun scheduleHistoryUpsert(
        collection: String?,
        slug: String,
        title: String?,
        thumbnail: String?,
        episodeSlug: String?,
        progressSeconds: Long,
        flushNow: Boolean = false,
    ) {
        val col = collection?.takeIf { it.isNotBlank() } ?: return
        if (slug.isBlank() || progressSeconds < 15) return
        historyDirty.set(true)
        scope.launch {
            if (!flushNow) delay(4_000)
            if (!historyDirty.compareAndSet(true, false) && !flushNow) return@launch
            runCatching {
                upsertHistory(col, slug, title, thumbnail, episodeSlug, progressSeconds)
            }
        }
    }

    private fun parseItems(root: JSONObject): List<LibraryEntry> {
        val arr = root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val collection = o.optString("collection")
                val slug = o.optString("slug")
                if (collection.isBlank() || slug.isBlank()) continue
                add(
                    LibraryEntry(
                        collection = collection,
                        slug = slug,
                        title = o.optString("title").takeIf { it.isNotBlank() },
                        thumbnail = o.optString("thumbnail").takeIf { it.isNotBlank() },
                        episodeSlug = o.optString("episodeSlug").takeIf { it.isNotBlank() },
                        progressSeconds = o.optLong("progressSeconds"),
                        lastWatchedAt = o.optString("lastWatchedAt").takeIf { it.isNotBlank() },
                        createdAt = o.optString("createdAt").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }
}
