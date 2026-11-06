package com.webunime.tv.ui.player

import android.app.Activity
import android.content.Intent
import com.webunime.tv.WebunimeApp
import com.webunime.tv.data.api.WatchRoom

/** Buka [PlayerActivity] sebagai penonton ruang nonton bareng. */
object WatchPartyLauncher {

    /**
     * @return false bila judul ruang tidak ditemukan di katalog.
     */
    suspend fun openAsViewer(activity: Activity, room: WatchRoom): Boolean {
        val app = activity.application as WebunimeApp
        val media = room.media
        val item = app.catalogRepository.findBySlugEnsured(
            slug = media.slug,
            collectionHint = media.collection,
        ) ?: return false
        val episode = media.episodeSlug?.let { slug -> item.episodes?.firstOrNull { it.slug == slug } }
        val title = buildString {
            append(media.title?.takeIf { it.isNotBlank() } ?: item.displayTitle())
            episode?.let { append(" · ").append(it.displayTitle()) }
        }
        val label = (episode?.players ?: item.players).orEmpty()
            .firstOrNull { it.url == media.serverUrl }
            ?.displayName()
            ?: "Server host"
        activity.startActivity(
            Intent(activity, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_URL, media.serverUrl)
                .putExtra(PlayerActivity.EXTRA_TITLE, title)
                .putExtra(PlayerActivity.EXTRA_SERVER, label)
                // Penonton memutar server yang sama persis dengan host (tanpa failover).
                .putExtra(PlayerActivity.EXTRA_SERVER_URLS, arrayOf(media.serverUrl))
                .putExtra(PlayerActivity.EXTRA_SERVER_LABELS, arrayOf(label))
                .putExtra(PlayerActivity.EXTRA_SLUG, media.slug)
                .putExtra(PlayerActivity.EXTRA_EPISODE, episode?.episode ?: -1)
                .putExtra(PlayerActivity.EXTRA_THUMBNAIL, item.thumbnail)
                .putExtra(PlayerActivity.EXTRA_RESUME_MS, (room.playback.positionNow() * 1000).toLong())
                .putExtra(PlayerActivity.EXTRA_COLLECTION, media.collection)
                .putExtra(PlayerActivity.EXTRA_EPISODE_SLUG, media.episodeSlug)
                .putExtra(PlayerActivity.EXTRA_PARTY_CODE, room.code),
        )
        return true
    }
}
