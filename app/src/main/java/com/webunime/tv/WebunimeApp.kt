package com.webunime.tv

import android.app.Application
import com.webunime.tv.data.AuthRepository
import com.webunime.tv.data.CatalogRepository
import com.webunime.tv.data.LibraryRepository
import com.webunime.tv.data.WatchSessionStore
import com.webunime.tv.data.api.ApiClient
import com.webunime.tv.data.api.PrefsCookieJar
import com.webunime.tv.data.api.SessionStore

class WebunimeApp : Application() {
    lateinit var sessionStore: SessionStore
        private set
    lateinit var apiClient: ApiClient
        private set
    lateinit var authRepository: AuthRepository
        private set
    lateinit var libraryRepository: LibraryRepository
        private set
    lateinit var catalogRepository: CatalogRepository
        private set
    lateinit var watchSessions: WatchSessionStore
        private set

    override fun onCreate() {
        super.onCreate()
        sessionStore = SessionStore(this)
        apiClient = ApiClient(sessionStore, PrefsCookieJar(this))
        authRepository = AuthRepository(apiClient, sessionStore)
        libraryRepository = LibraryRepository(apiClient)
        catalogRepository = CatalogRepository(this, apiClient)
        watchSessions = WatchSessionStore(this)
    }
}
