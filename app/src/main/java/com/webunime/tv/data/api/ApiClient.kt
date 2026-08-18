package com.webunime.tv.data.api

import com.webunime.tv.BuildConfig
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiClient(
    private val session: SessionStore,
    private val cookieJar: PrefsCookieJar,
) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val builder = original.newBuilder()
                .header("User-Agent", "${ApiConfig.USER_AGENT}/${BuildConfig.VERSION_NAME}")
                .header("Accept", "application/json")
            if (original.header("Authorization").isNullOrBlank()) {
                session.sid()?.let { builder.header("Authorization", "Bearer $it") }
            }
            if (original.body != null && original.header("Content-Type").isNullOrBlank()) {
                builder.header("Content-Type", "application/json")
            }
            val response = chain.proceed(builder.build())
            captureSid(response.request.url, response.headers("Set-Cookie"))
            if (response.code == 401 && !original.url.encodedPath.startsWith("/api/auth/login")) {
                session.clear()
            }
            response
        }
        .build()

    fun url(path: String): HttpUrl {
        val trimmed = if (path.startsWith("http")) path else "${ApiConfig.BASE_URL}$path"
        return trimmed.toHttpUrl()
    }

    fun get(path: String): String = execute(
        Request.Builder().url(url(path)).get().build(),
    )

    fun post(path: String, json: JSONObject? = null): String = execute(
        Request.Builder()
            .url(url(path))
            .post((json?.toString() ?: "{}").toRequestBody(JSON))
            .build(),
    )

    fun patch(path: String, json: JSONObject): String = execute(
        Request.Builder()
            .url(url(path))
            .patch(json.toString().toRequestBody(JSON))
            .build(),
    )

    fun delete(path: String): String = execute(
        Request.Builder()
            .url(url(path))
            .delete()
            .build(),
    )

    fun execute(request: Request): String {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 401 && !request.url.encodedPath.startsWith("/api/auth/login")) {
                throw UnauthorizedException()
            }
            if (!response.isSuccessful) {
                val err = runCatching { JSONObject(body).optString("error") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "HTTP ${response.code}"
                throw ApiException(response.code, err)
            }
            return body
        }
    }

    fun captureSid(url: HttpUrl, setCookies: List<String>) {
        for (raw in setCookies) {
            val cookie = Cookie.parse(url, raw) ?: continue
            if (cookie.name.equals(ApiConfig.COOKIE_SID, ignoreCase = true) && cookie.value.length == 64) {
                session.saveSid(cookie.value)
            }
        }
    }

    fun clearCookies() {
        cookieJar.clear()
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
