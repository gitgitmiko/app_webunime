package com.webunime.tv.data

/**
 * Bagian katalog yang bisa di-load on-demand (satu file JSON).
 */
enum class CatalogSection(val fileName: String) {
    MOVIES("movies.json"),
    INDONESIA("indonesia.json"),
    HORROR("horror.json"),
    SERIES_LATEST("series-latest.json"),
    SERIES("series.json"),
    ANIME_LATEST("anime-latest.json"),
    ANIME("anime.json"),
    ANIME_MOVIES("anime-movies.json"),
    ;

    companion object {
        /** Cukup untuk hero + cold start cepat (tanpa series/anime penuh). */
        val STARTUP: List<CatalogSection> = listOf(MOVIES, INDONESIA, HORROR)

        val ALL: List<CatalogSection> = entries.toList()
    }
}
