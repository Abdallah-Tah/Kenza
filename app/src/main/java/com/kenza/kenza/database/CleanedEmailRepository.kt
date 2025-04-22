package com.kenza.kenza.database

import androidx.annotation.WorkerThread
import com.kenza.kenza.database.models.CleanedEmail
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

class CleanedEmailRepository(private val cleanedEmailDao: CleanedEmailDao) {

    // Room executes Flow queries on a background thread.
    val allCleanedEmails: Flow<List<CleanedEmail>> = cleanedEmailDao.getAllCleanedEmails()

    // By default Room runs suspend queries off the main thread, therefore, we don't need to
    // implement anything else to ensure we're not doing long running database work
    // off the main thread.
    @WorkerThread
    suspend fun insert(cleanedEmail: CleanedEmail) {
        cleanedEmailDao.insert(cleanedEmail)
    }

    @WorkerThread
    suspend fun delete(cleanedEmail: CleanedEmail) {
        cleanedEmailDao.delete(cleanedEmail)
    }

    @WorkerThread
    suspend fun delete(cleanedEmails: List<CleanedEmail>) {
        cleanedEmailDao.delete(cleanedEmails)
    }

    @WorkerThread
    suspend fun findByMessageId(messageId: String): CleanedEmail? {
        return cleanedEmailDao.findByMessageId(messageId)
    }

    // Function to find emails older than 30 days (example)
    @WorkerThread
    suspend fun getEmailsToDelete(action: String = "moved_to_trash"): List<CleanedEmail> {
        val thirtyDaysInMillis = TimeUnit.DAYS.toMillis(30)
        val cutoffTimestamp = System.currentTimeMillis() - thirtyDaysInMillis
        return cleanedEmailDao.getEmailsOlderThan(action, cutoffTimestamp)
    }

    // TODO: Add methods for recovering emails (e.g., update action, remove from DB)
    // TODO: Add method for permanent deletion logic (combining getEmailsToDelete and delete)
}