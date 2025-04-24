package com.example.kenza.utils

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.delay
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import kotlin.math.pow

class GraphApiUtils {
    companion object {
        private const val TAG = "GraphApiUtils"
        private const val MAX_RETRIES = 5 // Max number of retries for rate limiting
        private const val INITIAL_DELAY_MS = 1000L // Initial delay in milliseconds
        
        /**
         * Fetches email pages from Microsoft Graph with pagination support.
         * @param accessToken Bearer token for Graph API
         * @param baseUrl Graph API URL for messages endpoint
         * @param filterQuery Optional filter query string (starting with &)
         * @param batchSize Number of items per page (max 50)
         * @param maxEmails Maximum total emails to fetch before stopping
         * @param initialSkipToken Initial skip token for pagination (can be either skiptoken or skip value)
         * @param initialIsSkipParameter Track which type the initial token is
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
            initialIsSkipParameter: Boolean = false,
            onBatch: (JSONArray) -> Unit,
            onProgress: (Int, Int) -> Unit = { _, _ -> },
            onComplete: () -> Unit,
            onError: (String) -> Unit
        ) {
            val client = OkHttpClient()
            var paginationValue: String? = initialSkipToken
            var isSkipParameter: Boolean = initialIsSkipParameter
            var fetchedCount = 0

            fun fetchPage() {
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

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) = onError(e.message ?: e.toString())
                    override fun onResponse(call: Call, response: Response) {
                        if (!response.isSuccessful) {
                            onError("Graph API error ${response.code}")
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
                                        fetchPage()
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
                            onError(e.message ?: e.toString())
                        }
                    }
                })
            }

            fetchPage()
        }

        suspend fun deleteEmail(
            accessToken: String,
            messageId: String,
            retryCount: Int = 0
        ): Pair<Boolean, String?> {
            val client = OkHttpClient()
            val requestUrl = "https://graph.microsoft.com/v1.0/me/messages/$messageId"
            Log.d(TAG, "Attempting to delete email: $messageId (Retry: $retryCount)")

            val request = Request.Builder()
                .url(requestUrl)
                .delete()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()

            try {
                val response = client.newCall(request).execute()

                return response.use {
                    if (it.isSuccessful) {
                        Log.d(TAG, "Successfully deleted email $messageId")
                        Pair(true, null)
                    } else {
                        val errorBody = it.body?.string()
                        Log.e(TAG, "Error deleting email $messageId: ${it.code} - $errorBody")

                        if (it.code == 429 && retryCount < MAX_RETRIES) {
                            val retryAfterSeconds = it.header("Retry-After", "1")?.toIntOrNull() ?: 1
                            val delayMs = (INITIAL_DELAY_MS * 2.0.pow(retryCount)).toLong() + (retryAfterSeconds * 1000L)
                            Log.w(TAG, "Rate limit hit for $messageId. Retrying after ${delayMs}ms...")
                            delay(delayMs)
                            deleteEmail(accessToken, messageId, retryCount + 1)
                        } else {
                            Pair(false, "Error ${it.code}: $errorBody")
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to delete email $messageId: ${e.message}", e)
                return Pair(false, e.message ?: "Network error")
            }
        }

        suspend fun moveEmail(
            accessToken: String,
            messageId: String,
            destinationFolderId: String,
            retryCount: Int = 0
        ): Pair<Boolean, String?> {
            val client = OkHttpClient()
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
                val response = client.newCall(request).execute()

                return response.use {
                    if (it.isSuccessful) {
                        Log.d(TAG, "Successfully moved email $messageId")
                        Pair(true, null)
                    } else {
                        val errorBody = it.body?.string()
                        Log.e(TAG, "Error moving email $messageId: ${it.code} - $errorBody")

                        if (it.code == 429 && retryCount < MAX_RETRIES) {
                            val retryAfterSeconds = it.header("Retry-After", "1")?.toIntOrNull() ?: 1
                            val delayMs = (INITIAL_DELAY_MS * 2.0.pow(retryCount)).toLong() + (retryAfterSeconds * 1000L)
                            Log.w(TAG, "Rate limit hit moving $messageId. Retrying after ${delayMs}ms...")
                            delay(delayMs)
                            moveEmail(accessToken, messageId, destinationFolderId, retryCount + 1)
                        } else {
                            Pair(false, "Error ${it.code}: $errorBody")
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Network error moving email $messageId: ${e.message}", e)
                return Pair(false, e.message ?: "Network error")
            }
        }

        suspend fun unsubscribe(accessToken: String, messageId: String): Pair<Boolean, String?> {
            Log.w(TAG, "Unsubscribe functionality is not implemented via Graph API directly for message $messageId.")
            return Pair(false, "Unsubscribe not implemented")
        }
    }
}
