package com.example.kenza.network

import org.json.JSONArray
import org.json.JSONObject

/**
 * Service interface for Microsoft Graph API operations
 */
interface GraphApiService {
    /**
     * Fetches email messages with pagination support
     * 
     * @param baseUrl Graph API URL for messages endpoint
     * @param filterQuery Optional filter query string (starting with &)
     * @param batchSize Number of items per page
     * @param maxEmails Maximum total emails to fetch
     * @param initialSkipToken Initial skip token for pagination
     * @return Triple containing JSONArray of messages, next skip token (if any), and total count
     */
    suspend fun fetchEmails(
        baseUrl: String,
        filterQuery: String = "",
        batchSize: Int,
        maxEmails: Int,
        initialSkipToken: String? = null
    ): Triple<JSONArray, String?, Int>
    
    /**
     * Deletes an email message
     * 
     * @param messageId The ID of the message to delete
     * @return Pair containing success boolean and error message (if any)
     */
    suspend fun deleteEmail(messageId: String): Pair<Boolean, String?>
    
    /**
     * Moves an email message to a specified folder
     * 
     * @param messageId The ID of the message to move
     * @param destinationFolderId The ID of the destination folder
     * @return Pair containing success boolean and error message (if any)
     */
    suspend fun moveEmail(messageId: String, destinationFolderId: String): Pair<Boolean, String?>
    
    /**
     * Finds a mail folder by name
     * 
     * @param folderName The display name of the folder to find
     * @return Pair containing folder ID (if found) and error message (if any)
     */
    suspend fun findFolder(folderName: String): Pair<String?, String?>
    
    /**
     * Creates a new mail folder
     * 
     * @param folderName The display name for the new folder
     * @return Pair containing folder ID (if created) and error message (if any)
     */
    suspend fun createFolder(folderName: String): Pair<String?, String?>
    
    /**
     * Finds an existing folder or creates it if it doesn't exist
     * 
     * @param folderName The display name of the folder
     * @return The ID of the folder, either found or newly created
     * @throws IOException if the folder cannot be found or created
     */
    suspend fun findOrCreateFolder(folderName: String): String
    
    /**
     * Checks if the API is currently being throttled
     * 
     * @return true if the API is throttled, false otherwise
     */
    fun isThrottled(): Boolean
    
    /**
     * Gets the remaining time in milliseconds until throttling ends
     * 
     * @return Time in milliseconds until throttling ends, 0 if not throttled
     */
    fun getRemainingThrottleTime(): Long
}