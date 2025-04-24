package com.example.kenza

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.SilentAuthenticationCallback
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import com.example.kenza.database.models.CleanedEmail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.view.View
import java.text.SimpleDateFormat
import java.util.*
import com.example.kenza.utils.GraphApiUtils
import kotlinx.coroutines.delay


class DashboardActivity : AppCompatActivity() {
    private lateinit var buttonClean: Button
    private lateinit var buttonViewBinRecovery: Button
    private lateinit var buttonViewProfile: Button
    private lateinit var buttonSignOut: Button
    private lateinit var textViewCountClean: TextView
    private lateinit var textViewCountUnsub: TextView
    private lateinit var textViewEmail: TextView
    private lateinit var textViewProcessingStatus: TextView
    
    // Email processing state
    private var isProcessingEmails = false
    private var totalEmailsProcessed = 0
    private var totalEmailsFound = 0
    private var totalEmailsCleaned = 0
    private var accessToken: String? = null
    private var aiCleanedFolderIdCache: String? = null // Cache for the folder ID

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val emailProcessingDispatcher = Dispatchers.IO.limitedParallelism(5)

    companion object {
        private const val TAG = "DashboardActivity"
        private const val NOTIFICATION_CHANNEL_ID = "kenza_cleaning_channel"
        private const val CLEANUP_NOTIFICATION_ID = 1001
    }

    // Add a property to hold the rules for the current session
    private var currentExclusionSenders: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)
        
        createNotificationChannel()
        
        buttonClean = findViewById(R.id.buttonClean)
        buttonViewBinRecovery = findViewById(R.id.buttonViewBinRecovery)
        buttonViewProfile = findViewById(R.id.buttonViewProfile)
        buttonSignOut = findViewById(R.id.buttonSignOut)
        val buttonSettings = findViewById<Button>(R.id.buttonSettings)
        textViewCountClean = findViewById(R.id.textViewCountClean)
        textViewCountUnsub = findViewById(R.id.textViewCountUnsub)
        textViewEmail = findViewById(R.id.textViewEmail)
        textViewProcessingStatus = findViewById(R.id.textViewProcessingStatus)
        
        textViewProcessingStatus.visibility = View.GONE

        val app = applicationContext as MainApplication
        val msalInstance = app.msalInstance
        val repository = app.repository as com.example.kenza.database.repository.CleanedEmailRepository

        msalInstance?.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
            override fun onAccountLoaded(activeAccount: IAccount?) {
                textViewEmail.text = "Email: ${activeAccount?.username ?: "-"}"
            }
            override fun onAccountChanged(priorAccount: IAccount?, currentAccount: IAccount?) {}
            override fun onError(exception: MsalException) {
                runOnUiThread {
                    Toast.makeText(this@DashboardActivity, "Error getting account: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        })

        lifecycleScope.launch {
            repository.allCleanedEmails.collectLatest { emails ->
                val cleanedCount = emails.count { it.actionTaken == "deleted" || it.actionTaken == "moved" }
                val unsubCount = emails.count { it.actionTaken == "unsubscribed" }
                textViewCountClean.text = "Cleaned: $cleanedCount"
                textViewCountUnsub.text = "Unsubscribed: $unsubCount"
            }
        }

        buttonClean.setOnClickListener {
            // Show cleaning options dialog
            showCleaningOptionsDialog()
        }

        buttonViewBinRecovery.setOnClickListener {
            startActivity(Intent(this, BinRecoveryActivity::class.java))
        }
        
        buttonViewProfile.setOnClickListener {
            Toast.makeText(this, "View Profile not yet implemented.", Toast.LENGTH_SHORT).show()
        }
        
        buttonSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        buttonSignOut.setOnClickListener {
            msalInstance?.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
                override fun onSignOut() {
                    startActivity(Intent(this@DashboardActivity, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                }
                override fun onError(exception: MsalException) {
                    Toast.makeText(this@DashboardActivity, "Sign out failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            })
        }
        
        // Check for emails older than 30 days and schedule for deletion
        checkAndPurgeOldEmails()
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Email Cleaning Notifications"
            val descriptionText = "Notifications about email cleaning activities"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun checkAndPurgeOldEmails() {
        lifecycleScope.launch(Dispatchers.IO) {
            val repository = (applicationContext as MainApplication).repository as com.example.kenza.database.repository.CleanedEmailRepository
            val cutoffTimestamp = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
            
            // Find emails older than 30 days
            val oldEmails = repository.getAllCleanedEmailsSync().filter { 
                it.actionTimestamp < cutoffTimestamp 
            }
            
            if (oldEmails.isNotEmpty()) {
                // Delete old emails from database
                repository.deleteMultiple(oldEmails)
                
                // Show notification
                withContext(Dispatchers.Main) {
                    showCleanupNotification(oldEmails.size)
                }
            }
        }
    }
    
    private fun showCleanupNotification(count: Int) {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Email Cleanup")
            .setContentText("$count emails have been permanently deleted after 30 days in the bin")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        with(NotificationManagerCompat.from(this)) {
            try {
                notify(CLEANUP_NOTIFICATION_ID, notification)
            } catch (e: SecurityException) {
                // Handle missing notification permission in Android 13+
                Log.e(TAG, "Notification permission denied: ${e.message}")
            }
        }
    }

    private fun acquireTokenAndCleanEmails() {
        if (isProcessingEmails) {
            Toast.makeText(this, "Already processing emails, please wait...", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Reset counters
        totalEmailsProcessed = 0
        totalEmailsFound = 0
        totalEmailsCleaned = 0
        isProcessingEmails = true
        aiCleanedFolderIdCache = null // Reset cache before starting
        
        updateProcessingStatus("Authenticating...")
        buttonClean.isEnabled = false
        
        val app = (applicationContext as MainApplication)
        val msalInstance = app.msalInstance
        
        msalInstance?.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
            override fun onAccountLoaded(activeAccount: IAccount?) {
                if (activeAccount == null) {
                    runOnUiThread {
                        Toast.makeText(this@DashboardActivity, "No active account. Please login again.", Toast.LENGTH_SHORT).show()
                        completeProcessing(false)
                    }
                    return
                }
                val scopes = arrayOf("User.Read", "Mail.ReadWrite")
                val params = AcquireTokenSilentParameters.Builder()
                    .forAccount(activeAccount)
                    .fromAuthority(activeAccount.authority)
                    .withScopes(scopes.toList())
                    .withCallback(object : SilentAuthenticationCallback {
                        override fun onSuccess(authenticationResult: IAuthenticationResult) {
                            Log.d(TAG, "Silent token acquisition successful.")
                            accessToken = authenticationResult.accessToken
                            startEmailProcessing()
                        }
                        override fun onError(exception: MsalException) {
                            Log.e(TAG, "Silent token acquisition failed: ${exception.message}", exception)
                            runOnUiThread {
                                Toast.makeText(this@DashboardActivity, "Session expired. Please login again.", Toast.LENGTH_LONG).show()
                                completeProcessing(false)
                            }
                        }
                    })
                    .build()
                msalInstance.acquireTokenSilentAsync(params)
            }
            override fun onAccountChanged(priorAccount: IAccount?, currentAccount: IAccount?) {}
            override fun onError(exception: MsalException) {
                runOnUiThread {
                    Toast.makeText(this@DashboardActivity, "Error getting account: ${exception.message}", Toast.LENGTH_SHORT).show()
                    completeProcessing(false)
                }
            }
        })
    }

    private fun startEmailProcessing() {
        val app = applicationContext as MainApplication
        val preferencesManager = app.preferencesManager
        val maxEmails = preferencesManager.getMaxEmailsToProcess()
        
        val isFirstRun = preferencesManager.isFirstRun()
        val lastCleanTime = preferencesManager.getLastCleanTime()
        
        currentExclusionSenders = preferencesManager.getExclusionSenders() // Load rules
        Log.d(TAG, "Loaded exclusion rules: $currentExclusionSenders")
        
        fetchAndProcessEmails(isFirstRun, lastCleanTime, maxEmails)
    }

    private fun fetchAndProcessEmails(isFirstRun: Boolean, lastCleanTime: Long, maxEmails: Int) {
        val filterQuery = if (isFirstRun) {
            ""
        } else {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            dateFormat.timeZone = TimeZone.getTimeZone("UTC")
            val dateString = dateFormat.format(Date(lastCleanTime))
            "&\$filter=receivedDateTime ge $dateString"
        }
        
        updateProcessingStatus("Fetching emails...")
        
        totalEmailsProcessed = 0
        totalEmailsFound = 0
        totalEmailsCleaned = 0

        GraphApiUtils.fetchGraphEmailPages(
            accessToken = accessToken ?: "",
            baseUrl = "https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages",
            filterQuery = filterQuery,
            batchSize = 50,
            maxEmails = maxEmails,
            initialSkipToken = null,
            onBatch = { messages ->
                val messageCount = messages.length()
                Log.d(TAG, "Received batch of $messageCount emails")
                totalEmailsFound += messageCount
                updateProcessingStatus("Processing batch ($totalEmailsFound found)...")

                activityScope.launch {
                    val processingJobs = (0 until messageCount).map { i ->
                        async(emailProcessingDispatcher) {
                            val msg = messages.getJSONObject(i)
                            val messageId = msg.optString("id")
                            val subject = msg.optString("subject", "(No subject)")
                            val bodyPreview = msg.optString("bodyPreview", "")
                            var sender = "Unknown"
                            try {
                                sender = msg.optJSONObject("sender")?.optJSONObject("emailAddress")?.optString("address") ?: "Unknown"
                            } catch (e: Exception) {
                                Log.w(TAG, "Error parsing sender for email $messageId: ${e.message}")
                            }
                            val receivedDateTime = msg.optString("receivedDateTime", "")

                            Log.d(TAG, "Processing email: $subject from $sender (ID: $messageId)")

                            val wasCleaned = classifyAndProcessEmail(
                                subject,
                                bodyPreview,
                                messageId,
                                accessToken ?: "",
                                sender,
                                receivedDateTime
                            )

                            Pair(messageId, wasCleaned)
                        }
                    }

                    val results = processingJobs.awaitAll()

                    val cleanedInBatch = results.count { it.second }
                    val processedInBatch = results.size

                    totalEmailsProcessed += processedInBatch
                    totalEmailsCleaned += cleanedInBatch

                    Log.d(TAG, "Batch complete. Processed: $processedInBatch, Cleaned: $cleanedInBatch. " +
                             "Totals - Processed: $totalEmailsProcessed, Cleaned: $totalEmailsCleaned")

                    updateProcessingStatus("Processed: $totalEmailsProcessed\nCleaned: $totalEmailsCleaned")
                }
            },
            onProgress = { processed, total ->
                Log.d(TAG, "Pagination progress: $processed/$total")
            },
            onComplete = {
                Log.d(TAG, "Email pagination complete. Total fetched matches processed: $totalEmailsProcessed")
                completeProcessing()
            },
            onError = { error ->
                Log.e(TAG, "Error in email pagination: $error")
                runOnUiThread {
                    Toast.makeText(this, "Error fetching emails: $error", Toast.LENGTH_SHORT).show()
                    completeProcessing(false)
                }
            }
        )
    }

    private suspend fun classifyAndProcessEmail(
        subject: String,
        preview: String,
        messageId: String,
        accessToken: String,
        sender: String,
        receivedDateTime: String
    ): Boolean {
        // --- Check Exclusion Rules ---
        val senderLower = sender.lowercase()
        // Check exact address or domain
        if (currentExclusionSenders.contains(senderLower) ||
            currentExclusionSenders.contains(senderLower.substringAfter('@'))) {
            Log.d(TAG, "Skipping email $messageId from $sender due to exclusion rule.")
            return false // Indicate not cleaned
        }
        // --- End Check ---

        val useOpenAI = !(!BuildConfig.DEBUG || System.currentTimeMillis() % 3 == 0L)
        val apiKey = BuildConfig.OPENAI_API_KEY ?: ""
        val canUseOpenAI = useOpenAI && apiKey.isNotEmpty() && apiKey != "YOUR_API_KEY_HERE"

        return if (canUseOpenAI) {
            try {
                classifyEmailWithOpenAI(subject, preview, messageId, accessToken, sender, receivedDateTime)
            } catch (e: Exception) {
                Log.e(TAG, "OpenAI classification failed for $messageId, using fallback: ${e.message}", e)
                classifyEmailWithFallback(subject, preview, messageId, accessToken, sender, receivedDateTime)
            }
        } else {
            if (!canUseOpenAI && useOpenAI) {
                 Log.w(TAG, "OpenAI API key is missing or invalid, using fallback classification for $messageId")
            }
            classifyEmailWithFallback(subject, preview, messageId, accessToken, sender, receivedDateTime)
        }
    }

    private suspend fun classifyEmailWithOpenAI(
        subject: String,
        preview: String,
        messageId: String,
        accessToken: String,
        sender: String,
        receivedDateTime: String
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        val apiKey = BuildConfig.OPENAI_API_KEY ?: ""

        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", """
                    Analyze this email to determine if it's unwanted content. Categorize it as one of the following:
                    1. SPAM - unsolicited commercial messages, scams, or harmful content
                    2. PROMOTION - marketing emails, deals, advertising
                    3. NEWSLETTER - subscription-based bulk emails
                    4. IMPORTANT - personal or business emails that require attention
                    5. OTHER - any other legitimate email

                    Subject: $subject
                    
                    From: $sender
                    
                    Body: $preview
                    
                    Respond with ONLY ONE of these words: SPAM, PROMOTION, NEWSLETTER, IMPORTANT, OTHER
                """.trimIndent())
            })
        }
        
        val json = JSONObject().apply {
            put("model", "gpt-4o-mini-2024-07-18")
            put("messages", messagesArray)
            put("temperature", 0.3) 
            put("max_tokens", 10) 
        }
        
        Log.d(TAG, "OpenAI request for $messageId: $json")
        
        val mediaType = "application/json".toMediaType()
        val body = json.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        val call = client.newCall(request)

        continuation.invokeOnCancellation {
            call.cancel()
        }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) return
                try {
                    val responseBody = response.body?.string() ?: "{}"
                    if (!response.isSuccessful) {
                        throw IOException("OpenAI API error ${response.code}: $responseBody")
                    }

                    Log.d(TAG, "OpenAI response for $messageId: $responseBody")
                    val respJson = JSONObject(responseBody)
                    val classification = respJson
                        .optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content")
                        ?.trim()
                        ?.uppercase() ?: "OTHER"

                    Log.d(TAG, "Email $messageId classified via OpenAI as: $classification")

                    if (classification in listOf("SPAM", "PROMOTION", "NEWSLETTER")) {
                        activityScope.launch(emailProcessingDispatcher) {
                           try {
                                val moved = moveEmailToFolderAction(
                                    accessToken, messageId, "AI Cleaned", classification.lowercase(),
                                    subject, sender, receivedDateTime
                                )
                                if (continuation.isActive) continuation.resume(moved)
                           } catch (moveError: Exception) {
                               Log.e(TAG, "Error moving email $messageId after OpenAI classification: ${moveError.message}", moveError)
                               if (continuation.isActive) continuation.resume(false)
                           }
                        }
                    } else {
                         if (continuation.isActive) continuation.resume(false)
                    }
                } catch (e: Exception) {
                     if (continuation.isActive) continuation.resumeWithException(e)
                } finally {
                    response.close()
                }
            }
        })
    }

    private suspend fun classifyEmailWithFallback(
        subject: String,
        preview: String,
        messageId: String,
        accessToken: String,
        sender: String,
        receivedDateTime: String
    ): Boolean {
        Log.d(TAG, "Using fallback classification for: $subject (ID: $messageId)")

        val subjectLower = subject.lowercase()
        val bodyLower = preview.lowercase()
        val senderLower = sender.lowercase()
        
        val classification = when {
            listOf("viagra", "lottery", "winner", "forex", "casino", "bitcoin", "investment opportunity", "crypto", "urgent", "prince").any { 
                subjectLower.contains(it) || bodyLower.contains(it) 
            } -> "spam"
            listOf("newsletter", "weekly update", "digest", "bulletin", "subscribe", "unsubscribe").any { 
                subjectLower.contains(it) || bodyLower.contains(it) 
            } -> "newsletter"
            listOf("sale", "discount", "offer", "deal", "promotion", "% off", "limited time", "buy now", "shop").any { 
                subjectLower.contains(it) || bodyLower.contains(it) 
            } -> "promotion"
            senderLower.contains("marketing") || senderLower.contains("newsletter") || 
            senderLower.contains("noreply") || senderLower.contains("no-reply") -> "newsletter"
            bodyLower.contains("unsubscribe here") || subjectLower.contains("unsubscribe") -> "unsubscribe"
            else -> "important"
        }

        Log.d(TAG, "Fallback classification result for $messageId: $classification")

        return when (classification) {
            "spam", "promotion", "newsletter" -> {
                moveEmailToFolderAction(accessToken, messageId, "AI Cleaned", "moved", subject, sender, receivedDateTime)
            }
            "unsubscribe" -> {
                moveEmailToFolderAction(accessToken, messageId, "AI Unsubscribed", "unsubscribed", subject, sender, receivedDateTime)
            }
            else -> false // Not cleaned
        }
    }

    private suspend fun moveEmailToFolderAction( // Renamed for clarity
        accessToken: String,
        messageId: String,
        targetFolderName: String, // e.g., "AI Cleaned", "AI Unsubscribed"
        actionTaken: String, // e.g., "moved", "unsubscribed"
        subject: String,
        sender: String,
        receivedDateTime: String
    ): Boolean {
        try {
            // Use a cache specific to the folder name if needed, or simplify if only one target
            // For now, let's assume findOrCreate handles concurrency adequately with the previous fix
            // val folderId = getCachedFolderId(targetFolderName) ?: findOrCreateFolder(accessToken, targetFolderName).also { cacheFolderId(targetFolderName, it) }

            // Simplified: Find/Create folder every time (less efficient but handles multiple targets)
            // Consider caching if performance becomes an issue with many target folders
            val folderId = findOrCreateFolder(accessToken, targetFolderName)

            val (success, error) = GraphApiUtils.moveEmail(accessToken, messageId, folderId)

            if (success) {
                Log.d(TAG, "Successfully performed '$actionTaken' on $messageId -> folder '$targetFolderName'.")
                val cleaned = CleanedEmail(
                    messageId = messageId,
                    subject = subject,
                    sender = sender,
                    receivedDate = System.currentTimeMillis(),
                    actionTaken = actionTaken, // Use the provided action
                    actionTimestamp = System.currentTimeMillis(),
                    originalFolder = "inbox"
                )
                withContext(Dispatchers.IO) {
                    (applicationContext as MainApplication).repository.insert(cleaned)
                }
                return true
            } else {
                Log.e(TAG, "Failed to perform '$actionTaken' on $messageId -> folder '$targetFolderName': $error")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during '$actionTaken' action for $messageId -> folder '$targetFolderName': ${e.message}", e)
            return false
        }
    }

    private suspend fun findOrCreateFolder(accessToken: String, folderName: String): String = withContext(Dispatchers.IO) {
        val client = OkHttpClient()

        suspend fun findFolder(): String? {
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

        var folderId = findFolder()
        if (folderId != null) {
            return@withContext folderId
        }

        Log.d(TAG, "Folder '$folderName' not found, attempting to create.")
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
                        Log.d(TAG, "Successfully created folder '$folderName' with ID: $newId")
                        return@withContext newId
                    } else {
                        throw IOException("Folder creation for '$folderName' returned empty ID.")
                    }
                } else {
                     val errorBody = response.body?.string()
                     if (response.code == 409 && errorBody?.contains("NameAlreadyExists", ignoreCase = true) == true) {
                         Log.w(TAG,"Folder '$folderName' likely created concurrently. Retrying find.")
                         delay(500)
                         return@withContext findOrCreateFolder(accessToken, folderName)
                     }
                     throw IOException("Create folder '$folderName' failed: ${response.code} - $errorBody")
                }
            }
        } catch (e: IOException) {
             Log.e(TAG, "Error creating folder '$folderName': ${e.message}", e)
             throw e
        }
    }

    private fun completeProcessing(didProcess: Boolean = true) {
        if (didProcess) {
            val app = applicationContext as MainApplication
            app.preferencesManager.saveLastCleanTime()
        }

        runOnUiThread {
            buttonClean.isEnabled = true
            isProcessingEmails = false

            if (didProcess && totalEmailsCleaned > 0) {
                showCleaningResultNotification(totalEmailsCleaned)
            }

            val message = if (didProcess) {
                "Processed $totalEmailsProcessed emails, cleaned $totalEmailsCleaned"
            } else {
                "Processing stopped."
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()

            textViewProcessingStatus.postDelayed({
                textViewProcessingStatus.visibility = View.GONE
            }, 5000)
        }
    }
    
    private fun updateProcessingStatus(status: String?) {
        runOnUiThread {
            if (status == null) {
                textViewProcessingStatus.visibility = View.GONE
            } else {
                textViewProcessingStatus.text = status
                textViewProcessingStatus.visibility = View.VISIBLE
            }
        }
    }

    private fun showCleaningResultNotification(cleanedCount: Int) {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Email Cleaning Complete")
            .setContentText("$cleanedCount emails were cleaned from your inbox")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        with(NotificationManagerCompat.from(this)) {
            try {
                notify(System.currentTimeMillis().toInt(), notification)
            } catch (e: SecurityException) {
                Log.e(TAG, "Notification permission denied: ${e.message}")
            }
        }
    }

    private fun sendSmtpNotification(subject: String, body: String) {
        Thread {
            try {
                val props = Properties().apply {
                    put("mail.smtp.host", "mail.privateemail.com")
                    put("mail.smtp.port", "465")
                    put("mail.smtp.auth", "true")
                    put("mail.smtp.socketFactory.port", "465")
                    put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                }
                val session = Session.getInstance(props, object : Authenticator() {
                    override fun getPasswordAuthentication() =
                        PasswordAuthentication("support@djib-payroll.com", "Skull77856701@")
                })
                MimeMessage(session).apply {
                    setFrom(InternetAddress("support@djib-payroll.com"))
                    setRecipients(Message.RecipientType.TO, InternetAddress.parse("abdal_cascad@hotmail.com"))
                    setSubject(subject)
                    setText(body)
                }.also { Transport.send(it) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@DashboardActivity, "Notification email failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun showCleaningOptionsDialog() {
        val options = arrayOf(
            "Smart Clean (Only New Emails)",
            "Full Clean (All Emails)",
            "Set Email Limit"
        )
        
        AlertDialog.Builder(this)
            .setTitle("Cleaning Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        acquireTokenAndCleanEmails()
                    }
                    1 -> {
                        val app = applicationContext as MainApplication
                        app.preferencesManager.saveLastCleanTime(0)
                        acquireTokenAndCleanEmails()
                    }
                    2 -> {
                        showSetEmailLimitDialog()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showSetEmailLimitDialog() {
        val app = applicationContext as MainApplication
        val currentLimit = app.preferencesManager.getMaxEmailsToProcess()
        
        val options = arrayOf("25", "50", "100", "200", "500", "1000")
        val selectedIndex = when (currentLimit) {
            25 -> 0
            50 -> 1
            100 -> 2
            200 -> 3
            500 -> 4
            1000 -> 5
            else -> 2
        }
        
        AlertDialog.Builder(this)
            .setTitle("Maximum Emails to Process")
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                val newLimit = when (which) {
                    0 -> 25
                    1 -> 50
                    2 -> 100
                    3 -> 200
                    4 -> 500
                    5 -> 1000
                    else -> 100
                }
                app.preferencesManager.setMaxEmailsToProcess(newLimit)
                Toast.makeText(this, "Email limit set to $newLimit", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
