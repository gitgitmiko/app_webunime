package com.webunime.tv.ui.player

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.webunime.tv.R
import com.webunime.tv.WebunimeApp
import com.webunime.tv.data.AniSkipClient
import com.webunime.tv.data.CatalogItem
import com.webunime.tv.data.EmbedResolver
import com.webunime.tv.data.Episode
import com.webunime.tv.data.PlayerRouter
import com.webunime.tv.data.WatchSession
import com.webunime.tv.data.WatchSessionStore
import com.webunime.tv.data.WebPlayerProxy
import com.webunime.tv.data.api.ApiConfig
import kotlinx.coroutines.launch
import org.json.JSONObject

class PlayerActivity : AppCompatActivity() {

    private var exoPlayer: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var webView: WebView
    private lateinit var titleBar: View
    private lateinit var titleView: TextView
    private lateinit var modeView: TextView
    private lateinit var skipActionButton: Button

    private var sourceUrl: String = ""
    private var serverLabel: String = ""
    private var exoFallbackUsed = false

    private var serverUrls: List<String> = emptyList()
    private var serverLabels: List<String> = emptyList()
    private var serverIndex: Int = 0
    private var contentSlug: String = ""
    private var contentEpisode: Int? = null
    private var contentThumb: String? = null
    private var contentCollection: String? = null
    private var contentEpisodeSlug: String? = null
    private var resumePositionMs: Long = 0L
    private var failoverInProgress = false
    private var playJobGeneration = 0

    private var catalogItem: CatalogItem? = null
    private var episodeList: List<Episode> = emptyList()
    private var episodeIndex: Int = -1

    /** Skip opening / next-episode prompts (anime). */
    private var skipTimes: AniSkipClient.SkipTimes? = null
    private var skipPromptKind: SkipPromptKind = SkipPromptKind.NONE
    private var skipOpDismissed = false
    private var skipOpUsed = false
    /** Timer auto-next setelah tombol "Episode Berikutnya" muncul di ED. */
    private var endingAutoNextArmed = false
    private var lastKnownPosSec = 0.0
    private var lastKnownDurSec = 0.0
    private var lastWebProgressSaveAt = 0L
    private var playbackOpenedAt = 0L

    @Volatile
    private var webVideoActive = false

    private var isAbyssWrapper = false

    /** Abaikan OK/seek sebentar setelah buka player (sisa key dari tombol Play di Detail). */
    private var ignoreRemoteUntil = 0L

    @Volatile
    private var qualityDialogShown = false

    private var lastQualityDialogAt = 0L
    private var qualityDialog: AlertDialog? = null

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideTitleRunnable = Runnable { titleBar.visibility = View.GONE }
    private val qualityTimeoutRunnable = Runnable {
        if (!qualityDialogShown) {
            Toast.makeText(this, "Kualitas tidak tersedia untuk server ini", Toast.LENGTH_SHORT).show()
        }
        qualityDialogShown = false
    }
    private val webFailTimeoutRunnable = Runnable {
        if (!webVideoActive && !isAbyssWrapper) {
            tryFailover("timeout")
        }
    }
    private val progressTicker = object : Runnable {
        override fun run() {
            persistProgress(flush = false)
            hideHandler.postDelayed(this, PROGRESS_TICK_MS)
        }
    }

    /** Akumulasi seek WebView: tekan cepat digabung jadi 1 seek (hindari stuck Hydrax/JW). */
    private var pendingSeekSec = 0
    private var seekHintServerLabel = ""
    private val applySeekRunnable = Runnable { flushPendingWebSeek() }
    private val clearSeekHintRunnable = Runnable {
        if (seekHintServerLabel.isNotEmpty()) {
            modeView.text = seekHintServerLabel
        }
    }
    /** Debounce HUD pause: buffering/embed sering fire pause sekejap lalu play lagi. */
    private val showPauseHudRunnable = Runnable { setTitleBarVisible(true) }
    private val autoNextEpisodeRunnable = Runnable {
        switchEpisode(1, auto = true)
    }
    private val hideSkipOpRunnable = Runnable {
        if (skipPromptKind == SkipPromptKind.SKIP_OP) {
            skipOpDismissed = true
            hideSkipPrompt()
        }
    }
    private val skipPromptTicker = object : Runnable {
        override fun run() {
            pollPlaybackPositionForSkip()
            hideHandler.postDelayed(this, SKIP_TICK_MS)
        }
    }

    private enum class SkipPromptKind { NONE, SKIP_OP, NEXT_EP }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.exoPlayerView)
        webView = findViewById(R.id.webPlayer)
        titleBar = findViewById(R.id.playerTitleBar)
        titleView = findViewById(R.id.playerTitle)
        modeView = findViewById(R.id.playerMode)
        skipActionButton = findViewById(R.id.skipActionButton)
        skipActionButton.setOnClickListener { activateSkipPrompt() }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    persistProgress()
                    finish()
                }
            }
        )

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        titleView.text = title

        contentSlug = intent.getStringExtra(EXTRA_SLUG).orEmpty()
        contentEpisode = intent.getIntExtra(EXTRA_EPISODE, -1).takeIf { it > 0 }
        contentThumb = intent.getStringExtra(EXTRA_THUMBNAIL)
        contentCollection = intent.getStringExtra(EXTRA_COLLECTION)
        contentEpisodeSlug = intent.getStringExtra(EXTRA_EPISODE_SLUG)
        resumePositionMs = intent.getLongExtra(EXTRA_RESUME_MS, 0L).coerceAtLeast(0L)

        serverUrls = intent.getStringArrayExtra(EXTRA_SERVER_URLS)?.toList().orEmpty()
            .filter { it.isNotBlank() }
        serverLabels = intent.getStringArrayExtra(EXTRA_SERVER_LABELS)?.toList().orEmpty()

        val initialUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val initialLabel = intent.getStringExtra(EXTRA_SERVER).orEmpty()
        if (serverUrls.isEmpty() && initialUrl.isNotBlank()) {
            serverUrls = listOf(initialUrl)
            serverLabels = listOf(initialLabel.ifBlank { "Server" })
        }
        if (serverLabels.size < serverUrls.size) {
            serverLabels = serverUrls.indices.map { i ->
                serverLabels.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "Server ${i + 1}"
            }
        }

        if (serverUrls.isEmpty()) {
            Toast.makeText(this, R.string.error_play, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (resumePositionMs <= 0L && contentSlug.isNotBlank()) {
            val saved = (application as WebunimeApp).watchSessions.get(contentSlug, contentEpisode)
            if (saved != null && !saved.isFinished() && saved.positionMs >= WatchSessionStore.MIN_RESUME_MS) {
                resumePositionMs = saved.positionMs
            }
        }

        serverIndex = 0
        loadEpisodeContext()
        if (catalogItem == null && contentSlug.isNotBlank()) {
            lifecycleScope.launch {
                val found = (application as WebunimeApp).catalogRepository
                    .findBySlugEnsured(contentSlug, contentCollection)
                if (isFinishing || found == null) return@launch
                catalogItem = found
                if (contentCollection.isNullOrBlank()) contentCollection = found.detailCollection()
                episodeList = found.episodes.orEmpty()
                    .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
                episodeIndex = resolveEpisodeIndex(contentEpisode, contentEpisodeSlug)
                prepareAnimeSkipTimes()
            }
        }
        prepareAnimeSkipTimes()
        playbackOpenedAt = SystemClock.elapsedRealtime()
        playCurrentServer()
    }

    private fun loadEpisodeContext() {
        if (contentSlug.isBlank()) return
        val item = (application as WebunimeApp).catalogRepository.cachedItem(
            contentCollection ?: "movies",
            contentSlug,
        ) ?: (application as WebunimeApp).catalogRepository.snapshot.findBySlug(contentSlug)
        catalogItem = item
        episodeList = item?.episodes.orEmpty()
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        episodeIndex = resolveEpisodeIndex(contentEpisode, contentEpisodeSlug)
    }

    /** Cocokkan episode aktif (angka + season bila ada) agar series multi-season aman. */
    private fun resolveEpisodeIndex(episodeNum: Int?, selectedEpisodeSlug: String?): Int {
        if (episodeList.isEmpty()) return -1
        if (!selectedEpisodeSlug.isNullOrBlank()) {
            val bySlug = episodeList.indexOfFirst { it.slug == selectedEpisodeSlug }
            if (bySlug >= 0) return bySlug
        }
        if (episodeNum == null) {
            return if (episodeList.size == 1) 0 else -1
        }
        // Prefer episode yang sama + season terdekat dari posisi sekarang.
        val currentSeason = episodeList.getOrNull(episodeIndex)?.season
        if (currentSeason != null) {
            val sameSeason = episodeList.indexOfFirst {
                it.episode == episodeNum && (it.season ?: currentSeason) == currentSeason
            }
            if (sameSeason >= 0) return sameSeason
        }
        val byNum = episodeList.indexOfFirst { it.episode == episodeNum }
        if (byNum >= 0) return byNum
        return if (episodeList.size == 1) 0 else -1
    }

    private fun isAnimeSkipEligible(): Boolean {
        val item = catalogItem ?: return false
        return item.type == "anime" || item.type == "anime-movie" || item.anime_slug != null
    }

    private fun prepareAnimeSkipTimes() {
        skipTimes = null
        skipOpDismissed = false
        skipOpUsed = false
        endingAutoNextArmed = false
        cancelAutoNextEpisode()
        lastKnownPosSec = 0.0
        lastKnownDurSec = 0.0
        hideSkipPrompt()
        hideHandler.removeCallbacks(skipPromptTicker)

        val anime = isAnimeSkipEligible()
        if (anime) {
            // Utamakan data scrape AniSkip di JSON episode; fallback heuristik 90 dtk.
            val epSkip = episodeList.getOrNull(episodeIndex)?.skip
                ?: episodeList.firstOrNull { it.episode == contentEpisode }?.skip
            val fromJson = epSkip?.let { info ->
                AniSkipClient.SkipTimes(
                    opening = info.op?.takeIf { it.isValid() }?.let {
                        AniSkipClient.Interval(it.start!!, it.end!!)
                    },
                    ending = info.ed?.takeIf { it.isValid() }?.let {
                        AniSkipClient.Interval(it.start!!, it.end!!)
                    },
                )
            }
            val heur = AniSkipClient.heuristic(null)
            skipTimes = AniSkipClient.SkipTimes(
                opening = fromJson?.opening ?: heur.opening,
                ending = fromJson?.ending,
            )
        }
        // Anime (skip OP/ED) atau series (near-end next) butuh poll posisi.
        if (anime || hasEpisodeNav()) {
            hideHandler.post(skipPromptTicker)
        }
    }

    private fun refreshEndingHeuristic(durationSec: Double) {
        if (!isAnimeSkipEligible() || durationSec < 120) return
        val current = skipTimes ?: return
        // Jangan timpa ED hasil scrape AniSkip.
        if (current.ending != null) return
        val heur = AniSkipClient.heuristic(durationSec)
        skipTimes = AniSkipClient.SkipTimes(
            opening = current.opening ?: heur.opening,
            ending = heur.ending,
        )
    }

    private fun pollPlaybackPositionForSkip() {
        if (!isAnimeSkipEligible() && !hasEpisodeNav()) return
        // Jangan evaluateJavascript di WebView (berat & sering picu OOM di TV).
        if (playerView.visibility == View.VISIBLE && exoPlayer != null) {
            val p = exoPlayer ?: return
            val pos = (p.currentPosition / 1000.0).coerceAtLeast(0.0)
            val dur = p.duration.takeIf { it > 0 }?.div(1000.0) ?: 0.0
            onPlaybackClock(pos, dur)
        }
    }

    private fun onPlaybackClock(posSec: Double, durSec: Double) {
        if (posSec.isFinite() && posSec >= 0) lastKnownPosSec = posSec
        if (durSec.isFinite() && durSec > 30) {
            if (kotlin.math.abs(durSec - lastKnownDurSec) > 1.0) {
                lastKnownDurSec = durSec
                refreshEndingHeuristic(durSec)
            } else {
                lastKnownDurSec = durSec
            }
        }
        updateSkipPrompt(lastKnownPosSec, lastKnownDurSec)
    }

    private fun updateSkipPrompt(posSec: Double, durSec: Double) {
        // Anime: Skip OP / Next di interval AniSkip (atau heuristik ED).
        if (isAnimeSkipEligible()) {
            updateAnimeSkipPrompt(posSec, durSec)
            return
        }
        // Series (dan konten ber-episode lain): near end → Next + auto-next.
        updateSeriesNearEndPrompt(posSec, durSec)
    }

    private fun updateAnimeSkipPrompt(posSec: Double, durSec: Double) {
        val times = skipTimes
        if (times == null) {
            hideSkipPrompt()
            return
        }
        val op = times.opening
        val ed = times.ending

        val inEnding = ed != null &&
            hasEpisodeNav() &&
            episodeIndex < episodeList.lastIndex &&
            posSec >= ed.startSec &&
            (durSec <= 0 || posSec < durSec - 0.35)

        if (inEnding) {
            hideHandler.removeCallbacks(hideSkipOpRunnable)
            showSkipPrompt(SkipPromptKind.NEXT_EP)
            armEndingAutoNext()
            return
        }

        val inOpening = op != null &&
            !skipOpDismissed &&
            !skipOpUsed &&
            posSec >= op.startSec &&
            posSec < (op.endSec - 1.5)

        if (inOpening) {
            if (skipPromptKind != SkipPromptKind.SKIP_OP) {
                showSkipPrompt(SkipPromptKind.SKIP_OP)
                hideHandler.removeCallbacks(hideSkipOpRunnable)
                hideHandler.postDelayed(hideSkipOpRunnable, SKIP_OP_VISIBLE_MS)
            }
            return
        }

        when (skipPromptKind) {
            SkipPromptKind.NEXT_EP -> hideSkipPrompt()
            SkipPromptKind.SKIP_OP -> {
                hideHandler.removeCallbacks(hideSkipOpRunnable)
                hideSkipPrompt()
            }
            SkipPromptKind.NONE -> Unit
        }
    }

    /**
     * Series tanpa AniSkip: di ~45 dtk terakhir (atau 95% durasi) tampilkan Next
     * dan arm auto-next. Tetap ada fallback onEnded (~2 dtk).
     */
    private fun updateSeriesNearEndPrompt(posSec: Double, durSec: Double) {
        if (!hasEpisodeNav() || episodeIndex >= episodeList.lastIndex) {
            if (skipPromptKind == SkipPromptKind.NEXT_EP) hideSkipPrompt()
            return
        }
        if (durSec < 90 || posSec < 30) {
            if (skipPromptKind == SkipPromptKind.NEXT_EP) hideSkipPrompt()
            return
        }
        val threshold = maxOf(durSec - SERIES_NEAR_END_SEC, durSec * 0.95)
        val nearEnd = posSec >= threshold && posSec < durSec - 0.35
        if (nearEnd) {
            showSkipPrompt(SkipPromptKind.NEXT_EP)
            armEndingAutoNext()
        } else if (skipPromptKind == SkipPromptKind.NEXT_EP) {
            hideSkipPrompt()
        }
    }

    private fun armEndingAutoNext() {
        if (endingAutoNextArmed) return
        endingAutoNextArmed = true
        scheduleAutoNextEpisode(AUTO_NEXT_FROM_ED_MS)
    }

    private fun showSkipPrompt(kind: SkipPromptKind) {
        if (skipPromptKind == kind && skipActionButton.visibility == View.VISIBLE) return
        skipPromptKind = kind
        skipActionButton.text = when (kind) {
            SkipPromptKind.SKIP_OP -> getString(R.string.skip_opening)
            SkipPromptKind.NEXT_EP -> getString(R.string.skip_next_episode)
            SkipPromptKind.NONE -> ""
        }
        skipActionButton.visibility = View.VISIBLE
        // Jangan requestFocus — merebut fokus dari WebView sering bikin player TV goyang/crash.
        skipActionButton.isFocusable = false
        // Jangan tampilkan title bar di sini — mirip “pause HUD” dan mengganggu nonton.
    }

    private fun hideSkipPrompt() {
        hideHandler.removeCallbacks(hideSkipOpRunnable)
        skipPromptKind = SkipPromptKind.NONE
        if (this::skipActionButton.isInitialized) {
            skipActionButton.visibility = View.GONE
        }
    }

    private fun activateSkipPrompt() {
        when (skipPromptKind) {
            SkipPromptKind.SKIP_OP -> {
                val end = skipTimes?.opening?.endSec ?: AniSkipClient.DEFAULT_OP_END_SEC
                skipOpUsed = true
                skipOpDismissed = true
                hideSkipPrompt()
                seekToAbsoluteSec(end + 0.35)
            }
            SkipPromptKind.NEXT_EP -> {
                hideSkipPrompt()
                switchEpisode(1)
            }
            SkipPromptKind.NONE -> Unit
        }
    }

    private fun seekToAbsoluteSec(sec: Double) {
        val targetMs = (sec.coerceAtLeast(0.0) * 1000.0).toLong()
        when {
            playerView.visibility == View.VISIBLE && exoPlayer != null -> {
                val p = exoPlayer ?: return
                val dur = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                p.seekTo(targetMs.coerceIn(0L, (dur - 250L).coerceAtLeast(0L)))
            }
            webView.visibility == View.VISIBLE -> {
                val js = """
                    (function(){
                      var t=$sec;
                      try{
                        if(typeof window.__wuSeekTo==="function"){ window.__wuSeekTo(t); return; }
                        var f=document.querySelector("iframe");
                        if(f&&f.contentWindow){ f.contentWindow.postMessage({type:"__wuSeekTo",time:t},"*"); }
                        var vids=document.querySelectorAll("video");
                        for(var i=0;i<vids.length;i++){
                          var v=vids[i];
                          if(!v||v.readyState<1) continue;
                          var d=v.duration||0;
                          var n=t;
                          if(d>0&&isFinite(d)) n=Math.max(0,Math.min(d-0.25,t));
                          v.currentTime=n;
                          return;
                        }
                      }catch(e){}
                    })();
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            }
        }
    }

    private fun playCurrentServer() {
        if (serverIndex !in serverUrls.indices) {
            Toast.makeText(this, R.string.error_play, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        failoverInProgress = false
        exoFallbackUsed = false
        webVideoActive = false
        hideHandler.removeCallbacks(webFailTimeoutRunnable)

        sourceUrl = serverUrls[serverIndex]
        serverLabel = serverLabels.getOrElse(serverIndex) { "Server" }
        modeView.text = serverLabel

        val generation = ++playJobGeneration
        lifecycleScope.launch {
            modeView.text = "$serverLabel · memuat…"
            val resolved = EmbedResolver.resolve(sourceUrl)
            if (generation != playJobGeneration) return@launch
            if (resolved.unsupported) {
                tryFailover(resolved.message ?: "unsupported")
                return@launch
            }
            val playUrl = resolved.url
            if (PlayerRouter.useExoPlayer(playUrl)) {
                startExo(playUrl, serverLabel)
            } else {
                val webUrl = when {
                    sourceUrl.contains("pixeldrain", ignoreCase = true) ->
                        EmbedResolver.pixeldrainEmbedUrl(sourceUrl) ?: playUrl
                    sourceUrl.contains("mega.", ignoreCase = true) ||
                        playUrl.contains("mega.", ignoreCase = true) ->
                        EmbedResolver.megaEmbedUrl(playUrl)
                            ?: EmbedResolver.megaEmbedUrl(sourceUrl)
                            ?: playUrl
                    else -> playUrl
                }
                startWeb(webUrl, serverLabel)
            }
        }
    }

    private fun tryFailover(reason: String) {
        if (failoverInProgress) return
        if (serverIndex >= serverUrls.lastIndex) {
            Toast.makeText(this, R.string.error_play, Toast.LENGTH_LONG).show()
            return
        }
        failoverInProgress = true
        hideHandler.removeCallbacks(webFailTimeoutRunnable)
        hideHandler.removeCallbacks(progressTicker)
        playJobGeneration++
        exoPlayer?.release()
        exoPlayer = null
        if (this::webView.isInitialized) {
            runCatching { webView.stopLoading() }
        }
        serverIndex++
        val next = serverLabels.getOrElse(serverIndex) { "Server" }
        Toast.makeText(
            this,
            getString(R.string.failover_server, next),
            Toast.LENGTH_SHORT
        ).show()
        playCurrentServer()
    }

    private fun startExo(url: String, server: String) {
        playerView.visibility = View.VISIBLE
        webView.visibility = View.GONE
        modeView.text = "$server · ExoPlayer"

        exoPlayer?.release()
        exoPlayer = null

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(
                "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"
            )
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(45_000)

        val headers = linkedMapOf<String, String>()
        PlayerRouter.refererFor(url)?.let { headers["Referer"] = it }
        if (headers.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(headers)
        }

        // Buffer lebih kecil: hemat RAM TV, 1080p cenderung lebih stabil (kurang GC/pressure).
        // Default tetap pilih server 1080; yang diubah hanya seberapa banyak yang di-buffer.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs */ 12_000,
                /* maxBufferMs */ 45_000,
                /* bufferForPlaybackMs */ 1_500,
                /* bufferForPlaybackAfterRebufferMs */ 3_000,
            )
            .setTargetBufferBytes(18 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setLoadControl(loadControl)
            .build()
            .also { exoPlayer = it }

        playerView.player = player
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        if (resumePositionMs > 0L) {
            player.seekTo(resumePositionMs)
            resumePositionMs = 0L
        }
        player.playWhenReady = true
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                setTitleBarVisible(!isPlaying)
                if (isPlaying) {
                    hideHandler.removeCallbacks(progressTicker)
                    hideHandler.post(progressTicker)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && contentSlug.isNotBlank()) {
                    val p = exoPlayer
                    val dur = p?.duration?.takeIf { it > 0 } ?: p?.currentPosition ?: 0L
                    persistWatch(dur, dur, finished = true, flush = true)
                    scheduleAutoNextEpisode()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val embed = EmbedResolver.pixeldrainEmbedUrl(sourceUrl)
                if (!exoFallbackUsed && embed != null) {
                    exoFallbackUsed = true
                    exoPlayer?.release()
                    exoPlayer = null
                    startWeb(embed, server)
                    return
                }
                tryFailover(error.message ?: "exo")
            }
        })
        pendingSeekSec = 0
        seekHintServerLabel = "$server · ExoPlayer"
        ignoreRemoteUntil = SystemClock.uptimeMillis() + REMOTE_GRACE_MS
        hideHandler.removeCallbacks(applySeekRunnable)
        hideHandler.removeCallbacks(clearSeekHintRunnable)
        playerView.requestFocus()
        showTitleThenAutoHide()
    }

    private fun setTitleBarVisible(visible: Boolean) {
        runOnUiThread {
            hideHandler.removeCallbacks(hideTitleRunnable)
            titleBar.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun showTitleThenAutoHide() {
        runOnUiThread {
            titleBar.visibility = View.VISIBLE
            hideHandler.removeCallbacks(hideTitleRunnable)
            hideHandler.postDelayed(hideTitleRunnable, TITLE_AUTO_HIDE_MS)
        }
    }

    private inner class PlaybackBridge {
        @android.webkit.JavascriptInterface
        fun onPlay() {
            webVideoActive = true
            hideHandler.removeCallbacks(webFailTimeoutRunnable)
            hideHandler.removeCallbacks(showPauseHudRunnable)
            setTitleBarVisible(false)
        }

        @android.webkit.JavascriptInterface
        fun onPause() {
            // Jangan langsung tampilkan judul: pause singkat (buffer/seek) sering diikuti play.
            hideHandler.removeCallbacks(showPauseHudRunnable)
            hideHandler.postDelayed(showPauseHudRunnable, PAUSE_HUD_DEBOUNCE_MS)
        }

        @android.webkit.JavascriptInterface
        fun onEnded() {
            if (contentSlug.isBlank()) return
            persistWatch(positionMs = 1L, durationMs = 1L, finished = true, flush = true)
            setTitleBarVisible(true)
            runOnUiThread { scheduleAutoNextEpisode() }
        }

        /** Dipanggil dari JS saat embed (Mega dll) menampilkan error file tidak tersedia. */
        @android.webkit.JavascriptInterface
        fun onSourceFailed(reason: String) {
            if (webVideoActive) return
            runOnUiThread {
                if (webVideoActive || failoverInProgress) return@runOnUiThread
                tryFailover(reason.ifBlank { "source-failed" })
            }
        }

        @android.webkit.JavascriptInterface
        fun onQualities(json: String) {
            runOnUiThread { showQualityDialog(json) }
        }

        @android.webkit.JavascriptInterface
        fun onProgress(positionSec: Double, durationSec: Double) {
            runOnUiThread { onPlaybackClock(positionSec, durationSec) }
            if (contentSlug.isBlank()) return
            val pos = (positionSec * 1000.0).toLong()
            val dur = (durationSec * 1000.0).toLong()
            if (pos < WatchSessionStore.MIN_SAVE_MS) return
            val now = SystemClock.uptimeMillis()
            // Jangan tulis SharedPreferences tiap detik — picu I/O berat di TV.
            if (now - lastWebProgressSaveAt < PROGRESS_TICK_MS) return
            lastWebProgressSaveAt = now
            persistWatch(pos, dur.coerceAtLeast(0L))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startWeb(url: String, server: String) {
        playerView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        modeView.text = "$server · WebView"
        webVideoActive = false
        isAbyssWrapper = WebPlayerProxy.isAbyss(url)
        // Sisa ACTION_UP dari tombol Play di Detail sering sampai ke sini dan
        // memanggil toggle → Hydrax yang baru autoplay langsung ter-pause.
        ignoreRemoteUntil = SystemClock.uptimeMillis() + REMOTE_GRACE_MS
        pendingSeekSec = 0
        hideHandler.removeCallbacks(applySeekRunnable)
        hideHandler.removeCallbacks(clearSeekHintRunnable)

        webView.removeJavascriptInterface("WebunimePlayback")
        webView.addJavascriptInterface(PlaybackBridge(), "WebunimePlayback")

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString =
                "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean = false
        }

        val isPixeldrain = url.contains("pixeldrain", ignoreCase = true)
        val isMega = url.contains("mega.nz", ignoreCase = true) ||
            url.contains("mega.co.nz", ignoreCase = true)
        val isP2pPlay = PlayerRouter.isP2pUrl(url) ||
            server.contains("p2p", ignoreCase = true)
        val seekMs = resumePositionMs
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): android.webkit.WebResourceResponse? {
                return WebPlayerProxy.intercept(request)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val next = request.url?.toString().orEmpty()
                return handleNav(view, next)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return handleNav(view, url)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    tryFailover("webview")
                }
            }

            override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                val u = pageUrl.orEmpty()
                if (EmbedResolver.isBlockedNavigation(u)) {
                    view?.stopLoading()
                    tryFailover("blocked")
                }
            }

            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                // Seek/play helpers universal: film Cast/Turbo + anime Mega/Blogger/dll.
                view?.evaluateJavascript(universalPlayerJs, null)
                // Watcher error Mega saja (ringan); jangan MutationObserver di semua halaman.
                if (isMega) {
                    view?.evaluateJavascript(sourceFailWatcherJs, null)
                    view?.evaluateJavascript(megaAutoplayJs, null)
                }
                val pageIsP2p = isP2pPlay ||
                    PlayerRouter.isP2pUrl(pageUrl.orEmpty()) ||
                    isP2pPlayUrl()
                if (pageIsP2p) {
                    view?.evaluateJavascript(p2pPlayAutoplayJs, null)
                    // Cadangan: klik/fokus tombol play setelah JW ready (mirip TurboVIP).
                    hideHandler.postDelayed({ tryP2pPlayClick() }, 700)
                    hideHandler.postDelayed({ tryP2pPlayClick() }, 1600)
                    hideHandler.postDelayed({ tryP2pPlayClick() }, 2800)
                }
                view?.evaluateJavascript(
                    """
                    (function(){
                      function hook(v){
                        if(!v||v.__wuHooked)return;
                        v.__wuHooked=true;
                        v.addEventListener('play',function(){try{WebunimePlayback.onPlay();}catch(e){}});
                        v.addEventListener('playing',function(){try{WebunimePlayback.onPlay();}catch(e){}});
                        v.addEventListener('pause',function(){try{WebunimePlayback.onPause();}catch(e){}});
                        v.addEventListener('ended',function(){try{WebunimePlayback.onEnded();}catch(e){}});
                        var lastReport=0;
                        v.addEventListener('timeupdate',function(){
                          try{
                            var now=Date.now();
                            if(now-lastReport<900) return;
                            lastReport=now;
                            WebunimePlayback.onProgress(v.currentTime, v.duration||0);
                          }catch(e){}
                        });
                        if(!v.paused){try{WebunimePlayback.onPlay();}catch(e){}}
                        var seek=$seekMs;
                        if(seek>0){
                          var apply=function(){
                            try{ v.currentTime=seek/1000; }catch(e){}
                          };
                          v.addEventListener('loadedmetadata',apply,{once:true});
                          setTimeout(apply,1200);
                        }
                      }
                      document.querySelectorAll('video').forEach(hook);
                      var tries=0;
                      var iv=setInterval(function(){
                        document.querySelectorAll('video').forEach(hook);
                        if(++tries>20) clearInterval(iv);
                      },1000);
                    })();
                    """.trimIndent(),
                    null
                )
                if (seekMs > 0) resumePositionMs = 0L
                if (isPixeldrain) {
                    view?.evaluateJavascript(
                        """
                        (function(){
                          var s=document.createElement('style');
                          s.textContent=[
                            'header,nav,footer,.navbar,.top-bar,.file-name,.filename,',
                            '.download,.btn,.ads,aside,.sidebar,.logo,.branding,',
                            '[class*="download"],[class*="toolbar"],[id*="ad"]{display:none!important;}',
                            'html,body{margin:0;padding:0;background:#000!important;overflow:hidden!important;}',
                            'video,.player,iframe,#player{width:100vw!important;height:100vh!important;',
                            'max-width:100%!important;max-height:100%!important;object-fit:contain!important;}'
                          ].join('');
                          document.documentElement.appendChild(s);
                          var v=document.querySelector('video');
                          if(v){v.controls=true;v.play&&v.play().catch(function(){});}
                        })();
                        """.trimIndent(),
                        null
                    )
                }
            }
        }

        if (EmbedResolver.isBlockedNavigation(url)) {
            tryFailover("blocked")
            return
        }

        if (WebPlayerProxy.isAbyss(url)) {
            webView.loadDataWithBaseURL(
                WebPlayerProxy.ABYSS_WRAPPER_BASE,
                WebPlayerProxy.abyssWrapperHtml(url),
                "text/html",
                "utf-8",
                null
            )
        } else {
            val headers = mutableMapOf<String, String>()
            PlayerRouter.refererFor(url)?.let { headers["Referer"] = it }
            if (headers.isEmpty()) {
                webView.loadUrl(url)
            } else {
                webView.loadUrl(url, headers)
            }
            hideHandler.removeCallbacks(webFailTimeoutRunnable)
            hideHandler.postDelayed(
                webFailTimeoutRunnable,
                when {
                    url.contains("mega.nz", ignoreCase = true) ||
                        url.contains("mega.co.nz", ignoreCase = true) -> MEGA_FAIL_TIMEOUT_MS
                    PlayerRouter.isP2pUrl(url) ||
                        server.contains("p2p", ignoreCase = true) -> P2PPLAY_FAIL_TIMEOUT_MS
                    else -> WEB_FAIL_TIMEOUT_MS
                }
            )
        }
        pendingSeekSec = 0
        seekHintServerLabel = "$server · WebView"
        hideHandler.removeCallbacks(applySeekRunnable)
        hideHandler.removeCallbacks(clearSeekHintRunnable)
        webView.requestFocus()
        showTitleThenAutoHide()
        hideHandler.removeCallbacks(progressTicker)
        hideHandler.postDelayed(progressTicker, WatchSessionStore.MIN_SAVE_MS)
    }

    private fun persistProgress(flush: Boolean = true) {
        if (contentSlug.isBlank()) return
        val exo = exoPlayer
        val exoVisible = exo != null &&
            this::playerView.isInitialized &&
            playerView.visibility == View.VISIBLE
        if (exoVisible) {
            val pos = exo!!.currentPosition
            val dur = exo.duration.takeIf { it > 0 } ?: 0L
            if (pos >= WatchSessionStore.MIN_SAVE_MS) {
                persistWatch(pos, dur, flush = flush)
                return
            }
            if (!this::webView.isInitialized || webView.visibility != View.VISIBLE) {
                persistWallClock(dur, flush)
                return
            }
        }
        val pos = (lastKnownPosSec * 1000.0).toLong()
        val dur = (lastKnownDurSec * 1000.0).toLong()
        if (pos >= WatchSessionStore.MIN_SAVE_MS) {
            persistWatch(pos, dur, flush = flush)
            return
        }
        persistWallClock(dur, flush)
    }

    private fun persistWallClock(durationMs: Long, flush: Boolean) {
        if (playbackOpenedAt <= 0L) return
        val elapsed = SystemClock.elapsedRealtime() - playbackOpenedAt
        if (elapsed >= WatchSessionStore.MIN_SAVE_MS) {
            persistWatch(elapsed, durationMs, flush = flush)
        }
    }

    private fun persistWebClock() {
        if (!this::webView.isInitialized || webView.visibility != View.VISIBLE) return
        webView.evaluateJavascript(
            """(function(){try{var c=window.__wuGetClock&&window.__wuGetClock();return c?JSON.stringify({p:c.p,d:c.d}):'{}';}catch(e){return '{}';}})()""",
        ) { raw ->
            val text = raw?.trim()?.trim('"')?.replace("\\\"", "\"") ?: return@evaluateJavascript
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return@evaluateJavascript
            val p = obj.optDouble("p", 0.0)
            val d = obj.optDouble("d", 0.0)
            if (!p.isFinite() || p < 5.0) return@evaluateJavascript
            lastKnownPosSec = p
            if (d.isFinite() && d > 0) lastKnownDurSec = d
            persistWatch((p * 1000.0).toLong(), (d * 1000.0).toLong(), flush = true)
        }
    }

    private fun persistWatch(
        positionMs: Long,
        durationMs: Long,
        finished: Boolean = false,
        flush: Boolean = false,
    ) {
        if (contentSlug.isBlank()) return
        val app = application as WebunimeApp
        val collection = ApiConfig.normalizeItemCollection(
            contentCollection ?: catalogItem?.detailCollection(),
        )
        contentCollection = collection
        val episodeSlug = contentEpisodeSlug ?: episodeList.getOrNull(episodeIndex)?.slug
        val title = titleView.text?.toString().orEmpty()
        if (finished) {
            app.watchSessions.markFinished(
                slug = contentSlug,
                episode = contentEpisode,
                title = title,
                thumbnail = contentThumb,
                durationMs = durationMs,
            )
        } else {
            app.watchSessions.save(
                WatchSession(
                    slug = contentSlug,
                    episode = contentEpisode,
                    title = title,
                    thumbnail = contentThumb,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    collection = collection,
                    episodeSlug = episodeSlug,
                ),
            )
        }
        app.libraryRepository.scheduleHistoryUpsert(
            collection = collection,
            slug = contentSlug,
            title = title,
            thumbnail = contentThumb,
            episodeSlug = episodeSlug,
            episodeNum = contentEpisode,
            progressSeconds = (if (finished) (durationMs / 1000L).coerceAtLeast(1L) else positionMs / 1000L),
            flushNow = flush || finished,
        )
    }

    private fun handleNav(view: WebView, next: String): Boolean {
        if (next.isBlank() || next.startsWith("about:")) return false
        if (WebPlayerProxy.isAdRequest(next)) return true
        if (EmbedResolver.isBlockedNavigation(next)) {
            Toast.makeText(this, "Link situs sumber diblokir", Toast.LENGTH_SHORT).show()
            return true
        }
        if (!next.startsWith("http://") && !next.startsWith("https://")) {
            return true
        }
        return false
    }

    private fun hasEpisodeNav(): Boolean = episodeList.size > 1 && episodeIndex >= 0

    private fun scheduleAutoNextEpisode(delayMs: Long = AUTO_NEXT_EPISODE_MS) {
        if (!hasEpisodeNav() || episodeIndex >= episodeList.lastIndex) return
        hideHandler.removeCallbacks(autoNextEpisodeRunnable)
        hideHandler.postDelayed(autoNextEpisodeRunnable, delayMs)
    }

    private fun cancelAutoNextEpisode() {
        hideHandler.removeCallbacks(autoNextEpisodeRunnable)
    }

    /**
     * Ganti episode tanpa keluar player.
     * @param delta +1 next / -1 previous
     */
    private fun switchEpisode(delta: Int, auto: Boolean = false) {
        cancelAutoNextEpisode()
        if (!hasEpisodeNav()) return
        val nextIdx = episodeIndex + delta
        if (nextIdx !in episodeList.indices) {
            val msg = if (delta > 0) R.string.episode_no_next else R.string.episode_no_prev
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return
        }
        val item = catalogItem ?: return
        val ep = episodeList[nextIdx]
        val players = PlayerRouter.preferredPlayers(item, ep)
        if (players.isEmpty()) {
            Toast.makeText(this, R.string.error_no_players, Toast.LENGTH_SHORT).show()
            return
        }

        persistProgress()
        playJobGeneration++
        hideHandler.removeCallbacks(webFailTimeoutRunnable)
        hideHandler.removeCallbacks(progressTicker)
        exoPlayer?.release()
        exoPlayer = null
        if (this::webView.isInitialized) {
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
            }
        }

        episodeIndex = nextIdx
        contentEpisode = ep.episode
        contentThumb = item.thumbnail ?: contentThumb
        titleView.text = buildString {
            append(item.displayTitle())
            append(" · ")
            append(ep.displayTitle())
        }
        serverUrls = players.mapNotNull { it.url }
        serverLabels = players.map { it.displayName() }
        serverIndex = 0
        failoverInProgress = false
        exoFallbackUsed = false
        webVideoActive = false
        pendingSeekSec = 0

        val saved = (application as WebunimeApp).watchSessions.get(contentSlug, contentEpisode)
        resumePositionMs = if (!auto && saved != null && !saved.isFinished() &&
            saved.positionMs >= WatchSessionStore.MIN_RESUME_MS
        ) {
            saved.positionMs
        } else {
            0L
        }

        val toastRes = when {
            auto -> R.string.episode_auto_next
            delta > 0 -> R.string.episode_next
            else -> R.string.episode_prev
        }
        Toast.makeText(this, getString(toastRes, ep.displayTitle()), Toast.LENGTH_SHORT).show()
        prepareAnimeSkipTimes()
        playbackOpenedAt = SystemClock.elapsedRealtime()
        playCurrentServer()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && isBackLike(event.keyCode)) {
            if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
                webView.goBack()
                return true
            }
            persistProgress()
            finish()
            return true
        }
        // Skip Opening / Next Episode: OK mengaktifkan popup
        if (skipPromptKind != SkipPromptKind.NONE && isOkKey(event.keyCode)) {
            if (SystemClock.uptimeMillis() < ignoreRemoteUntil) return true
            if (event.action == KeyEvent.ACTION_UP) activateSkipPrompt()
            return true
        }
        // Series / anime: Channel±, Next/Prev, atau Atas/Bawah → ganti episode
        if (isEpisodeNavKey(event.keyCode) && hasEpisodeNav()) {
            if (SystemClock.uptimeMillis() < ignoreRemoteUntil) return true
            if (event.action == KeyEvent.ACTION_UP) {
                val delta = if (isNextEpisodeKey(event.keyCode)) 1 else -1
                switchEpisode(delta)
            }
            return true
        }
        if (webView.visibility == View.VISIBLE &&
            event.action == KeyEvent.ACTION_UP &&
            isQualityKey(event.keyCode) &&
            (webVideoActive || isAbyssWrapper)
        ) {
            requestQualityPicker()
            return true
        }
        // Seek seragam: semua server WebView (Hydrax/Turbo/Cast/anime embed) + ExoPlayer.
        if ((webView.visibility == View.VISIBLE || playerView.visibility == View.VISIBLE) &&
            isSeekKey(event.keyCode) &&
            qualityDialog?.isShowing != true
        ) {
            if (SystemClock.uptimeMillis() < ignoreRemoteUntil) return true
            return handleSeekKey(event)
        }
        if (webView.visibility == View.VISIBLE &&
            event.action == KeyEvent.ACTION_DOWN &&
            !isBackLike(event.keyCode) &&
            !isOkKey(event.keyCode) &&
            !isSeekKey(event.keyCode) &&
            !isEpisodeNavKey(event.keyCode) &&
            !isQualityKey(event.keyCode)
        ) {
            showTitleThenAutoHide()
            peekPlayerChrome()
        }
        if (webView.visibility == View.VISIBLE && isOkKey(event.keyCode)) {
            // Grace: cegah pause Hydrax, tapi izinkan OK untuk klik poster p2pplay.
            if (SystemClock.uptimeMillis() < ignoreRemoteUntil && !isP2pPlayUrl()) return true
            if (webVideoActive || isAbyssWrapper) {
                if (event.action == KeyEvent.ACTION_UP) togglePlayback()
                return true
            }
            // Mega / p2pplay: OK = boot poster ATAU toggle pause/play.
            if (event.action == KeyEvent.ACTION_UP && (isMegaPlayerUrl() || isP2pPlayUrl())) {
                if (isP2pPlayUrl()) {
                    // Setelah video hidup, selalu toggle (jangan bootstrap play lagi).
                    if (webVideoActive) togglePlayback() else tryP2pPlayClick()
                } else {
                    tryMegaPlayClick()
                }
                return true
            }
            showTitleThenAutoHide()
            return super.dispatchKeyEvent(event)
        }
        if (playerView.visibility == View.VISIBLE && isOkKey(event.keyCode)) {
            if (SystemClock.uptimeMillis() < ignoreRemoteUntil) return true
            if (event.action == KeyEvent.ACTION_UP) {
                val p = exoPlayer
                if (p != null) {
                    p.playWhenReady = !p.playWhenReady
                    showTitleThenAutoHide()
                }
            }
            return true
        }
        if (playerView.visibility == View.VISIBLE && !isBackLike(event.keyCode) && !isSeekKey(event.keyCode)) {
            if (playerView.dispatchKeyEvent(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isBackLike(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE

    private fun isOkKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            keyCode == KeyEvent.KEYCODE_BUTTON_SELECT

    private fun isQualityKey(keyCode: Int): Boolean {
        // Saat series/anime: Atas/Bawah dipakai ganti episode; kualitas lewat Menu/Info.
        if (hasEpisodeNav() &&
            (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
        ) {
            return false
        }
        return keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_MENU ||
            keyCode == KeyEvent.KEYCODE_INFO ||
            keyCode == KeyEvent.KEYCODE_GUIDE ||
            keyCode == KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK
    }

    private fun isEpisodeNavKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_CHANNEL_UP ||
            keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN ||
            keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
            keyCode == KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD ||
            keyCode == KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN

    private fun isNextEpisodeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_CHANNEL_UP ||
            keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
            keyCode == KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN

    private fun isSeekKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_MEDIA_REWIND ||
            keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
            keyCode == KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD ||
            keyCode == KeyEvent.KEYCODE_MEDIA_STEP_FORWARD

    private fun handleSeekKey(event: KeyEvent): Boolean {
        // Consume UP juga agar JW / PlayerView tidak ikut seek sendiri.
        if (event.action != KeyEvent.ACTION_DOWN) return true
        cancelAutoNextEpisode()
        val dir = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> -1
            else -> 1
        }
        val step = when {
            event.repeatCount >= 12 -> 30
            event.repeatCount >= 4 -> 15
            else -> 10
        }
        pendingSeekSec = (pendingSeekSec + dir * step).coerceIn(-3600, 3600)
        showSeekHint(pendingSeekSec)
        showTitleThenAutoHide()
        if (webView.visibility == View.VISIBLE) peekPlayerChrome()
        hideHandler.removeCallbacks(applySeekRunnable)
        hideHandler.postDelayed(applySeekRunnable, SEEK_DEBOUNCE_MS)
        return true
    }

    private fun showSeekHint(pendingSec: Int) {
        if (seekHintServerLabel.isEmpty()) {
            seekHintServerLabel = modeView.text?.toString().orEmpty()
        }
        val sign = if (pendingSec >= 0) "+" else "-"
        modeView.text = "$sign${formatSeekDuration(kotlin.math.abs(pendingSec))}"
        hideHandler.removeCallbacks(clearSeekHintRunnable)
        hideHandler.postDelayed(clearSeekHintRunnable, 1_600L)
    }

    /** < 60 dtk → "45 dtk"; ≥ 60 → "2 menit" / "2 menit 10 dtk" */
    private fun formatSeekDuration(totalSec: Int): String {
        if (totalSec < 60) return "$totalSec dtk"
        val m = totalSec / 60
        val s = totalSec % 60
        return if (s == 0) "$m menit" else "$m menit $s dtk"
    }

    private fun flushPendingWebSeek() {
        val delta = pendingSeekSec
        pendingSeekSec = 0
        if (delta == 0) return
        when {
            playerView.visibility == View.VISIBLE && exoPlayer != null -> seekExoBy(delta)
            webView.visibility == View.VISIBLE -> seekWebBy(delta)
        }
    }

    private fun seekExoBy(deltaSec: Int) {
        val player = exoPlayer ?: return
        val dur = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val next = (player.currentPosition + deltaSec * 1000L).coerceIn(0L, (dur - 250L).coerceAtLeast(0L))
        player.seekTo(next)
    }

    private fun seekWebBy(deltaSec: Int) {
        val js = """
            (function(){
              try{
                if(typeof window.__wuSeekBy==="function"){ window.__wuSeekBy($deltaSec); return; }
                var f=document.querySelector("iframe");
                if(f&&f.contentWindow){ f.contentWindow.postMessage({type:"__wuSeekBy",delta:$deltaSec},"*"); }
                var vids=document.querySelectorAll("video");
                for(var i=0;i<vids.length;i++){
                  var v=vids[i];
                  if(!v||v.readyState<1) continue;
                  var n=v.currentTime+($deltaSec);
                  var d=v.duration||0;
                  if(d>0&&isFinite(d)) n=Math.max(0,Math.min(d-0.25,n));
                  else n=Math.max(0,n);
                  v.currentTime=n;
                  return;
                }
              }catch(e){}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun isMegaPlayerUrl(): Boolean {
        val u = sourceUrl
        return u.contains("mega.nz", ignoreCase = true) ||
            u.contains("mega.co.nz", ignoreCase = true) ||
            (this::webView.isInitialized &&
                webView.url.orEmpty().contains("mega.", ignoreCase = true))
    }

    private fun isP2pPlayUrl(): Boolean {
        if (serverLabel.contains("p2p", ignoreCase = true)) return true
        if (PlayerRouter.isP2pUrl(sourceUrl)) return true
        return this::webView.isInitialized && PlayerRouter.isP2pUrl(webView.url.orEmpty())
    }

    private fun tryMegaPlayClick() {
        if (!this::webView.isInitialized) return
        webView.evaluateJavascript(
            """
            (function(){
              try{
                if(typeof window.__wuMegaPlay==="function"){ window.__wuMegaPlay(); return; }
                var sels=[
                  "button.play-video-button",".play-video-button","button[class*='play']",
                  "[class*='play-button']","[aria-label*='Play' i]","[aria-label*='play' i]",
                  ".viewer-play-button",".video-wrapper button",".play-button"
                ];
                for(var i=0;i<sels.length;i++){
                  var el=document.querySelector(sels[i]);
                  if(el){ try{el.focus();}catch(e){} try{el.click(); return;}catch(e){} }
                }
                var v=document.querySelector("video");
                if(v){ try{v.muted=false; v.play();}catch(e){} }
              }catch(e){}
            })();
            """.trimIndent(),
            null
        )
        showTitleThenAutoHide()
    }

    private fun tryP2pPlayClick() {
        if (isFinishing || isDestroyed) return
        if (!this::webView.isInitialized) return
        if (!isP2pPlayUrl()) return
        webView.evaluateJavascript(
            """
            (function(){
              try{
                if(typeof window.__wuP2pTryPlay==="function"){ window.__wuP2pTryPlay(); return; }
                var o=document.getElementById('overlay');
                if(o){ try{o.click();}catch(e){} try{if(o.parentNode)o.parentNode.removeChild(o);}catch(e){} }
                var c=document.getElementById('player-button-container');
                if(c){ try{c.focus();}catch(e){} c.click(); return; }
                var b=document.getElementById('player-button');
                if(b){ try{b.focus();}catch(e){} b.click(); return; }
                if(typeof jwplayer==="function"){
                  var p=null;
                  try{ p=jwplayer("vstr"); }catch(e){}
                  if(!p||typeof p.play!=="function"){ try{ p=jwplayer(); }catch(e){} }
                  if(p&&typeof p.play==="function"){
                    try{ p.play(true); }catch(e){ try{ p.play(); }catch(e2){} }
                  }
                }
                var sels=[
                  ".jw-icon-display",".jw-display-icon-display",".jw-display-icon-container",
                  ".jw-icon-playback","[aria-label='Play']","[aria-label*='Play' i]",
                  "button[class*='play']",".play-button"
                ];
                for(var i=0;i<sels.length;i++){
                  var el=document.querySelector(sels[i]);
                  if(el){
                    try{ el.setAttribute("tabindex","0"); el.focus(); }catch(e){}
                    try{ el.click(); return; }catch(e){}
                  }
                }
                var v=document.querySelector("video");
                if(v){ try{v.muted=false; v.play();}catch(e){} }
              }catch(e){}
            })();
            """.trimIndent(),
            null
        )
        showTitleThenAutoHide()
    }

    private fun togglePlayback() {
        val js = """
            (function(){
              try{
                if(typeof window.__wuToggle==="function"){ window.__wuToggle(); return; }
                var f=document.querySelector("iframe");
                if(f&&f.contentWindow){ f.contentWindow.postMessage("__wuToggle","*"); }
              }catch(e){}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun requestQualityPicker() {
        qualityDialogShown = false
        hideHandler.removeCallbacks(qualityTimeoutRunnable)
        val js = """
            (function(){
              try{
                if(typeof window.__wuRequestQualities==="function"){ window.__wuRequestQualities(); return; }
                if(typeof window.__wuReportQualities==="function"){ window.__wuReportQualities(); return; }
                var f=document.querySelector("iframe");
                if(f&&f.contentWindow){ f.contentWindow.postMessage("__wuGetQualities","*"); }
              }catch(e){}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        hideHandler.postDelayed(qualityTimeoutRunnable, 1800)
    }

    private fun showQualityDialog(rawJson: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastQualityDialogAt < 800) return
        if (qualityDialog?.isShowing == true) return
        lastQualityDialogAt = now
        qualityDialogShown = true
        hideHandler.removeCallbacks(qualityTimeoutRunnable)
        val parsed = runCatching {
            val cleaned = when {
                rawJson.startsWith("\"") ->
                    org.json.JSONTokener(rawJson).nextValue()?.toString() ?: rawJson
                else -> rawJson
            }
            JSONObject(cleaned)
        }.getOrNull() ?: run {
            Toast.makeText(this, "Gagal memuat daftar kualitas", Toast.LENGTH_SHORT).show()
            return
        }

        val levels = parsed.optJSONArray("levels")
        if (levels == null || levels.length() == 0) {
            Toast.makeText(this, "Tidak ada pilihan resolusi", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = Array(levels.length()) { i ->
            val o = levels.getJSONObject(i)
            val label = o.optString("label", "Quality ${i + 1}")
            if (o.optBoolean("active")) "● $label" else label
        }
        val indices = IntArray(levels.length()) { i ->
            levels.getJSONObject(i).optInt("i", i)
        }

        qualityDialog?.dismiss()
        qualityDialog = AlertDialog.Builder(this)
            .setTitle("Pilih kualitas")
            .setItems(labels) { _, which ->
                applyQuality(indices[which])
                Toast.makeText(
                    this,
                    "Kualitas: ${labels[which].removePrefix("● ").trim()}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { qualityDialog = null }
            .show()
    }

    private fun applyQuality(index: Int) {
        val js = """
            (function(){
              try{
                if(typeof window.__wuSetQuality==="function"){ window.__wuSetQuality($index); return; }
                var f=document.querySelector("iframe");
                if(f&&f.contentWindow){ f.contentWindow.postMessage({type:"__wuSetQuality",index:$index},"*"); }
              }catch(e){}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun peekPlayerChrome() {
        val js = """
            (function(){
              try{
                if(typeof window.__wuShowPlayerUi==="function"){ window.__wuShowPlayerUi(); return; }
                var f=document.querySelector("iframe");
                if(f&&f.contentWindow){ f.contentWindow.postMessage("__wuShowUi","*"); }
              }catch(e){}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    override fun onPause() {
        persistProgress()
        persistWebClock()
        super.onPause()
    }

    override fun onStop() {
        persistProgress()
        super.onStop()
        exoPlayer?.playWhenReady = false
    }

    override fun onDestroy() {
        hideHandler.removeCallbacks(hideTitleRunnable)
        hideHandler.removeCallbacks(qualityTimeoutRunnable)
        hideHandler.removeCallbacks(webFailTimeoutRunnable)
        hideHandler.removeCallbacks(progressTicker)
        hideHandler.removeCallbacks(applySeekRunnable)
        hideHandler.removeCallbacks(clearSeekHintRunnable)
        hideHandler.removeCallbacks(autoNextEpisodeRunnable)
        hideHandler.removeCallbacks(hideSkipOpRunnable)
        hideHandler.removeCallbacks(skipPromptTicker)
        hideHandler.removeCallbacks(showPauseHudRunnable)
        qualityDialog?.dismiss()
        qualityDialog = null
        if (this::webView.isInitialized) {
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SERVER = "server"
        const val EXTRA_SERVER_URLS = "server_urls"
        const val EXTRA_SERVER_LABELS = "server_labels"
        const val EXTRA_SLUG = "slug"
        const val EXTRA_EPISODE = "episode"
        const val EXTRA_THUMBNAIL = "thumbnail"
        const val EXTRA_RESUME_MS = "resume_ms"
        const val EXTRA_COLLECTION = "collection"
        const val EXTRA_EPISODE_SLUG = "episode_slug"

        private const val TITLE_AUTO_HIDE_MS = 4_000L
        private const val WEB_FAIL_TIMEOUT_MS = 32_000L
        /** Mega error page biasanya cepat; jangan tunggu 32 dtk. */
        private const val MEGA_FAIL_TIMEOUT_MS = 14_000L
        /** bun.p2pplay SPA + JWPlayer butuh decrypt + ads setup lebih lama. */
        private const val P2PPLAY_FAIL_TIMEOUT_MS = 55_000L
        private const val PROGRESS_TICK_MS = 10_000L
        private const val SEEK_DEBOUNCE_MS = 140L
        private const val REMOTE_GRACE_MS = 1_200L
        /** Setelah video ended. */
        private const val AUTO_NEXT_EPISODE_MS = 2_000L
        /** Saat tombol Next muncul di ending — beri waktu tekan OK dulu. */
        private const val AUTO_NEXT_FROM_ED_MS = 8_000L
        /** Series: detik sebelum akhir untuk tombol Next + arm auto-next. */
        private const val SERIES_NEAR_END_SEC = 45.0
        private const val SKIP_OP_VISIBLE_MS = 10_000L
        private const val SKIP_TICK_MS = 1_000L
        private const val PAUSE_HUD_DEBOUNCE_MS = 500L
    }

    /**
     * Deteksi halaman error Mega / embed mati ("file no longer available/accessible")
     * lalu minta app failover ke server berikutnya (mis. Wibufile).
     */
    private val sourceFailWatcherJs: String = """
            (function(){
              if(window.__wuSourceFailWatch) return;
              window.__wuSourceFailWatch=true;
              var reported=false;
              function textOf(){
                try{
                  var parts=[];
                  if(document.title) parts.push(document.title);
                  if(document.body) parts.push(document.body.innerText||document.body.textContent||"");
                  var err=document.querySelector(
                    ".error-message,.error,.fm-dialog-body,.download.error,"+
                    "[class*='error'],[class*='unavailable'],#error"
                  );
                  if(err) parts.push(err.innerText||err.textContent||"");
                  return parts.join(" ").toLowerCase();
                }catch(e){ return ""; }
              }
              function match(t){
                if(!t) return null;
                var keys=[
                  "no longer available","no longer accessible","file is unavailable",
                  "file you are trying to download is no longer",
                  "the file you are trying to download is no longer",
                  "link you have clicked is not available",
                  "invalid or expired link","invalid link","expired link",
                  "this link is no longer available","temporarily unavailable",
                  "etooman","over quota","bandwidth limit","transfer quota"
                ];
                for(var i=0;i<keys.length;i++){
                  if(t.indexOf(keys[i])>=0) return keys[i];
                }
                return null;
              }
              function check(){
                if(reported) return true;
                try{
                  var v=document.querySelector("video");
                  if(v && !v.paused && v.readyState>=2 && v.currentTime>0.2) return false;
                }catch(e){}
                var hit=match(textOf());
                if(!hit) return false;
                reported=true;
                try{ WebunimePlayback.onSourceFailed(hit); }catch(e){}
                return true;
              }
              setTimeout(function(){
                if(check()) return;
                var n=0;
                var iv=setInterval(function(){
                  n++;
                  if(check()||n>30) clearInterval(iv);
                },800);
              },1500);
            })();
        """.trimIndent()

    /**
     * Mega: otomatis klik tombol Play (halaman file/embed sering menunggu interaksi).
     * Juga expose __wuMegaPlay untuk tombol OK remote.
     */
    private val megaAutoplayJs: String = """
            (function(){
              if(window.__wuMegaAuto) return;
              window.__wuMegaAuto=true;
              function playingVid(){
                try{
                  var v=document.querySelector("video");
                  return v && !v.paused && v.readyState>=2;
                }catch(e){ return false; }
              }
              function clickPlay(){
                try{
                  if(playingVid()) return true;
                  var sels=[
                    "button.play-video-button",".play-video-button",
                    "button.viewer-play-button",".viewer-play-button",
                    ".play-video",".video-theatre-play",
                    "[aria-label='Play']","[aria-label='play']",
                    "[title='Play']",".play-button","button.mega-button.positive"
                  ];
                  for(var i=0;i<sels.length;i++){
                    var nodes=document.querySelectorAll(sels[i]);
                    for(var j=0;j<nodes.length;j++){
                      var el=nodes[j];
                      if(!el) continue;
                      var t=(el.innerText||el.textContent||el.getAttribute("aria-label")||"").toLowerCase();
                      var cls=(el.className||"").toString().toLowerCase();
                      if(t.indexOf("download")>=0||cls.indexOf("download")>=0) continue;
                      if(t.indexOf("pause")>=0||cls.indexOf("pause")>=0) continue;
                      try{ el.focus(); }catch(e){}
                      try{ el.click(); return true; }catch(e){}
                    }
                  }
                  var v=document.querySelector("video");
                  if(v && v.paused){
                    try{ v.muted=false; v.controls=true; }catch(e){}
                    try{ v.play(); return true; }catch(e){}
                  }
                }catch(e){}
                return false;
              }
              window.__wuMegaPlay=function(){ clickPlay(); };
              var n=0;
              var iv=setInterval(function(){
                n++;
                if(playingVid()){
                  clearInterval(iv);
                  try{ WebunimePlayback.onPlay(); }catch(e){}
                  return;
                }
                clickPlay();
                if(n>24) clearInterval(iv);
              },500);
              setTimeout(clickPlay, 800);
              setTimeout(clickPlay, 1800);
            })();
        """.trimIndent()

    /** Autostart JWPlayer di playcdn.de / bun.p2pplay (+ hook progress + fokus tombol play). */
    private val p2pPlayAutoplayJs: String = """
            (function(){
              if(window.__wuP2pPlay) return;
              window.__wuP2pPlay=true;
              window.__wuP2pStarted=false;
              var autoIv=null;
              function stopAuto(){
                window.__wuP2pStarted=true;
                if(autoIv){ clearInterval(autoIv); autoIv=null; }
              }
              function notifyPlay(){
                stopAuto();
                try{WebunimePlayback.onPlay();}catch(e){}
              }
              function notifyPause(){ try{WebunimePlayback.onPause();}catch(e){} }
              function notifyEnded(){ try{WebunimePlayback.onEnded();}catch(e){} }
              function getJw(){
                try{
                  if(typeof jwplayer!=="function") return null;
                  var p=null;
                  try{ p=jwplayer("vstr"); }catch(e){}
                  if(p&&typeof p.play==="function") return p;
                  try{ p=jwplayer(); }catch(e){}
                  if(p&&typeof p.play==="function") return p;
                }catch(e){}
                return null;
              }
              function focusPlayBtn(){
                try{
                  var sels=[
                    ".jw-icon-display",".jw-display-icon-display",".jw-display-icon-container",
                    ".jw-icon-playback","[aria-label='Play']","[aria-label*='Play' i]",
                    "#player-button-container","#player-button",".player-button"
                  ];
                  for(var i=0;i<sels.length;i++){
                    var el=document.querySelector(sels[i]);
                    if(!el) continue;
                    try{ el.setAttribute("tabindex","0"); }catch(e){}
                    try{ el.focus(); }catch(e){}
                    return el;
                  }
                  var p=getJw();
                  if(p&&typeof p.getContainer==="function"){
                    var c=p.getContainer();
                    if(c){ try{ c.setAttribute("tabindex","0"); c.focus(); }catch(e){} }
                  }
                }catch(e){}
                return null;
              }
              function hookJw(p){
                try{
                  if(!p||p.__wuHooked) return;
                  p.__wuHooked=true;
                  p.on('play', function(){ notifyPlay(); pickBestQuality(p); });
                  p.on('levels', function(){ pickBestQuality(p); });
                  p.on('visualQuality', function(){});
                  p.on('pause', notifyPause);
                  p.on('complete', notifyEnded);
                  p.on('time', function(e){
                    try{
                      WebunimePlayback.onProgress(e.position||0, e.duration||0);
                    }catch(err){}
                  });
                  // Levels HLS sering baru muncul setelah play sebentar
                  setTimeout(function(){ pickBestQuality(p); }, 800);
                  setTimeout(function(){ pickBestQuality(p); }, 2500);
                  setTimeout(function(){ pickBestQuality(p); }, 5000);
                }catch(e){}
              }
              function scoreLevel(l){
                if(!l) return -1;
                var label=String(l.label||l.name||"").toLowerCase();
                var h=Number(l.height)||0;
                if(/auto/.test(label) && !h) return 0; // Auto terakhir dipilih hanya jika tak ada opsi lain
                if(h>=1000 || /1080|fhd|full\s*hd/.test(label)) return 1080 + h;
                if(h>=700 || (/\b720\b|\bhd\b/.test(label) && !/1080/.test(label))) return 720 + h;
                if(h>=400 || /\b480\b/.test(label)) return 480 + h;
                if(h>0) return h;
                if(/high|tinggi/.test(label)) return 900;
                if(/medium|sedang/.test(label)) return 500;
                if(/low|rendah/.test(label)) return 200;
                return 1;
              }
              function pickBestQuality(p){
                try{
                  if(!p||typeof p.getQualityLevels!=="function"||typeof p.setCurrentQuality!=="function") return;
                  var levels=p.getQualityLevels()||[];
                  if(!levels.length) return;
                  var best=-1, bestScore=-1;
                  for(var i=0;i<levels.length;i++){
                    var s=scoreLevel(levels[i]);
                    if(s>bestScore){ bestScore=s; best=i; }
                  }
                  if(best<0) return;
                  var cur=typeof p.getCurrentQuality==="function"?p.getCurrentQuality():-1;
                  if(cur!==best) p.setCurrentQuality(best);
                }catch(e){}
              }
              function clickPoster(){
                try{
                  var o=document.getElementById('overlay');
                  if(o){ try{o.click();}catch(e){} try{if(o.parentNode)o.parentNode.removeChild(o);}catch(e){} }
                  var c=document.getElementById('player-button-container');
                  if(c){ try{c.focus();}catch(e){} c.click(); return true; }
                  var b=document.getElementById('player-button');
                  if(b){ try{b.focus();}catch(e){} b.click(); return true; }
                  var el=document.querySelector(
                    '#player-button-container,#player-button,.player-button,[class*="dot-pulse"]'
                  );
                  if(el){
                    var t=el.closest('#player-button-container')||el;
                    try{ t.focus(); }catch(e){}
                    t.click();
                    return true;
                  }
                }catch(e){}
                return false;
              }
              function tryPlay(){
                // Sudah start: jangan paksa play lagi (biar pause user tidak di-override)
                if(window.__wuP2pStarted) return true;
                if(document.getElementById('player-button-container') ||
                   document.getElementById('player-button')){
                  return clickPoster();
                }
                try{ clickPoster(); }catch(e){}
                try{
                  var p=getJw();
                  if(p){
                    hookJw(p);
                    var st=typeof p.getState==="function"?p.getState():"";
                    if(st==="playing"||st==="buffering"){
                      stopAuto();
                      return true;
                    }
                    var btn=focusPlayBtn();
                    try{ if(btn) btn.click(); }catch(e){}
                    if(typeof p.play==="function"){
                      try{ p.play(true); }catch(e){ try{ p.play(); }catch(e2){} }
                    }
                    return false;
                  }
                }catch(e){}
                try{
                  var sels=[
                    ".jw-icon-display",".jw-display-icon-display",".jw-display-icon-container",
                    ".jw-display button",".jw-icon-playback","[aria-label='Play']","button[class*='play']"
                  ];
                  for(var i=0;i<sels.length;i++){
                    var el=document.querySelector(sels[i]);
                    if(el){
                      try{ el.setAttribute("tabindex","0"); el.focus(); }catch(e){}
                      el.click();
                      return false;
                    }
                  }
                }catch(e){}
                try{
                  var v=document.querySelector("video");
                  if(v){
                    if(!v.paused&&!v.ended){ stopAuto(); notifyPlay(); return true; }
                    v.muted=false; v.play(); return false;
                  }
                }catch(e){}
                focusPlayBtn();
                return false;
              }
              window.__wuP2pTryPlay=function(){
                // Poster/overlay masih ada → boot play. Sudah di JWPlayer → toggle pause/play saja.
                if(document.getElementById('player-button-container') ||
                   document.getElementById('player-button') ||
                   document.getElementById('overlay')){
                  tryPlay();
                  return;
                }
                try{
                  var p=getJw();
                  if(p){
                    hookJw(p);
                    var st=typeof p.getState==="function"?p.getState():"";
                    if(st==="playing"||st==="buffering"){
                      stopAuto();
                      if(typeof p.pause==="function") p.pause();
                    } else if(typeof p.play==="function") {
                      focusPlayBtn();
                      try{ p.play(true); }catch(e){ p.play(); }
                    }
                    return;
                  }
                }catch(e){}
                try{
                  if(typeof window.__wuToggle==="function"){ window.__wuToggle(); return; }
                  var v=document.querySelector("video");
                  if(v){ if(v.paused) v.play(); else v.pause(); }
                }catch(e){}
              };
              var n=0;
              autoIv=setInterval(function(){
                if(window.__wuP2pStarted || ++n>60){
                  if(autoIv){ clearInterval(autoIv); autoIv=null; }
                  return;
                }
                tryPlay();
              }, 500);
              setTimeout(tryPlay, 400);
              setTimeout(tryPlay, 1200);
              setTimeout(focusPlayBtn, 900);
            })();
        """.trimIndent()

    /** Inject di setiap halaman WebView (termasuk anime Mega/Blogger yang tidak lewat shim proxy). */
    private val universalPlayerJs: String = """
            (function(){
              if(window.__wuUniversalSeek) return;
              window.__wuUniversalSeek=true;
              function __wuVid(){ try{return document.querySelector("video");}catch(e){return null;} }
              function __wuJw(){
                try{
                  if(typeof jwplayer==="function"){
                    var p=jwplayer();
                    if(p&&typeof p.getPosition==="function") return p;
                  }
                }catch(e){}
                return null;
              }
              if(typeof window.__wuSeekBy!=="function"){
                window.__wuSeekBy=function(delta){
                  delta=Number(delta)||0;
                  if(!delta) return;
                  try{
                    var jp=__wuJw();
                    if(jp&&typeof jp.seek==="function"){
                      var st=typeof jp.getState==="function"?jp.getState():"";
                      if(st==="idle"||st==="complete"||st===""){ /* skip */ }
                      else {
                        var pos=jp.getPosition()||0;
                        var dur=typeof jp.getDuration==="function"?jp.getDuration():0;
                        if(dur>0&&isFinite(dur)){
                          jp.seek(Math.max(0,Math.min(dur-0.35,pos+delta)));
                          return;
                        }
                      }
                    }
                  }catch(e){}
                  try{
                    var v=__wuVid();
                    if(v){
                      var n=v.currentTime+delta;
                      var d=v.duration||0;
                      if(d>0&&isFinite(d)) n=Math.max(0,Math.min(d-0.25,n));
                      else n=Math.max(0,n);
                      v.currentTime=n;
                    }
                  }catch(e){}
                };
              }
              window.__wuSeekTo=function(t){
                t=Number(t);
                if(!isFinite(t)||t<0) return;
                try{
                  var jp=__wuJw();
                  if(jp&&typeof jp.seek==="function"){
                    var dur=typeof jp.getDuration==="function"?jp.getDuration():0;
                    var n=t;
                    if(dur>0&&isFinite(dur)) n=Math.max(0,Math.min(dur-0.35,t));
                    jp.seek(n);
                    return;
                  }
                }catch(e){}
                try{
                  var v=__wuVid();
                  if(v){
                    var d=v.duration||0;
                    var n=t;
                    if(d>0&&isFinite(d)) n=Math.max(0,Math.min(d-0.25,t));
                    v.currentTime=n;
                  }
                }catch(e){}
              };
              window.__wuGetClock=function(){
                try{
                  var jp=__wuJw();
                  if(jp&&typeof jp.getPosition==="function"){
                    return {p:jp.getPosition()||0,d:(typeof jp.getDuration==="function"?jp.getDuration():0)||0};
                  }
                }catch(e){}
                try{
                  var v=__wuVid();
                  if(v) return {p:v.currentTime||0,d:v.duration||0};
                }catch(e){}
                return {p:0,d:0};
              };
              if(typeof window.__wuToggle!=="function"){
                window.__wuToggle=function(){
                  try{
                    // Utamakan JWPlayer (p2pplay) — pause video mentah sering di-resume player.
                    var jp=__wuJw();
                    if(jp){
                      var s=typeof jp.getState==="function"?jp.getState():"";
                      if(s==="playing"||s==="buffering") jp.pause(); else jp.play();
                      return;
                    }
                    var v=__wuVid();
                    if(v){ if(v.paused) v.play(); else v.pause(); }
                  }catch(e){}
                };
              }
              try{
                window.addEventListener("message",function(e){
                  var d=e&&e.data;
                  if(d&&typeof d==="object"&&d.type==="__wuSeekBy"){
                    try{ window.__wuSeekBy(d.delta); }catch(ex){}
                  } else if(d&&typeof d==="object"&&d.type==="__wuSeekTo"){
                    try{ window.__wuSeekTo(d.time); }catch(ex){}
                  } else if(d==="__wuToggle"){
                    try{ window.__wuToggle(); }catch(ex){}
                  }
                });
              }catch(e){}
            })();
        """.trimIndent()
}
