package com.webunime.tv.ui.browse

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import com.webunime.tv.R
import com.webunime.tv.data.CatalogItem

/**
 * Trailer hero ringan untuk TV low-RAM:
 * - Delay [FOCUS_DELAY_MS] sebelum start (batal jika fokus pindah).
 * - Satu WebView iframe YouTube (mute + autoplay), dibuat on-demand.
 * - Destroy total saat stop (bukan hanya hide) agar heap dibebaskan.
 */
class HeroTrailerPlayer(
    private val activity: Activity,
    private val host: FrameLayout,
    private val backdrop: ImageView,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var webView: WebView? = null
    private var activeKey: String? = null

    fun schedule(youtubeKey: String?, delayMs: Long = FOCUS_DELAY_MS) {
        cancelPending()
        destroyPlayer()
        val key = normalizeKey(youtubeKey) ?: return
        val wait = delayMs.coerceAtLeast(0L)
        val run = Runnable { start(key) }
        pending = run
        if (wait == 0L) handler.post(run)
        else handler.postDelayed(run, wait)
    }

    fun cancel() {
        cancelPending()
        destroyPlayer()
    }

    fun release() = cancel()

    private fun cancelPending() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun start(key: String) {
        if (activity.isFinishing) return
        if (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed) return
        destroyPlayer()
        activeKey = key

        val wv = WebView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.BLACK)
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
            isLongClickable = false
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            settings.apply {
                javaScriptEnabled = true
                mediaPlaybackRequiresUserGesture = false
                domStorageEnabled = false
                databaseEnabled = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                loadWithOverviewMode = true
                useWideViewPort = true
                // Hemat: jangan load gambar di luar video frame bila memungkinkan
                blockNetworkImage = false
                loadsImagesAutomatically = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = false
                }
            }
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(
                    WebView.RENDERER_PRIORITY_IMPORTANT,
                    true,
                )
            }
            loadDataWithBaseURL(
                "https://www.youtube-nocookie.com",
                buildEmbedHtml(key),
                "text/html",
                "utf-8",
                null,
            )
        }

        host.visibility = View.VISIBLE
        host.addView(wv)
        webView = wv
        // Sembunyikan ImageView backdrop selagi trailer jalan (hemat satu layer bitmap).
        backdrop.visibility = View.INVISIBLE
    }

    private fun destroyPlayer() {
        activeKey = null
        val wv = webView
        webView = null
        if (wv != null) {
            try {
                host.removeView(wv)
            } catch (_: Exception) {
                /* ignore */
            }
            try {
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.clearHistory()
                wv.clearCache(true)
                wv.removeAllViews()
                wv.destroy()
            } catch (_: Exception) {
                /* ignore */
            }
        }
        host.removeAllViews()
        host.visibility = View.GONE
        backdrop.visibility = View.VISIBLE
    }

    companion object {
        const val FOCUS_DELAY_MS = 2000L

        private val KEY_RE = Regex("""^[\w-]{6,20}$""")

        fun normalizeKey(raw: String?): String? {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return null
            // Terima full URL singkat
            val fromUrl = Regex(
                """(?:youtu\.be/|youtube(?:-nocookie)?\.com/(?:embed/|watch\?v=|shorts/))([\w-]{6,20})""",
            ).find(s)?.groupValues?.getOrNull(1)
            val key = fromUrl ?: s
            return key.takeIf { KEY_RE.matches(it) }
        }

        private fun buildEmbedHtml(key: String): String {
            // mute+autoplay+loop; controls off — cocok background hero.
            val src =
                "https://www.youtube-nocookie.com/embed/$key" +
                    "?autoplay=1&mute=1&controls=0&modestbranding=1&rel=0" +
                    "&playsinline=1&fs=0&iv_load_policy=3&disablekb=1" +
                    "&loop=1&playlist=$key"
            return """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1"/>
                  <style>
                    html,body{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden}
                    iframe{border:0;position:fixed;inset:0;width:100%;height:100%}
                  </style>
                </head>
                <body>
                  <iframe
                    src="$src"
                    allow="autoplay; encrypted-media; picture-in-picture"
                    allowfullscreen
                    referrerpolicy="strict-origin-when-cross-origin"></iframe>
                </body>
                </html>
            """.trimIndent()
        }

        fun attach(activity: Activity): HeroTrailerPlayer? {
            val host = activity.findViewById<FrameLayout>(R.id.browseTrailerHost) ?: return null
            val backdrop = activity.findViewById<ImageView>(R.id.browseBackdrop) ?: return null
            return HeroTrailerPlayer(activity, host, backdrop)
        }
    }
}
