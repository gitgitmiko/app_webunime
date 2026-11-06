package com.webunime.tv.data.api

import com.squareup.moshi.Moshi
import com.webunime.tv.BuildConfig
import com.webunime.tv.data.CatalogItem
import com.webunime.tv.data.CatalogJson
import com.webunime.tv.data.CatalogSyncStatus
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * REST katalog WEBUNIME (`/api/v1`, MySQL di server).
 * Semua fungsi blocking — panggil dari Dispatchers.IO.
 * Gagal jaringan / HTTP 5xx / JSON rusak → [IOException]; 404 → null.
 * Item di-parse [CatalogJson] (toleran: mis. episode 1030.5 One Piece bikin Moshi gagal).
 */
class CatalogApi(moshi: Moshi) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private val syncAdapter = moshi.adapter(CatalogSyncStatus::class.java)

    fun listCollection(
        collection: String,
        page: Int,
        limit: Int,
        q: String = "",
        genre: String = "",
        sort: String = "",
    ): CatalogPage {
        val url = url("catalog", collection) {
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", limit.toString())
            if (q.isNotBlank()) addQueryParameter("q", q)
            if (genre.isNotBlank()) addQueryParameter("genre", genre)
            if (sort.isNotBlank()) addQueryParameter("sort", sort)
        }
        val body = get(url) ?: return CatalogPage(collection = collection, page = page, limit = limit)
        val o = parseObject(body)
        return CatalogPage(
            collection = o.optString("collection", collection),
            page = o.optInt("page", page),
            limit = o.optInt("limit", limit),
            total = o.optInt("total", 0),
            items = CatalogJson.parseItemList(o.optJSONArray("items")),
        )
    }

    fun hero(limit: Int): List<CatalogItem> {
        val url = url("hero") { addQueryParameter("limit", limit.toString()) }
        val body = get(url) ?: return emptyList()
        return CatalogJson.parseItemList(parseObject(body).optJSONArray("items"))
    }

    fun search(query: String, limit: Int): List<CatalogItem> {
        val url = url("search") {
            addQueryParameter("q", query)
            addQueryParameter("limit", limit.toString())
        }
        val body = get(url) ?: return emptyList()
        return CatalogJson.parseItemList(parseObject(body).optJSONArray("items"))
    }

    /** Judul lengkap (episodes + players). */
    fun item(collection: String, slug: String): CatalogItem? {
        val body = get(url("catalog", collection, slug)) ?: return null
        return CatalogJson.parseItem(body) ?: throw IOException("Detail rusak $collection/$slug")
    }

    fun syncStatus(): CatalogSyncStatus? {
        val body = get(url("docs", "sync-status")) ?: return null
        return syncAdapter.fromJson(body)
    }

    private fun url(vararg segments: String, query: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        "${ApiConfig.BASE_URL}${ApiConfig.API_PREFIX}".toHttpUrl().newBuilder()
            .apply {
                for (s in segments) addPathSegment(s)
                query()
            }
            .build()

    private fun get(url: HttpUrl): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "${ApiConfig.USER_AGENT}/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} ${url.encodedPath}")
            return response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IOException("Body kosong ${url.encodedPath}")
        }
    }

    private fun parseObject(body: String): JSONObject =
        runCatching { JSONObject(body.trim()) }
            .getOrElse { throw IOException("JSON tidak valid", it) }
}
