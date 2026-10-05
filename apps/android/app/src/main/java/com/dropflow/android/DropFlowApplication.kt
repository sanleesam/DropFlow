package com.dropflow.android

import android.app.Application
import com.dropflow.android.core.network.transfer.TransferEngine
import com.dropflow.android.storage.UserPreferencesRepository

class DropFlowApplication : Application() {

    lateinit var userPreferencesRepository: UserPreferencesRepository
        private set

    lateinit var transferEngine: TransferEngine
        private set

    override fun onCreate() {
        super.onCreate()
        userPreferencesRepository = UserPreferencesRepository(this)
        transferEngine = TransferEngine(this)
        transferEngine.startServer()
    }

    override fun onTerminate() {
        super.onTerminate()
        transferEngine.stopServer()
    }
}
