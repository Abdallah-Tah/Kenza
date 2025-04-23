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
    suspend fun deleteMultiple(cleanedEmails: List<CleanedEmail>) {
        cleanedEmailDao.delete(cleanedEmails)
    }

    @WorkerThread
    suspend fun findByMessageId(messageId: String): CleanedEmail? {
        return cleanedEmailDao.findByMessageId(messageId)
    }

    @WorkerThread
    suspend fun getEmailsToDelete(action: String = "moved"): List<CleanedEmail> {
        val thirtyDaysInMillis = TimeUnit.DAYS.toMillis(30)
        val cutoffTimestamp = System.currentTimeMillis() - thirtyDaysInMillis
        return cleanedEmailDao.getEmailsOlderThan(action, cutoffTimestamp)
    }
    
    @WorkerThread
    fun getAllCleanedEmailsSync(): List<CleanedEmail> {
        return cleanedEmailDao.getAllCleanedEmailsSync()
    }
    
    @WorkerThread
    fun getEmailsByActionSync(action: String): List<CleanedEmail> {
        return cleanedEmailDao.getEmailsByActionSync(action)
    }
    
    @WorkerThread
    suspend fun purgeOldEmails(days: Int = 30): Int {
        val cutoffTimestamp = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
        return cleanedEmailDao.deleteOlderThan(cutoffTimestamp)
    }
}