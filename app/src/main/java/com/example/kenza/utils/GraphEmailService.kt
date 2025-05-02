package com.example.kenza.utils

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder

/**
 * Service class for Microsoft Graph API email operations
 */
class GraphEmailService(private val apiClient: GraphApiClient) {
    companion object {
        private const val TAG = "GraphEmailService"
        private const val BASE_URL = "https://graph.microsoft.com/v1.0"
    }
    
    /**
     * Fetches email pages from Microsoft Graph with pagination support
     */
    fun fetchEmailPages(
        accessToken: String,
        baseUrl: String,
        filterQuery: String = "",
        batchSize: Int,
        maxEmails: Int,
        initialSkipToken: String?,
        onBatch: (JSONArray) -> Unit,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        var paginationValue: String? = initialSkipToken
        var isSkipParameter = false
        var fetchedCount = 0
        
        suspend fun fetchPageWithRetry() {
            val urlBuilder = StringBuilder("$baseUrl?").apply {
                append("\$top=").append(batchSize)
                if (filterQuery.isNotEmpty()) append(filterQuery)
                paginationValue?.let {
                    val paramName = if (isSkipParameter) "\$skip" else "\$skiptoken"
                    append("&$paramName=").append(it)
                }
            }
            
            val requestUrl = urlBuilder.toString()
            Log.d(TAG, "Fetching page with URL: $requestUrl (isSkip=$isSkipParameter)")
            
            val request = apiClient.buildGetRequest(requestUrl, accessToken)
            
            try {
                apiClient.executeRequest(request).use { response ->
                    if (!response.isSuccessful) {
                        onError("Graph API error ${response.code}: ${response.body?.string()}")
                        return
                    }
                    
                    val body = response.body?.string() ?: run {
                        onError("Empty response body")
                        return
                    }
                    
                    try {
                        val json = JSONObject(body)
                        val array = json.optJSONArray("value") ?: JSONArray()
                        onBatch(array)
                        fetchedCount += array.length()
                        onProgress(fetchedCount, maxEmails)
                        
                        val nextLink = json.optString("@odata.nextLink", null)
                        Log.d(TAG, "Next link: $nextLink")
                        
                        if (fetchedCount >= maxEmails || nextLink.isNullOrEmpty()) {
                            Log.d(TAG, "Pagination complete. Fetched $fetchedCount emails")
                            onComplete()
                        } else {
                            try {
                                paginationValue = null
                                isSkipParameter = false
                                
                                val decodedNextLink = try {
                                    URLDecoder.decode(nextLink, "UTF-8")
                                } catch (e: Exception) {
                                    Log.w(TAG, "Could not URL-decode nextLink, using raw: $nextLink")
                                    nextLink
                                }
                                
                                val uri = Uri.parse(decodedNextLink)
                                
                                paginationValue = uri.getQueryParameter("\$skiptoken") ?: uri.getQueryParameter("\$skip")
                                isSkipParameter = paginationValue == uri.getQueryParameter("\$skip")
                                
                                // Fallback to regex if needed
                                if (paginationValue == null) {
                                    paginationValue = extractPaginationValueWithRegex(nextLink)
                                }
                                
                                Log.d(TAG, "Extracted pagination value: $paginationValue (isSkipParameter=$isSkipParameter)")
                                
                                if (paginationValue != null) {
                                    // Add a small delay between pagination requests to avoid rate limiting
                                    kotlinx.coroutines.delay(300)
                                    fetchPageWithRetry()
                                } else {
                                    Log.e(TAG, "Failed to extract \$skiptoken OR \$skip from nextLink: $nextLink")
                                    onComplete()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error extracting pagination value: ${e.message}", e)
                                onError("Pagination error: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        onError("JSON parsing error: ${e.message}")
                    }
                }
            } catch (e: IOException) {
                onError("Network error: ${e.message}")
            }
        }
        
        MainScope().launch {
            fetchPageWithRetry()
        }
    }
    
    /**
     * Moves an email to a specified folder
     */
    suspend fun moveEmail(
        accessToken: String,
        messageId: String,
        destinationFolderId: String
    ): Pair<Boolean, String?> {
        val requestUrl = "$BASE_URL/me/messages/$messageId/move"
        Log.d(TAG, "Attempting to move email: $messageId to folder $destinationFolderId")
        
        val jsonBody = JSONObject().apply {
            put("destinationId", destinationFolderId)
        }
        
        val request = apiClient.buildJsonPostRequest(requestUrl, accessToken, jsonBody)
        
        return try {
            apiClient.executeRequest(request).use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Successfully moved email $messageId")
                    Pair(true, null)
                } else {
                    val errorBody = response.body?.string() ?: ""
                    Log.e(TAG, "Error moving email $messageId: ${response.code} - $errorBody")
                    Pair(false, "Error ${response.code}: $errorBody")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error moving email $messageId: ${e.message}", e)
            Pair(false, "Error: ${e.message ?: "Unknown error"}")
        }
    }
    
    /**
     * Deletes an email
     */
    suspend fun deleteEmail(
        accessToken: String,
        messageId: String
    ): Pair<Boolean, String?> {
        val requestUrl = "$BASE_URL/me/messages/$messageId"
        Log.d(TAG, "Attempting to delete email: $messageId")
        
        val request = apiClient.buildDeleteRequest(requestUrl, accessToken)
        
        return try {
            apiClient.executeRequest(request).use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Successfully deleted email $messageId")
                    Pair(true, null)
                } else {
                    val errorBody = response.body?.string() ?: ""
                    Log.e(TAG, "Error deleting email $messageId: ${response.code} - $errorBody")
                    Pair(false, "Error ${response.code}: $errorBody")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting email $messageId: ${e.message}", e)
            Pair(false, "Error: ${e.message ?: "Unknown error"}")
        }
    }
    
    /**
     * Finds or creates a folder and returns its ID
     */
    suspend fun findOrCreateFolder(
        accessToken: String,
        folderName: String
    ): String = withContext(Dispatchers.IO) {
        // Helper function to find a folder by name
        suspend fun findFolder(): String? {
            val url = "$BASE_URL/me/mailFolders?\$filter=displayName eq '$folderName'"
            val request = apiClient.buildGetRequest(url, accessToken)
            
            try {
                apiClient.executeRequest(request).use { response ->
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        val folders = JSONObject(responseBody ?: "").optJSONArray("value")
                        if (folders != null && folders.length() > 0) {
                            val existingId = folders.getJSONObject(0).optString("id")
                            if (existingId.isNotEmpty()) {
                                Log.d(TAG, "Found existing folder '$folderName' with ID: $existingId")
                                return existingId
                            }
                        }
                    } else {
                        Log.w(TAG, "Failed to query for folder '$folderName': ${response.code}")
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Network error finding folder '$folderName': ${e.message}", e)
            }
            return null
        }
        
        // First attempt to find the folder
        val folderId = findFolder()
        if (folderId != null) {
            return@withContext folderId
        }
        
        // If not found, try to create it
        Log.d(TAG, "Folder '$folderName' not found, attempting to create.")
        val createUrl = "$BASE_URL/me/mailFolders"
        val createJson = JSONObject().apply { put("displayName", folderName) }
        val createRequest = apiClient.buildJsonPostRequest(createUrl, accessToken, createJson)
        
        try {
            apiClient.executeRequest(createRequest).use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val newId = JSONObject(responseBody ?: "").optString("id")
                    if (newId.isNotEmpty()) {
                        Log.d(TAG, "Successfully created folder '$folderName' with ID: $newId")
                        return@withContext newId
                    } else {
                        throw IOException("Folder creation for '$folderName' returned empty ID.")
                    }
                } else if (response.code == 409) {
                    // Conflict might mean folder was created concurrently
                    Log.w(TAG, "Folder '$folderName' likely created concurrently. Retrying find.")
                    kotlinx.coroutines.delay(500)
                    
                    // Try finding it again
                    val retryFolderId = findFolder()
                    if (retryFolderId != null) {
                        return@withContext retryFolderId
                    } else {
                        throw IOException("Folder creation failed with 409, but couldn't find it immediately after.")
                    }
                } else {
                    val errorBody = response.body?.string()
                    throw IOException("Create folder '$folderName' failed: ${response.code} - $errorBody")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error creating folder '$folderName': ${e.message}", e)
            throw e
        }
    }
    
    /**
     * Helper method to extract pagination token using regex
     */
    private fun extractPaginationValueWithRegex(nextLink: String): String? {
        var skipToken: String? = null
        var isSkip = false
        
        // Try skiptoken first
        val skipTokenRegex = "(?:\\$|%24)skiptoken=([^&]+)".toRegex()
        val skipTokenMatch = skipTokenRegex.find(nextLink)
        skipToken = skipTokenMatch?.groups?.get(1)?.value
        
        if (skipToken == null) {
            // Try skip parameter
            val skipRegex = "(?:\\$|%24)skip=([^&]+)".toRegex()
            val skipMatch = skipRegex.find(nextLink)
            skipToken = skipMatch?.groups?.get(1)?.value
            isSkip = skipToken != null
        }
        
        return skipToken
    }
}