package com.mohit.dimplex

import android.app.Application
import com.mohit.dimplex.ble.HeaterController
import com.mohit.dimplex.data.AppDb
import com.mohit.dimplex.data.HeaterStore
import com.mohit.dimplex.data.Settings

class DimplexApp : Application() {
    lateinit var db: AppDb
        private set
    lateinit var heaters: HeaterStore
        private set
    lateinit var settings: Settings
        private set
    lateinit var controller: HeaterController
        private set

    override fun onCreate() {
        super.onCreate()
        db = AppDb.build(this)
        heaters = HeaterStore(this)
        settings = Settings(this)
        controller = HeaterController(this, db, heaters, settings)
    }
}

val android.content.Context.app: DimplexApp get() = applicationContext as DimplexApp
