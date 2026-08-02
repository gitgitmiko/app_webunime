package com.webunime.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Ambil interval opening/ending dari AniSkip (via MAL id dari Jikan).
 * Gagal → null; pemanggil memakai heuristik.
 */
object AniSkipClient {

    data class Interval(val startSec: Double, val endSec: Double) {
        fun contains(pos: Double, padEnd: Double = 0.0): Boolean =
            pos >= startSec && pos < (endTimePadded(padEnd))

        private fun endTimePadded(pad: Double) = endSec - pad
    }

    data class SkipTimes(
        val opening: Interval?,
        val ending: Interval?,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(title: String, episode: Int): SkipTimes? = withContext(Dispatchers.IO) {
        if (title.isBlank() || episode <= 0) return@withContext null
        val malId = resolveMalId(title) ?: return@withContext null
        fetchSkipTimes(malId, episode)
    }

    private fun resolveMalId(title: String): Int? {
        val q = cleanTitle(title)
        if (q.length < 2) return null
        val url =
            "https://api.jikan.moe/v4/anime?q=${URLEncoder.encode(q, "UTF-8")}&limit=5"
        val body = httpGet(url) ?: return null
        val data = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull() ?: return null
        if (data.length() == 0) return null
        val qLower = q.lowercase()
        var bestId: Int? = null
        var bestScore = -1
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val id = o.optInt("mal_id", 0).takeIf { it > 0 } ?: continue
            val titles = buildList {
                o.optString("title").takeIf { it.isNotBlank() }?.let { add(it) }
                o.optString("title_english").takeIf { it.isNotBlank() }?.let { add(it) }
                o.optJSONObject("title_japanese") // ignore
                val arr = o.optJSONArray("titles")
                if (arr != null) {
                    for (j in 0 until arr.length()) {
                        arr.optJSONObject(j)?.optString("title")
                            ?.takeIf { it.isNotBlank() }
                            ?.let { add(it) }
                    }
                }
            }
            val score = titles.maxOfOrNull { t ->
                val tl = t.lowercase()
                when {
                    tl == qLower -> 100
                    tl.startsWith(qLower) -> 80
                    tl.contains(qLower) -> 60
                    qLower.contains(tl) && tl.length >= 6 -> 40
                    else -> 0
                }
            } ?: 0
            if (score > bestScore) {
                bestScore = score
                bestId = id
            }
        }
        return if (bestScore >= 40) bestId else data.optJSONObject(0)?.optInt("mal_id")?.takeIf { it > 0 }
    }

    private fun fetchSkipTimes(malId: Int, episode: Int): SkipTimes? {
        val url =
            "https://api.aniskip.com/v2/skip-times/$malId/$episode?types=op&types=ed&types=mixed-op&types=mixed-ed"
        val body = httpGet(url) ?: return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (!root.optBoolean("found", false)) return null
        val results = root.optJSONArray("results") ?: return null
        var op: Interval? = null
        var ed: Interval? = null
        for (i in 0 until results.length()) {
            val row = results.optJSONObject(i) ?: continue
            val type = row.optString("skipType")
            val interval = row.optJSONObject("interval") ?: continue
            val start = interval.optDouble("startTime", Double.NaN)
            val end = interval.optDouble("endTime", Double.NaN)
            if (!start.isFinite() || !end.isFinite() || end <= start) continue
            when (type) {
                "op", "mixed-op" -> if (op == null) op = Interval(start, end)
                "ed", "mixed-ed" -> if (ed == null) ed = Interval(start, end)
            }
        }
        if (op == null && ed == null) return null
        return SkipTimes(op, ed)
    }

    private fun httpGet(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "WEBUNIME-TV/1.0")
            .header("Accept", "application/json")
            .build()
        return runCatching {
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) null else res.body?.string()
            }
        }.getOrNull()
    }

    /** Bersihkan judul katalog agar pencarian Jikan lebih akurat. */
    fun cleanTitle(raw: String): String =
        raw
            .replace(Regex("""\s*\(\d{4}\)\s*$"""), "")
            .replace(Regex("""(?i)\s*subtitle:.*$"""), "")
            .replace(Regex("""(?i)\s*(bd|batch|complete|sub\s*indo).*$"""), "")
            .trim()

    /** Heuristik bila AniSkip tidak tersedia. */
    fun heuristic(durationSec: Double?): SkipTimes {
        val op = Interval(0.0, DEFAULT_OP_END_SEC)
        val ed = if (durationSec != null && durationSec > DEFAULT_OP_END_SEC + DEFAULT_ED_LEN_SEC + 60) {
            Interval((durationSec - DEFAULT_ED_LEN_SEC).coerceAtLeast(DEFAULT_OP_END_SEC + 30), durationSec)
        } else {
            null
        }
        return SkipTimes(op, ed)
    }

    const val DEFAULT_OP_END_SEC = 90.0
    const val DEFAULT_ED_LEN_SEC = 90.0
}
