package com.webunime.tv.data

import com.webunime.tv.data.api.ApiClient
import com.webunime.tv.data.api.ApiException
import com.webunime.tv.data.api.AuthUser
import com.webunime.tv.data.api.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AuthRepository(
    private val api: ApiClient,
    private val session: SessionStore,
) {
    fun currentUser(): AuthUser? = session.user

    fun isLoggedIn(): Boolean = session.hasSession() || session.user != null

    suspend fun restoreSession(): AuthUser? = withContext(Dispatchers.IO) {
        runCatching { fetchMe() }.getOrNull()
    }

    suspend fun login(login: String, password: String): AuthUser = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("login", login.trim())
            .put("password", password)
        val raw = api.post("/api/auth/login", body)
        val user = parseUser(JSONObject(raw).optJSONObject("user"))
            ?: throw ApiException(401, "Email/username atau password salah.")
        session.saveUser(user)
        runCatching { fetchMe() }.getOrDefault(user)
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        runCatching { api.post("/api/auth/logout", JSONObject()) }
        session.clear()
        api.clearCookies()
    }

    suspend fun fetchMe(): AuthUser = withContext(Dispatchers.IO) {
        val raw = api.get("/api/auth/me")
        val user = parseUser(JSONObject(raw).optJSONObject("user"))
            ?: throw ApiException(401, "Belum masuk.")
        session.saveUser(user)
        user
    }

    suspend fun updateProfile(displayName: String): AuthUser = withContext(Dispatchers.IO) {
        val raw = api.patch(
            "/api/auth/profile",
            JSONObject().put("displayName", displayName.trim()),
        )
        val user = parseUser(JSONObject(raw).optJSONObject("user"))
            ?: throw ApiException(500, "Gagal memperbarui profil.")
        session.saveUser(user)
        user
    }

    suspend fun pingCatalog(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            api.get("/api/v1")
            true
        }.getOrDefault(false)
    }

    private fun parseUser(o: JSONObject?): AuthUser? {
        if (o == null) return null
        val id = o.optInt("id")
        if (id <= 0) return null
        return AuthUser(
            id = id,
            email = o.optString("email").takeIf { it.isNotBlank() },
            username = o.optString("username").takeIf { it.isNotBlank() },
            displayName = o.optString("displayName").takeIf { it.isNotBlank() },
            createdAt = o.optString("createdAt").takeIf { it.isNotBlank() },
            isActive = o.optBoolean("isActive", true),
            canInvite = o.optBoolean("canInvite", false),
            isAdmin = o.optBoolean("isAdmin", false),
        )
    }
}
