package com.webunime.tv.data.api

object ApiConfig {
    const val BASE_URL = "https://gitgitmiko.my.id"
    const val COOKIE_SID = "webunime_sid"
    const val USER_AGENT = "WEBUNIME-TV/1.11"

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

    val PARENT_COLLECTIONS = setOf(
        "movies",
        "series",
        "horror",
        "indonesia",
        "anime",
        "anime-movies",
    )

    val DOC_NAMES = setOf(
        "anime-schedule",
        "sync-status",
        "players",
        "series-players",
        "horror-players",
        "indonesia-players",
    )
}
