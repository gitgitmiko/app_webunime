package com.webunime.tv.ui.player

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.webunime.tv.data.EmbedResolver
import com.webunime.tv.data.PlayerRouter
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

    /**
     * True setelah pemutaran WebView benar-benar dimulai (event `play` pertama).
     * Selama false — termasuk saat masih ada gerbang/dialog seperti verifikasi
     * Cast ("click to verify you're a human") atau "Resume watching?" — tombol OK
     * diteruskan ke WebView agar mengklik tombol yang sedang fokus. Setelah play
     * dimulai, OK berpindah jadi toggle play/pause.
     */
    @Volatile
    private var webVideoActive = false

    /**
     * True bila WebView memuat Hydrax/abyss di dalam iframe wrapper. Untuk kasus
     * ini tombol OK SELALU toggle play/pause (via postMessage ke iframe), karena
     * wrapper tidak punya elemen fokus yang perlu diklik.
     */
    private var isAbyssWrapper = false

    /** Digunakan agar toast "kualitas tidak tersedia" tidak muncul bila dialog sukses. */
    @Volatile
    private var qualityDialogShown = false

    /** Debounce: cegah dialog kualitas muncul 2x dari jalur ganda (postMessage + bridge). */
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
                    finish()
                }
            }
        )

        sourceUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        serverLabel = intent.getStringExtra(EXTRA_SERVER).orEmpty()
        titleView.text = title
        modeView.text = serverLabel

        if (sourceUrl.isBlank()) {
            Toast.makeText(this, R.string.error_play, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            modeView.text = "$serverLabel · memuat…"
            val resolved = EmbedResolver.resolve(sourceUrl)
            if (resolved.unsupported) {
                Toast.makeText(
                    this@PlayerActivity,
                    resolved.message ?: getString(R.string.error_play),
                    Toast.LENGTH_LONG
                ).show()
                finish()
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
            .setBufferDurationsMs(
                /* minBufferMs */ 50_000,
                /* maxBufferMs */ 180_000,
                /* bufferForPlaybackMs */ 5_000,
                /* bufferForPlaybackAfterRebufferMs */ 8_000
            )
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
        player.playWhenReady = true
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                setTitleBarVisible(!isPlaying)
            }

            override fun onPlayerError(error: PlaybackException) {
                // Pixeldrain API gagal → fallback embed bersih
                val embed = EmbedResolver.pixeldrainEmbedUrl(sourceUrl)
                if (!exoFallbackUsed && embed != null) {
                    exoFallbackUsed = true
                    exoPlayer?.release()
                    exoPlayer = null
                    startWeb(embed, server)
                    return
                }
                Toast.makeText(
                    this@PlayerActivity,
                    getString(R.string.error_play) + ": " + (error.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        })
        playerView.requestFocus()
    }

    /** Sembunyikan bar judul saat sedang play, tampilkan lagi saat pause. */
    private fun setTitleBarVisible(visible: Boolean) {
        runOnUiThread {
            hideHandler.removeCallbacks(hideTitleRunnable)
            titleBar.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    /**
     * Tampilkan judul lalu sembunyikan otomatis setelah beberapa detik.
     * Dipakai untuk embed iframe lintas-origin yang status play/pause-nya
     * tidak bisa dibaca — judul muncul saat ada interaksi tombol, lalu hilang.
     */
    private fun showTitleThenAutoHide() {
        runOnUiThread {
            titleBar.visibility = View.VISIBLE
            hideHandler.removeCallbacks(hideTitleRunnable)
            hideHandler.postDelayed(hideTitleRunnable, TITLE_AUTO_HIDE_MS)
        }
    }

    /** Bridge dari <video> di WebView (same-origin, mis. Pixeldrain) → toggle judul. */
    private inner class PlaybackBridge {
        @android.webkit.JavascriptInterface
        fun onPlay() {
            webVideoActive = true
            setTitleBarVisible(false)
        }

        @android.webkit.JavascriptInterface
        fun onPause() = setTitleBarVisible(true)

        /** Daftar kualitas dari JWPlayer (via shim / postMessage wrapper). */
        @android.webkit.JavascriptInterface
        fun onQualities(json: String) {
            runOnUiThread { showQualityDialog(json) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startWeb(url: String, server: String) {
        playerView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        modeView.text = "$server · WebView"
        webVideoActive = false
        isAbyssWrapper = WebPlayerProxy.isAbyss(url)

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

            override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                val u = pageUrl.orEmpty()
                if (EmbedResolver.isBlockedNavigation(u)) {
                    view?.stopLoading()
                    Toast.makeText(
                        this@PlayerActivity,
                        "Redirect ke situs sumber diblokir — coba server lain",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                // Hook play/pause dari elemen <video> same-origin agar judul ikut
                // hilang saat diputar dan muncul lagi saat dijeda. Iframe lintas-origin
                // tidak bisa diakses, judul tetap tampil (aman, tidak error).
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
                        if(!v.paused){try{WebunimePlayback.onPlay();}catch(e){}}
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
                if (isPixeldrain) {
                    // Sembunyikan chrome Pixeldrain; fokus ke video
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
            Toast.makeText(this, R.string.error_play, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (WebPlayerProxy.isAbyss(url)) {
            // Hydrax/abyss harus berjalan di dalam iframe (anti-direct-access),
            // seolah di-embed dari playeriframe.sbs.
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
        }
        webView.requestFocus()
        // Embed dianggap langsung memutar → judul tampil sebentar lalu hilang.
        showTitleThenAutoHide()
    }

    private fun handleNav(view: WebView, next: String): Boolean {
        if (next.isBlank() || next.startsWith("about:")) return false
        // Iklan / pop-under: blokir tanpa keluar dari player
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
            // Jika WebView sempat terlempar ke halaman lain (iklan), back kembali
            // ke player dulu — bukan langsung keluar ke pemilihan server.
            if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
                webView.goBack()
                return true
            }
            finish()
            return true
        }
        // Hotkey kualitas: UP / MENU / INFO → dialog native (bisa dinavigasi D-pad)
        if (webView.visibility == View.VISIBLE &&
            event.action == KeyEvent.ACTION_UP &&
            isQualityKey(event.keyCode) &&
            (webVideoActive || isAbyssWrapper)
        ) {
            requestQualityPicker()
            return true
        }
        // Mode WebView (embed lintas-origin): tekan tombol remote (selain OK) →
        // judul muncul sebentar lalu hilang lagi otomatis.
        if (webView.visibility == View.VISIBLE &&
            event.action == KeyEvent.ACTION_DOWN &&
            !isBackLike(event.keyCode) &&
            !isOkKey(event.keyCode)
        ) {
            showTitleThenAutoHide()
            // Turbo/Hydrax: tampilkan chrome player sebentar
            peekPlayerChrome()
        }
        // Tombol OK di WebView:
        // - Bila video sudah aktif → toggle play/pause via API player (JWPlayer/
        //   <video>), bukan tap. Konsumsi kedua ACTION agar tidak double-toggle.
        // - Bila belum ada video (mis. gerbang verifikasi Cast) → TERUSKAN ke
        //   WebView agar tombol yang sedang fokus menerima klik asli (tepercaya).
        if (webView.visibility == View.VISIBLE && isOkKey(event.keyCode)) {
            // Hydrax (iframe) selalu toggle; embed lain toggle setelah play dimulai.
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

    /** UP / MENU / INFO membuka panel kualitas native. */
    private fun isQualityKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_MENU ||
            keyCode == KeyEvent.KEYCODE_INFO ||
            keyCode == KeyEvent.KEYCODE_GUIDE ||
            keyCode == KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK

    /**
     * Toggle play/pause di WebView. Untuk Cast/TurboVIP (dokumen top) langsung
     * memanggil `window.__wuToggle()`; untuk Hydrax (di dalam iframe) perintah
     * dikirim ke iframe lewat postMessage. Fungsi __wuToggle didefinisikan di
     * shim (WebPlayerProxy.clientShim).
     */
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

    /** Minta daftar kualitas dari JWPlayer (top-level atau via bridge iframe). */
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
        val now = android.os.SystemClock.uptimeMillis()
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

    /** Tampilkan chrome JWPlayer sebentar (Turbo/Hydrax). */
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

    override fun onStop() {
        super.onStop()
        exoPlayer?.playWhenReady = false
    }

    override fun onDestroy() {
        hideHandler.removeCallbacks(hideTitleRunnable)
        hideHandler.removeCallbacks(qualityTimeoutRunnable)
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
        private const val TITLE_AUTO_HIDE_MS = 4_000L
    }
}
