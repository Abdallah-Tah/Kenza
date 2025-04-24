package com.example.kenza.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.Date

/**
 * Manager class for handling app preferences
 */
class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * Save the timestamp of the last email cleaning operation
     */
    fun saveLastCleanTime(timestamp: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_CLEAN_TIME, timestamp).apply()
    }

    /**
     * Get the timestamp of the last email cleaning operation
     * @return timestamp in milliseconds, or 0 if never cleaned before
     */
    fun getLastCleanTime(): Long {
        return prefs.getLong(KEY_LAST_CLEAN_TIME, 0)
    }

    /**
     * Check if this is the first time the app has run a cleaning operation
     */
    fun isFirstRun(): Boolean {
        return getLastCleanTime() == 0L
    }

    /**
     * Set the maximum number of emails to process in a single cleaning operation
     */
    fun setMaxEmailsToProcess(count: Int) {
        prefs.edit().putInt(KEY_MAX_EMAILS, count).apply()
    }

    /**
     * Get the maximum number of emails to process in a single cleaning operation
     */
    fun getMaxEmailsToProcess(): Int {
        return prefs.getInt(KEY_MAX_EMAILS, DEFAULT_MAX_EMAILS)
    }

    companion object {
        private const val PREFERENCES_NAME = "kenza_preferences"
        private const val KEY_LAST_CLEAN_TIME = "last_clean_time"
        private const val KEY_MAX_EMAILS = "max_emails_to_process"
        private const val DEFAULT_MAX_EMAILS = 100 // Default max emails to process
    }
}