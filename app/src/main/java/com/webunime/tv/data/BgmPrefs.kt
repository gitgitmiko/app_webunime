package com.webunime.tv.data

import android.content.Context

/** Preferensi BGM (volume + mute) disimpan lokal di TV. */
object BgmPrefs {
    private const val PREFS = "bgm_prefs"
    private const val KEY_MUTED = "muted"
    private const val KEY_VOLUME = "volume"
    private const val KEY_VOLUME_SET = "volume_set"

    const val DEFAULT_VOLUME = 0.32f

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isMuted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MUTED, false)

    fun setMuted(context: Context, muted: Boolean) {
        prefs(context).edit().putBoolean(KEY_MUTED, muted).apply()
    }

    fun toggleMuted(context: Context): Boolean {
        val next = !isMuted(context)
        setMuted(context, next)
        return next
    }

    fun hasUserVolume(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VOLUME_SET, false)

    fun volume(context: Context): Float =
        prefs(context).getFloat(KEY_VOLUME, DEFAULT_VOLUME).coerceIn(0f, 1f)

    fun setVolume(context: Context, volume: Float) {
        prefs(context).edit()
            .putFloat(KEY_VOLUME, volume.coerceIn(0f, 1f))
            .putBoolean(KEY_VOLUME_SET, true)
            .apply()
    }

    /** Volume efektif untuk player (0 jika mute). */
    fun effectiveVolume(context: Context): Float =
        if (isMuted(context)) 0f else volume(context)
}
