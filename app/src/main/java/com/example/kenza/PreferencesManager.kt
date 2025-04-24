package com.example.kenza

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("KenzaPrefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MAX_EMAILS = "max_emails"
        private const val KEY_LAST_CLEAN_TIME = "last_clean_time"
        private const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
        private const val KEY_SCHEDULE_HOUR = "schedule_hour"
        private const val KEY_SCHEDULE_MINUTE = "schedule_minute"
        private const val KEY_EXCLUSION_SENDERS = "exclusion_senders"
        private const val DEFAULT_MAX_EMAILS = 100
    }

    fun saveApiKey(apiKey: String) {
        sharedPreferences.edit().putString(KEY_API_KEY, apiKey).apply()
    }

    fun getApiKey(): String? {
        return sharedPreferences.getString(KEY_API_KEY, null)
    }

    fun saveMaxEmailsToProcess(maxEmails: Int) {
        sharedPreferences.edit().putInt(KEY_MAX_EMAILS, maxEmails).apply()
    }

    fun getMaxEmailsToProcess(): Int {
        return sharedPreferences.getInt(KEY_MAX_EMAILS, DEFAULT_MAX_EMAILS)
    }

    fun saveLastCleanTime() {
        sharedPreferences.edit().putLong(KEY_LAST_CLEAN_TIME, System.currentTimeMillis()).apply()
    }

    fun getLastCleanTime(): Long {
        return sharedPreferences.getLong(KEY_LAST_CLEAN_TIME, 0)
    }

    // --- New methods for scheduling ---
    fun setScheduleEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_SCHEDULE_ENABLED, enabled).apply()
    }

    fun isScheduleEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_SCHEDULE_ENABLED, false)
    }

    fun setScheduleTime(hour: Int, minute: Int) {
        sharedPreferences.edit()
            .putInt(KEY_SCHEDULE_HOUR, hour)
            .putInt(KEY_SCHEDULE_MINUTE, minute)
            .apply()
    }

    fun getScheduleTime(): Pair<Int, Int> {
        val hour = sharedPreferences.getInt(KEY_SCHEDULE_HOUR, -1) // Default -1 indicates not set
        val minute = sharedPreferences.getInt(KEY_SCHEDULE_MINUTE, -1)
        return Pair(hour, minute)
    }

    // --- New methods for exclusion rules ---
    fun saveExclusionSenders(senders: Set<String>) {
        sharedPreferences.edit().putStringSet(KEY_EXCLUSION_SENDERS, senders).apply()
    }

    fun getExclusionSenders(): Set<String> {
        return sharedPreferences.getStringSet(KEY_EXCLUSION_SENDERS, emptySet()) ?: emptySet()
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