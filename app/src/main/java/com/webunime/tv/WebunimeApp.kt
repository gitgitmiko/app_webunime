package com.webunime.tv

import android.app.Application
import com.webunime.tv.data.CatalogRepository
import com.webunime.tv.data.WatchSessionStore

class WebunimeApp : Application() {
    lateinit var catalogRepository: CatalogRepository
        private set

    lateinit var watchSessions: WatchSessionStore
        private set

    override fun onCreate() {
        super.onCreate()
        catalogRepository = CatalogRepository(this)
        watchSessions = WatchSessionStore(this)
    }
}
