package com.webunime.tv.data

/**
 * Bagian katalog yang bisa di-load on-demand (satu file JSON).
 */
enum class CatalogSection(val fileName: String, val apiName: String) {
    MOVIES("movies.json", "movies"),
    INDONESIA("indonesia.json", "indonesia"),
    HORROR("horror.json", "horror"),
    MARVEL("marvel.json", "marvel"),
    SERIES_LATEST("series-latest.json", "series-latest"),
    /** Index ringan (~0.5MB) — detail episode di-hydrate dari series.json. */
    SERIES("series-index.json", "series"),
    ANIME_LATEST("anime-latest.json", "anime-latest"),
    /** Index ringan (~1.3MB) — detail episode di-hydrate dari anime.json. */
    ANIME("anime-index.json", "anime"),
    ANIME_MOVIES("anime-movies.json", "anime-movies"),
    ;

    companion object {
        /** Cukup untuk hero + cold start cepat (tanpa series/anime penuh). */
        val STARTUP: List<CatalogSection> = listOf(MOVIES, INDONESIA, HORROR)

        val ALL: List<CatalogSection> = entries.toList()
    }
}
