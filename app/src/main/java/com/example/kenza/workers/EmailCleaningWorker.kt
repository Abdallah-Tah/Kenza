// filepath: c:\Users\amohamed\AndroidStudioProjects\Kenza\app\src\main\java\com\example\kenza\workers\EmailCleaningWorker.kt
package com.example.kenza.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.kenza.MainApplication
import com.example.kenza.utils.GraphApiUtils
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.SilentAuthenticationCallback
import com.microsoft.identity.client.exception.MsalException
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.text.SimpleDateFormat
import java.util.*
import com.example.kenza.database.models.CleanedEmail
import org.json.JSONObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class EmailCleaningWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "EmailCleaningWorker"
        const val WORK_NAME = "scheduled_email_cleaning"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Scheduled cleaning work starting...")
        val application = applicationContext as MainApplication
        val preferencesManager = application.preferencesManager
        val msalInstance = application.msalInstance

        if (msalInstance == null) {
            Log.e(TAG, "MSAL instance is null. Cannot proceed.")
            return Result.failure()
        }

        try {
            val accessToken = acquireTokenSilently(msalInstance)
            if (accessToken == null) {
                Log.e(TAG, "Failed to acquire token silently for background work.")
                return Result.failure()
            }

            Log.d(TAG, "Background token acquired. Starting email fetch.")

            // Get the parameters for email processing
            val maxEmails = preferencesManager.getMaxEmailsToProcess()
            val lastCleanTime = preferencesManager.getLastCleanTime()
            val filterQuery = if (lastCleanTime == 0L) "" else {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                dateFormat.timeZone = TimeZone.getTimeZone("UTC")
                val dateString = dateFormat.format(Date(lastCleanTime))
                "&\$filter=receivedDateTime ge $dateString"
            }
            val exclusionSenders = preferencesManager.getExclusionSenders()
            var totalProcessed = 0
            var totalCleaned = 0

            // Create a coroutine scope tied to our worker
            val processingScope = CoroutineScope(Dispatchers.IO + Job())

            val processedMessages = mutableListOf<Deferred<Pair<Boolean, Boolean>>>() // (processed, cleaned)

            // Process emails in the background
            withContext(Dispatchers.IO) {
                GraphApiUtils.fetchGraphEmailPages(
                    accessToken = accessToken,
                    baseUrl = "https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages",
                    filterQuery = filterQuery,
                    batchSize = 50,
                    maxEmails = maxEmails,
                    initialSkipToken = null,
                    onBatch = { messages ->
                        val messageCount = messages.length()
                        Log.d(TAG, "Worker received batch of $messageCount emails")
                        
                        // Process each email in the batch using async to handle suspend functions
                        for (i in 0 until messageCount) {
                            val msg = messages.getJSONObject(i)
                            val messageId = msg.optString("id")
                            val subject = msg.optString("subject", "(No subject)")
                            val bodyPreview = msg.optString("bodyPreview", "")
                            val sender = msg.optJSONObject("sender")?.optJSONObject("emailAddress")
                                ?.optString("address")?.lowercase() ?: "unknown"
                            val receivedDateTime = msg.optString("receivedDateTime", "")

                            // Launch an async task for each email
                            val processTask = processingScope.async {
                                // Skip emails from excluded senders
                                if (exclusionSenders.any { 
                                    sender == it.lowercase() || 
                                    sender.endsWith("@${it.lowercase()}") || 
                                    sender.contains(it.lowercase()) 
                                }) {
                                    Log.d(TAG, "Worker skipping $messageId from $sender due to exclusion rule.")
                                    Pair(true, false) // processed but not cleaned
                                } else {
                                    // Classify and process the email
                                    val classification = classifyEmailWithFallback(subject, bodyPreview, sender)
                                    Log.d(TAG, "Worker classified $messageId as: $classification")

                                    if (classification in listOf("spam", "newsletter", "promotion", "unsubscribe")) {
                                        val folderName = if (classification == "unsubscribe") "AI Unsubscribed" else "AI Cleaned"
                                        val actionType = if (classification == "unsubscribe") "unsubscribed" else "moved"
                                        
                                        try {
                                            val folderMoved = moveEmailToFolder(
                                                accessToken, messageId, folderName, actionType,
                                                subject, sender, receivedDateTime
                                            )
                                            if (folderMoved) {
                                                Log.d(TAG, "Worker successfully processed email $messageId as $classification")
                                                Pair(true, true) // processed and cleaned
                                            } else {
                                                Log.e(TAG, "Worker failed to move email $messageId to $folderName")
                                                Pair(true, false) // processed but not cleaned
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error during email $messageId processing: ${e.message}", e)
                                            Pair(true, false) // processed but not cleaned
                                        }
                                    } else {
                                        Pair(true, false) // processed but not cleaned
                                    }
                                }
                            }
                            processedMessages.add(processTask)
                        }
                    },
                    onProgress = { processed, total -> 
                        Log.d(TAG, "Worker pagination progress: $processed/$total") 
                    },
                    onComplete = { 
                        // Wait for all processing tasks to complete
                        runBlocking {
                            val results = processedMessages.awaitAll()
                            totalProcessed = results.count { it.first }
                            totalCleaned = results.count { it.second }
                            Log.d(TAG, "Worker pagination complete. Total processed: $totalProcessed, Cleaned: $totalCleaned")
                        }
                    },
                    onError = { error -> 
                        Log.e(TAG, "Worker pagination error: $error") 
                    }
                )
            }
            
            // Cancel our processing scope
            processingScope.cancel()

            // Update last clean time in preferences
            preferencesManager.saveLastCleanTime()
            Log.d(TAG, "Scheduled cleaning work finished. Processed: $totalProcessed, Cleaned: $totalCleaned")
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error during scheduled cleaning work: ${e.message}", e)
            return Result.failure()
        }
    }

    // Helper to get authentication token silently
    private suspend fun acquireTokenSilently(msalInstance: ISingleAccountPublicClientApplication): String? =
        suspendCancellableCoroutine { continuation -> 
            msalInstance.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
                override fun onAccountLoaded(activeAccount: IAccount?) {
                    if (activeAccount == null) {
                        Log.w(TAG, "No current account found for silent auth.")
                        if (continuation.isActive) continuation.resume(null)
                        return
                    }

                    val scopes = arrayOf("User.Read", "Mail.ReadWrite")
                    val params = AcquireTokenSilentParameters.Builder()
                        .forAccount(activeAccount)
                        .fromAuthority(activeAccount.authority)
                        .withScopes(scopes.toList())
                        .withCallback(object : SilentAuthenticationCallback {
                            override fun onSuccess(authenticationResult: IAuthenticationResult) {
                                Log.d(TAG, "Background silent token success.")
                                if (continuation.isActive) continuation.resume(authenticationResult.accessToken)
                            }
                            override fun onError(exception: MsalException) {
                                Log.e(TAG, "Background silent token failed: ${exception.message}")
                                if (continuation.isActive) continuation.resume(null)
                            }
                        })
                        .build()

                    msalInstance.acquireTokenSilentAsync(params)
                }

                override fun onAccountChanged(priorAccount: IAccount?, currentAccount: IAccount?) {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onError(exception: MsalException) {
                    Log.e(TAG, "Error getting account: ${exception.message}")
                    if (continuation.isActive) continuation.resume(null)
                }
            })
        }

    // Simple classification function - similar to the fallback in DashboardActivity
    private fun classifyEmailWithFallback(subject: String, bodyPreview: String, sender: String): String {
        val subjectLower = subject.lowercase()
        val bodyLower = bodyPreview.lowercase()
        val senderLower = sender.lowercase()
        
        return when {
            listOf("viagra", "lottery", "winner", "forex", "casino", "bitcoin", "investment opportunity", 
                   "crypto", "urgent", "prince").any { 
                subjectLower.contains(it) || bodyLower.contains(it) 
            } -> "spam"
            
            listOf("newsletter", "weekly update", "digest", "bulletin", "subscribe", "unsubscribe").any { 
                subjectLower.contains(it) || bodyLower.contains(it) 
            } -> "newsletter"
            
            listOf("sale", "discount", "offer", "deal", "promotion", "% off", "limited time", 
                   "buy now", "shop").any { 
                subjectLower.contains(it) || bodyLower.contains(it) 
            } -> "promotion"
            
            senderLower.contains("marketing") || senderLower.contains("newsletter") || 
            senderLower.contains("noreply") || senderLower.contains("no-reply") -> "newsletter"
            
            (subjectLower.contains("unsubscribe") || bodyLower.contains("unsubscribe now")) &&
            (bodyLower.contains("mailing list") || bodyLower.contains("newsletter")) -> "unsubscribe"
            
            else -> "important"
        }
    }

    private suspend fun moveEmailToFolder(
        accessToken: String,
        messageId: String,
        folderName: String,
        actionType: String,
        subject: String,
        sender: String,
        receivedDateTime: String
    ): Boolean {
        try {
            // Find or create the target folder
            val folderId = findOrCreateFolder(accessToken, folderName)
            
            // Move the email
            val (success, error) = GraphApiUtils.moveEmail(accessToken, messageId, folderId)
            
            if (success) {
                Log.d(TAG, "Worker: Successfully moved $messageId to $folderName folder")
                
                // Save record to database
                val cleaned = CleanedEmail(
                    messageId = messageId,
                    subject = subject,
                    sender = sender,
                    receivedDate = System.currentTimeMillis(),
                    actionTaken = actionType,
                    actionTimestamp = System.currentTimeMillis(),
                    originalFolder = "inbox"
                )
                
                // Use the application repository to save the record
                // Wrap the repository call in withContext to ensure it's called in a coroutine context
                withContext(Dispatchers.IO) {
                    val app = applicationContext as MainApplication
                    app.repository.insert(cleaned)
                }
                
                return true
            } else {
                Log.e(TAG, "Worker: Failed to move $messageId to $folderName folder: $error")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Worker: Error moving email $messageId to $folderName: ${e.message}", e)
            return false
        }
    }

    private suspend fun findOrCreateFolder(accessToken: String, folderName: String): String = withContext(Dispatchers.IO) {
        val client = OkHttpClient()

        // Helper function to find a folder by name - removed unnecessary suspend modifier
        fun findFolder(): String? {
            val findRequest = Request.Builder()
                .url("https://graph.microsoft.com/v1.0/me/mailFolders?\$filter=displayName eq '$folderName'")
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
            try {
                client.newCall(findRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        val folders = JSONObject(responseBody ?: "").optJSONArray("value")
                        if (folders != null && folders.length() > 0) {
                            val existingId = folders.getJSONObject(0).optString("id")
                            if (existingId.isNotEmpty()) {
                                Log.d(TAG, "Worker found existing folder '$folderName' with ID: $existingId")
                                return existingId
                            }
                        }
                    } else {
                        Log.w(TAG, "Worker failed to query for folder '$folderName': ${response.code}")
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Worker network error finding folder '$folderName': ${e.message}", e)
            }
            return null
        }

        // First attempt to find the folder
        val folderId = findFolder()
        if (folderId != null) {
            return@withContext folderId
        }

        // If not found, try to create it
        Log.d(TAG, "Worker: Folder '$folderName' not found, attempting to create.")
        val mediaType = "application/json".toMediaType()
        val createJson = JSONObject().apply { put("displayName", folderName) }
        val createBody = createJson.toString().toRequestBody(mediaType)
        val createRequest = Request.Builder()
            .url("https://graph.microsoft.com/v1.0/me/mailFolders")
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Content-Type", "application/json")
            .post(createBody)
            .build()

        try {
            client.newCall(createRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val newId = JSONObject(responseBody ?: "").optString("id")
                    if (newId.isNotEmpty()) {
                        Log.d(TAG, "Worker successfully created folder '$folderName' with ID: $newId")
                        return@withContext newId
                    } else {
                        throw IOException("Worker folder creation for '$folderName' returned empty ID.")
                    }
                } else {
                    val errorBody = response.body?.string()
                    // Handle conflict (409) - another request may have created the folder already
                    if (response.code == 409 && errorBody?.contains("NameAlreadyExists", ignoreCase = true) == true) {
                        Log.w(TAG, "Worker: Folder '$folderName' likely created concurrently. Retrying find.")
                        delay(500)
                        // Try finding it again after a short delay - need to use a new variable name to avoid smart cast issue
                        val retryFolderId = findFolder()
                        if (retryFolderId != null) {
                            return@withContext retryFolderId
                        } else {
                            throw IOException("Worker: Folder '$folderName' creation failed with 409, but couldn't find it immediately after.")
                        }
                    } else {
                        throw IOException("Worker: Create folder '$folderName' failed: ${response.code} - $errorBody")
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Worker error creating folder '$folderName': ${e.message}", e)
            throw e
        }
    }
}