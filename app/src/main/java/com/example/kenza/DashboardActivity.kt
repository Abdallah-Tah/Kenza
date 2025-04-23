package com.example.kenza

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
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

class DashboardActivity : AppCompatActivity() {
    private lateinit var buttonClean: Button
    private lateinit var buttonViewBinRecovery: Button
    private lateinit var buttonViewProfile: Button
    private lateinit var buttonSignOut: Button
    private lateinit var textViewCountClean: TextView
    private lateinit var textViewCountUnsub: TextView
    private lateinit var textViewEmail: TextView

    companion object {
        private const val TAG = "DashboardActivity"
        private const val NOTIFICATION_CHANNEL_ID = "kenza_cleaning_channel"
        private const val CLEANUP_NOTIFICATION_ID = 1001
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

        val app = applicationContext as MainApplication
        val msalInstance = app.msalInstance
        val repository = app.repository

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
            acquireTokenAndCleanEmails()
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
            val repository = (applicationContext as MainApplication).repository
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
        buttonClean.isEnabled = false
        
        val app = (applicationContext as MainApplication).msalInstance
        app?.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
            override fun onAccountLoaded(activeAccount: IAccount?) {
                if (activeAccount == null) {
                    runOnUiThread {
                        Toast.makeText(this@DashboardActivity, "No active account. Please login again.", Toast.LENGTH_SHORT).show()
                        buttonClean.isEnabled = true
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
                            val accessToken = authenticationResult.accessToken
                            fetchRecentEmailsAndClassify(accessToken)
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
                            }
                        }
                    })
                    .build()
                app.acquireTokenSilentAsync(params)
            }
            override fun onAccountChanged(priorAccount: IAccount?, currentAccount: IAccount?) {}
            override fun onError(exception: MsalException) {
                runOnUiThread {
                    Toast.makeText(this@DashboardActivity, "Error getting account: ${exception.message}", Toast.LENGTH_SHORT).show()
                    buttonClean.isEnabled = true
                }
            }
        })
    }

    private fun fetchRecentEmailsAndClassify(accessToken: String) {
        val graphUrl =
            "https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages?\$top=25&\$select=id,subject,bodyPreview,from,receivedDateTime,sender"
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(graphUrl)
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@DashboardActivity, "Failed to fetch emails: ${e.message}", Toast.LENGTH_SHORT).show()
                    buttonClean.isEnabled = true
                }
            }
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    runOnUiThread {
                        Toast.makeText(this@DashboardActivity, "Failed to fetch emails: ${response.code}", Toast.LENGTH_SHORT).show()
                        buttonClean.isEnabled = true
                    }
                    return
                }
                val body = response.body?.string() ?: return
                val json = JSONObject(body)
                val messages = json.optJSONArray("value") ?: return
                
                var processedCount = 0
                var cleanedCount = 0
                
                for (i in 0 until messages.length()) {
                    val msg = messages.getJSONObject(i)
                    val messageId = msg.optString("id")
                    val subject = msg.optString("subject")
                    val bodyPreview = msg.optString("bodyPreview")
                    val sender = msg.optJSONObject("sender")?.optJSONObject("emailAddress")?.optString("address") ?: "Unknown"
                    val receivedDateTime = msg.optString("receivedDateTime")
                    
                    classifyEmailWithOpenAI(
                        subject,
                        bodyPreview,
                        messageId,
                        accessToken,
                        sender,
                        receivedDateTime,
                        onComplete = { wasClassified ->
                            processedCount++
                            if (wasClassified) cleanedCount++
                            
                            // When all emails have been processed
                            if (processedCount == messages.length()) {
                                runOnUiThread {
                                    buttonClean.isEnabled = true
                                    if (cleanedCount > 0) {
                                        showCleaningResultNotification(cleanedCount)
                                    }
                                    Toast.makeText(this@DashboardActivity, 
                                        "Processed ${messages.length()} emails, cleaned $cleanedCount", 
                                        Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                }
            }
        })
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
        val client = OkHttpClient()
        val prompt = """
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
        """.trimIndent()
        
        val json = JSONObject().apply {
            put("model", "gpt-3.5-turbo")
            put("messages", listOf(mapOf("role" to "user", "content" to prompt)))
            put("temperature", 0.3) // Lower temperature for more focused results
            put("max_tokens", 10) // We only need a single word response
        }
        val mediaType = "application/json".toMediaType()
        val body = json.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "OpenAI API call failed: ${e.message}")
                onComplete(false)
            }
            
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    Log.e(TAG, "OpenAI API error: ${response.code}")
                    onComplete(false)
                    return
                }
                
                try {
                    val respJson = JSONObject(response.body?.string() ?: "{}")
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
                    Log.e(TAG, "Error processing OpenAI response: ${e.message}")
                    onComplete(false)
                }
            }
        })
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
                    val createJson = JSONObject().put("displayName", "AI Cleaned")
                    val mediaType = "application/json".toMediaType()
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
        val body = JSONObject().put("destinationId", folderId).toString().toRequestBody(mediaType)
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
                    val app = (call.request().tag() as? Context ?: applicationContext) as MainApplication
                    CoroutineScope(Dispatchers.IO).launch { 
                        app.repository.insert(cleaned)
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
}
