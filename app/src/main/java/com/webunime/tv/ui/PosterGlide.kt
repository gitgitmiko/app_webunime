package com.webunime.tv.ui

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import okhttp3.OkHttpClient
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Loader poster katalog: timeout lebih longgar + header browser.
 * Host kconaz (Film Indonesia) sering lambat / menolak UA Dalvik default Glide.
 */
object PosterGlide {
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .addInterceptor { chain ->
                    val req = chain.request()
                    chain.proceed(
                        req.newBuilder()
                            .header("User-Agent", BROWSER_UA)
                            .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                            .header("Referer", refererFor(req.url.host))
                            .build(),
                    )
                }
                .build()
            Glide.get(context.applicationContext).registry.replace(
                GlideUrl::class.java,
                InputStream::class.java,
                OkHttpUrlLoader.Factory(client),
            )
            installed = true
        }
    }

    fun model(url: String): GlideUrl =
        GlideUrl(
            url,
            LazyHeaders.Builder()
                .addHeader("User-Agent", BROWSER_UA)
                .addHeader("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                .addHeader("Referer", refererForHostOf(url))
                .build(),
        )

    /** Cadangan bila host asal gagal (WordPress Photon). */
    fun fallbackModels(url: String): List<Any> {
        val out = mutableListOf<Any>(model(url))
        photonUrl(url)?.let { out.add(model(it)) }
        return out
    }

    private fun refererForHostOf(url: String): String =
        try {
            refererFor(java.net.URI(url).host.orEmpty())
        } catch (_: Exception) {
            "https://gitgitmiko.my.id/"
        }

    private fun refererFor(host: String): String {
        val h = host.lowercase()
        return when {
            h.contains("kconaz") -> "https://kconaz.com/"
            h.contains("cccscholarships") -> "https://cccscholarships.org/"
            h.isNotBlank() -> "https://$h/"
            else -> "https://gitgitmiko.my.id/"
        }
    }

    private fun photonUrl(url: String): String? {
        return try {
            val uri = java.net.URI(url)
            val host = uri.host?.lowercase() ?: return null
            if (host.contains("kconaz") || host.contains("cccscholarships")) {
                val path = uri.rawPath ?: return null
                "https://i0.wp.com/$host$path"
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
