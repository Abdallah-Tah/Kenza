package com.example.kenza.database.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cleaned_emails")
data class CleanedEmail(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: String, // Unique ID from the email server
    val subject: String?,
    val sender: String?,
    val receivedDate: Long, // Store as timestamp
    val actionTaken: String, // e.g., "deleted", "moved", "unsubscribed"
    val actionTimestamp: Long = System.currentTimeMillis(), // Timestamp when action was taken
    val originalFolder: String? // Where it was before cleaning
)