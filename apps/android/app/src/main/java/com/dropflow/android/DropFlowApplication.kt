package com.dropflow.android

import android.app.Application
import com.dropflow.android.core.network.transfer.TransferEngine
import com.dropflow.android.storage.UserPreferencesRepository
import com.dropflow.android.storage.TransferHistoryRepository

class DropFlowApplication : Application() {

    lateinit var userPreferencesRepository: UserPreferencesRepository
        private set

    lateinit var transferEngine: TransferEngine
        private set
    lateinit var transferHistory: TransferHistoryRepository
        private set

    override fun onCreate() {
        super.onCreate()
        userPreferencesRepository = UserPreferencesRepository(this)
        transferHistory = TransferHistoryRepository(this)
        transferEngine = TransferEngine(this, transferHistory)
        transferEngine.startServer()
    }

    override fun onTerminate() {
        super.onTerminate()
        transferEngine.stopServer()
    }
}
