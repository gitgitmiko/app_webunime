package com.webunime.tv.ui.player

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Bitmap
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
import com.webunime.tv.data.EmbedResolver
import com.webunime.tv.data.PlayerRouter
import com.webunime.tv.data.WatchSession
import com.webunime.tv.data.WatchSessionStore
import com.webunime.tv.data.WebPlayerProxy
import kotlinx.coroutines.launch
import org.json.JSONObject

class PlayerActivity : AppCompatActivity() {

    private var exoPlayer: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var webView: WebView
    private lateinit var titleBar: View
    private lateinit var titleView: TextView
    private lateinit var modeView: TextView

    private var sourceUrl: String = ""
    private var serverLabel: String = ""
    private var exoFallbackUsed = false

    private var serverUrls: List<String> = emptyList()
    private var serverLabels: List<String> = emptyList()
    private var serverIndex: Int = 0
    private var contentSlug: String = ""
    private var contentEpisode: Int? = null
    private var contentThumb: String? = null
    private var resumePositionMs: Long = 0L
    private var failoverInProgress = false
    private var playJobGeneration = 0

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
            persistProgress()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.exoPlayerView)
        webView = findViewById(R.id.webPlayer)
        titleBar = findViewById(R.id.playerTitleBar)
        titleView = findViewById(R.id.playerTitle)
        modeView = findViewById(R.id.playerMode)

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
        playCurrentServer()
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
                val webUrl = EmbedResolver.pixeldrainEmbedUrl(sourceUrl)
                    ?.takeIf { sourceUrl.contains("pixeldrain", ignoreCase = true) }
                    ?: playUrl
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

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(50_000, 180_000, 5_000, 8_000)
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
                    (application as WebunimeApp).watchSessions.remove(contentSlug, contentEpisode)
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
            setTitleBarVisible(false)
        }

        @android.webkit.JavascriptInterface
        fun onPause() = setTitleBarVisible(true)

        @android.webkit.JavascriptInterface
        fun onQualities(json: String) {
            runOnUiThread { showQualityDialog(json) }
        }

        @android.webkit.JavascriptInterface
        fun onProgress(positionSec: Double, durationSec: Double) {
            if (contentSlug.isBlank()) return
            val pos = (positionSec * 1000.0).toLong()
            val dur = (durationSec * 1000.0).toLong()
            if (pos < WatchSessionStore.MIN_SAVE_MS) return
            (application as WebunimeApp).watchSessions.save(
                WatchSession(
                    slug = contentSlug,
                    episode = contentEpisode,
                    title = titleView.text?.toString().orEmpty(),
                    thumbnail = contentThumb,
                    positionMs = pos,
                    durationMs = dur.coerceAtLeast(0L),
                )
            )
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
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString =
                "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
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
                view?.evaluateJavascript(
                    """
                    (function(){
                      function hook(v){
                        if(!v||v.__wuHooked)return;
                        v.__wuHooked=true;
                        v.addEventListener('play',function(){try{WebunimePlayback.onPlay();}catch(e){}});
                        v.addEventListener('playing',function(){try{WebunimePlayback.onPlay();}catch(e){}});
                        v.addEventListener('pause',function(){try{WebunimePlayback.onPause();}catch(e){}});
                        v.addEventListener('ended',function(){try{WebunimePlayback.onPause();}catch(e){}});
                        v.addEventListener('timeupdate',function(){
                          try{
                            if(v.currentTime>15){
                              WebunimePlayback.onProgress(v.currentTime, v.duration||0);
                            }
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
                      var mo=new MutationObserver(function(){
                        document.querySelectorAll('video').forEach(hook);
                      });
                      mo.observe(document.documentElement,{childList:true,subtree:true});
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
            hideHandler.postDelayed(webFailTimeoutRunnable, WEB_FAIL_TIMEOUT_MS)
        }
        pendingSeekSec = 0
        seekHintServerLabel = "$server · WebView"
        hideHandler.removeCallbacks(applySeekRunnable)
        hideHandler.removeCallbacks(clearSeekHintRunnable)
        webView.requestFocus()
        showTitleThenAutoHide()
    }

    private fun persistProgress() {
        if (contentSlug.isBlank()) return
        val player = exoPlayer ?: return
        val pos = player.currentPosition
        val dur = player.duration.takeIf { it > 0 } ?: 0L
        if (pos < WatchSessionStore.MIN_SAVE_MS) return
        (application as WebunimeApp).watchSessions.save(
            WatchSession(
                slug = contentSlug,
                episode = contentEpisode,
                title = titleView.text?.toString().orEmpty(),
                thumbnail = contentThumb,
                positionMs = pos,
                durationMs = dur,
            )
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
            !isSeekKey(event.keyCode)
        ) {
            showTitleThenAutoHide()
            peekPlayerChrome()
        }
        if (webView.visibility == View.VISIBLE && isOkKey(event.keyCode)) {
            // Jangan toggle selama grace: mencegah pause tak sengaja di Hydrax.
            if (SystemClock.uptimeMillis() < ignoreRemoteUntil) return true
            if (webVideoActive || isAbyssWrapper) {
                if (event.action == KeyEvent.ACTION_UP) togglePlayback()
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

    private fun isQualityKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_MENU ||
            keyCode == KeyEvent.KEYCODE_INFO ||
            keyCode == KeyEvent.KEYCODE_GUIDE ||
            keyCode == KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK

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
        private const val TITLE_AUTO_HIDE_MS = 4_000L
        private const val WEB_FAIL_TIMEOUT_MS = 32_000L
        private const val PROGRESS_TICK_MS = 10_000L
        private const val SEEK_DEBOUNCE_MS = 140L
        private const val REMOTE_GRACE_MS = 1_200L
    }

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
              if(typeof window.__wuToggle!=="function"){
                window.__wuToggle=function(){
                  try{
                    var v=__wuVid();
                    if(v){ if(v.paused) v.play(); else v.pause(); return; }
                    var jp=__wuJw();
                    if(jp){
                      var s=typeof jp.getState==="function"?jp.getState():"";
                      if(s==="playing"||s==="buffering") jp.pause(); else jp.play();
                    }
                  }catch(e){}
                };
              }
              try{
                window.addEventListener("message",function(e){
                  var d=e&&e.data;
                  if(d&&typeof d==="object"&&d.type==="__wuSeekBy"){
                    try{ window.__wuSeekBy(d.delta); }catch(ex){}
                  } else if(d==="__wuToggle"){
                    try{ window.__wuToggle(); }catch(ex){}
                  }
                });
              }catch(e){}
            })();
        """.trimIndent()
}
