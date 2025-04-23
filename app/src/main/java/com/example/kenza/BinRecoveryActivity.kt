package com.example.kenza

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.kenza.database.models.CleanedEmail
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.SilentAuthenticationCallback
import com.microsoft.identity.client.exception.MsalException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView

class BinRecoveryActivity : AppCompatActivity() {
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressIndicator: CircularProgressIndicator
    private lateinit var emptyView: TextView
    private lateinit var fabEmptyBin: FloatingActionButton
    
    private val emailAdapter = BinEmailAdapter { email -> restoreEmail(email) }
    private var accessToken: String? = null
    
    companion object {
        private const val TAG = "BinRecoveryActivity"
        private const val RETENTION_DAYS = 30 // Number of days to retain emails before permanent deletion
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bin_recovery)
        
        setupViews()
        getAccessTokenAndLoadEmails()
    }
    
    private fun setupViews() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        
        recyclerView = findViewById(R.id.recyclerViewBin)
        progressIndicator = findViewById(R.id.progressIndicator)
        emptyView = findViewById(R.id.textViewEmptyBin)
        fabEmptyBin = findViewById(R.id.fabEmptyBin)
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = emailAdapter
        
        fabEmptyBin.setOnClickListener {
            confirmEmptyBin()
        }
    }
    
    private fun confirmEmptyBin() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Empty Bin")
            .setMessage("Are you sure you want to permanently delete all emails in the bin? This action cannot be undone.")
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .setPositiveButton("Empty Bin") { _, _ ->
                emptyBin()
            }
            .show()
    }
    
    private fun getAccessTokenAndLoadEmails() {
        showLoading(true)
        
        val app = (applicationContext as MainApplication).msalInstance
        app?.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
            override fun onAccountLoaded(activeAccount: com.microsoft.identity.client.IAccount?) {
                if (activeAccount == null) {
                    runOnUiThread {
                        Toast.makeText(this@BinRecoveryActivity, "No active account. Please login again.", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return
                }
                
                val scopes = arrayOf("User.Read", "Mail.ReadWrite")
                val params = com.microsoft.identity.client.AcquireTokenSilentParameters.Builder()
                    .forAccount(activeAccount)
                    .fromAuthority(activeAccount.authority)
                    .withScopes(scopes.toList())
                    .withCallback(object : SilentAuthenticationCallback {
                        override fun onSuccess(authenticationResult: com.microsoft.identity.client.IAuthenticationResult) {
                            accessToken = authenticationResult.accessToken
                            loadCleanedEmails()
                            checkAndPurgeOldEmails()
                        }
                        
                        override fun onError(exception: MsalException) {
                            runOnUiThread {
                                Toast.makeText(this@BinRecoveryActivity, "Failed to authenticate: ${exception.message}", Toast.LENGTH_SHORT).show()
                                finish()
                            }
                        }
                    })
                    .build()
                
                app.acquireTokenSilentAsync(params)
            }
            
            override fun onAccountChanged(priorAccount: com.microsoft.identity.client.IAccount?, currentAccount: com.microsoft.identity.client.IAccount?) {}
            
            override fun onError(exception: MsalException) {
                runOnUiThread {
                    Toast.makeText(this@BinRecoveryActivity, "Error getting account: ${exception.message}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        })
    }
    
    private fun loadCleanedEmails() {
        lifecycleScope.launch {
            val repository = (applicationContext as MainApplication).repository
            repository.allCleanedEmails.collect { emails ->
                val cleanedEmails = emails.filter { it.actionTaken == "moved" }
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    if (cleanedEmails.isEmpty()) {
                        emptyView.visibility = View.VISIBLE
                        fabEmptyBin.visibility = View.GONE
                    } else {
                        emptyView.visibility = View.GONE
                        fabEmptyBin.visibility = View.VISIBLE
                        emailAdapter.submitList(cleanedEmails)
                    }
                }
            }
        }
    }
    
    private fun restoreEmail(email: CleanedEmail) {
        if (accessToken == null) {
            Toast.makeText(this, "Authentication token not available", Toast.LENGTH_SHORT).show()
            return
        }
        
        showLoading(true)
        
        // First find the ID of the inbox folder
        val client = OkHttpClient()
        client.newCall(
            Request.Builder()
                .url("https://graph.microsoft.com/v1.0/me/mailFolders/inbox")
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    showLoading(false)
                    Toast.makeText(this@BinRecoveryActivity, "Failed to get inbox: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    runOnUiThread {
                        showLoading(false)
                        Toast.makeText(this@BinRecoveryActivity, "Failed to get inbox: ${response.code}", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                
                val inboxJson = JSONObject(response.body?.string() ?: "{}")
                val inboxId = inboxJson.optString("id")
                
                if (inboxId.isBlank()) {
                    runOnUiThread {
                        showLoading(false)
                        Toast.makeText(this@BinRecoveryActivity, "Could not find inbox folder", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                
                // Now move the email back to the inbox
                moveEmailBack(email, inboxId)
            }
        })
    }
    
    private fun moveEmailBack(email: CleanedEmail, inboxId: String) {
        val mediaType = "application/json".toMediaType()
        val body = JSONObject()
            .put("destinationId", inboxId)
            .toString()
            .toRequestBody(mediaType)
        
        OkHttpClient().newCall(
            Request.Builder()
                .url("https://graph.microsoft.com/v1.0/me/messages/${email.messageId}/move")
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    showLoading(false)
                    Toast.makeText(this@BinRecoveryActivity, "Failed to restore email: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            
            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    showLoading(false)
                    if (response.isSuccessful) {
                        // Update the database to mark this email as restored
                        lifecycleScope.launch(Dispatchers.IO) {
                            val repository = (applicationContext as MainApplication).repository
                            repository.delete(email)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@BinRecoveryActivity, "Email restored successfully", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        Toast.makeText(this@BinRecoveryActivity, "Failed to restore email: ${response.code}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }
    
    private fun checkAndPurgeOldEmails() {
        lifecycleScope.launch(Dispatchers.IO) {
            val repository = (applicationContext as MainApplication).repository
            val allEmails = repository.getAllCleanedEmailsSync()
            
            val currentTime = System.currentTimeMillis()
            val cutoffTime = currentTime - TimeUnit.DAYS.toMillis(RETENTION_DAYS.toLong())
            
            val oldEmails = allEmails.filter { it.actionTimestamp < cutoffTime }
            if (oldEmails.isNotEmpty()) {
                repository.deleteMultiple(oldEmails)
                
                // Notify the user about purged emails
                withContext(Dispatchers.Main) {
                    if (oldEmails.size == 1) {
                        Toast.makeText(this@BinRecoveryActivity, "1 email older than $RETENTION_DAYS days was permanently deleted", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@BinRecoveryActivity, "${oldEmails.size} emails older than $RETENTION_DAYS days were permanently deleted", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    
    private fun emptyBin() {
        showLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val repository = (applicationContext as MainApplication).repository
            val cleanedEmails = repository.getEmailsByActionSync("moved")
            repository.deleteMultiple(cleanedEmails)
            
            withContext(Dispatchers.Main) {
                showLoading(false)
                Toast.makeText(this@BinRecoveryActivity, "Bin emptied successfully", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun showLoading(isLoading: Boolean) {
        progressIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
        recyclerView.visibility = if (isLoading) View.GONE else View.VISIBLE
        if (isLoading) {
            emptyView.visibility = View.GONE
        }
    }
    
    inner class BinEmailAdapter(private val onRestoreClick: (CleanedEmail) -> Unit) : 
        RecyclerView.Adapter<BinEmailAdapter.BinEmailViewHolder>() {
        
        private var emails: List<CleanedEmail> = emptyList()
        private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        
        fun submitList(newList: List<CleanedEmail>) {
            emails = newList
            notifyDataSetChanged()
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BinEmailViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_bin_email, parent, false)
            return BinEmailViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: BinEmailViewHolder, position: Int) {
            val email = emails[position]
            
            holder.subjectView.text = email.subject ?: "(No subject)"
            holder.senderView.text = email.sender ?: "(Unknown sender)"
            holder.dateView.text = dateFormat.format(Date(email.actionTimestamp))
            
            // Calculate days left before deletion
            val daysLeft = RETENTION_DAYS - TimeUnit.MILLISECONDS.toDays(
                System.currentTimeMillis() - email.actionTimestamp
            ).toInt()
            
            holder.classificationView.text = "Will be deleted in $daysLeft days"
            
            // Set restore button click listener
            holder.restoreButton.setOnClickListener {
                onRestoreClick(email)
            }
        }
        
        override fun getItemCount() = emails.size
        
        inner class BinEmailViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val subjectView: TextView = itemView.findViewById(R.id.textViewSubject)
            val senderView: TextView = itemView.findViewById(R.id.textViewSender)
            val dateView: TextView = itemView.findViewById(R.id.textViewDate)
            val classificationView: TextView = itemView.findViewById(R.id.textViewClassification)
            val restoreButton: ImageButton = itemView.findViewById(R.id.buttonRestore)
        }
    }
}