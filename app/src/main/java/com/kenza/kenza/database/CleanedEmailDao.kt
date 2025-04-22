package com.kenza.kenza.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.kenza.kenza.database.models.CleanedEmail
import kotlinx.coroutines.flow.Flow

@Dao
interface CleanedEmailDao {
    @Insert
    suspend fun insert(cleanedEmail: CleanedEmail)

    @Query("SELECT * FROM cleaned_emails ORDER BY actionTimestamp DESC")
    fun getAllCleanedEmails(): Flow<List<CleanedEmail>>

    @Query("SELECT * FROM cleaned_emails WHERE actionTaken = :action AND actionTimestamp < :timestamp")
    suspend fun getEmailsOlderThan(action: String, timestamp: Long): List<CleanedEmail>

    @Query("SELECT * FROM cleaned_emails WHERE messageId = :messageId LIMIT 1")
    suspend fun findByMessageId(messageId: String): CleanedEmail?

    @Delete
    suspend fun delete(cleanedEmail: CleanedEmail)

    @Delete
    suspend fun delete(cleanedEmails: List<CleanedEmail>)

    // TODO: Add other necessary queries (e.g., update action)
}