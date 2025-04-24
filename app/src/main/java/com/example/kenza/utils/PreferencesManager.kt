package com.example.kenza.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages application preferences and settings
 */
class PreferencesManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFERENCES_NAME, Context.MODE_PRIVATE
    )
    
    companion object {
        private const val PREFERENCES_NAME = "kenza_preferences"
        private const val KEY_LAST_CLEAN_TIME = "last_clean_time"
        private const val KEY_MAX_EMAILS = "max_emails_to_process"
        private const val KEY_FIRST_RUN = "first_run_completed"
        private const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
        private const val KEY_SCHEDULE_HOUR = "schedule_hour"
        private const val KEY_SCHEDULE_MINUTE = "schedule_minute"
        private const val KEY_EXCLUSION_SENDERS = "exclusion_senders"
        
        // Default values
        private const val DEFAULT_MAX_EMAILS = 200 // Increased from 100 to 200
    }
    
    /**
     * Save the timestamp of when the email cleaning was last performed
     * @param timestamp Optional timestamp to save, defaults to current time
     */
    fun saveLastCleanTime(timestamp: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_CLEAN_TIME, timestamp).apply()
        
        // Mark first run as completed
        if (isFirstRun()) {
            prefs.edit().putBoolean(KEY_FIRST_RUN, false).apply()
        }
    }
    
    /**
     * Get the timestamp of when email cleaning was last performed
     * @return Timestamp in milliseconds or 0 if never performed
     */
    fun getLastCleanTime(): Long {
        return prefs.getLong(KEY_LAST_CLEAN_TIME, 0)
    }
    
    /**
     * Check if this is the first time the app is running the cleaning operation
     * @return True if this is the first run, false otherwise
     */
    fun isFirstRun(): Boolean {
        return !prefs.contains(KEY_FIRST_RUN) || prefs.getBoolean(KEY_FIRST_RUN, true)
    }
    
    /**
     * Get the maximum number of emails to process in a single cleaning operation
     * @return The maximum number of emails to process
     */
    fun getMaxEmailsToProcess(): Int {
        return prefs.getInt(KEY_MAX_EMAILS, DEFAULT_MAX_EMAILS)
    }
    
    /**
     * Set the maximum number of emails to process in a single cleaning operation
     * @param max The maximum number of emails to process
     */
    fun setMaxEmailsToProcess(max: Int) {
        prefs.edit().putInt(KEY_MAX_EMAILS, max).apply()
    }

    fun setScheduleEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SCHEDULE_ENABLED, enabled).apply()
    }

    fun isScheduleEnabled(): Boolean {
        return prefs.getBoolean(KEY_SCHEDULE_ENABLED, false)
    }

    fun setScheduleTime(hour: Int, minute: Int) {
        prefs.edit()
            .putInt(KEY_SCHEDULE_HOUR, hour)
            .putInt(KEY_SCHEDULE_MINUTE, minute)
            .apply()
    }

    fun getScheduleTime(): Pair<Int, Int> {
        val hour = prefs.getInt(KEY_SCHEDULE_HOUR, -1) // Default -1 indicates not set
        val minute = prefs.getInt(KEY_SCHEDULE_MINUTE, -1)
        return Pair(hour, minute)
    }

    fun saveExclusionSenders(senders: Set<String>) {
        prefs.edit().putStringSet(KEY_EXCLUSION_SENDERS, senders).apply()
    }

    fun getExclusionSenders(): Set<String> {
        return prefs.getStringSet(KEY_EXCLUSION_SENDERS, emptySet()) ?: emptySet()
    }

    fun addExclusionSender(sender: String) {
        val current = getExclusionSenders().toMutableSet()
        current.add(sender.lowercase().trim()) // Store lowercase for case-insensitive matching
        saveExclusionSenders(current)
    }

    fun removeExclusionSender(sender: String) {
        val current = getExclusionSenders().toMutableSet()
        current.remove(sender.lowercase().trim())
        saveExclusionSenders(current)
    }
}