package com.webunime.tv.ui.player

import android.os.SystemClock
import android.util.Log
import com.webunime.tv.data.WatchPartyRepository
import com.webunime.tv.data.api.ApiException
import com.webunime.tv.data.api.RoomEvent
import com.webunime.tv.data.api.RoomMedia
import com.webunime.tv.data.api.RoomPlayback
import com.webunime.tv.data.api.WatchRoom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Sinkron nonton bareng di player TV (port logika web + watch-sync-agent).
 * Host: kirim posisi saat play/pause/lompat dan tiap [HEARTBEAT_MS].
 * Penonton: ikuti posisi host; drift > [DRIFT_SEC] → seek.
 * Semua callback [Host] dipanggil di main thread (scope lifecycle activity).
 */
class WatchPartyController(
    private val scope: CoroutineScope,
    private val repo: WatchPartyRepository,
    private val host: Host,
    val isHost: Boolean,
) {
    interface Host {
        /** Posisi video sekarang (detik); null bila video belum siap. */
        fun partyPositionSec(): Double?
        fun partyIsPlaying(): Boolean
        fun partySeekTo(sec: Double)
        fun partySetPlaying(playing: Boolean)
        fun partyStatusChanged(text: String)
        /** Penonton: host ganti episode/server. */
        fun partyMediaChanged(media: RoomMedia, playback: RoomPlayback)
        fun partyEnded(message: String)
    }

    var code: String? = null
        private set

    private var media: RoomMedia? = null
    private var desired: RoomPlayback? = null
    private var viewers: List<String> = emptyList()
    private var hostOnline = true
    private var lastPushAt = 0L
    private var lastPushed: RoomPlayback? = null
    private var lastSeekAt = 0L
    private var lastToggleAt = 0L
    private var streamJob: Job? = null
    private var tickJob: Job? = null
    private var stopped = false
    private var lastStatus: String? = null

    /** Host: buat ruang dari media yang sedang diputar. */
    fun startAsHost(media: RoomMedia, onCreated: (WatchRoom) -> Unit, onError: (String) -> Unit) {
        scope.launch {
            val room = runCatching { repo.createRoom(media) }
                .getOrElse {
                    onError((it as? ApiException)?.message ?: "Gagal membuat ruang.")
                    return@launch
                }
            if (stopped) {
                runCatching { repo.closeRoom(room.code) }
                return@launch
            }
            attach(room)
            onCreated(room)
        }
    }

    /** Penonton: gabung ruang yang sudah diambil snapshot-nya. */
    fun startAsViewer(room: WatchRoom) {
        attach(room)
    }

    private fun attach(room: WatchRoom) {
        code = room.code
        media = room.media
        desired = room.playback
        viewers = room.viewers
        hostOnline = room.hostOnline
        publishStatus()
        streamJob = scope.launch { streamLoop(room.code) }
        tickJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (isHost) maybePushPlayback(force = false) else enforce()
            }
        }
    }

    /** Host ganti episode / server (termasuk failover). */
    fun onHostMediaChanged(next: RoomMedia) {
        if (!isHost) return
        val c = code ?: return
        val prev = media
        if (prev != null && prev.slug == next.slug && prev.episodeSlug == next.episodeSlug &&
            prev.serverUrl == next.serverUrl
        ) {
            return
        }
        media = next
        scope.launch {
            runCatching { repo.changeMedia(c, next) }
                .onFailure { Log.w(TAG, "media gagal: ${it.message}") }
        }
    }

    /** Host: play/pause/seek terdeteksi → kirim segera. */
    fun onHostPlaybackEvent() {
        if (isHost) maybePushPlayback(force = true)
    }

    /**
     * Keluar dari player. Host menutup ruang untuk semua; penonton cukup putus.
     */
    fun stop() {
        if (stopped) return
        stopped = true
        streamJob?.cancel()
        tickJob?.cancel()
        val c = code ?: return
        if (isHost) {
            scope.launch(NonCancellable) { runCatching { repo.closeRoom(c) } }
        }
    }

    private suspend fun streamLoop(code: String) {
        while (scope.isActive && !stopped) {
            try {
                repo.streamEvents(code) { event -> scope.launch { onEvent(event) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.httpCode == 404 || e.httpCode == 401) {
                    end(if (e.httpCode == 401) "Sesi login berakhir." else ENDED_MESSAGE)
                    return
                }
                Log.w(TAG, "SSE ${e.httpCode}: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "SSE putus: ${e.message}")
            }
            if (stopped) return
            // Putus jaringan / server restart: cek ruang masih ada lalu sambung lagi.
            delay(RECONNECT_MS)
            val alive = runCatching { repo.getRoom(code) }
            if (alive.isSuccess && alive.getOrNull() == null) {
                end(ENDED_MESSAGE)
                return
            }
        }
    }

    private fun onEvent(event: RoomEvent) {
        if (stopped) return
        when (event) {
            is RoomEvent.State -> {
                viewers = event.room.viewers
                hostOnline = event.room.hostOnline
                if (!isHost) {
                    desired = event.room.playback
                    // Tersambung ulang: media bisa berubah selama koneksi putus.
                    if (event.room.media.serverUrl != media?.serverUrl ||
                        event.room.media.episodeSlug != media?.episodeSlug
                    ) {
                        media = event.room.media
                        host.partyMediaChanged(event.room.media, event.room.playback)
                    }
                }
            }
            is RoomEvent.Presence -> {
                viewers = event.viewers
                hostOnline = event.hostOnline
            }
            is RoomEvent.Playback -> if (!isHost) {
                desired = event.playback
                enforce()
            }
            is RoomEvent.Media -> if (!isHost) {
                media = event.media
                desired = event.playback
                host.partyMediaChanged(event.media, event.playback)
            }
            is RoomEvent.Closed -> {
                if (!isHost) end(ENDED_MESSAGE)
                return
            }
        }
        publishStatus()
    }

    private fun maybePushPlayback(force: Boolean) {
        val c = code ?: return
        val pos = host.partyPositionSec() ?: return
        val playing = host.partyIsPlaying()
        val prev = lastPushed
        val flipped = prev == null || prev.playing != playing
        val jumped = prev != null && abs(pos - prev.positionNow()) > JUMP_SEC
        val due = SystemClock.elapsedRealtime() - lastPushAt >= HEARTBEAT_MS
        if (!force && !flipped && !jumped && !due) return
        if (force && !flipped && !jumped) return
        lastPushAt = SystemClock.elapsedRealtime()
        lastPushed = RoomPlayback(playing = playing, time = pos)
        scope.launch {
            runCatching { repo.pushPlayback(c, playing, pos) }
                .onFailure { Log.w(TAG, "playback gagal: ${it.message}") }
        }
    }

    private fun enforce() {
        val want = desired ?: return
        val pos = host.partyPositionSec() ?: return
        val now = SystemClock.elapsedRealtime()
        // Status play WebView bisa basi (event JS hilang) → jangan toggle bolak-balik.
        if (want.playing != host.partyIsPlaying() && now - lastToggleAt > SEEK_COOLDOWN_MS) {
            lastToggleAt = now
            host.partySetPlaying(want.playing)
        }
        val target = want.positionNow()
        val limit = if (want.playing) DRIFT_SEC else PAUSED_DRIFT_SEC
        if (abs(pos - target) > limit && now - lastSeekAt > SEEK_COOLDOWN_MS) {
            lastSeekAt = now
            host.partySeekTo(target)
        }
    }

    private fun publishStatus() {
        val c = code ?: return
        val count = viewers.size
        val text = when {
            isHost -> "Nobar $c · $count penonton"
            !hostOnline -> "Nobar $c · host terputus, menunggu…"
            desired?.playing == false -> "Nobar $c · dijeda host"
            else -> "Nobar $c · mengikuti host"
        }
        // Heartbeat host tiap 5 detik → jangan munculkan HUD bila teks sama.
        if (text == lastStatus) return
        lastStatus = text
        host.partyStatusChanged(text)
    }

    private fun end(message: String) {
        if (stopped) return
        stopped = true
        tickJob?.cancel()
        host.partyEnded(message)
    }

    companion object {
        private const val TAG = "WatchParty"
        private const val TICK_MS = 1_500L
        private const val HEARTBEAT_MS = 5_000L
        private const val RECONNECT_MS = 3_000L
        private const val JUMP_SEC = 2.0
        private const val DRIFT_SEC = 2.5
        private const val PAUSED_DRIFT_SEC = 0.75
        private const val SEEK_COOLDOWN_MS = 3_000L
        const val ENDED_MESSAGE = "Nonton bareng sudah berakhir."
    }
}
