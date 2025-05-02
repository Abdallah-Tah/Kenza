package com.example.kenza

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

class PreferencesManager(context: Context) {
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("KenzaPrefs", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "PreferencesManager"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MAX_EMAILS = "max_emails"
        private const val KEY_LAST_CLEAN_TIME = "last_clean_time"
        private const val KEY_LAST_PROCESSED_EMAIL_DATE = "last_processed_email_date"
        private const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
        private const val KEY_SCHEDULE_HOUR = "schedule_hour"
        private const val KEY_SCHEDULE_MINUTE = "schedule_minute"
        private const val KEY_EXCLUSION_SENDERS = "exclusion_senders"
        private const val KEY_PROCESSING_IN_PROGRESS = "processing_in_progress"
        private const val KEY_PROCESSING_BATCH_ID = "processing_batch_id"
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

    /**
     * Save the last clean time in a safer way.
     * This method now uses commit() instead of apply() to ensure the write happens immediately
     * to avoid data loss issues if the app crashes.
     */
    fun saveLastCleanTime(timestamp: Long = System.currentTimeMillis()): Boolean {
        Log.d(TAG, "Updating last clean time to: ${formatTimestamp(timestamp)}")
        return sharedPreferences.edit().putLong(KEY_LAST_CLEAN_TIME, timestamp).commit()
    }

    fun getLastCleanTime(): Long {
        val time = sharedPreferences.getLong(KEY_LAST_CLEAN_TIME, 0)
        Log.d(TAG, "Retrieved last clean time: ${formatTimestamp(time)}")
        return time
    }

    /**
     * Saves the received datetime of the last processed email.
     * This provides a more accurate way to track which emails have been processed.
     */
    fun saveLastProcessedEmailDate(isoDateString: String): Boolean {
        Log.d(TAG, "Saving last processed email date: $isoDateString")
        return sharedPreferences.edit().putString(KEY_LAST_PROCESSED_EMAIL_DATE, isoDateString).commit()
    }

    /**
     * Gets the received datetime of the last processed email in ISO format.
     * Returns null if no emails have been processed yet.
     */
    fun getLastProcessedEmailDate(): String? {
        val date = sharedPreferences.getString(KEY_LAST_PROCESSED_EMAIL_DATE, null)
        Log.d(TAG, "Retrieved last processed email date: $date")
        return date
    }

    /**
     * Sets a flag indicating email processing is in progress.
     * This helps recover from crashes by checking if a previous processing run was interrupted.
     */
    fun setProcessingInProgress(inProgress: Boolean): Boolean {
        return sharedPreferences.edit().putBoolean(KEY_PROCESSING_IN_PROGRESS, inProgress).commit()
    }

    /**
     * Checks if processing was previously in progress (and might have been interrupted).
     */
    fun isProcessingInProgress(): Boolean {
        return sharedPreferences.getBoolean(KEY_PROCESSING_IN_PROGRESS, false)
    }

    /**
     * Saves a batch identifier when processing a batch of emails.
     * This can be used for recovery if processing is interrupted.
     */
    fun saveProcessingBatchId(batchId: String?): Boolean {
        return sharedPreferences.edit().putString(KEY_PROCESSING_BATCH_ID, batchId).commit()
    }

    /**
     * Gets the last batch ID that was being processed.
     */
    fun getProcessingBatchId(): String? {
        return sharedPreferences.getString(KEY_PROCESSING_BATCH_ID, null)
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

    /**
     * Helper function to format timestamps for logging
     */
    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp == 0L) return "0 (never)"
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            sdf.format(java.util.Date(timestamp))
        } catch (e: Exception) {
            timestamp.toString()
        }
    }
}