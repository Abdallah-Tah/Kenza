package com.example.kenza.database.repository

import androidx.annotation.WorkerThread
import com.example.kenza.database.dao.CleanedEmailDao
import com.example.kenza.database.models.CleanedEmail
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

class CleanedEmailRepository(private val cleanedEmailDao: CleanedEmailDao) {
    val allCleanedEmails: Flow<List<CleanedEmail>> = cleanedEmailDao.getAllCleanedEmails()

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

    @WorkerThread
    suspend fun getEmailsToDelete(action: String = "moved_to_trash"): List<CleanedEmail> {
        val thirtyDaysInMillis = TimeUnit.DAYS.toMillis(30)
        val cutoffTimestamp = System.currentTimeMillis() - thirtyDaysInMillis
        return cleanedEmailDao.getEmailsOlderThan(action, cutoffTimestamp)
    }
}