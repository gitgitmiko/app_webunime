package com.webunime.tv.data.api

object ApiConfig {
    const val BASE_URL = "https://gitgitmiko.my.id"
    const val COOKIE_SID = "webunime_sid"
    const val USER_AGENT = "WEBUNIME-TV/1.11"

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
