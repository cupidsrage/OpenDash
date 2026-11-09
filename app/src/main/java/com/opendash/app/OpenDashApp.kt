package com.opendash.app

import android.app.Application

class OpenDashApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UpdateWorker.schedule(this)
    }
}
