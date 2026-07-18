package com.webunime.tv

import android.app.Application
import com.webunime.tv.data.CatalogRepository

class WebunimeApp : Application() {
    lateinit var catalogRepository: CatalogRepository
        private set

    override fun onCreate() {
        super.onCreate()
        catalogRepository = CatalogRepository(this)
    }
}
