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
    /** Cadangan bila [thumbnail] gagal load (mis. poster 404 → screenshot episode). Tidak dari JSON. */
    @Json(ignore = true)
    val thumbnailAlt: String? = null,
    val rating: String? = null,
    val quality: String? = null,
    val durasi: String? = null,
    val genre: List<String>? = null,
    val sinopsis: String? = null,
    val slug: String? = null,
    val source: String? = null,
    val players: List<PlayerServer>? = null,
    val episodes: List<Episode>? = null,
    val episodes_count: Int? = null,
    val anime_slug: String? = null,
    val episode: Int? = null,
    val episode_source: String? = null,
    /** MyAnimeList id (hasil enrich AniSkip di WEBUNIME). */
    val mal_id: Int? = null,
) {
    fun displayTitle(): String = judul?.takeIf { it.isNotBlank() } ?: nama ?: slug ?: "Tanpa judul"

    fun displayMeta(): String {
        val parts = mutableListOf<String>()
        tahun?.takeIf { it.isNotBlank() }?.let { parts += it }
        quality?.takeIf { it.isNotBlank() }?.let { parts += it }
        rating?.takeIf { it.isNotBlank() }?.let { parts += "★ $it" }
        durasi?.takeIf { it.isNotBlank() }?.let { parts += it }
        return parts.joinToString(" · ")
    }

    fun isSeriesLike(): Boolean =
        type == "series" || type == "anime" || type == "anime-movie" || !episodes.isNullOrEmpty()

    /** Jumlah episode yang tersedia di katalog (utamakan daftar scraped). */
    fun totalEpisodes(): Int? {
        episodes?.size?.takeIf { it > 0 }?.let { return it }
        return episodes_count?.takeIf { it > 0 }
    }

    /** Badge "N EPS" untuk baris Series / Anime (bukan feed Anime Terbaru per-episode). */
    fun showsEpisodeCountBadge(): Boolean {
        if (anime_slug != null && episode != null && episodes.isNullOrEmpty()) return false
        if (type == "series" || type == "anime") return totalEpisodes() != null
        return !episodes.isNullOrEmpty() && (totalEpisodes() ?: 0) > 1
    }

    /** Label badge pojok kanan atas: total EPS untuk series/anime, kualitas untuk film. */
    fun posterBadgeLabel(): String? {
        if (showsEpisodeCountBadge()) {
            val n = totalEpisodes() ?: return null
            return "$n EPS"
        }
        return quality?.trim()?.takeIf { it.isNotBlank() }?.uppercase()
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
    val horror: List<CatalogItem> = emptyList(),
    val anime: List<CatalogItem> = emptyList(),
    val animeMovies: List<CatalogItem> = emptyList(),
    val animeLatest: List<CatalogItem> = emptyList(),
) {
    fun findBySlug(slug: String): CatalogItem? {
        if (slug.isBlank()) return null
        val all = movies + series + horror + anime + animeMovies
        all.firstOrNull { it.slug == slug }?.let { return it }
        // Feed anime-terbaru: hanya anime_slug — ambil entri penuh dari katalog anime
        anime.firstOrNull { it.slug == slug || it.anime_slug == slug }?.let { return it }
        animeMovies.firstOrNull { it.slug == slug || it.anime_slug == slug }?.let { return it }
        return null
    }

    fun findLatestFeedEntry(animeSlug: String, episode: Int?): CatalogItem? =
        animeLatest.firstOrNull {
            it.anime_slug == animeSlug && (episode == null || it.episode == episode)
        }

    /** Cari di seluruh katalog (film, series, horror, anime). */
    fun search(query: String, limit: Int = 40): List<CatalogItem> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        val pool = (movies + series + horror + anime + animeMovies)
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
