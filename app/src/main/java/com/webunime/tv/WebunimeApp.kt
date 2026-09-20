package com.webunime.tv

import android.app.Application
import com.webunime.tv.data.BgmController
import com.webunime.tv.data.CatalogRepository
import com.webunime.tv.data.LibraryRepository
import com.webunime.tv.data.WatchSessionStore
import com.webunime.tv.ui.PosterGlide

class WebunimeApp : Application() {
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
        libraryRepository = LibraryRepository(this)
        catalogRepository = CatalogRepository(this)
        watchSessions = WatchSessionStore(this)
        bgm = BgmController(this).also { it.start() }
    }
}
