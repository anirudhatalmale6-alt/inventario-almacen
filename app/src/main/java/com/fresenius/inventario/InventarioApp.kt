package com.fresenius.inventario

import android.app.Application

class InventarioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: InventarioApp
            private set
    }
}
