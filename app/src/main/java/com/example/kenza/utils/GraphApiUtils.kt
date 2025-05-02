package com.example.kenza.utils

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlin.math.pow

class GraphApiUtils {
    companion object {
        private const val TAG = "GraphApiUtils"
        private const val MAX_RETRIES = 6 // Increased max retries for rate limiting
        private const val INITIAL_DELAY_MS = 1000L // Initial delay in milliseconds
        private const val CONCURRENCY_LIMIT = 3 // Reduced concurrent requests to avoid throttling
        private val activeRequests = java.util.concurrent.atomic.AtomicInteger(0)
        
        // Add a static field to track throttling status
        private var isThrottled = false
        private var throttleEndTime = 0L
        
        // Public methods to check throttling status - needed for DashboardActivity
        fun isThrottled(): Boolean = isThrottled && throttleEndTime > System.currentTimeMillis()
        
        fun getRemainingThrottleTime(): Long {
            return if (isThrottled && throttleEndTime > System.currentTimeMillis()) {
                throttleEndTime - System.currentTimeMillis()
            } else {
                0L
            }
        }
        
        // Make this public for use in DashboardActivity
        fun createHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(45, TimeUnit.SECONDS) // Increased timeout
                .readTimeout(45, TimeUnit.SECONDS)  // Increased timeout
                .writeTimeout(45, TimeUnit.SECONDS) // Increased timeout
                .retryOnConnectionFailure(true)     // Enable retry on connection failure
                .build()
        }
        
        /**
         * Fetches email pages from Microsoft Graph with pagination support.
         * @param accessToken Bearer token for Graph API
         * @param baseUrl Graph API URL for messages endpoint
         * @param filterQuery Optional filter query string (starting with &)
         * @param batchSize Number of items per page (max 50)
         * @param maxEmails Maximum total emails to fetch before stopping
         * @param initialSkipToken Initial skip token for pagination (can be either skiptoken or skip value)
         * @param onBatch Callback invoked with each batch of messages
         * @param onProgress Callback invoked with (fetchedCount, maxEmails)
         * @param onComplete Callback invoked when fetching completes
         * @param onError Callback invoked on error with message
         */
        fun fetchGraphEmailPages(
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
            val client = createHttpClient()
            var paginationValue: String? = initialSkipToken
            var isSkipParameter = false
            var fetchedCount = 0
            var retryCount = 0
            
            suspend fun fetchPageWithRetry() {
                if (retryCount >= MAX_RETRIES) {
                    onError("Failed to fetch emails after $MAX_RETRIES retries")
                    return
                }
                
                // Check concurrency limit and wait if needed
                while (activeRequests.get() >= CONCURRENCY_LIMIT) {
                    delay(500)
                }
                
                // Check global throttling first
                waitForThrottlingToEnd()
                
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
                
                val request = Request.Builder()
                    .url(requestUrl)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .build()

                activeRequests.incrementAndGet()
                
                try {
                    // Move network call to IO dispatcher to prevent NetworkOnMainThreadException
                    val response = withContext(Dispatchers.IO) {
                        client.newCall(request).execute()
                    }
                    
                    response.use { 
                        activeRequests.decrementAndGet()
                        
                        if (it.code == 429) {
                            // Throttling detected, implement exponential backoff
                            val backoffMs = handleThrottling(it, retryCount)
                            Log.w(TAG, "Rate limited (429). Retrying after $backoffMs ms (retry $retryCount)")
                            retryCount++
                            delay(backoffMs)
                            fetchPageWithRetry()
                            return
                        }
                        
                        if (!it.isSuccessful) {
                            if (retryCount < MAX_RETRIES && 
                                (it.code == 500 || it.code == 502 || it.code == 503 || it.code == 504)) {
                                // Server error, retry with backoff
                                val backoffMs = (INITIAL_DELAY_MS * 2.0.pow(retryCount)).toLong()
                                Log.w(TAG, "Server error ${it.code}. Retrying after $backoffMs ms (retry $retryCount)")
                                retryCount++
                                delay(backoffMs)
                                fetchPageWithRetry()
                                return
                            }
                            
                            onError("Graph API error ${it.code}: ${it.body?.string()}")
                            return
                        }
                        
                        val body = it.body?.string() ?: run {
                            onError("Empty response body")
                            return
                        }
                        
                        try {
                            val json = JSONObject(body)
                            val array = json.optJSONArray("value") ?: JSONArray()
                            onBatch(array)
                            fetchedCount += array.length()
                            onProgress(fetchedCount, maxEmails)
                            
                            // Reset retry count on success
                            retryCount = 0

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
                                    Log.d(TAG, "Decoded next link: $decodedNextLink")

                                    val uri = Uri.parse(decodedNextLink)

                                    paginationValue = uri.getQueryParameter("\$skiptoken")
                                    if (paginationValue != null) {
                                        isSkipParameter = false
                                        Log.d(TAG, "Found \$skiptoken using Uri.parse")
                                    } else {
                                        paginationValue = uri.getQueryParameter("\$skip")
                                        if (paginationValue != null) {
                                            isSkipParameter = true
                                            Log.d(TAG, "Found \$skip using Uri.parse")
                                        }
                                    }

                                    if (paginationValue == null) {
                                        Log.d(TAG, "Uri.parse failed for both, trying Regex on original link: $nextLink")
                                        
                                        var regex = "(?:\\\$|%24)skiptoken=([^&]+)".toRegex()
                                        var matchResult = regex.find(nextLink)
                                        paginationValue = matchResult?.groups?.get(1)?.value
                                        
                                        if (paginationValue != null) {
                                             isSkipParameter = false
                                             Log.d(TAG, "Found \$skiptoken using Regex")
                                        } else {
                                            regex = "(?:\\\$|%24)skip=([^&]+)".toRegex()
                                            matchResult = regex.find(nextLink)
                                            paginationValue = matchResult?.groups?.get(1)?.value
                                            if (paginationValue != null) {
                                                isSkipParameter = true
                                                Log.d(TAG, "Found \$skip using Regex")
                                            }
                                        }
                                    }

                                    Log.d(TAG, "Extracted pagination value: $paginationValue (isSkipParameter=$isSkipParameter)")

                                    if (paginationValue != null) {
                                        // Add a small delay between pagination requests to avoid rate limiting
                                        delay(300)
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
                    activeRequests.decrementAndGet()
                    
                    if (retryCount < MAX_RETRIES) {
                        // Exponential backoff for network failures
                        val backoffMs = handleNetworkError(e, retryCount)
                        Log.w(TAG, "Network error: ${e.message}. Retrying after $backoffMs ms (retry $retryCount)")
                        retryCount++
                        delay(backoffMs)
                        fetchPageWithRetry()
                    } else {
                        onError("Network error after $MAX_RETRIES retries: ${e.message}")
                    }
                }
            }

            MainScope().launch {
                fetchPageWithRetry()
            }
        }

        // Helper method to check for throttling and wait if needed
        private suspend fun waitForThrottlingToEnd() {
            if (isThrottled && throttleEndTime > System.currentTimeMillis()) {
                val waitTime = throttleEndTime - System.currentTimeMillis()
                Log.w(TAG, "API is throttled. Waiting ${waitTime}ms before proceeding.")
                delay(waitTime + 500) // Add a small buffer
                isThrottled = false
            }
        }

        // Helper method to handle throttling responses
        private suspend fun handleThrottling(response: Response, retryCount: Int): Long {
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

        // Helper method to handle network errors with appropriate backoff
        private suspend fun handleNetworkError(error: IOException, retryCount: Int): Long {
            val message = error.message ?: ""
            val baseDelay = if (message.contains("Unable to resolve host") || 
                               message.contains("Failed to connect") ||
                               message.contains("Connection refused") ||
                               message.contains("timeout")) {
                // For DNS or connection issues, use longer backoff
                3000L * 2.0.pow(retryCount.toDouble())
            } else {
                // For other network issues
                INITIAL_DELAY_MS * 2.0.pow(retryCount.toDouble())
            }
            
            return baseDelay.toLong().coerceAtMost(60000L) // Cap at 1 minute
        }

        suspend fun deleteEmail(
            accessToken: String,
            messageId: String,
            retryCount: Int = 0
        ): Pair<Boolean, String?> {
            val client = createHttpClient()
            val requestUrl = "https://graph.microsoft.com/v1.0/me/messages/$messageId"
            Log.d(TAG, "Attempting to delete email: $messageId (Retry: $retryCount)")

            val request = Request.Builder()
                .url(requestUrl)
                .delete()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()

            try {
                // Check global throttling first
                waitForThrottlingToEnd()
                
                while (activeRequests.get() >= CONCURRENCY_LIMIT) {
                    delay(300)
                }
                
                activeRequests.incrementAndGet()
                
                // Move network call to IO dispatcher
                val response = withContext(Dispatchers.IO) {
                    client.newCall(request).execute()
                }
                
                activeRequests.decrementAndGet()

                return response.use {
                    if (it.isSuccessful) {
                        Log.d(TAG, "Successfully deleted email $messageId")
                        Pair(true, null)
                    } else {
                        val errorBody = it.body?.string()
                        Log.e(TAG, "Error deleting email $messageId: ${it.code} - $errorBody")

                        if (it.code == 429) {
                            val waitMs = handleThrottling(it, retryCount)
                            Log.w(TAG, "Rate limit hit for $messageId. Retrying after ${waitMs}ms... (retry ${retryCount+1}/$MAX_RETRIES)")
                            delay(waitMs)
                            if (retryCount < MAX_RETRIES) {
                                return deleteEmail(accessToken, messageId, retryCount + 1)
                            } else {
                                return Pair(false, "Maximum retries exceeded due to rate limiting")
                            }
                        } else if ((it.code >= 500 || it.code == 408) && retryCount < MAX_RETRIES) {
                            // Server errors or timeouts
                            val delayMs = (INITIAL_DELAY_MS * 2.0.pow(retryCount)).toLong()
                            Log.w(TAG, "Server error ${it.code} for $messageId. Retrying after ${delayMs}ms... (retry ${retryCount+1}/$MAX_RETRIES)")
                            delay(delayMs)
                            return deleteEmail(accessToken, messageId, retryCount + 1)
                        } else {
                            Pair(false, "Error ${it.code}: $errorBody")
                        }
                    }
                }
            } catch (e: IOException) {
                activeRequests.decrementAndGet()
                Log.e(TAG, "Network error deleting email $messageId: ${e.message}", e)
                
                // Improved retry handling for network errors
                if (retryCount < MAX_RETRIES) {
                    val delayMs = handleNetworkError(e, retryCount)
                    Log.w(TAG, "Network error for $messageId. Retrying after ${delayMs}ms... (retry ${retryCount+1}/$MAX_RETRIES)")
                    delay(delayMs)
                    return deleteEmail(accessToken, messageId, retryCount + 1)
                }
                
                return Pair(false, "Network error: ${e.message}")
            }
        }

        suspend fun moveEmail(
            accessToken: String,
            messageId: String,
            destinationFolderId: String,
            retryCount: Int = 0
        ): Pair<Boolean, String?> {
            // Check global throttling first
            waitForThrottlingToEnd()
            
            val client = createHttpClient()
            val requestUrl = "https://graph.microsoft.com/v1.0/me/messages/$messageId/move"
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

            try {
                // Check concurrency limit and wait if needed - add jitter to avoid thundering herd
                while (activeRequests.get() >= CONCURRENCY_LIMIT) {
                    val waitTime = 300L + (Math.random() * 400).toLong()
                    delay(waitTime)
                }
                
                activeRequests.incrementAndGet()
                
                // Move network call to IO dispatcher
                val response = withContext(Dispatchers.IO) {
                    client.newCall(request).execute()
                }
                
                activeRequests.decrementAndGet()

                return response.use {
                    if (it.isSuccessful) {
                        Log.d(TAG, "Successfully moved email $messageId")
                        Pair(true, null)
                    } else {
                        val errorBody = it.body?.string() ?: ""
                        Log.e(TAG, "Error moving email $messageId: ${it.code} - $errorBody")

                        if (it.code == 429 || (errorBody.contains("ApplicationThrottled") && errorBody.contains("MailboxConcurrency"))) {
                            // Rate limiting - look at both error code AND content
                            val waitMs = handleThrottling(it, retryCount)
                            
                            Log.w(TAG, "Rate limit hit moving $messageId. Retrying after ${waitMs}ms... (retry ${retryCount+1}/$MAX_RETRIES)")
                            delay(waitMs)
                            
                            if (retryCount < MAX_RETRIES) {
                                return moveEmail(accessToken, messageId, destinationFolderId, retryCount + 1)
                            } else {
                                return Pair(false, "Maximum retries exceeded due to rate limiting")
                            }
                        } else if ((it.code >= 500 || it.code == 408) && retryCount < MAX_RETRIES) {
                            // Server errors or timeouts
                            val delayMs = (INITIAL_DELAY_MS * 2.0.pow(retryCount)).toLong()
                            Log.w(TAG, "Server error ${it.code} for $messageId. Retrying after ${delayMs}ms... (retry ${retryCount+1}/$MAX_RETRIES)")
                            delay(delayMs)
                            return moveEmail(accessToken, messageId, destinationFolderId, retryCount + 1)
                        } else if (it.code == 404 && retryCount < 2) {
                            // Message not found, could be a temporary indexing issue
                            Log.w(TAG, "Message $messageId not found. This could be temporary. Retrying after 2s...")
                            delay(2000)
                            return moveEmail(accessToken, messageId, destinationFolderId, retryCount + 1)
                        } else {
                            Pair(false, "Error ${it.code}: $errorBody")
                        }
                    }
                }
            } catch (e: IOException) {
                activeRequests.decrementAndGet()
                Log.e(TAG, "Network error moving email $messageId: ${e.message}", e)
                
                // Improved retry handling for network errors
                if (retryCount < MAX_RETRIES) {
                    val delayMs = handleNetworkError(e, retryCount)
                    Log.w(TAG, "Network error moving $messageId. Retrying after ${delayMs}ms... (retry ${retryCount+1}/$MAX_RETRIES)")
                    delay(delayMs)
                    return moveEmail(accessToken, messageId, destinationFolderId, retryCount + 1)
                }
                
                return Pair(false, "Network error: ${e.message ?: "Unknown network error"}")
            } catch (e: Exception) {
                activeRequests.decrementAndGet()
                Log.e(TAG, "Unexpected error moving email $messageId: ${e.message}", e)
                
                // Handle other unexpected errors
                if (retryCount < MAX_RETRIES) {
                    val delayMs = (INITIAL_DELAY_MS * 2.0.pow(retryCount)).toLong()
                    Log.w(TAG, "Unexpected error moving $messageId. Retrying after ${delayMs}ms... (retry ${retryCount+1}/$MAX_RETRIES)")
                    delay(delayMs)
                    return moveEmail(accessToken, messageId, destinationFolderId, retryCount + 1)
                }
                
                return Pair(false, "Error: ${e.message ?: "Unknown error"}")
            }
        }

        suspend fun unsubscribe(accessToken: String, messageId: String): Pair<Boolean, String?> {
            Log.w(TAG, "Unsubscribe functionality is not implemented via Graph API directly for message $messageId.")
            return Pair(false, "Unsubscribe not implemented")
        }
    }
}
