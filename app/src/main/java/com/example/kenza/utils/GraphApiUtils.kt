package com.example.kenza.utils

import android.net.Uri
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class GraphApiUtils {
    companion object {
        /**
         * Fetches email pages from Microsoft Graph with pagination support.
         * @param accessToken Bearer token for Graph API
         * @param baseUrl Graph API URL for messages endpoint
         * @param filterQuery Optional filter query string (starting with &)
         * @param batchSize Number of items per page (max 50)
         * @param maxEmails Maximum total emails to fetch before stopping
         * @param initialSkipToken Initial skip token for pagination
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
            val client = OkHttpClient()
            var skipToken: String? = initialSkipToken
            var fetchedCount = 0

            fun fetchPage() {
                val urlBuilder = StringBuilder("$baseUrl?").apply {
                    // Escape the dollar sign so Kotlin treats it as a literal parameter name
                    append("\$top=").append(batchSize)
                    if (filterQuery.isNotEmpty()) append(filterQuery)
                    skipToken?.let { append("&\$skiptoken=").append(it) }
                }
                val request = Request.Builder()
                    .url(urlBuilder.toString())
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
                            if (fetchedCount >= maxEmails || nextLink.isNullOrEmpty()) {
                                onComplete()
                            } else {
                                skipToken = Uri.parse(nextLink).getQueryParameter("\$skiptoken")
                                fetchPage()
                            }
                        } catch (e: Exception) {
                            onError(e.message ?: e.toString())
                        }
                    }
                })
            }

            fetchPage()
        }
    }
}
