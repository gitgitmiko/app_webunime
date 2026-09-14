package com.webunime.tv.data

import android.content.Context

/** Preferensi pemutaran (TV lokal). */
object PlaybackPrefs {
    private const val PREFS = "playback_prefs"
    /** false = sembunyikan pilihan server untuk film/series (default). */
    private const val KEY_SHOW_FILM_SERVERS = "show_film_server_picker"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun showFilmServerPicker(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_FILM_SERVERS, false)

    fun setShowFilmServerPicker(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_FILM_SERVERS, show).apply()
    }

    fun toggleShowFilmServerPicker(context: Context): Boolean {
        val next = !showFilmServerPicker(context)
        setShowFilmServerPicker(context, next)
        return next
    }
}
