package com.example.kenza.network

import android.net.Uri
import android.util.Log
import com.example.kenza.auth.GraphTokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow

/**
 * Implementation of GraphApiService that handles all Microsoft Graph API operations
 * with robust error handling, throttling management, and retry mechanisms.
 */
class GraphApiClient(
    private val tokenProvider: GraphTokenProvider,
    private val httpClient: OkHttpClient = createDefaultHttpClient()
) : GraphApiService {
    companion object {
        private const val TAG = "GraphApiClient"
        private const val BASE_URL = "https://graph.microsoft.com/v1.0"
        private const val MAX_RETRIES = 6
        private const val INITIAL_DELAY_MS = 1000L
        private const val CONCURRENCY_LIMIT = 3
        
        private val activeRequests = AtomicInteger(0)
        private var isThrottled = false
        private var throttleEndTime = 0L
        
        // Cache for folder IDs to reduce lookups
        private val folderIdCache = mutableMapOf<String, String>()
        
        /**
         * Creates a default OkHttpClient with appropriate timeouts
         */
        fun createDefaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(45, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(45, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
    
    override suspend fun fetchEmails(
        baseUrl: String,
        filterQuery: String,
        batchSize: Int,
        maxEmails: Int,
        initialSkipToken: String?
    ): Triple<JSONArray, String?, Int> = withContext(Dispatchers.IO) {
        val accessToken = tokenProvider.getAccessToken() ?: throw IOException("Failed to get access token")
        
        val allMessages = JSONArray()
        var nextSkipToken: String? = initialSkipToken
        var isSkipParameter = false
        var fetchedCount = 0
        var retryCount = 0
        
        while (fetchedCount < maxEmails && retryCount < MAX_RETRIES) {
            var shouldRetry = false
            try {
                // Check concurrency limit and wait if needed
                while (activeRequests.get() >= CONCURRENCY_LIMIT) {
                    delay(500)
                }
                
                // Check global throttling first
                waitForThrottlingToEnd()
                
                val urlBuilder = StringBuilder("$baseUrl?").apply {
                    append("\$top=").append(batchSize)
                    if (filterQuery.isNotEmpty()) append(filterQuery)
                    nextSkipToken?.let {
                        val paramName = if (isSkipParameter) "\$skip" else "\$skiptoken"
                        append("&$paramName=").append(it)
                    }
                }
                
                val requestUrl = urlBuilder.toString()
                Log.d(TAG, "Fetching emails with URL: $requestUrl (isSkip=$isSkipParameter)")
                
                val request = Request.Builder()
                    .url(requestUrl)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .build()
                
                activeRequests.incrementAndGet()
                
                httpClient.newCall(request).execute().use { response ->
                    activeRequests.decrementAndGet()
                    
                    if (response.code == 429) {
                        val backoffMs = handleThrottling(response, retryCount)
                        Log.w(TAG, "Rate limited (429). Retrying after $backoffMs ms (retry $retryCount)")
                        retryCount++
                        delay(backoffMs)
                        shouldRetry = true
                        return@use
                    }
                    
                    if (!response.isSuccessful) {
                        if (retryCount < MAX_RETRIES && 
                            (response.code == 500 || response.code == 502 || response.code == 503 || response.code == 504)) {
                            val backoffMs = (INITIAL_DELAY_MS * 2.0.pow(retryCount)).toLong()
                            Log.w(TAG, "Server error ${response.code}. Retrying after $backoffMs ms (retry $retryCount)")
                            retryCount++
                            delay(backoffMs)
                            shouldRetry = true
                            return@use
                        }
                        
                        throw IOException("Graph API error ${response.code}: ${response.body?.string()}")
                    }
                    
                    val body = response.body?.string() ?: throw IOException("Empty response body")
                    val json = JSONObject(body)
                    val array = json.optJSONArray("value") ?: JSONArray()
                    
                    // Merge messages with existing results
                    for (i in 0 until array.length()) {
                        allMessages.put(array.get(i))
                    }
                    
                    fetchedCount += array.length()
                    retryCount = 0  // Reset retry count on success
                    
                    val nextLink = json.optString("@odata.nextLink", null)
                    Log.d(TAG, "Next link: $nextLink")
                    
                    if (fetchedCount >= maxEmails || nextLink.isNullOrEmpty()) {
                        Log.d(TAG, "Pagination complete. Fetched $fetchedCount emails")
                        fetchedCount = maxEmails
                        nextSkipToken = null
                    } else {
                        nextSkipToken = extractSkipToken(nextLink)
                        isSkipParameter = nextSkipToken?.startsWith("skip=") == true
                        if (isSkipParameter) {
                            nextSkipToken = nextSkipToken?.substring(5)
                        }
                        
                        if (nextSkipToken == null) {
                            Log.e(TAG, "Failed to extract pagination token from nextLink: $nextLink")
                            fetchedCount = maxEmails
                        } else {
                            delay(300)
                        }
                    }
                }
                
                if (shouldRetry) continue
                
                if (nextSkipToken == null && fetchedCount >= maxEmails) break
                
            } catch (e: IOException) {
                Log.e(TAG, "Network error fetching emails: ${e.message}", e)
                
                if (retryCount < MAX_RETRIES) {
                    val backoffMs = handleNetworkError(e, retryCount)
                    Log.w(TAG, "Network error: ${e.message}. Retrying after $backoffMs ms (retry $retryCount)")
                    retryCount++
                    delay(backoffMs)
                } else {
                    throw IOException("Network error after $MAX_RETRIES retries: ${e.message}", e)
                }
            }
        }
        
        Triple(allMessages, nextSkipToken, fetchedCount)
    }
    
    override suspend fun deleteEmail(messageId: String): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        val accessToken = tokenProvider.getAccessToken() ?: return@withContext Pair(false, "Failed to get access token")
        var retryCount = 0
        
        while (retryCount < MAX_RETRIES) {
            var shouldRetry = false
            try {
                // Check global throttling first
                waitForThrottlingToEnd()
                
                while (activeRequests.get() >= CONCURRENCY_LIMIT) {
                    delay(300)
                }
                
                val requestUrl = "$BASE_URL/me/messages/$messageId"
                Log.d(TAG, "Attempting to delete email: $messageId (Retry: $retryCount)")
                
                val request = Request.Builder()
                    .url(requestUrl)
                    .delete()
                    .addHeader("Authorization", "Bearer $accessToken")
                    .build()
                
                activeRequests.incrementAndGet()
                
                httpClient.newCall(request).execute().use { response ->
                    activeRequests.decrementAndGet()
                    
                    if (response.isSuccessful) {
                        Log.d(TAG, "Successfully deleted email $messageId")
                        return@withContext Pair(true, null)
                    } else {
                        val errorBody = response.body?.string()
                        Log.e(TAG, "Error deleting email $messageId: ${response.code} - $errorBody")
                        
                        if (response.code == 429) {
                            val waitMs = handleThrottling(response, retryCount)
                            Log.w(TAG, "Rate limit hit for $messageId. Retrying after ${waitMs}ms... (retry ${retryCount+1}/$MAX_RETRIES)")
                            retryCount++
                            delay(waitMs)
                            shouldRetry = true
                            return@use
                        } else if ((response.code >= 500 || response.code == 408) && retryCount < MAX_RETRIES) {
                            val delayMs = (INITIAL_DELAY_MS * 2.0.pow(retryCount)).toLong()
                            Log.w(TAG, "Server error ${response.code} for $messageId. Retrying after ${delayMs}ms... (retry ${retryCount+1}/$MAX_RETRIES)")
                            retryCount++
                            delay(delayMs)
                            shouldRetry = true
                            return@use
                        } else {
                            return@withContext Pair(false, "Error ${response.code}: $errorBody")
                        }
                    }
                }
                
                if (shouldRetry) continue
                
            } catch (e: IOException) {
                activeRequests.decrementAndGet()
                Log.e(TAG, "Network error deleting email $messageId: ${e.message}", e)
                
                if (retryCount < MAX_RETRIES) {
                    val delayMs = handleNetworkError(e, retryCount)
                    Log.w(TAG, "Network error for $messageId. Retrying after ${delayMs}ms... (retry ${retryCount+1}/$MAX_RETRIES)")
                    retryCount++
                    delay(delayMs)
                } else {
                    return@withContext Pair(false, "Network error: ${e.message}")
                }
            }
        }
        
        Pair(false, "Maximum retries exceeded")
    }
    
    override suspend fun moveEmail(messageId: String, destinationFolderId: String): Pair<Boolean, String?> = 
        withContext(Dispatchers.IO) {
            val accessToken = tokenProvider.getAccessToken() ?: return@withContext Pair(false, "Failed to get access token")
            var retryCount = 0
            
            while (retryCount < MAX_RETRIES) {
                var shouldRetry = false
                try {
                    // Check global throttling first
                    waitForThrottlingToEnd()
                    
                    // Check concurrency limit and wait if needed
                    while (activeRequests.get() >= CONCURRENCY_LIMIT) {
                        val waitTime = 300L + (Math.random() * 400).toLong()
                        delay(waitTime)
                    }
                    
                    val requestUrl = "$BASE_URL/me/messages/$messageId/move"
                    Log.d(TAG, "Attempting to move email: $messageId to folder $destinationFolderId (Retry: $retryCount)")
                    
                    val mediaType = "application/json".toMediaType()
                    val jsonBody = JSONObject().apply {
                        put("destinationId", destinationFolderId)
                    }
                    val body = jsonBody.toString().toRequestBody(mediaType)
                    
                    val request = Request.Builder()
                        .url(requestUrl)
                        .addHeader("Authorization", "Bearer $accessToken")
                        .addHeader("Content-Type", "application/json")
                        .post(body)
                        .build()
                    
                    activeRequests.incrementAndGet()
                    
                    httpClient.newCall(request).execute().use { response ->
                        activeRequests.decrementAndGet()
                        
                        if (response.isSuccessful) {
                            Log.d(TAG, "Successfully moved email $messageId")
                            return@withContext Pair(true, null)
                        } else {
                            val errorBody = response.body?.string() ?: ""
                            Log.e(TAG, "Error moving email $messageId: ${response.code} - $errorBody")
                            
                            if (response.code == 429 || (errorBody.contains("ApplicationThrottled") && errorBody.contains("MailboxConcurrency"))) {
                                val waitMs = handleThrottling(response, retryCount)
                                Log.w(TAG, "Rate limit hit moving $messageId. Retrying after ${waitMs}ms... (retry ${retryCount+1}/$MAX_RETRIES)")
                                retryCount++
                                delay(waitMs)
                                shouldRetry = true
                                return@use
                            } else if ((response.code >= 500 || response.code == 408) && retryCount < MAX_RETRIES) {
                                val delayMs = (INITIAL_DELAY_MS * 2.0.pow(retryCount)).toLong()
                                Log.w(TAG, "Server error ${response.code} for move $messageId. Retrying after ${delayMs}ms... (retry ${retryCount+1}/$MAX_RETRIES)")
                                retryCount++
                                delay(delayMs)
                                shouldRetry = true
                                return@use
                            } else {
                                return@withContext Pair(false, "Error ${response.code}: $errorBody")
                            }
                        }
                    }
                    
                    if (shouldRetry) continue
                    
                } catch (e: IOException) {
                    activeRequests.decrementAndGet()
                    Log.e(TAG, "Network error moving email $messageId: ${e.message}", e)
                    
                    if (retryCount < MAX_RETRIES) {
                        val delayMs = handleNetworkError(e, retryCount)
                        Log.w(TAG, "Network error moving $messageId. Retrying after ${delayMs}ms... (retry ${retryCount+1}/$MAX_RETRIES)")
                        retryCount++
                        delay(delayMs)
                    } else {
                        return@withContext Pair(false, "Network error: ${e.message}")
                    }
                }
            }
            
            Pair(false, "Maximum retries exceeded")
        }
    
    override suspend fun findFolder(folderName: String): Pair<String?, String?> = withContext(Dispatchers.IO) {
        // Check cache first
        folderIdCache[folderName]?.let {
            Log.d(TAG, "Using cached folder ID for '$folderName': $it")
            return@withContext Pair(it, null)
        }
        
        val accessToken = tokenProvider.getAccessToken() ?: return@withContext Pair(null, "Failed to get access token")
        
        try {
            // Check global throttling first
            waitForThrottlingToEnd()
            
            val request = Request.Builder()
                .url("$BASE_URL/me/mailFolders?\$filter=displayName eq '$folderName'")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    Log.w(TAG, "Failed to query for folder '$folderName': ${response.code} - $errorBody")
                    return@withContext Pair(null, errorBody)
                }
                
                val responseBody = response.body?.string() ?: ""
                val json = JSONObject(responseBody)
                val folders = json.optJSONArray("value")
                
                if (folders != null && folders.length() > 0) {
                    val folder = folders.getJSONObject(0)
                    val id = folder.optString("id")
                    Log.d(TAG, "Found existing folder '$folderName' with ID: $id")
                    
                    // Cache the folder ID
                    if (id.isNotEmpty()) {
                        folderIdCache[folderName] = id
                    }
                    
                    return@withContext Pair(id, null)
                } else {
                    Log.d(TAG, "Folder '$folderName' not found")
                    return@withContext Pair(null, null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception finding folder '$folderName': ${e.message}")
            return@withContext Pair(null, e.message)
        }
    }
    
    override suspend fun createFolder(folderName: String): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val accessToken = tokenProvider.getAccessToken() ?: return@withContext Pair(null, "Failed to get access token")
        
        try {
            // Check global throttling first
            waitForThrottlingToEnd()
            
            val createJson = JSONObject().apply {
                put("displayName", folderName)
            }
            val mediaType = "application/json".toMediaType()
            val createBody = createJson.toString().toRequestBody(mediaType)
            
            val createRequest = Request.Builder()
                .url("$BASE_URL/me/mailFolders")
                .addHeader("Authorization", "Bearer $accessToken")
                .post(createBody)
                .build()
            
            httpClient.newCall(createRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    Log.e(TAG, "Create folder '$folderName' failed: ${response.code} - $errorBody")
                    
                    // If folder already exists error, try to find it
                    if (response.code == 409) {
                        Log.d(TAG, "Folder '$folderName' already exists (409). Trying to find it.")
                        delay(1000) // Brief delay
                        return@withContext findFolder(folderName)
                    }
                    
                    return@withContext Pair(null, errorBody)
                }
                
                val responseBody = response.body?.string() ?: ""
                val json = JSONObject(responseBody)
                val id = json.optString("id")
                
                if (id.isNotEmpty()) {
                    Log.d(TAG, "Successfully created folder '$folderName' with ID: $id")
                    
                    // Cache the new folder ID
                    folderIdCache[folderName] = id
                    
                    return@withContext Pair(id, null)
                } else {
                    Log.e(TAG, "Created folder '$folderName' but ID was empty")
                    return@withContext Pair(null, "Created folder but no ID returned")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating folder '$folderName': ${e.message}")
            return@withContext Pair(null, e.message)
        }
    }
    
    override suspend fun findOrCreateFolder(folderName: String): String = withContext(Dispatchers.IO) {
        // Check cache first
        folderIdCache[folderName]?.let {
            Log.d(TAG, "Using cached folder ID for '$folderName': $it")
            return@withContext it
        }
        
        var retryCount = 0
        val maxRetries = 6
        var lastException: Exception? = null
        
        while (retryCount < maxRetries) {
            try {
                // Check if we've been throttled globally
                if (isThrottled()) {
                    val waitTime = getRemainingThrottleTime()
                    if (waitTime > 0) {
                        Log.w(TAG, "API is throttled. Waiting ${waitTime}ms before finding/creating folder '$folderName'.")
                        delay(waitTime + 100) // Add a small buffer
                    }
                }
                
                // Try to find the folder first
                val (folderId, errorMessage) = findFolder(folderName)
                
                if (folderId != null) {
                    return@withContext folderId
                }
                
                if (errorMessage?.contains("ApplicationThrottled") == true ||
                    errorMessage?.contains("MailboxConcurrency") == true) {
                    // Handle throttling with increased backoff
                    val waitTimeMs = (2000L * Math.pow(2.0, retryCount.toDouble())).toLong().coerceAtMost(30000L)
                    Log.w(TAG, "Throttled when finding folder '$folderName'. Waiting ${waitTimeMs}ms")
                    delay(waitTimeMs)
                    retryCount++
                    continue
                }
                
                // If not found and no throttling, try to create it
                Log.d(TAG, "Folder '$folderName' not found, attempting to create.")
                val (createdId, createErrorMessage) = createFolder(folderName)
                
                if (createdId != null) {
                    return@withContext createdId
                }
                
                if (createErrorMessage?.contains("ApplicationThrottled") == true ||
                    createErrorMessage?.contains("MailboxConcurrency") == true) {
                    // Handle throttling with increased backoff
                    val waitTimeMs = (2000L * Math.pow(2.0, retryCount.toDouble())).toLong().coerceAtMost(30000L)
                    Log.w(TAG, "Throttled when creating folder '$folderName'. Waiting ${waitTimeMs}ms")
                    delay(waitTimeMs)
                    retryCount++
                    continue
                }
                
                // Other errors
                Log.e(TAG, "Failed to find or create folder '$folderName': $createErrorMessage")
                throw IOException("Failed to find or create folder '$folderName': $createErrorMessage")
                
            } catch (e: Exception) {
                lastException = e
                val waitTimeMs = (2000L * Math.pow(1.8, retryCount.toDouble())).toLong().coerceAtMost(30000L)
                Log.e(TAG, "Exception in findOrCreateFolder '$folderName', retry ${retryCount+1}/$maxRetries: ${e.message}")
                delay(waitTimeMs)
                retryCount++
            }
        }
        
        // All retries failed
        throw lastException ?: IOException("Failed to find or create folder '$folderName' after $maxRetries attempts")
    }
    
    override fun isThrottled(): Boolean = isThrottled && throttleEndTime > System.currentTimeMillis()
    
    override fun getRemainingThrottleTime(): Long {
        return if (isThrottled && throttleEndTime > System.currentTimeMillis()) {
            throttleEndTime - System.currentTimeMillis()
        } else {
            0L
        }
    }
    
    // Helper methods
    
    /**
     * Waits until global throttling period is over
     */
    private suspend fun waitForThrottlingToEnd() {
        if (isThrottled && throttleEndTime > System.currentTimeMillis()) {
            val waitTime = throttleEndTime - System.currentTimeMillis()
            Log.w(TAG, "API is throttled. Waiting ${waitTime}ms before proceeding.")
            delay(waitTime + 500) // Add a small buffer
            isThrottled = false
        }
    }
    
    /**
     * Handles throttling responses by setting appropriate throttling parameters
     * 
     * @param response The HTTP response containing throttling headers
     * @param retryCount Current retry attempt count
     * @return The amount of time to wait before retrying
     */
    private fun handleThrottling(response: okhttp3.Response, retryCount: Int): Long {
        val retryAfterSeconds = response.header("Retry-After")?.toIntOrNull() ?: (retryCount + 1)
        val waitMs = (INITIAL_DELAY_MS * 2.0.pow(retryCount)).toLong() + (retryAfterSeconds * 1000L)
        
        // Set global throttling if seeing consecutive throttling
        if (retryCount > 1) {
            isThrottled = true
            throttleEndTime = System.currentTimeMillis() + waitMs + 2000
            Log.w(TAG, "Applying global throttling until ${throttleEndTime}")
        }
        
        return waitMs
    }
    
    /**
     * Handles network errors with appropriate backoff strategy
     * 
     * @param error The IOException that occurred
     * @param retryCount Current retry attempt count
     * @return The amount of time to wait before retrying
     */
    private fun handleNetworkError(error: IOException, retryCount: Int): Long {
        val message = error.message ?: ""
        val baseDelay = if (message.contains("Unable to resolve host") || 
                           message.contains("Failed to connect") ||
                           message.contains("Connection refused") ||
                           message.contains("timeout")) {
            3000L * 2.0.pow(retryCount.toDouble())
        } else {
            INITIAL_DELAY_MS * 2.0.pow(retryCount.toDouble())
        }
        
        return baseDelay.toLong().coerceAtMost(60000L)
    }
    
    /**
     * Extracts skip token from Graph API nextLink URL
     * 
     * @param nextLink The nextLink URL from Graph API response
     * @return The extracted skip token or null if not found
     */
    private fun extractSkipToken(nextLink: String): String? {
        try {
            val decodedNextLink = try {
                URLDecoder.decode(nextLink, "UTF-8")
            } catch (e: Exception) {
                Log.w(TAG, "Could not URL-decode nextLink, using raw: $nextLink")
                nextLink
            }
            
            val uri = Uri.parse(decodedNextLink)
            
            var skipToken = uri.getQueryParameter("\$skiptoken")
            if (skipToken != null) {
                return skipToken
            }
            
            val skip = uri.getQueryParameter("\$skip")
            if (skip != null) {
                return "skip=$skip"
            }
            
            var regex = "(?:\\\$|%24)skiptoken=([^&]+)".toRegex()
            var matchResult = regex.find(nextLink)
            skipToken = matchResult?.groups?.get(1)?.value
            
            if (skipToken != null) {
                return skipToken
            }
            
            regex = "(?:\\\$|%24)skip=([^&]+)".toRegex()
            matchResult = regex.find(nextLink)
            val skipValue = matchResult?.groups?.get(1)?.value
            
            if (skipValue != null) {
                return "skip=$skipValue"
            }
            
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting pagination value: ${e.message}", e)
            return null
        }
    }
}