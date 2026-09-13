package com.webunime.tv.data.api

/** Helper koleksi katalog (bukan endpoint akun). */
object ApiConfig {
    fun normalizeItemCollection(raw: String?): String {
        val c = raw?.trim()?.lowercase().orEmpty()
        return when {
            c == "anime-latest" -> "anime"
            c == "series-latest" -> "series"
            c in ITEM_COLLECTIONS -> c
            else -> "movies"
        }
    }

    val ITEM_COLLECTIONS = setOf(
        "movies",
        "series",
        "horror",
        "indonesia",
        "anime",
        "anime-movies",
        "anime-latest",
        "series-latest",
    )
}
