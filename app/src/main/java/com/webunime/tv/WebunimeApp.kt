package com.webunime.tv

import android.app.Application
import com.webunime.tv.data.AuthRepository
import com.webunime.tv.data.BgmController
import com.webunime.tv.data.CatalogRepository
import com.webunime.tv.data.LibraryRepository
import com.webunime.tv.data.WatchSessionStore
import com.webunime.tv.data.api.ApiClient
import com.webunime.tv.data.api.PrefsCookieJar
import com.webunime.tv.data.api.SessionStore
import com.webunime.tv.ui.PosterGlide

class WebunimeApp : Application() {
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
    lateinit var bgm: BgmController
        private set

    override fun onCreate() {
        super.onCreate()
        PosterGlide.install(this)
        val session = SessionStore(this)
        apiClient = ApiClient(session, PrefsCookieJar(this))
        authRepository = AuthRepository(apiClient, session)
        libraryRepository = LibraryRepository(this, apiClient, authRepository)
        catalogRepository = CatalogRepository(this)
        watchSessions = WatchSessionStore(this)
        bgm = BgmController(this).also { it.start() }
    }
}
