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
import java.util.concurrent.atomic.AtomicInteger

class LibraryRepository(
    private val api: ApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val historyDirty = AtomicBoolean(false)
    private val pendingHistoryWrites = AtomicInteger(0)

    @Volatile
    var favorites: List<LibraryEntry> = emptyList()
        private set

    @Volatile
    var history: List<LibraryEntry> = emptyList()
        private set

    suspend fun refresh(): Unit = withContext(Dispatchers.IO) {
        var waited = 0
        while (pendingHistoryWrites.get() > 0 && waited < 2_500) {
            delay(50)
            waited += 50
        }
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
        val qs = "collection=${enc(col)}&slug=${enc(s)}"
        val body = JSONObject(api.get("/api/v1/me/favorites/check?$qs"))
        body.optBoolean("favorite", false)
    }

    suspend fun addFavorite(
        collection: String,
        slug: String,
        title: String?,
        thumbnail: String?,
    ) = withContext(Dispatchers.IO) {
        api.post("/api/v1/me/favorites", libraryBody(collection, slug, title, thumbnail))
        val entry = LibraryEntry(collection, slug.lowercase(), title, thumbnail)
        favorites = listOf(entry) + favorites.filterNot {
            it.collection == collection && it.slug.equals(slug, true)
        }
    }

    suspend fun removeFavorite(collection: String, slug: String) = withContext(Dispatchers.IO) {
        val col = collection.trim()
        val s = slug.trim().lowercase()
        api.delete("/api/v1/me/favorites/${enc(col)}/${enc(s)}")
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
        api.post(
            "/api/v1/me/history",
            libraryBody(collection, slug, title, thumbnail, episodeSlug, progressSeconds),
        )
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
        api.delete("/api/v1/me/history/${enc(col)}/${enc(s)}")
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
        if (slug.isBlank() || progressSeconds < 5) return
        history = listOf(
            LibraryEntry(
                collection = col,
                slug = slug.lowercase(),
                title = title,
                thumbnail = thumbnail,
                episodeSlug = episodeSlug,
                progressSeconds = progressSeconds.coerceAtLeast(0L),
            ),
        ) + history.filterNot { it.collection == col && it.slug.equals(slug, true) }
        historyDirty.set(true)
        pendingHistoryWrites.incrementAndGet()
        scope.launch {
            try {
                if (!flushNow) delay(1_500)
                if (!flushNow && !historyDirty.compareAndSet(true, false)) return@launch
                historyDirty.set(false)
                runCatching {
                    upsertHistory(col, slug, title, thumbnail, episodeSlug, progressSeconds)
                }
            } finally {
                pendingHistoryWrites.decrementAndGet()
            }
        }
    }

    private fun libraryBody(
        collection: String,
        slug: String,
        title: String?,
        thumbnail: String?,
        episodeSlug: String? = null,
        progressSeconds: Long? = null,
    ): JSONObject {
        val json = JSONObject()
            .put("collection", collection)
            .put("slug", slug.lowercase())
        if (!title.isNullOrBlank()) json.put("title", title)
        if (!thumbnail.isNullOrBlank()) json.put("thumbnail", thumbnail)
        if (!episodeSlug.isNullOrBlank()) json.put("episodeSlug", episodeSlug)
        if (progressSeconds != null) json.put("progressSeconds", progressSeconds.coerceAtLeast(0L))
        return json
    }

    private fun enc(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

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
