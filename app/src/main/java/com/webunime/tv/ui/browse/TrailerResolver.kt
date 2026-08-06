package com.webunime.tv.ui.browse

import android.os.Handler
import android.os.Looper
import com.webunime.tv.BuildConfig
import com.webunime.tv.data.CatalogItem
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolve YouTube trailer key: field katalog → (opsional) TMDB on-demand.
 * Cache memori agar fokus ulang tidak hit API berulang.
 */
object TrailerResolver {
    private val main = Handler(Looper.getMainLooper())
    private val cache = ConcurrentHashMap<String, String>()
    private val miss = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    fun cachedOrField(item: CatalogItem): String? {
        item.trailer_youtube?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val id = cacheKey(item)
        cache[id]?.let { return it }
        if (miss.contains(id)) return null
        return null
    }

    /**
     * Jika field kosong dan [BuildConfig.TMDB_API_KEY] ada, resolve di background.
     * [onResult] dipanggil di main thread (null = gagal / tidak ada).
     */
    fun resolveAsync(item: CatalogItem, onResult: (String?) -> Unit) {
        val fromField = item.trailer_youtube?.trim()?.takeIf { it.isNotEmpty() }
        if (fromField != null) {
            cache[cacheKey(item)] = fromField
            onResult(fromField)
            return
        }
        val id = cacheKey(item)
        cache[id]?.let {
            onResult(it)
            return
        }
        if (miss.contains(id)) {
            onResult(null)
            return
        }
        val apiKey = BuildConfig.TMDB_API_KEY.trim()
        if (apiKey.isEmpty()) {
            onResult(null)
            return
        }
        if (!inFlight.add(id)) return

        val title = item.displayTitle()
        val year = item.tahun?.trim().orEmpty()
        val isTv = item.type.orEmpty().contains("series", ignoreCase = true) ||
            !item.episodes.isNullOrEmpty()

        fun fail() {
            inFlight.remove(id)
            miss.add(id)
            main.post { onResult(null) }
        }

        searchTmdb(apiKey, title, year, isTv) { mediaType, tmdbId ->
            if (tmdbId == null) {
                fail()
                return@searchTmdb
            }
            fetchVideos(apiKey, mediaType, tmdbId) { key ->
                inFlight.remove(id)
                if (key.isNullOrBlank()) {
                    miss.add(id)
                } else {
                    cache[id] = key
                }
                main.post { onResult(key) }
            }
        }
    }

    private fun cacheKey(item: CatalogItem): String =
        item.slug?.takeIf { it.isNotBlank() } ?: item.displayTitle()

    private fun searchTmdb(
        apiKey: String,
        title: String,
        year: String,
        preferTv: Boolean,
        done: (mediaType: String, id: Int?) -> Unit,
    ) {
        val kinds = if (preferTv) listOf("tv", "movie") else listOf("movie", "tv")
        fun tryKind(index: Int) {
            if (index >= kinds.size) {
                done("movie", null)
                return
            }
            val kind = kinds[index]
            val url = StringBuilder("https://api.themoviedb.org/3/search/$kind")
                .append("?api_key=").append(apiKey)
                .append("&query=").append(java.net.URLEncoder.encode(title, "UTF-8"))
                .append("&include_adult=false&language=en-US")
            if (year.isNotBlank()) {
                if (kind == "movie") url.append("&year=").append(year)
                else url.append("&first_air_date_year=").append(year)
            }
            getJson(url.toString()) { json ->
                val results = json?.optJSONArray("results")
                var bestId: Int? = null
                var bestScore = -1
                if (results != null) {
                    for (i in 0 until results.length()) {
                        val row = results.optJSONObject(i) ?: continue
                        val name = row.optString("title")
                            .ifBlank { row.optString("name") }
                        var s = titleScore(title, name)
                        val date = row.optString("release_date")
                            .ifBlank { row.optString("first_air_date") }
                        if (year.isNotBlank() && date.startsWith(year)) s += 25
                        if (s > bestScore) {
                            bestScore = s
                            bestId = row.optInt("id", 0).takeIf { it > 0 }
                        }
                    }
                }
                if (bestId != null && bestScore >= 40) done(kind, bestId)
                else tryKind(index + 1)
            }
        }
        tryKind(0)
    }

    private fun fetchVideos(
        apiKey: String,
        mediaType: String,
        tmdbId: Int,
        done: (String?) -> Unit,
    ) {
        val url =
            "https://api.themoviedb.org/3/$mediaType/$tmdbId/videos?api_key=$apiKey&language=en-US"
        getJson(url) { json ->
            val results = json?.optJSONArray("results")
            if (results == null) {
                done(null)
                return@getJson
            }
            val order = listOf("Trailer", "Teaser", "Clip", "Featurette")
            val yt = mutableListOf<JSONObject>()
            for (i in 0 until results.length()) {
                val row = results.optJSONObject(i) ?: continue
                if (row.optString("site") != "YouTube") continue
                if (row.optString("key").isBlank()) continue
                yt += row
            }
            for (type in order) {
                val hit = yt.firstOrNull { it.optString("type") == type }
                if (hit != null) {
                    done(hit.optString("key"))
                    return@getJson
                }
            }
            done(yt.firstOrNull()?.optString("key"))
        }
    }

    private fun getJson(url: String, done: (JSONObject?) -> Unit) {
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = done(null)
            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    if (!res.isSuccessful) {
                        done(null)
                        return
                    }
                    val body = res.body?.string().orEmpty()
                    done(runCatching { JSONObject(body) }.getOrNull())
                }
            }
        })
    }

    private fun titleScore(query: String, candidate: String): Int {
        val q = query.lowercase().trim()
        val c = candidate.lowercase().trim()
        if (c.isEmpty()) return 0
        if (c == q) return 100
        if (c.startsWith(q) || q.startsWith(c)) return 80
        if (c.contains(q) || q.contains(c)) return 60
        return 0
    }
}
