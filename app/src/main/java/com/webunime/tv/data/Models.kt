package com.webunime.tv.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class CatalogItem(
    val id: Int? = null,
    val type: String? = null,
    val nama: String? = null,
    val judul: String? = null,
    val tahun: String? = null,
    val thumbnail: String? = null,
    /**
     * Backdrop 16:9 untuk kartu fokus / background browse.
     * Diisi scraper (TMDB → situs); kosong = app fallback crop portrait.
     */
    val thumbnail_landscape: String? = null,
    /** Cadangan bila [thumbnail] gagal load (mis. poster 404 → screenshot episode). Tidak dari JSON. */
    @Json(ignore = true)
    val thumbnailAlt: String? = null,
    val rating: String? = null,
    val quality: String? = null,
    /** Negara produksi (field API); fallback parse dari sinopsis jika kosong. */
    val negara: String? = null,
    /** True jika judul/feed baru di-scrape pada sync terakhir (badge NEW di TV). */
    val is_new: Boolean? = null,
    val durasi: String? = null,
    val genre: List<String>? = null,
    val sinopsis: String? = null,
    val slug: String? = null,
    /** Nama koleksi API (`movies`, `anime`, …). */
    val catalog: String? = null,
    val source: String? = null,
    /** Tanggal rilis (teks/ISO) — dipakai sort Film Indonesia terbaru. */
    val rilis: String? = null,
    val rilis_iso: String? = null,
    val players: List<PlayerServer>? = null,
    val episodes: List<Episode>? = null,
    val episodes_count: Int? = null,
    val anime_slug: String? = null,
    /** Feed Series Terbaru — slug parent di series.json. */
    val series_slug: String? = null,
    val episode: Int? = null,
    /** Season untuk feed series / episode multi-season. */
    val season: Int? = null,
    val episode_source: String? = null,
    /** MyAnimeList id (hasil enrich AniSkip di WEBUNIME). */
    val mal_id: Int? = null,
) {
    fun displayTitle(): String = judul?.takeIf { it.isNotBlank() } ?: nama ?: slug ?: "Tanpa judul"

    fun displayCountry(): String? =
        negara?.trim()?.takeIf { it.isNotBlank() }
            ?: parsedSinopsis().negara?.trim()?.takeIf { it.isNotBlank() }

    fun displayMeta(): String {
        val parts = mutableListOf<String>()
        tahun?.takeIf { it.isNotBlank() }?.let { parts += it }
        quality?.takeIf { it.isNotBlank() }?.let { parts += it }
        rating?.takeIf { it.isNotBlank() }?.let { parts += "★ $it" }
        durasi?.takeIf { it.isNotBlank() }?.let { parts += it }
        return parts.joinToString(" · ")
    }

    /** Kunci sort rilis terbaru: YYYYMMDD, fallback tahun, lalu 0. */
    fun releaseSortKey(): Long {
        val iso = rilis_iso?.trim().orEmpty()
        val isoMatch = Regex("""^(\d{4})-(\d{2})-(\d{2})""").find(iso)
        if (isoMatch != null) {
            val (y, m, d) = isoMatch.destructured
            return "$y$m$d".toLongOrNull() ?: 0L
        }
        val text = rilis?.trim().orEmpty()
        val months = mapOf(
            "jan" to "01", "january" to "01",
            "feb" to "02", "february" to "02",
            "mar" to "03", "march" to "03",
            "apr" to "04", "april" to "04",
            "mei" to "05", "may" to "05",
            "jun" to "06", "june" to "06",
            "jul" to "07", "july" to "07",
            "agu" to "08", "aug" to "08", "august" to "08", "agustus" to "08",
            "sep" to "09", "september" to "09",
            "okt" to "10", "oct" to "10", "october" to "10", "oktober" to "10",
            "nov" to "11", "november" to "11",
            "des" to "12", "dec" to "12", "december" to "12", "desember" to "12",
        )
        val m = Regex("""^(\d{1,2})\s+([A-Za-z]+)\s+(\d{4})$""").find(text)
        if (m != null) {
            val day = m.groupValues[1].padStart(2, '0')
            val mon = months[m.groupValues[2].lowercase()]
            val year = m.groupValues[3]
            if (mon != null) return "$year$mon$day".toLongOrNull() ?: 0L
        }
        val yearOnly = tahun?.trim()?.toLongOrNull()
        return if (yearOnly != null) yearOnly * 10000L else 0L
    }

    fun isSeriesLike(): Boolean =
        type == "series" || type == "anime" || type == "anime-movie" || !episodes.isNullOrEmpty()

    /** Jumlah episode yang tersedia di katalog (utamakan daftar scraped). */
    fun totalEpisodes(): Int? {
        episodes?.size?.takeIf { it > 0 }?.let { return it }
        return episodes_count?.takeIf { it > 0 }
    }

    /** Badge "N EPS" untuk baris Series / Anime (bukan feed terbaru per-episode). */
    fun showsEpisodeCountBadge(): Boolean {
        if (anime_slug != null && episode != null && episodes.isNullOrEmpty()) return false
        if (series_slug != null && episode != null && episodes.isNullOrEmpty()) return false
        if (type == "series" || type == "anime") return totalEpisodes() != null
        return !episodes.isNullOrEmpty() && (totalEpisodes() ?: 0) > 1
    }

    /** Label badge pojok kanan atas: NEW > total EPS > kualitas film. */
    fun posterBadgeLabel(): String? {
        if (is_new == true) return "NEW"
        if (showsEpisodeCountBadge()) {
            val n = totalEpisodes() ?: return null
            return "$n EPS"
        }
        return quality?.trim()?.takeIf { it.isNotBlank() }?.uppercase()
    }

    fun showsNewBadge(): Boolean = is_new == true

    /** Sinopsis yang sudah dipisah dari blok metadata scraper (Sutradara, Negara, dll). */
    fun parsedSinopsis(): SinopsisMeta = SinopsisMeta.parse(sinopsis)

    fun detailSlug(): String =
        anime_slug?.takeIf { it.isNotBlank() }
            ?: series_slug?.takeIf { it.isNotBlank() }
            ?: slug.orEmpty()

    fun detailCollection(): String {
        val cat = catalog?.trim()?.lowercase().orEmpty()
        if (cat == "anime-latest" || !anime_slug.isNullOrBlank() || type == "anime") return "anime"
        if (cat == "series-latest" || !series_slug.isNullOrBlank() || type == "series") return "series"
        if (type == "anime-movie" || cat == "anime-movies") return "anime-movies"
        if (cat in PARENT_COLLECTIONS) return cat
        return "movies"
    }

    fun isHydrated(): Boolean =
        !players.isNullOrEmpty() ||
            !episodes.isNullOrEmpty() ||
            (sinopsis?.length ?: 0) > 320

    companion object {
        private val PARENT_COLLECTIONS = setOf(
            "movies", "series", "horror", "indonesia", "anime", "anime-movies",
        )
    }
}

/**
 * Metadata yang biasanya digabung di teks [CatalogItem.sinopsis] oleh scraper LK21.
 */
data class SinopsisMeta(
    val plot: String,
    val sutradara: String? = null,
    val bintangFilm: String? = null,
    val negara: String? = null,
    val release: String? = null,
    val updated: String? = null,
) {
    fun hasCredits(): Boolean =
        !sutradara.isNullOrBlank() ||
            !bintangFilm.isNullOrBlank() ||
            !negara.isNullOrBlank() ||
            !release.isNullOrBlank() ||
            !updated.isNullOrBlank()

    /** Baris kredit untuk layar detail (kosong jika tidak ada field). */
    fun creditsText(): String {
        if (!hasCredits()) return ""
        return buildString {
            sutradara?.let { append("Sutradara: ").append(it).append('\n') }
            bintangFilm?.let { append("Bintang Film: ").append(it).append('\n') }
            negara?.let { append("Negara: ").append(it).append('\n') }
            release?.let { append("Release: ").append(it).append('\n') }
            updated?.let { append("Updated: ").append(it) }
        }.trimEnd()
    }

    companion object {
        private val META_LABELS = listOf(
            "Awards",
            "Budget",
            "Worldwide Gross",
            "Soundtrack",
            "Subtitle",
            "Sutradara",
            "Bintang Film",
            "Negara",
            "Votes",
            "Release",
            "Updated",
        )

        private val META_LINE = Regex(
            """(?m)^(?:${META_LABELS.joinToString("|") { Regex.escape(it) }}):""",
        )

        fun parse(raw: String?): SinopsisMeta {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return SinopsisMeta("")

            fun extract(label: String): String? {
                val re = Regex("""(?m)^${Regex.escape(label)}:\s*(.+)$""")
                return re.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            }

            val metaStart = META_LINE.find(text)?.range?.first
            val plot = if (metaStart != null) {
                text.substring(0, metaStart).trim()
            } else {
                text
            }

            return SinopsisMeta(
                plot = plot.ifBlank { text },
                sutradara = extract("Sutradara"),
                bintangFilm = extract("Bintang Film"),
                negara = extract("Negara"),
                release = extract("Release"),
                updated = extract("Updated"),
            )
        }
    }
}

@JsonClass(generateAdapter = false)
data class PlayerServer(
    val no: Int? = null,
    val server: String? = null,
    val label: String? = null,
    val url: String? = null,
    @Json(name = "default") val isDefault: Boolean? = null,
) {
    fun displayName(): String {
        val raw = label?.takeIf { it.isNotBlank() } ?: server ?: "Server"
        return raw
            .replace(Regex("^ganti\\s*player\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { "Server" }
            .uppercase()
    }
}

@JsonClass(generateAdapter = false)
data class SkipSegment(
    val start: Double? = null,
    val end: Double? = null,
) {
    fun isValid(): Boolean {
        val s = start ?: return false
        val e = end ?: return false
        return e > s && s >= 0
    }
}

@JsonClass(generateAdapter = false)
data class EpisodeSkip(
    val op: SkipSegment? = null,
    val ed: SkipSegment? = null,
    val source: String? = null,
)

@JsonClass(generateAdapter = false)
data class Episode(
    val season: Int? = null,
    val episode: Int? = null,
    val title: String? = null,
    val slug: String? = null,
    val source: String? = null,
    val date: String? = null,
    val players: List<PlayerServer>? = null,
    /** Interval OP/ED dari AniSkip (hasil enrich di WEBUNIME). */
    val skip: EpisodeSkip? = null,
) {
    fun displayTitle(): String {
        val ep = episode ?: return title ?: "Episode"
        val s = season
        return if (s != null && s > 0) "S${s}E$ep" else "E$ep"
    }
}

data class CatalogSnapshot(
    val movies: List<CatalogItem> = emptyList(),
    val series: List<CatalogItem> = emptyList(),
    val seriesLatest: List<CatalogItem> = emptyList(),
    val horror: List<CatalogItem> = emptyList(),
    val indonesia: List<CatalogItem> = emptyList(),
    val anime: List<CatalogItem> = emptyList(),
    val animeMovies: List<CatalogItem> = emptyList(),
    val animeLatest: List<CatalogItem> = emptyList(),
) {
    fun findBySlug(slug: String): CatalogItem? {
        if (slug.isBlank()) return null
        val all = movies + series + horror + indonesia + anime + animeMovies
        all.firstOrNull { it.slug == slug }?.let { return it }
        // Feed anime-terbaru: hanya anime_slug — ambil entri penuh dari katalog anime
        anime.firstOrNull { it.slug == slug || it.anime_slug == slug }?.let { return it }
        animeMovies.firstOrNull { it.slug == slug || it.anime_slug == slug }?.let { return it }
        // Feed series-terbaru: series_slug → entri penuh di series.json
        series.firstOrNull { it.slug == slug || it.series_slug == slug }?.let { return it }
        return null
    }

    fun findLatestFeedEntry(animeSlug: String, episode: Int?): CatalogItem? =
        animeLatest.firstOrNull {
            it.anime_slug == animeSlug && (episode == null || it.episode == episode)
        }

    /** Cari di seluruh katalog (film, series, horror, indonesia, anime). */
    fun search(query: String, limit: Int = 40): List<CatalogItem> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        val pool = (movies + series + horror + indonesia + anime + animeMovies)
            .distinctBy { it.slug ?: "${it.anime_slug}:${it.judul}" }
        return pool
            .asSequence()
            .filter { item ->
                val title = item.displayTitle().lowercase()
                val slug = (item.slug ?: item.anime_slug ?: "").lowercase()
                val genres = item.genre.orEmpty().joinToString(" ").lowercase()
                title.contains(q) || slug.contains(q) || genres.contains(q)
            }
            .sortedBy { item ->
                val title = item.displayTitle().lowercase()
                when {
                    title.startsWith(q) -> 0
                    title.contains(q) -> 1
                    else -> 2
                }
            }
            .take(limit)
            .toList()
    }
}
