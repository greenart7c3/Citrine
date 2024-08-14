package com.greenart7c3.citrine

import android.app.Application
import com.greenart7c3.citrine.service.LocalPreferences

class Citrine : Application() {
    override fun onCreate() {
        super.onCreate()

        instance = this
        LocalPreferences.loadSettingsFromEncryptedStorage(this)
    }

    companion object {
        @Volatile
        private var instance: Citrine? = null

        fun getInstance(): Citrine =
            instance ?: synchronized(this) {
                instance ?: Citrine().also { instance = it }
            }
    }
}
