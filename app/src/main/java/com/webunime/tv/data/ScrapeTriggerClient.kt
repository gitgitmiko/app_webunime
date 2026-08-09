package com.webunime.tv.data

import com.webunime.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ScrapeTriggerResult(
    val ok: Boolean,
    val httpCode: Int,
    val errorCode: String? = null,
    val message: String,
)

/**
 * Memicu workflow scraper lewat proxy Vercel (PAT tidak ada di APK).
 */
class ScrapeTriggerClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun startScrape(): ScrapeTriggerResult = withContext(Dispatchers.IO) {
        val url = BuildConfig.SCRAPE_PROXY_URL.trim()
        if (url.isBlank()) {
            return@withContext ScrapeTriggerResult(
                ok = false,
                httpCode = 0,
                errorCode = "not_configured",
                message = "Proxy scrape belum dikonfigurasi",
            )
        }
        runCatching {
            val request = Request.Builder()
                .url(url)
                .post(ByteArray(0).toRequestBody(null))
                .header("User-Agent", "WEBUNIME-TV/${BuildConfig.VERSION_NAME}")
                .header("X-Webunime-Key", BuildConfig.SCRAPE_APP_KEY)
                .header("Content-Type", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(body) }.getOrNull()
                val message = json?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: body.takeIf { it.isNotBlank() }
                    ?: "HTTP ${response.code}"
                val errorCode = json?.optString("error")?.takeIf { it.isNotBlank() }
                ScrapeTriggerResult(
                    ok = response.isSuccessful || response.code == 202,
                    httpCode = response.code,
                    errorCode = errorCode,
                    message = message,
                )
            }
        }.getOrElse {
            ScrapeTriggerResult(
                ok = false,
                httpCode = 0,
                errorCode = "network",
                message = it.message ?: "Gagal menghubungi proxy",
            )
        }
    }
}
