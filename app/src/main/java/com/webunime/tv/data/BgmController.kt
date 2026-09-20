package com.webunime.tv.data

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.webunime.tv.ui.player.PlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background music untuk beranda/detail.
 * Pause otomatis saat [PlayerActivity] aktif atau app tidak terlihat.
 */
class BgmController(private val app: Application) : Application.ActivityLifecycleCallbacks {

    fun interface Listener {
        fun onTrackChanged(title: String?)
    }

    data class Track(val title: String, val url: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private var player: ExoPlayer? = null
    private var tracks: List<Track> = emptyList()
    private var prepared = AtomicBoolean(false)
    private var preparing = AtomicBoolean(false)

    private var startedActivities = 0
    private var videoHold = 0
    /** True setelah splash katalog hilang — BGM hanya boleh main saat beranda siap. */
    @Volatile
    private var browseReady = false
    @Volatile
    private var currentTitle: String? = null

    fun currentTitle(): String? = displayTitle()

    fun isMuted(): Boolean = BgmPrefs.isMuted(app)

    fun userVolume(): Float = BgmPrefs.volume(app)

    fun setMuted(muted: Boolean) {
        BgmPrefs.setMuted(app, muted)
        applyAudioLevel()
        notifyListeners()
    }

    fun toggleMuted(): Boolean {
        val next = BgmPrefs.toggleMuted(app)
        applyAudioLevel()
        notifyListeners()
        return next
    }

    fun setUserVolume(volume: Float) {
        BgmPrefs.setVolume(app, volume)
        applyAudioLevel()
    }

    private fun applyAudioLevel() {
        player?.volume = BgmPrefs.effectiveVolume(app)
    }

    fun setBrowseReady(ready: Boolean) {
        if (browseReady == ready) {
            if (ready) {
                ensurePrepared()
                syncPlayback()
            }
            return
        }
        browseReady = ready
        if (ready) {
            ensurePrepared()
        }
        syncPlayback()
        notifyListeners()
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onTrackChanged(displayTitle())
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun start() {
        app.registerActivityLifecycleCallbacks(this)
        // Playlist diunduh setelah beranda siap (setBrowseReady), bukan saat splash load.
    }

    fun release() {
        app.unregisterActivityLifecycleCallbacks(this)
        mainHandler.post {
            player?.release()
            player = null
            prepared.set(false)
            browseReady = false
        }
    }

    private fun ensurePrepared() {
        if (prepared.get() || !preparing.compareAndSet(false, true)) return
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { loadPlaylist() }
            tracks = loaded.tracks
            if (!BgmPrefs.hasUserVolume(app)) {
                BgmPrefs.setVolume(app, loaded.volume)
            }
            if (tracks.isEmpty()) {
                preparing.set(false)
                Log.w(TAG, "BGM playlist kosong")
                return@launch
            }
            buildPlayer(tracks)
            prepared.set(true)
            preparing.set(false)
            syncPlayback()
        }
    }

    private fun buildPlayer(items: List<Track>) {
        player?.release()
        val exo = ExoPlayer.Builder(app).build().also { p ->
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ false,
            )
            p.volume = BgmPrefs.effectiveVolume(app)
            p.repeatMode = Player.REPEAT_MODE_ALL
            p.shuffleModeEnabled = false
            p.setMediaItems(items.map { MediaItem.fromUri(it.url) })
            p.addListener(
                object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        publishTitle(titleForIndex(p.currentMediaItemIndex))
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            publishTitle(titleForIndex(p.currentMediaItemIndex))
                        }
                    }
                },
            )
            p.prepare()
        }
        player = exo
        publishTitle(titleForIndex(0))
    }

    private fun titleForIndex(index: Int): String? =
        tracks.getOrNull(index)?.title?.takeIf { it.isNotBlank() }

    private fun displayTitle(): String? =
        if (browseReady && videoHold <= 0) currentTitle else null

    private fun publishTitle(title: String?) {
        currentTitle = title
        notifyListeners()
    }

    private fun notifyListeners() {
        val shown = displayTitle()
        listeners.forEach { runCatching { it.onTrackChanged(shown) } }
    }

    private fun syncPlayback() {
        val wantPlay =
            browseReady && startedActivities > 0 && videoHold <= 0 && tracks.isNotEmpty()
        val p = player ?: return
        if (wantPlay) {
            if (!p.playWhenReady) p.playWhenReady = true
        } else {
            if (p.playWhenReady) p.playWhenReady = false
        }
        notifyListeners()
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
        if (activity is PlayerActivity) videoHold++
        if (browseReady) ensurePrepared()
        syncPlayback()
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity is PlayerActivity) videoHold = (videoHold - 1).coerceAtLeast(0)
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        syncPlayback()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private data class PlaylistLoad(val volume: Float, val tracks: List<Track>)

    private fun loadPlaylist(): PlaylistLoad {
        for (base in PLAYLIST_BASES) {
            val body = fetchText(base + "playlist.json") ?: continue
            val parsed = parsePlaylist(body, base)
            if (parsed.tracks.isNotEmpty()) return parsed
        }
        return PlaylistLoad(BgmPrefs.DEFAULT_VOLUME, fallbackTracks())
    }

    private fun parsePlaylist(body: String, base: String): PlaylistLoad {
        return runCatching {
            val o = JSONObject(body.trim().removePrefix("\uFEFF"))
            val vol = o.optDouble("volume", BgmPrefs.DEFAULT_VOLUME.toDouble()).toFloat()
                .coerceIn(0.05f, 1f)
            val arr = o.optJSONArray("tracks") ?: return PlaylistLoad(vol, emptyList())
            val list = ArrayList<Track>(arr.length())
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val file = t.optString("file").trim()
                if (file.isEmpty()) continue
                val title = t.optString("title").trim().ifBlank { titleFromFile(file) }
                val explicit = t.optString("url").trim()
                val url = if (explicit.isNotBlank()) {
                    explicit
                } else {
                    base + Uri.encode(file)
                }
                list += Track(title = title, url = url)
            }
            PlaylistLoad(vol, list)
        }.getOrElse { PlaylistLoad(BgmPrefs.DEFAULT_VOLUME, emptyList()) }
    }

    private fun fallbackTracks(): List<Track> {
        val base = PLAYLIST_BASES.first()
        return FALLBACK_FILES.map { (title, file) ->
            Track(title = title, url = base + Uri.encode(file))
        }
    }

    private fun fetchText(url: String): String? {
        return runCatching {
            val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                res.body?.string()
            }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "BgmController"

        val PLAYLIST_BASES = listOf(
            "https://cdn.jsdelivr.net/gh/gitgitmiko/WEBUNIME@main/public/music/",
            "https://raw.githubusercontent.com/gitgitmiko/WEBUNIME/main/public/music/",
        )

        private val FALLBACK_FILES = listOf(
            "Dragon Nest" to "01 - Dragon Nest.mp3",
            "Prairie Village" to "02 - Prairie Village.mp3",
            "Mana Ridge Village" to "04 - Mana Ridge Village.mp3",
            "The Rock Cave Gateway" to "05 - The Rock Cave Gateway.mp3",
            "Saint Haven (old)" to "08 - Saint Haven (old).mp3",
            "Saint Haven (New)" to "12 - Saint Haven (New).mp3",
        )

        fun titleFromFile(file: String): String {
            val base = file.substringAfterLast('/').substringBeforeLast('.')
            return base.replace(Regex("""^\d+\s*[-–.]\s*"""), "").trim().ifBlank { base }
        }
    }
}
