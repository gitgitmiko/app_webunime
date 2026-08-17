package com.webunime.tv.data

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

data class WatchSession(
    val slug: String,
    val episode: Int? = null,
    val title: String,
    val thumbnail: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis(),
    val collection: String? = null,
    val episodeSlug: String? = null,
) {
    val key: String get() = sessionKey(slug, episode)

    /** Progress 0..1; null jika belum cukup data. */
    fun progressFraction(): Float? {
        if (durationMs <= 0L || positionMs <= 0L) return null
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    /** Anggap selesai jika sisa < 90 detik atau progress > 92%. */
    fun isFinished(): Boolean {
        if (durationMs > 0L) {
            val left = durationMs - positionMs
            if (left < 90_000L) return true
            if (positionMs.toFloat() / durationMs >= 0.92f) return true
        }
        return false
    }

    companion object {
        fun sessionKey(slug: String, episode: Int?): String =
            if (episode != null && episode > 0) "$slug#$episode" else slug
    }
}

class WatchSessionStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, WatchSession::class.java)
    private val adapter = moshi.adapter<List<WatchSession>>(listType)

    @Synchronized
    fun all(): List<WatchSession> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { adapter.fromJson(raw).orEmpty() }
            .getOrDefault(emptyList())
            .sortedByDescending { it.updatedAt }
    }

    /** Baris "Lanjutkan" — belum selesai & sudah ada progress bermakna. */
    @Synchronized
    fun continueWatching(limit: Int = 20): List<WatchSession> =
        all()
            .filter { it.positionMs >= MIN_RESUME_MS && !it.isFinished() }
            .take(limit)

    @Synchronized
    fun get(slug: String, episode: Int?): WatchSession? {
        val key = WatchSession.sessionKey(slug, episode)
        return all().firstOrNull { it.key == key }
    }

    @Synchronized
    fun save(session: WatchSession) {
        if (session.slug.isBlank()) return
        if (session.positionMs < MIN_SAVE_MS && session.durationMs <= 0L) return
        val others = all().filterNot { it.key == session.key }
        val next = (listOf(session) + others).take(MAX_SESSIONS)
        prefs.edit().putString(KEY, adapter.toJson(next)).apply()
    }

    /** Episode dianggap sudah ditonton (selesai / hampir selesai). */
    @Synchronized
    fun isWatched(slug: String, episode: Int?): Boolean {
        if (slug.isBlank()) return false
        return get(slug, episode)?.isFinished() == true
    }

    /** Tandai episode selesai agar tetap ada penanda di Detail, tanpa muncul di Lanjutkan. */
    @Synchronized
    fun markFinished(
        slug: String,
        episode: Int?,
        title: String,
        thumbnail: String?,
        durationMs: Long = 0L,
    ) {
        if (slug.isBlank()) return
        val dur = durationMs.coerceAtLeast(1L)
        save(
            WatchSession(
                slug = slug,
                episode = episode,
                title = title,
                thumbnail = thumbnail,
                positionMs = dur,
                durationMs = dur,
            )
        )
    }

    @Synchronized
    fun remove(slug: String, episode: Int?) {
        val key = WatchSession.sessionKey(slug, episode)
        val next = all().filterNot { it.key == key }
        prefs.edit().putString(KEY, adapter.toJson(next)).apply()
    }

    companion object {
        private const val PREFS = "watch_sessions"
        private const val KEY = "sessions_v1"
        /** Cukup untuk banyak episode series/anime + film. */
        private const val MAX_SESSIONS = 200
        /** Jangan simpan scrub awal yang terlalu pendek. */
        const val MIN_SAVE_MS = 5_000L
        /** Minimal progress agar muncul di "Lanjutkan". */
        const val MIN_RESUME_MS = 5_000L
    }
}
