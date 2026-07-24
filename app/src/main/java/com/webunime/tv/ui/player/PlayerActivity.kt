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
        playerView.requestFocus()
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
        if (webView.visibility == View.VISIBLE &&
            event.action == KeyEvent.ACTION_DOWN &&
            !isBackLike(event.keyCode) &&
            !isOkKey(event.keyCode)
        ) {
            showTitleThenAutoHide()
            peekPlayerChrome()
        }
        if (webView.visibility == View.VISIBLE && isOkKey(event.keyCode)) {
            if (webVideoActive || isAbyssWrapper) {
                if (event.action == KeyEvent.ACTION_UP) togglePlayback()
                return true
            }
            showTitleThenAutoHide()
            return super.dispatchKeyEvent(event)
        }
        if (playerView.visibility == View.VISIBLE && !isBackLike(event.keyCode)) {
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
    }
}
