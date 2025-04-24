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
import kotlinx.coroutines.withContext
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
    private var currentSkipToken: String? = null

    companion object {
        private const val TAG = "DashboardActivity"
        private const val NOTIFICATION_CHANNEL_ID = "kenza_cleaning_channel"
        private const val CLEANUP_NOTIFICATION_ID = 1001
        private const val DEFAULT_PAGE_SIZE = 50 // Number of emails to fetch per batch
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)
        
        createNotificationChannel()
        
        buttonClean = findViewById(R.id.buttonClean)
        buttonViewBinRecovery = findViewById(R.id.buttonViewBinRecovery)
        buttonViewProfile = findViewById(R.id.buttonViewProfile)
        buttonSignOut = findViewById(R.id.buttonSignOut)
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
        currentSkipToken = null
        isProcessingEmails = true
        
        updateProcessingStatus("Authenticating...")
        buttonClean.isEnabled = false
        
        val app = (applicationContext as MainApplication)
        val msalInstance = app.msalInstance
        
        msalInstance?.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
            override fun onAccountLoaded(activeAccount: IAccount?) {
                if (activeAccount == null) {
                    runOnUiThread {
                        Toast.makeText(this@DashboardActivity, "No active account. Please login again.", Toast.LENGTH_SHORT).show()
                        buttonClean.isEnabled = true
                        isProcessingEmails = false
                        updateProcessingStatus(null)
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
                            Log.e(TAG, "Error Code: ${exception.errorCode}")
                            Log.e(TAG, "Error Type: ${exception.javaClass.simpleName}")
                            Log.e(TAG, "Is Interaction Required: ${exception is MsalUiRequiredException}")
                            Log.e(TAG, "Cause: ${exception.cause}")
                            runOnUiThread {
                                Toast.makeText(this@DashboardActivity, "Session expired. Please login again.", Toast.LENGTH_LONG).show()
                                buttonClean.isEnabled = true
                                isProcessingEmails = false
                                updateProcessingStatus(null)
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
                    buttonClean.isEnabled = true
                    isProcessingEmails = false
                    updateProcessingStatus(null)
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
        
        fetchAndProcessEmails(isFirstRun, lastCleanTime, maxEmails)
    }

    private fun fetchAndProcessEmails(isFirstRun: Boolean, lastCleanTime: Long, maxEmails: Int) {
        val filterQuery = if (isFirstRun) {
            // First run - no date filter, just fetch newest emails first
            ""
        } else {
            // Convert timestamp to ISO 8601 format for Microsoft Graph API
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            dateFormat.timeZone = TimeZone.getTimeZone("UTC")
            val dateString = dateFormat.format(Date(lastCleanTime))
            "&\$filter=receivedDateTime ge $dateString"
        }
        
        updateProcessingStatus("Fetching emails...")
        
        // Reset counters
        totalEmailsProcessed = 0
        totalEmailsFound = 0
        totalEmailsCleaned = 0
        
        // Use the new GraphApiUtils for pagination
        GraphApiUtils.fetchGraphEmailPages(
            accessToken = accessToken ?: "",
            baseUrl = "https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages",
            filterQuery = filterQuery,
            batchSize = 50,  // Microsoft API limit is 50
            maxEmails = maxEmails,
            initialSkipToken = null,
            onBatch = { messages ->
                val messageCount = messages.length()
                Log.d(TAG, "Processing batch of $messageCount emails")
                totalEmailsFound += messageCount
                updateProcessingStatus("Processing batch of $messageCount emails...")
                
                // Initialize counters for this batch
                var processedInBatch = 0
                var cleanedInBatch = 0
                
                // Process each email in this batch
                for (i in 0 until messageCount) {
                    val msg = messages.getJSONObject(i)
                    val messageId = msg.optString("id")
                    val subject = msg.optString("subject", "(No subject)")
                    val bodyPreview = msg.optString("bodyPreview", "")
                    var sender = "Unknown"
                    
                    try {
                        sender = msg.optJSONObject("sender")?.optJSONObject("emailAddress")?.optString("address") ?: "Unknown"
                    } catch (e: Exception) {
                        Log.w(TAG, "Error parsing sender for email: ${e.message}")
                    }
                    
                    val receivedDateTime = msg.optString("receivedDateTime", "")
                    
                    Log.d(TAG, "Processing email: $subject from $sender")
                    
                    // Classify and process this email
                    classifyEmailWithOpenAI(
                        subject,
                        bodyPreview,
                        messageId,
                        accessToken ?: "",
                        sender,
                        receivedDateTime,
                        onComplete = { wasClassified ->
                            processedInBatch++
                            if (wasClassified) cleanedInBatch++
                            
                            totalEmailsProcessed++
                            totalEmailsCleaned += if (wasClassified) 1 else 0
                            
                            Log.d(TAG, "Email processed: $subject, wasClassified=$wasClassified, " +
                                     "totalProcessed=$totalEmailsProcessed, totalCleaned=$totalEmailsCleaned")
                            
                            updateProcessingStatus("Processed: $totalEmailsProcessed\nCleaned: $totalEmailsCleaned")
                        }
                    )
                }
            },
            onProgress = { processed, total ->
                Log.d(TAG, "Pagination progress: $processed/$total")
            },
            onComplete = {
                Log.d(TAG, "Email pagination complete. Total processed: $totalEmailsProcessed")
                completeProcessing()
            },
            onError = { error ->
                Log.e(TAG, "Error in email pagination: $error")
                runOnUiThread {
                    Toast.makeText(this, "Error fetching emails: $error", Toast.LENGTH_SHORT).show()
                    completeProcessing()
                }
            }
        )
    }

    private fun completeProcessing() {
        // Save the time of this cleaning operation
        val app = applicationContext as MainApplication
        app.preferencesManager.saveLastCleanTime()
        
        runOnUiThread {
            buttonClean.isEnabled = true
            isProcessingEmails = false
            
            if (totalEmailsCleaned > 0) {
                showCleaningResultNotification(totalEmailsCleaned)
            }
            
            // Update status in UI
            Toast.makeText(this, 
                "Processed $totalEmailsProcessed emails, cleaned $totalEmailsCleaned", 
                Toast.LENGTH_LONG).show()
                
            // Hide status after a delay
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

    private fun classifyEmailWithOpenAI(
        subject: String,
        preview: String,
        messageId: String,
        accessToken: String,
        sender: String,
        receivedDateTime: String,
        onComplete: (Boolean) -> Unit
    ) {
        // Check if we can use OpenAI API
        if (!BuildConfig.DEBUG || System.currentTimeMillis() % 3 == 0L) {
            // Use fallback classification for debugging or randomly in production
            classifyEmailWithFallback(subject, preview, messageId, accessToken, sender, receivedDateTime, onComplete)
            return
        }
    
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
            
        // Check if OpenAI API key is empty or not set
        val apiKey = BuildConfig.OPENAI_API_KEY ?: ""
        if (apiKey.isEmpty() || apiKey == "YOUR_API_KEY_HERE") {
            Log.w(TAG, "OpenAI API key is missing, using fallback classification")
            classifyEmailWithFallback(subject, preview, messageId, accessToken, sender, receivedDateTime, onComplete)
            return
        }
        
        try {
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
                put("model", "gpt-3.5-turbo")
                put("messages", messagesArray)
                put("temperature", 0.3) // Lower temperature for more focused results
                put("max_tokens", 10) // We only need a single word response
            }
            
            Log.d(TAG, "OpenAI request: $json")
            
            val mediaType = "application/json".toMediaType()
            val body = json.toString().toRequestBody(mediaType)
            val request = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "OpenAI API call failed: ${e.message}", e)
                    runOnUiThread {
                        Toast.makeText(this@DashboardActivity, 
                            "AI classification failed: ${e.message?.take(50)}", 
                            Toast.LENGTH_SHORT).show()
                    }
                    onComplete(false)
                }
                
                override fun onResponse(call: Call, response: Response) {
                    val responseBody = response.body?.string() ?: "{}"
                    
                    if (!response.isSuccessful) {
                        Log.e(TAG, "OpenAI API error ${response.code}: $responseBody")
                        runOnUiThread {
                            Toast.makeText(this@DashboardActivity, 
                                "AI error: ${response.code} - ${responseBody.take(50)}", 
                                Toast.LENGTH_SHORT).show()
                        }
                        onComplete(false)
                        return
                    }
                    
                    try {
                        Log.d(TAG, "OpenAI response: $responseBody")
                        val respJson = JSONObject(responseBody)
                        val classification = respJson
                            .optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("message")
                            ?.optString("content")
                            ?.trim()
                            ?.uppercase() ?: "OTHER"
                        
                        Log.d(TAG, "Email classified as: $classification - Subject: $subject")
                        
                        when (classification) {
                            "SPAM", "PROMOTION", "NEWSLETTER" -> {
                                moveEmailToAICleanedFolder(
                                    accessToken, 
                                    messageId,
                                    classification.lowercase(),
                                    subject,
                                    sender,
                                    receivedDateTime,
                                    onSuccess = { 
                                        onComplete(true)
                                    },
                                    onError = { e -> 
                                        Log.e(TAG, "Error moving email: $e") 
                                        onComplete(false)
                                    }
                                )
                            }
                            else -> onComplete(false)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing OpenAI response: ${e.message}", e)
                        runOnUiThread {
                            Toast.makeText(this@DashboardActivity, 
                                "Error processing AI response: ${e.message?.take(50)}", 
                                Toast.LENGTH_SHORT).show()
                        }
                        onComplete(false)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up OpenAI request: ${e.message}", e)
            runOnUiThread {
                Toast.makeText(this, "AI setup error: ${e.message?.take(50)}", Toast.LENGTH_SHORT).show()
            }
            onComplete(false)
        }
    }
    
    /**
     * Fallback email classification method when OpenAI is not available
     */
    private fun classifyEmailWithFallback(
        subject: String,
        preview: String,
        messageId: String,
        accessToken: String,
        sender: String,
        receivedDateTime: String,
        onComplete: (Boolean) -> Unit
    ) {
        Log.d(TAG, "Using fallback classification for: $subject")
        
        // Simple rule-based classification for testing
        val subjectLower = subject.lowercase()
        val bodyLower = preview.lowercase()
        val senderLower = sender.lowercase()
        
        // Determine classification based on simple rules
        val classification = when {
            // Spam indicators
            listOf("viagra", "lottery", "winner", "forex", "casino", "bitcoin", "investment opportunity", "crypto", "urgent", "prince").any { 
                subjectLower.contains(it) || bodyLower.contains(it) 
            } -> "spam"
            
            // Newsletter indicators
            listOf("newsletter", "weekly update", "digest", "bulletin", "subscribe", "unsubscribe").any { 
                subjectLower.contains(it) || bodyLower.contains(it) 
            } -> "newsletter"
            
            // Promotion indicators
            listOf("sale", "discount", "offer", "deal", "promotion", "% off", "limited time", "buy now", "shop").any { 
                subjectLower.contains(it) || bodyLower.contains(it) 
            } -> "promotion"
            
            // Consider sender domain
            senderLower.contains("marketing") || senderLower.contains("newsletter") || 
            senderLower.contains("noreply") || senderLower.contains("no-reply") -> "newsletter"
            
            // Otherwise, leave it alone (important)
            else -> "important"
        }
        
        Log.d(TAG, "Fallback classification result: $classification for $subject")
        
        // Only move emails that are considered unwanted
        if (classification in listOf("spam", "newsletter", "promotion")) {
            moveEmailToAICleanedFolder(
                accessToken,
                messageId,
                classification,
                subject,
                sender,
                receivedDateTime,
                onSuccess = { onComplete(true) },
                onError = { error -> 
                    Log.e(TAG, "Error moving email: $error")
                    onComplete(false)
                }
            )
        } else {
            // Email is important, skip it
            onComplete(false)
        }
    }

    private fun moveEmailToAICleanedFolder(
        accessToken: String,
        messageId: String,
        classification: String,
        subject: String,
        sender: String,
        receivedDateTime: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val client = OkHttpClient()
        client.newCall(
            Request.Builder()
                .url("https://graph.microsoft.com/v1.0/me/mailFolders")
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = onError("Folders load failed: ${e.message}")
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) return onError("Folders load failed: ${response.code}")
                val folders = JSONObject(response.body?.string() ?: "").optJSONArray("value")
                val folderId = (0 until (folders?.length() ?: 0))
                    .map { folders!!.getJSONObject(it) }
                    .firstOrNull { it.optString("displayName") == "AI Cleaned" }
                    ?.optString("id")
                if (folderId != null) {
                    moveEmailToFolder(accessToken, messageId, folderId, classification, subject, sender, receivedDateTime, onSuccess, onError)
                } else {
                    val mediaType = "application/json".toMediaType()
                    val createJson = JSONObject()
                    createJson.put("displayName", "AI Cleaned")
                    val body = createJson.toString().toRequestBody(mediaType)
                    
                    client.newCall(
                        Request.Builder()
                            .url("https://graph.microsoft.com/v1.0/me/mailFolders")
                            .addHeader("Authorization", "Bearer $accessToken")
                            .addHeader("Content-Type", "application/json")
                            .post(body)
                            .build()
                    ).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) = onError("Create folder failed: ${e.message}")
                        override fun onResponse(call: Call, response: Response) {
                            if (!response.isSuccessful) return onError("Create folder failed: ${response.code}")
                            val newId = JSONObject(response.body?.string() ?: "").optString("id")
                            if (newId.isNotEmpty()) 
                                moveEmailToFolder(accessToken, messageId, newId, classification, subject, sender, receivedDateTime, onSuccess, onError)
                            else onError("Folder creation returned empty ID")
                        }
                    })
                }
            }
        })
    }

    private fun moveEmailToFolder(
        accessToken: String,
        messageId: String,
        folderId: String,
        classification: String,
        subject: String,
        sender: String,
        receivedDateTime: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val mediaType = "application/json".toMediaType()
        val jsonBody = JSONObject()
        jsonBody.put("destinationId", folderId)
        val body = jsonBody.toString().toRequestBody(mediaType)
        
        OkHttpClient().newCall(
            Request.Builder()
                .url("https://graph.microsoft.com/v1.0/me/messages/$messageId/move")
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = onError("Move failed: ${e.message}")
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    // Store the cleaned email in the database
                    val cleaned = CleanedEmail(
                        messageId = messageId,
                        subject = subject,
                        sender = sender,
                        receivedDate = System.currentTimeMillis(),
                        actionTaken = "moved",
                        actionTimestamp = System.currentTimeMillis(),
                        originalFolder = "inbox"
                    )
                    CoroutineScope(Dispatchers.IO).launch {
                        (applicationContext as MainApplication).repository.insert(cleaned)
                    }
                    onSuccess()
                } else {
                    onError("Move failed: ${response.code}")
                }
            }
        })
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

    /**
     * Show a dialog to let the user choose cleaning options
     */
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
                        // Smart clean - use existing last clean timestamp
                        acquireTokenAndCleanEmails()
                    }
                    1 -> {
                        // Full clean - reset the last clean time and process all emails
                        val app = applicationContext as MainApplication
                        app.preferencesManager.saveLastCleanTime(0) // Reset to epoch time
                        acquireTokenAndCleanEmails()
                    }
                    2 -> {
                        // Set email limit
                        showSetEmailLimitDialog()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * Show a dialog to let the user set the maximum number of emails to process
     */
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
            else -> 2 // Default to 100
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
