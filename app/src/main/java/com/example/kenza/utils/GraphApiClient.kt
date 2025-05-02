package com.example.kenza.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * GraphApiClient abstracts Microsoft Graph API calls with proper error handling,
 * throttling detection, and automatic retries.
 */
interface GraphApiClient {
    /**
     * Executes a Graph API request with automatic retry and throttling handling
     */
    suspend fun executeRequest(request: Request): Response
    
    /**
     * Checks if the API is currently being throttled
     */
    fun isThrottled(): Boolean
    
    /**
     * Gets the remaining throttle time in milliseconds
     */
    fun getRemainingThrottleTime(): Long
    
    /**
     * Builds a GET request for the Graph API
     */
    fun buildGetRequest(url: String, accessToken: String): Request
    
    /**
     * Builds a POST request with JSON body for the Graph API
     */
    fun buildJsonPostRequest(url: String, accessToken: String, jsonBody: JSONObject): Request
    
    /**
     * Builds a DELETE request for the Graph API
     */
    fun buildDeleteRequest(url: String, accessToken: String): Request
}

/**
 * Implementation of GraphApiClient using OkHttp
 */
class GraphApiClientImpl : GraphApiClient {
    companion object {
        private const val TAG = "GraphApiClient"
        private const val MAX_RETRIES = 6
        private const val INITIAL_DELAY_MS = 1000L
        private const val CONCURRENCY_LIMIT = 3
        
        // Shared state to coordinate across all instances
        @Volatile private var isThrottled = false
        @Volatile private var throttleEndTime = 0L
        private val activeRequests = AtomicInteger(0)
        private val networkErrorCount = AtomicInteger(0)
        @Volatile private var lastNetworkErrorTime = 0L
        @Volatile private var globalBackoffEndTime = 0L
    }
    
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false) // We'll handle retries ourselves
        .build()
    
    override suspend fun executeRequest(request: Request): Response {
        var retryCount = 0
        var lastException: Exception? = null
        
        while (retryCount <= MAX_RETRIES) {
            try {
                // Check if we're in global backoff mode due to network issues
                val remainingGlobalBackoff = globalBackoffEndTime - System.currentTimeMillis()
                if (remainingGlobalBackoff > 0) {
                    Log.w(TAG, "In global backoff mode. Waiting ${remainingGlobalBackoff}ms before attempting request")
                    delay(remainingGlobalBackoff + Random.nextLong(100, 500))
                }
                
                // Check throttling and wait if needed
                waitForThrottlingToEnd()
                
                // Check concurrency and wait if needed
                while (activeRequests.get() >= CONCURRENCY_LIMIT) {
                    val waitTime = 300 + Random.nextLong(0, 500)
                    Log.d(TAG, "Concurrency limit reached (${activeRequests.get()}/$CONCURRENCY_LIMIT). Waiting ${waitTime}ms")
                    delay(waitTime)
                }
                
                activeRequests.incrementAndGet()
                
                val response = withContext(Dispatchers.IO) {
                    client.newCall(request).execute()
                }
                
                activeRequests.decrementAndGet()
                
                // Reset network error count on successful response
                networkErrorCount.set(0)
                
                // Handle throttling
                if (response.code == 429) {
                    val retryAfter = response.header("Retry-After")?.toIntOrNull() ?: (retryCount + 1)
                    val waitMs = calculateBackoff(retryCount, retryAfter)
                    
                    // Apply global throttling on repeated throttling
                    if (retryCount > 1) {
                        setThrottled(waitMs + 2000)
                    }
                    
                    Log.w(TAG, "Rate limited (429). Retry ${retryCount + 1}/$MAX_RETRIES after ${waitMs}ms")
                    response.close()
                    delay(waitMs)
                    retryCount++
                    continue
                }
                
                // Handle server errors
                if (response.code in 500..599 || response.code == 408) {
                    if (retryCount < MAX_RETRIES) {
                        val waitMs = calculateBackoff(retryCount)
                        Log.w(TAG, "Server error ${response.code}. Retry ${retryCount + 1}/$MAX_RETRIES after ${waitMs}ms")
                        response.close()
                        delay(waitMs)
                        retryCount++
                        continue
                    }
                }
                
                return response
            } catch (e: IOException) {
                activeRequests.decrementAndGet()
                lastException = e
                
                // Update network error tracking
                val currentTime = System.currentTimeMillis()
                val timeSinceLastError = currentTime - lastNetworkErrorTime
                lastNetworkErrorTime = currentTime
                
                // If errors are happening in quick succession, increment counter
                if (timeSinceLastError < 5000) {
                    networkErrorCount.incrementAndGet()
                } else {
                    // Reset if it's been a while since the last error
                    networkErrorCount.set(1)
                }
                
                // If we see multiple network errors across different requests, 
                // implement a circuit breaker pattern with global backoff
                if (networkErrorCount.get() >= 5) {
                    val backoffTime = 5000L * Math.pow(2.0, Math.min(5, networkErrorCount.get() / 5).toDouble()).toLong()
                    setGlobalBackoff(backoffTime)
                    Log.w(TAG, "Multiple network errors detected. Setting global backoff for ${backoffTime}ms")
                }
                
                if (retryCount < MAX_RETRIES) {
                    val waitMs = calculateNetworkErrorBackoff(e, retryCount)
                    Log.w(TAG, "Network error: ${e.message}. Retry ${retryCount + 1}/$MAX_RETRIES after ${waitMs}ms")
                    delay(waitMs)
                    retryCount++
                } else {
                    throw e
                }
            }
        }
        
        throw lastException ?: IOException("Maximum retries exceeded")
    }
    
    override fun isThrottled(): Boolean = isThrottled && throttleEndTime > System.currentTimeMillis()
    
    override fun getRemainingThrottleTime(): Long = 
        if (isThrottled()) throttleEndTime - System.currentTimeMillis() else 0L
    
    override fun buildGetRequest(url: String, accessToken: String): Request =
        Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .build()
    
    override fun buildJsonPostRequest(url: String, accessToken: String, jsonBody: JSONObject): Request {
        val mediaType = "application/json".toMediaType()
        val requestBody = jsonBody.toString().toRequestBody(mediaType)
        
        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()
    }
    
    override fun buildDeleteRequest(url: String, accessToken: String): Request =
        Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .delete()
            .build()
    
    /**
     * Helper method to wait if the API is being throttled
     */
    private suspend fun waitForThrottlingToEnd() {
        if (isThrottled()) {
            val waitTime = getRemainingThrottleTime()
            Log.w(TAG, "API is throttled. Waiting ${waitTime}ms before proceeding.")
            delay(waitTime + 500) // Add a small buffer
            isThrottled = false
        }
    }
    
    /**
     * Set the API as throttled for a specified duration
     */
    private fun setThrottled(durationMs: Long) {
        isThrottled = true
        throttleEndTime = System.currentTimeMillis() + durationMs
        Log.w(TAG, "Setting throttle until ${throttleEndTime}")
    }
    
    /**
     * Set global backoff for all requests due to network issues
     */
    private fun setGlobalBackoff(durationMs: Long) {
        globalBackoffEndTime = System.currentTimeMillis() + durationMs.coerceAtMost(120000) // Max 2 minutes
    }
    
    /**
     * Calculate backoff time with exponential increase
     */
    private fun calculateBackoff(retryCount: Int, retryAfterSeconds: Int = 0): Long {
        val exponentialBackoff = (INITIAL_DELAY_MS * Math.pow(2.0, retryCount.toDouble())).toLong()
        val retryAfterMs = retryAfterSeconds * 1000L
        return maxOf(exponentialBackoff, retryAfterMs).coerceAtMost(60000L) // Cap at 1 minute
    }
    
    /**
     * Calculate backoff time for network errors
     */
    private fun calculateNetworkErrorBackoff(error: IOException, retryCount: Int): Long {
        val message = error.message ?: ""
        val isConnectionIssue = message.contains("Unable to resolve host") || 
                                message.contains("Failed to connect") ||
                                message.contains("Connection refused") ||
                                message.contains("timeout")
        
        // More aggressive backoff for DNS/connection issues
        val baseDelay = if (isConnectionIssue) {
            3000L * Math.pow(2.0, (retryCount + 1).toDouble())
        } else {
            INITIAL_DELAY_MS * Math.pow(2.0, retryCount.toDouble())
        }
        
        // Add jitter to prevent thundering herd
        val jitter = Random.nextLong(0, 1000)
        
        return (baseDelay + jitter).coerceAtMost(120000L) // Cap at 2 minutes
    }
    
    /**
     * Delay helper that works with suspend functions
     */
    private suspend fun delay(timeMs: Long) {
        kotlinx.coroutines.delay(timeMs)
    }
}