package com.example.kenza

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager
import com.example.kenza.database.AppDatabase
import com.example.kenza.database.repository.CleanedEmailRepository
import com.microsoft.identity.client.*
import com.microsoft.identity.client.IPublicClientApplication.ISingleAccountApplicationCreatedListener
import com.microsoft.identity.client.exception.MsalException
import com.example.kenza.utils.PreferencesManager

class MainApplication : Application() {
    lateinit var preferencesManager: PreferencesManager
        private set

    private lateinit var database: AppDatabase
    lateinit var repository: CleanedEmailRepository
        private set
        
    var msalInstance: ISingleAccountPublicClientApplication? = null
    
    companion object {
        private const val TAG = "MainApplication"
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize preferences manager
        preferencesManager = PreferencesManager(this)
        
        // Initialize database and repository
        database = AppDatabase.getDatabase(this)
        repository = CleanedEmailRepository(database.cleanedEmailDao())
        
        // Initialize MSAL
        initializeMsal()
        
        // Configure WorkManager for background tasks
        val workManagerConfig = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()
        WorkManager.initialize(this, workManagerConfig)
        
        // Check for scheduled cleaning
        checkAndRestoreScheduledCleaning()
        
        Log.d(TAG, "Application initialized successfully")
    }
    
    private fun initializeMsal() {
        val config = PublicClientApplication.createSingleAccountPublicClientApplication(
            this,
            R.raw.auth_config_single_account,
            object : ISingleAccountApplicationCreatedListener {
                override fun onCreated(application: ISingleAccountPublicClientApplication) {
                    msalInstance = application
                    Log.d(TAG, "MSAL initialized successfully")
                }
                
                override fun onError(exception: MsalException) {
                    Log.e(TAG, "Failed to initialize MSAL: ${exception.message}", exception)
                    msalInstance = null
                }
            }
        )
    }
    
    private fun checkAndRestoreScheduledCleaning() {
        try {
            // If schedule was enabled before app restart, make sure it's still set up
            if (preferencesManager.isScheduleEnabled()) {
                val (hour, minute) = preferencesManager.getScheduleTime()
                if (hour != -1 && minute != -1) {
                    Log.d(TAG, "Restoring scheduled cleaning at $hour:$minute")
                    
                    // We'll recreate the worker in the SettingsActivity when the user visits it
                    // or we could implement the scheduling here directly
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring scheduled cleaning: ${e.message}", e)
        }
    }
}