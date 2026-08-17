package com.webunime.tv.data.api

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

class PrefsCookieJar(context: Context) : CookieJar {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val kept = loadAll()
                .filter { it.expiresAt > now }
                .filterNot { existing ->
                    cookies.any { it.name == existing.name && it.domain == existing.domain }
                }
            saveAll(kept + cookies)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val valid = loadAll().filter { it.expiresAt > now && it.matches(url) }
            saveAll(valid)
            return valid
        }
    }

    fun clear() {
        synchronized(lock) {
            prefs.edit().remove(KEY).apply()
        }
    }

    private fun loadAll(): List<Cookie> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val cookie = cookieFromJson(o) ?: continue
                    add(cookie)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveAll(cookies: List<Cookie>) {
        val arr = JSONArray()
        cookies.forEach { arr.put(jsonFromCookie(it)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun jsonFromCookie(cookie: Cookie): JSONObject =
        JSONObject()
            .put("name", cookie.name)
            .put("value", cookie.value)
            .put("domain", cookie.domain)
            .put("path", cookie.path)
            .put("expiresAt", cookie.expiresAt)
            .put("secure", cookie.secure)
            .put("httpOnly", cookie.httpOnly)
            .put("hostOnly", cookie.hostOnly)

    private fun cookieFromJson(o: JSONObject): Cookie? {
        val name = o.optString("name")
        val value = o.optString("value")
        val domain = o.optString("domain")
        if (name.isBlank() || value.isBlank() || domain.isBlank()) return null
        val builder = Cookie.Builder()
            .name(name)
            .value(value)
            .path(o.optString("path").ifBlank { "/" })
            .expiresAt(o.optLong("expiresAt", Long.MAX_VALUE / 2))
        if (o.optBoolean("hostOnly", true)) builder.hostOnlyDomain(domain) else builder.domain(domain)
        if (o.optBoolean("secure")) builder.secure()
        if (o.optBoolean("httpOnly")) builder.httpOnly()
        return runCatching { builder.build() }.getOrNull()
    }

    companion object {
        private const val PREFS = "webunime_cookies"
        private const val KEY = "cookies_v1"
    }
}
