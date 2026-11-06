package com.webunime.tv.data.api

/** Server WEBUNIME + helper koleksi katalog. */
object ApiConfig {
    const val BASE_URL = "https://gitgitmiko.my.id"
    const val API_PREFIX = "/api/v1"
    const val USER_AGENT = "WEBUNIME-TV"
    const val COOKIE_SID = "webunime_sid"

    fun normalizeItemCollection(raw: String?): String {
        val c = raw?.trim()?.lowercase().orEmpty()
        return when {
            c == "anime-latest" -> "anime"
            c == "series-latest" -> "series"
            c == "marvel" -> "movies"
            c in ITEM_COLLECTIONS -> c
            else -> "movies"
        }
    }

    val ITEM_COLLECTIONS = setOf(
        "movies",
        "series",
        "horror",
        "marvel",
        "indonesia",
        "anime",
        "anime-movies",
        "anime-latest",
        "series-latest",
    )
}
