package com.example.kenza

import android.app.Application
import com.example.kenza.database.AppDatabase
import com.example.kenza.database.repository.CleanedEmailRepository
import com.example.kenza.utils.PreferencesManager
import com.microsoft.identity.client.ISingleAccountPublicClientApplication

class MainApplication : Application() {
    var msalInstance: ISingleAccountPublicClientApplication? = null
    lateinit var database: AppDatabase
    lateinit var repository: CleanedEmailRepository
    lateinit var preferencesManager: PreferencesManager

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
        repository = CleanedEmailRepository(database.cleanedEmailDao())
        preferencesManager = PreferencesManager(this)
    }
}