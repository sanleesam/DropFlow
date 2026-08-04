package com.dropflow.android

import android.app.Application
import com.dropflow.android.storage.UserPreferencesRepository

class DropFlowApplication : Application() {

    lateinit var userPreferencesRepository: UserPreferencesRepository
        private set

    override fun onCreate() {
        super.onCreate()
        userPreferencesRepository = UserPreferencesRepository(this)
    }
}
