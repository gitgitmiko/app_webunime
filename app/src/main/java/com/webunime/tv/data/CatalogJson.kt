package com.webunime.tv.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parser katalog tahan banting. Moshi reflection sering gagal diam-diam
 * pada judul besar (One Piece ~1000+ episode) sehingga server/episode kosong.
 */
internal object CatalogJson {
    private const val TAG = "CatalogJson"

    fun parseItem(raw: String): CatalogItem? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed[0] != '{') return null
        return runCatching { fromObject(JSONObject(trimmed)) }
            .onFailure { Log.w(TAG, "parse item gagal: ${it.message}") }
            .getOrNull()
    }

    fun parseItemList(arr: JSONArray?): List<CatalogItem> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                fromObject(o)?.let { add(it) }
            }
        }
    }

    private fun fromObject(o: JSONObject): CatalogItem? {
        val slug = o.optString("slug").takeIf { it.isNotBlank() }
        val judul = o.optString("judul").takeIf { it.isNotBlank() }
        val nama = o.optString("nama").takeIf { it.isNotBlank() }
        if (slug == null && judul == null && nama == null) return null
        return CatalogItem(
            id = optPositiveInt(o, "id"),
            type = o.optString("type").takeIf { it.isNotBlank() },
            nama = nama,
            judul = judul,
            tahun = optLooseString(o, "tahun"),
            thumbnail = o.optString("thumbnail").takeIf { it.isNotBlank() },
            thumbnail_landscape = o.optString("thumbnail_landscape").takeIf { it.isNotBlank() },
            rating = optLooseString(o, "rating"),
            quality = o.optString("quality").takeIf { it.isNotBlank() },
            negara = o.optString("negara").takeIf { it.isNotBlank() },
            is_new = optBool(o, "is_new"),
            durasi = o.optString("durasi").takeIf { it.isNotBlank() },
            genre = parseGenre(o.opt("genre")),
            sinopsis = o.optString("sinopsis").takeIf { it.isNotBlank() },
            slug = slug,
            catalog = o.optString("catalog").takeIf { it.isNotBlank() },
            source = o.optString("source").takeIf { it.isNotBlank() },
            rilis = o.optString("rilis").takeIf { it.isNotBlank() },
            rilis_iso = o.optString("rilis_iso").takeIf { it.isNotBlank() },
            players = parsePlayers(o.optJSONArray("players")),
            episodes = parseEpisodes(o.optJSONArray("episodes")),
            episodes_count = optPositiveInt(o, "episodes_count"),
            anime_slug = o.optString("anime_slug").takeIf { it.isNotBlank() },
            series_slug = o.optString("series_slug").takeIf { it.isNotBlank() },
            episode = optPositiveInt(o, "episode"),
            season = optPositiveInt(o, "season"),
            episode_source = o.optString("episode_source").takeIf { it.isNotBlank() }
                ?: o.optString("episode_slug").takeIf { it.isNotBlank() },
            mal_id = optPositiveInt(o, "mal_id"),
        )
    }

    private fun parseEpisodes(arr: JSONArray?): List<Episode>? {
        if (arr == null) return null
        val out = ArrayList<Episode>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Episode(
                    season = optPositiveInt(o, "season"),
                    episode = optPositiveInt(o, "episode"),
                    title = o.optString("title").takeIf { it.isNotBlank() },
                    slug = o.optString("slug").takeIf { it.isNotBlank() },
                    source = o.optString("source").takeIf { it.isNotBlank() },
                    date = o.optString("date").takeIf { it.isNotBlank() },
                    players = parsePlayers(o.optJSONArray("players")),
                    skip = parseSkip(o.optJSONObject("skip")),
                ),
            )
        }
        return out.ifEmpty { null }
    }

    private fun parsePlayers(arr: JSONArray?): List<PlayerServer>? {
        if (arr == null) return null
        val out = ArrayList<PlayerServer>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").takeIf { it.isNotBlank() } ?: continue
            out.add(
                PlayerServer(
                    no = optPositiveInt(o, "no"),
                    server = o.optString("server").takeIf { it.isNotBlank() },
                    label = o.optString("label").takeIf { it.isNotBlank() },
                    url = url,
                    isDefault = optBool(o, "default"),
                ),
            )
        }
        return out.ifEmpty { null }
    }

    private fun parseSkip(o: JSONObject?): EpisodeSkip? {
        if (o == null) return null
        return EpisodeSkip(
            op = parseSkipSeg(o.optJSONObject("op")),
            ed = parseSkipSeg(o.optJSONObject("ed")),
            source = o.optString("source").takeIf { it.isNotBlank() },
        )
    }

    private fun parseSkipSeg(o: JSONObject?): SkipSegment? {
        if (o == null) return null
        val start = o.optDouble("start", Double.NaN).takeIf { it.isFinite() }
        val end = o.optDouble("end", Double.NaN).takeIf { it.isFinite() }
        return SkipSegment(start, end)
    }

    private fun parseGenre(raw: Any?): List<String>? = when (raw) {
        is JSONArray -> buildList {
            for (i in 0 until raw.length()) {
                raw.optString(i).trim().takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }.ifEmpty { null }
        is String -> raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { null }
        else -> null
    }

    private fun optPositiveInt(o: JSONObject, key: String): Int? {
        if (!o.has(key) || o.isNull(key)) return null
        return when (val v = o.opt(key)) {
            is Number -> v.toInt().takeIf { it > 0 }
            is String -> v.toIntOrNull()?.takeIf { it > 0 }
            else -> o.optInt(key, 0).takeIf { it > 0 }
        }
    }

    private fun optLooseString(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.opt(key)?.toString()?.trim()?.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun optBool(o: JSONObject, key: String): Boolean? {
        if (!o.has(key) || o.isNull(key)) return null
        return when (val v = o.opt(key)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.equals("true", true) || v == "1"
            else -> null
        }
    }
}
