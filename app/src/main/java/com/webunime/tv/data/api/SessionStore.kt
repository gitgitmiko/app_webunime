package com.webunime.tv.data.api

import android.content.Context
import org.json.JSONObject

class SessionStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    var user: AuthUser? = loadUser()
        private set

    fun sid(): String? = prefs.getString(KEY_SID, null)?.takeIf { it.length == 64 }

    fun hasSession(): Boolean = !sid().isNullOrBlank()

    fun saveSid(sid: String) {
        prefs.edit().putString(KEY_SID, sid.lowercase()).apply()
    }

    fun saveUser(user: AuthUser) {
        this.user = user
        val json = JSONObject()
            .put("id", user.id)
            .put("email", user.email)
            .put("username", user.username)
            .put("displayName", user.displayName)
            .put("createdAt", user.createdAt)
            .put("isActive", user.isActive)
            .put("canInvite", user.canInvite)
            .put("isAdmin", user.isAdmin)
        prefs.edit().putString(KEY_USER, json.toString()).apply()
    }

    fun updateDisplayName(name: String) {
        val current = user ?: return
        saveUser(current.copy(displayName = name))
    }

    fun clear() {
        user = null
        prefs.edit().clear().apply()
    }

    private fun loadUser(): AuthUser? {
        val raw = prefs.getString(KEY_USER, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            AuthUser(
                id = o.optInt("id"),
                email = o.optString("email").takeIf { it.isNotBlank() },
                username = o.optString("username").takeIf { it.isNotBlank() },
                displayName = o.optString("displayName").takeIf { it.isNotBlank() },
                createdAt = o.optString("createdAt").takeIf { it.isNotBlank() },
                isActive = o.optBoolean("isActive", true),
                canInvite = o.optBoolean("canInvite", false),
                isAdmin = o.optBoolean("isAdmin", false),
            )
        }.getOrNull()
    }

    companion object {
        private const val PREFS = "webunime_session"
        private const val KEY_SID = "sid"
        private const val KEY_USER = "user"
    }
}
