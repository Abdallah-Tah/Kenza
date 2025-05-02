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
import android.net.ConnectivityManager
import android.os.Build
import android.view.View
import java.text.SimpleDateFormat
import java.util.*
import com.example.kenza.utils.GraphApiUtils
import com.example.kenza.utils.GraphEmailService
import com.example.kenza.utils.GraphApiProvider
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
    private lateinit var graphApiService: com.example.kenza.utils.GraphEmailService  // Changed from network.GraphApiService to utils.GraphEmailService

    // Email processing state
    private var isProcessingEmails = false
    private var totalEmailsProcessed = 0
    private var totalEmailsFound = 0
    private var totalEmailsCleaned = 0
    private var accessToken: String? = null
    private var aiCleanedFolderIdCache: String? = null // Cache for the folder ID

    // Email processing queue
    private var emailProcessingQueue: com.example.kenza.utils.EmailProcessingQueue? = null

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

        // Debug: Check if API key is available
        val apiKey = BuildConfig.OPENAI_API_KEY
        Log.d(TAG, "OpenAI API key is ${if (apiKey.isNotEmpty()) "available" else "missing"} (length: ${apiKey.length})")

        createNotificationChannel()

        // Corrected: Use graphEmailService instead of graphApiService
        graphApiService = GraphApiProvider.graphEmailService

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
        val app = applicationContext as MainApplication
        val preferencesManager = app.preferencesManager
        
        // Set the processing flag to true and clear any previous batch ID
        preferencesManager.setProcessingInProgress(true)
        preferencesManager.saveProcessingBatchId(null)
        
        // Check if a previous run was interrupted
        val wasInterrupted = preferencesManager.isProcessingInProgress() && !isFirstRun
        if (wasInterrupted) {
            Log.w(TAG, "Detected a previous interrupted email processing session. Continuing from last known point.")
        }
        
        // Use the ISO date from the last processed email if available, otherwise use timestamp
        val lastProcessedDate = preferencesManager.getLastProcessedEmailDate()
        val filterQuery = if (isFirstRun) {
            ""
        } else if (lastProcessedDate != null) {
            // Use the more precise ISO date string if available
            Log.d(TAG, "Filtering emails newer than ISO date: $lastProcessedDate")
            "&\$filter=receivedDateTime gt $lastProcessedDate"
        } else {
            // Fall back to timestamp-based filtering
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            dateFormat.timeZone = TimeZone.getTimeZone("UTC")
            val dateString = dateFormat.format(Date(lastCleanTime))
            Log.d(TAG, "Filtering emails newer than timestamp: $dateString")
            "&\$filter=receivedDateTime gt $dateString"
        }

        updateProcessingStatus("Fetching emails...")

        totalEmailsProcessed = 0
        totalEmailsFound = 0
        totalEmailsCleaned = 0

        // Initialize email processing queue with 50-email chunks
        emailProcessingQueue = com.example.kenza.utils.EmailProcessingQueue(
            scope = activityScope,
            processFunction = { task ->
                // This function will process a single email and return whether it was cleaned
                val wasCleaned = classifyAndProcessEmail(
                    subject = task.subject,
                    preview = task.preview,
                    messageId = task.id,
                    accessToken = task.accessToken,
                    sender = task.sender,
                    receivedDateTime = task.receivedDateTime
                )

                // Update our totals when an email is successfully cleaned
                if (wasCleaned) {
                    totalEmailsCleaned++
                    
                    // Store this email's received date as our latest processed email
                    // This helps us pick up from exactly where we left off if interrupted
                    if (task.receivedDateTime.isNotEmpty()) {
                        preferencesManager.saveLastProcessedEmailDate(task.receivedDateTime)
                    }
                }

                wasCleaned
            },
            chunkSize = 50, // Process in chunks of 50
            processingDelayMs = 1000, // Add a delay between chunks to avoid throttling
            onStatsUpdate = { stats ->
                // Update UI when processing stats change
                totalEmailsProcessed = stats.processed

                runOnUiThread {
                    updateProcessingStatus("Found: $totalEmailsFound\nProcessed: ${stats.processed}\nCleaned: $totalEmailsCleaned\nQueued: ${stats.queued}")
                }
            }
        )

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
                updateProcessingStatus("Queuing batch ($totalEmailsFound found)...")

                activityScope.launch {
                    val emailTasks = mutableListOf<com.example.kenza.utils.EmailProcessingQueue.EmailTask>()

                    // Convert JSON messages to EmailTask objects
                    for (i in 0 until messageCount) {
                        try {
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

                            // Add to our batch of emails to process
                            emailTasks.add(
                                com.example.kenza.utils.EmailProcessingQueue.EmailTask(
                                    id = messageId,
                                    subject = subject,
                                    preview = bodyPreview,
                                    sender = sender,
                                    receivedDateTime = receivedDateTime,
                                    accessToken = accessToken ?: ""
                                )
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing email message: ${e.message}")
                        }
                    }

                    // Add all emails to the processing queue
                    emailProcessingQueue?.enqueueEmails(emailTasks)
                }
            },
            onProgress = { processed, total ->
                Log.d(TAG, "Pagination progress: $processed/$total")
            },
            onComplete = {
                Log.d(TAG, "Email pagination complete. Total fetched: $totalEmailsFound")

                // Wait for all emails to be processed before completing
                activityScope.launch {
                    var remainingEmails = 1 // Just to enter the loop

                    // Poll until queue is empty
                    while (remainingEmails > 0) {
                        val stats = emailProcessingQueue?.getStats()
                        remainingEmails = stats?.queued ?: 0

                        // If there are emails still being processed, wait a bit
                        if (remainingEmails > 0) {
                            delay(1000)
                        }
                    }

                    // Clean up the queue
                    emailProcessingQueue?.stop()
                    emailProcessingQueue = null

                    // Complete the processing
                    completeProcessing()
                }
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

        // Simplified logic to use OpenAI
        val apiKey = BuildConfig.OPENAI_API_KEY ?: ""
        
        // Log the API key length for debugging (never log the actual key)
        Log.d(TAG, "OpenAI API key is ${if (apiKey.length > 5) "valid" else "missing or invalid"} (length: ${apiKey.length})")
        
        // Use OpenAI if we have a valid API key
        val canUseOpenAI = apiKey.length > 20

        return if (canUseOpenAI) {
            try {
                classifyEmailWithOpenAI(subject, preview, messageId, accessToken, sender, receivedDateTime)
            } catch (e: Exception) {
                Log.e(TAG, "OpenAI classification failed for $messageId, using fallback: ${e.message}", e)
                classifyEmailWithFallback(subject, preview, messageId, accessToken, sender, receivedDateTime)
            }
        } else {
            if (!canUseOpenAI) {
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
    ): Boolean {
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
                    Analyze the following email content (Subject, Sender, Body Preview) to determine its category. Respond with ONLY ONE of the following words:

                    1.  **SPAM**: Unsolicited junk, scams, phishing attempts, or clearly unwanted bulk mail.
                    2.  **PROMOTION**: Marketing emails, advertisements, sales offers, deals.
                    3.  **NEWSLETTER**: Regular updates, digests, or bulletins the user likely subscribed to.
                    4.  **UNSUBSCRIBE**: Emails primarily offering a way to unsubscribe from mailing lists or newsletters, even if also promotional or newsletter-like. Look for explicit "unsubscribe" links or phrases.
                    5.  **IMPORTANT**: Personal messages, direct business communication, replies, notifications requiring user action (e.g., password resets, alerts).
                    6.  **OTHER**: Legitimate emails that don't fit the above categories (e.g., confirmations, transactional emails, general updates).

                    Subject: $subject
                    Sender: $sender
                    Body Preview: $preview
                """.trimIndent())
            })
        }

        val json = JSONObject().apply {
            put("model", "gpt-3.5-turbo")
            put("messages", messagesArray)
            put("temperature", 0.2)
            put("max_tokens", 15)
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

        // Add network connectivity check
        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkInfo = connectivityManager.activeNetworkInfo
        
        if (networkInfo == null || !networkInfo.isConnected) {
            Log.e(TAG, "No network connection available for OpenAI API call")
            return classifyEmailWithFallback(subject, preview, messageId, accessToken, sender, receivedDateTime)
        }

        // Execute with retry logic
        var retryCount = 0
        val maxRetries = 2
        
        try {
            while (true) {
                try {
                    return withContext(Dispatchers.IO) {
                        val response = client.newCall(request).execute()
                        response.use { resp ->
                            val responseBody = resp.body?.string() ?: "{}"
                            if (!resp.isSuccessful) {
                                // For server errors (5xx), retry
                                if (resp.code in 500..599 && retryCount < maxRetries) {
                                    retryCount++
                                    Log.w(TAG, "OpenAI API server error ${resp.code}, retrying (${retryCount}/${maxRetries}): $responseBody")
                                    delay(1000L * retryCount)
                                    return@withContext false // Continue the outer while loop
                                }
                                
                                throw IOException("OpenAI API error ${resp.code}: $responseBody")
                            }

                            Log.d(TAG, "OpenAI response for $messageId: $responseBody")
                            val respJson = JSONObject(responseBody)
                            val classification = respJson
                                .optJSONArray("choices")
                                ?.optJSONObject(0)
                                ?.optJSONObject("message")
                                ?.optString("content")
                                ?.trim()
                                ?.uppercase()
                                ?.replace(".", "")
                                ?.split(" ")?.firstOrNull()
                                ?: "OTHER"

                            Log.d(TAG, "Email $messageId classified via OpenAI as: $classification")

                            val actionFolder: String?
                            val actionTaken: String?

                            when (classification) {
                                "SPAM", "PROMOTION", "NEWSLETTER" -> {
                                    actionFolder = "AI Cleaned"
                                    actionTaken = "moved"
                                }
                                "UNSUBSCRIBE" -> {
                                    actionFolder = "AI Unsubscribed"
                                    actionTaken = "unsubscribed"
                                }
                                else -> {
                                    actionFolder = null
                                    actionTaken = null
                                }
                            }

                            if (actionFolder != null && actionTaken != null) {
                                withContext(emailProcessingDispatcher) {
                                    moveEmailToFolderAction(
                                        accessToken,
                                        messageId, actionFolder, actionTaken,
                                        subject, sender, receivedDateTime
                                    )
                                }
                            } else {
                                false
                            }
                        }
                    }
                } catch (e: IOException) {
                    if (retryCount < maxRetries) {
                        retryCount++
                        Log.w(TAG, "Network failure for OpenAI API, retrying (${retryCount}/${maxRetries}): ${e.message}")
                        delay(1000L * retryCount)
                    } else {
                        Log.e(TAG, "OpenAI API call failed after $maxRetries retries: ${e.message}")
                        return classifyEmailWithFallback(subject, preview, messageId, accessToken, sender, receivedDateTime)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in OpenAI classification: ${e.message}", e)
            return classifyEmailWithFallback(subject, preview, messageId, accessToken, sender, receivedDateTime)
        }
        
        // This won't be reached due to the infinite loop with returns, but it keeps the compiler happy
        return false
    }

    private suspend fun classifyEmailWithFallback(
        subject: String,
        preview: String,
        messageId: String,
        accessToken: String,
        sender: String,
        receivedDateTime: String
    ): Boolean {
        val subjectLower = subject.lowercase()
        val bodyLower = preview.lowercase()
        val senderLower = sender.lowercase()

        val classification = when {
            listOf("viagra", "lottery", "winner", "claim prize", "urgent action required", "account suspended", "verify your account", "inheritance", "nigerian prince", "crypto opportunity", "forex trading", "casino bonus").any {
                subjectLower.contains(it) || bodyLower.contains(it)
            } -> "spam"

            (subjectLower.contains("unsubscribe") || bodyLower.contains("unsubscribe") || bodyLower.contains("manage preferences") || bodyLower.contains("mailing list")) &&
            (senderLower.contains("noreply") || senderLower.contains("no-reply") || senderLower.contains("newsletter") || senderLower.contains("marketing") || senderLower.contains("support") || senderLower.contains("info@") || senderLower.contains("news@") || senderLower.contains("updates@"))
            -> "unsubscribe"

            listOf("sale", "discount", "offer", "% off", "limited time", "shop now", "view deals", "coupon", "promotion", "advertisement", "flyer", "catalog").any {
                subjectLower.contains(it) || bodyLower.contains(it)
            } -> "promotion"

            listOf("newsletter", "weekly update", "digest", "bulletin", "subscription", "updates from").any {
                subjectLower.contains(it) || bodyLower.contains(it)
            } || senderLower.contains("newsletter") || senderLower.contains("updates@") -> "newsletter"

            senderLower.contains("noreply") || senderLower.contains("no-reply") || senderLower.contains("marketing@") || senderLower.contains("info@") || senderLower.contains("support@") || senderLower.contains("news@") -> "newsletter"

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
            else -> false
        }
    }

    private suspend fun moveEmailToFolderAction(
        accessToken: String,
        messageId: String,
        targetFolderName: String,
        actionTaken: String,
        subject: String,
        sender: String,
        receivedDateTime: String
    ): Boolean {
        try {
            // Pass both accessToken and targetFolderName to findOrCreateFolder
            val folderId = graphApiService.findOrCreateFolder(accessToken, targetFolderName)

            // Pass accessToken to moveEmail as well
            val (success, error) = graphApiService.moveEmail(accessToken, messageId, folderId)

            if (success) {
                Log.d(TAG, "Successfully performed '$actionTaken' on $messageId -> folder '$targetFolderName'.")
                val cleaned = CleanedEmail(
                    messageId = messageId,
                    subject = subject,
                    sender = sender,
                    receivedDate = System.currentTimeMillis(),
                    actionTaken = actionTaken,
                    actionTimestamp = System.currentTimeMillis(),
                    originalFolder = "inbox"
                )
                withContext(Dispatchers.IO) {
                    (applicationContext as MainApplication).repository.insert(cleaned)
                }
                return true
            } else {
                Log.e(TAG, "Failed to perform '$actionTaken' on $messageId to folder '$targetFolderName': $error")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during moveEmailToFolderAction for $messageId: ${e.message}", e)
            return false
        }
    }

    private fun completeProcessing(didProcess: Boolean = true) {
        val app = applicationContext as MainApplication
        val preferencesManager = app.preferencesManager
        
        if (didProcess) {
            // If we successfully processed emails, update the last clean time
            preferencesManager.saveLastCleanTime()
            
            // Clear the processing flags to indicate we're done
            preferencesManager.setProcessingInProgress(false)
            preferencesManager.saveProcessingBatchId(null)
            
            Log.d(TAG, "Email processing completed successfully. ${totalEmailsProcessed} processed, ${totalEmailsCleaned} cleaned.")
        } else {
            // We still need to clear the processing flag even if we didn't complete normally
            preferencesManager.setProcessingInProgress(false)
            
            Log.w(TAG, "Email processing stopped without completion.")
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
