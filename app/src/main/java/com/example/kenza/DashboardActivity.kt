package com.example.kenza

import android.content.Intent
import android.os.Bundle
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Properties
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

class DashboardActivity : AppCompatActivity() {
    private lateinit var buttonClean: Button
    private lateinit var buttonViewBinRecovery: Button
    private lateinit var buttonViewProfile: Button
    private lateinit var buttonSignOut: Button
    private lateinit var textViewCountClean: TextView
    private lateinit var textViewCountUnsub: TextView
    private lateinit var textViewEmail: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

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
            Toast.makeText(this, "View Bin Recovery not yet implemented.", Toast.LENGTH_SHORT).show()
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
    }

    private fun acquireTokenAndCleanEmails() {
        val app = (applicationContext as MainApplication).msalInstance
        app?.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
            override fun onAccountLoaded(activeAccount: IAccount?) {
                if (activeAccount == null) {
                    runOnUiThread {
                        Toast.makeText(this@DashboardActivity, "No active account. Please login again.", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                val scopes = arrayOf("User.Read", /*"Mail.ReadWrite",*/ "offline_access")
                val params = AcquireTokenSilentParameters.Builder()
                    .forAccount(activeAccount)
                    .fromAuthority(activeAccount.authority)
                    .withScopes(scopes.toList())
                    .withCallback(object : SilentAuthenticationCallback {
                        override fun onSuccess(authenticationResult: IAuthenticationResult) {
                            val accessToken = authenticationResult.accessToken
                            fetchRecentEmailsAndClassify(accessToken)
                        }
                        override fun onError(exception: MsalException) {
                            runOnUiThread {
                                Toast.makeText(this@DashboardActivity, "Session expired. Please login again.", Toast.LENGTH_LONG).show()
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
                }
            }
        })
    }

    private fun fetchRecentEmailsAndClassify(accessToken: String) {
        val graphUrl =
            "https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages?\$top=10&\$select=id,subject,bodyPreview,from,receivedDateTime"
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(graphUrl)
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@DashboardActivity, "Failed to fetch emails: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    runOnUiThread {
                        Toast.makeText(this@DashboardActivity, "Failed to fetch emails: ${response.code}", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                val body = response.body?.string() ?: return
                val json = JSONObject(body)
                val messages = json.optJSONArray("value") ?: return
                for (i in 0 until messages.length()) {
                    val msg = messages.getJSONObject(i)
                    classifyEmailWithOpenAI(
                        msg.optString("subject"),
                        msg.optString("bodyPreview"),
                        msg.optString("id"),
                        accessToken
                    )
                }
                runOnUiThread {
                    Toast.makeText(this@DashboardActivity, "Fetched and classified ${messages.length()} emails.", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun classifyEmailWithOpenAI(
        subject: String,
        preview: String,
        messageId: String,
        accessToken: String
    ) {
        val client = OkHttpClient()
        val prompt =
            "Classify this email as spam, promotion, newsletter, important, or other. Subject: $subject. Body: $preview. Respond with only one word."
        val json = JSONObject().apply {
            put("model", "gpt-3.5-turbo")
            put("messages", listOf(mapOf("role" to "user", "content" to prompt)))
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
                sendSmtpNotification("OpenAI API Failure", "Error: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) return
                val respJson = JSONObject(response.body?.string() ?: return)
                val classification = respJson
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.trim()
                    ?.lowercase()
                if (classification in listOf("spam", "promotion", "newsletter")) {
                    moveEmailToAICleanedFolder(accessToken, messageId,
                        onSuccess = {
                            val cleaned = CleanedEmail(
                                messageId = messageId,
                                subject = subject,
                                sender = null,
                                receivedDate = System.currentTimeMillis(),
                                actionTaken = "moved",
                                actionTimestamp = System.currentTimeMillis(),
                                originalFolder = "inbox"
                            )
                            val app = applicationContext as MainApplication
                            CoroutineScope(Dispatchers.IO).launch { app.repository.insert(cleaned) }
                            runOnUiThread {
                                Toast.makeText(this@DashboardActivity, "Email moved: $subject", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onError = { e -> runOnUiThread { Toast.makeText(this@DashboardActivity, e, Toast.LENGTH_SHORT).show() } }
                    )
                }
            }
        })
    }

    private fun moveEmailToAICleanedFolder(
        accessToken: String,
        messageId: String,
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
                    moveEmailToFolder(accessToken, messageId, folderId, onSuccess, onError)
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
                            if (newId.isNotEmpty()) moveEmailToFolder(accessToken, messageId, newId, onSuccess, onError)
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
                if (response.isSuccessful) onSuccess() else onError("Move failed: ${response.code}")
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
