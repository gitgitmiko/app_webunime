package com.webunime.tv.data

import com.webunime.tv.data.api.ApiClient
import com.webunime.tv.data.api.ApiException
import com.webunime.tv.data.api.RoomEvent
import com.webunime.tv.data.api.RoomMedia
import com.webunime.tv.data.api.RoomPlayback
import com.webunime.tv.data.api.WatchRoom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Nonton bareng (`/api/v1/rooms`, wajib login). Event dibaca lewat SSE di OkHttp
 * biasa — satu koneksi terbuka + ping server tiap 25 detik, tanpa library tambahan.
 */
class WatchPartyRepository(private val api: ApiClient) {

    /** SSE: tanpa callTimeout; readTimeout > heartbeat server agar koneksi mati terdeteksi. */
    private val streamClient = api.http.newBuilder()
        .readTimeout(70, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun listRooms(): List<WatchRoom> = withContext(Dispatchers.IO) {
        val arr = JSONObject(api.get("/api/v1/rooms")).optJSONArray("rooms") ?: JSONArray()
        buildList {
            for (i in 0 until arr.length()) parseRoom(arr.optJSONObject(i))?.let { add(it) }
        }
    }

    /** null bila ruang sudah berakhir (404). */
    suspend fun getRoom(code: String): WatchRoom? = withContext(Dispatchers.IO) {
        try {
            parseRoom(JSONObject(api.get("/api/v1/rooms/${code.uppercase()}")).optJSONObject("room"))
        } catch (e: ApiException) {
            if (e.httpCode == 404) null else throw e
        }
    }

    suspend fun createRoom(media: RoomMedia): WatchRoom = withContext(Dispatchers.IO) {
        val raw = api.post("/api/v1/rooms", mediaBody(media))
        parseRoom(JSONObject(raw).optJSONObject("room"))
            ?: throw ApiException(500, "Gagal membuat ruang.")
    }

    suspend fun changeMedia(code: String, media: RoomMedia) = withContext(Dispatchers.IO) {
        api.post("/api/v1/rooms/$code/media", mediaBody(media))
    }

    suspend fun pushPlayback(code: String, playing: Boolean, timeSec: Double) =
        withContext(Dispatchers.IO) {
            api.post(
                "/api/v1/rooms/$code/playback",
                JSONObject().put("playing", playing).put("time", timeSec.coerceAtLeast(0.0)),
            )
        }

    suspend fun closeRoom(code: String) = withContext(Dispatchers.IO) {
        api.delete("/api/v1/rooms/$code")
    }

    /**
     * Baca event sampai server menutup koneksi atau coroutine dibatalkan.
     * @throws ApiException 404 (ruang habis) / 401; IOException untuk putus jaringan.
     */
    suspend fun streamEvents(code: String, onEvent: (RoomEvent) -> Unit) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(api.url("/api/v1/rooms/$code/events"))
                .header("Accept", "text/event-stream")
                .get()
                .build()
            val call = streamClient.newCall(request)
            suspendCancellableCoroutine<Unit> { cont ->
                cont.invokeOnCancellation { call.cancel() }
                cont.resumeWith(runCatching { readStream(call, onEvent) })
            }
            coroutineContext.ensureActive()
        }

    private fun readStream(call: Call, onEvent: (RoomEvent) -> Unit) {
        call.execute().use { response ->
            if (!response.isSuccessful) {
                val err = runCatching {
                    JSONObject(response.body?.string().orEmpty()).optString("error")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP ${response.code}"
                throw ApiException(response.code, err)
            }
            val source = response.body?.source() ?: throw IOException("SSE tanpa body")
            val data = StringBuilder()
            while (true) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.isEmpty() -> {
                        if (data.isNotEmpty()) {
                            parseEvent(data.toString())?.let(onEvent)
                            data.setLength(0)
                        }
                    }
                    line.startsWith("data:") -> data.append(line.substring(5).trimStart())
                    // ": ping" heartbeat & field lain diabaikan.
                }
            }
        }
    }

    private fun parseEvent(raw: String): RoomEvent? {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return when (o.optString("type")) {
            "state" -> parseRoom(o.optJSONObject("room"))?.let { RoomEvent.State(it) }
            "presence" -> RoomEvent.Presence(
                viewers = parseNames(o.optJSONArray("viewers")),
                hostOnline = o.optBoolean("hostOnline", true),
            )
            "playback" -> RoomEvent.Playback(parsePlayback(o.optJSONObject("playback")))
            "media" -> parseMedia(o.optJSONObject("media"))?.let {
                RoomEvent.Media(it, parsePlayback(o.optJSONObject("playback")))
            }
            "closed" -> RoomEvent.Closed(o.optString("reason").takeIf { it.isNotBlank() })
            else -> null
        }
    }

    private fun parseRoom(o: JSONObject?): WatchRoom? {
        if (o == null) return null
        val code = o.optString("code").takeIf { it.isNotBlank() } ?: return null
        val media = parseMedia(o.optJSONObject("media")) ?: return null
        return WatchRoom(
            code = code,
            hostId = o.optInt("hostId"),
            hostName = o.optString("hostName").takeIf { it.isNotBlank() },
            hostOnline = o.optBoolean("hostOnline", true),
            media = media,
            playback = parsePlayback(o.optJSONObject("playback")),
            viewers = parseNames(o.optJSONArray("viewers")),
        )
    }

    private fun parseMedia(o: JSONObject?): RoomMedia? {
        if (o == null) return null
        val collection = o.optString("collection").takeIf { it.isNotBlank() } ?: return null
        val slug = o.optString("slug").takeIf { it.isNotBlank() } ?: return null
        val serverUrl = o.optString("serverUrl").takeIf { it.isNotBlank() } ?: return null
        return RoomMedia(
            collection = collection,
            slug = slug,
            episodeSlug = o.optNullableString("episodeSlug"),
            serverUrl = serverUrl,
            title = o.optNullableString("title"),
            episodeLabel = o.optNullableString("episodeLabel"),
        )
    }

    private fun parsePlayback(o: JSONObject?): RoomPlayback = RoomPlayback(
        playing = o?.optBoolean("playing", false) ?: false,
        time = o?.optDouble("time", 0.0)?.takeIf { it.isFinite() } ?: 0.0,
    )

    private fun parseNames(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
        }
    }

    private fun mediaBody(media: RoomMedia): JSONObject = JSONObject()
        .put("collection", media.collection)
        .put("slug", media.slug.lowercase())
        .put("episodeSlug", media.episodeSlug ?: JSONObject.NULL)
        .put("serverUrl", media.serverUrl)

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
