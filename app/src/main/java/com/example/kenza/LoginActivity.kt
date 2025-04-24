package com.example.kenza

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.microsoft.identity.client.*
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalServiceException
import com.microsoft.identity.client.exception.MsalUiRequiredException

class LoginActivity : AppCompatActivity() {
    private lateinit var loginButton: Button
    private val TAG = "LoginActivity"
    // Updated scopes to EXACTLY match what's configured in Azure AD
    private val microsoftScopes = listOf(
        "User.Read",
        "Mail.ReadWrite", 
        "email",
        "openid", 
        "profile"
        // Removed offline_access as it's not shown in your Azure permissions screenshot
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        loginButton = findViewById(R.id.buttonLogin)
        loginButton.text = "Choose Email Provider"
        loginButton.setOnClickListener {
            showProviderSelectionDialog()
        }
    }

    private fun showProviderSelectionDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_email_provider, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        // Microsoft login
        dialogView.findViewById<Button>(R.id.buttonMicrosoft).setOnClickListener {
            dialog.dismiss()
            initiateMicrosoftLogin()
        }

        // Gmail login (to be implemented)
        dialogView.findViewById<Button>(R.id.buttonGmail).setOnClickListener {
            dialog.dismiss()
            Toast.makeText(this, "Gmail login coming soon!", Toast.LENGTH_SHORT).show()
        }

        // Yahoo login (to be implemented)
        dialogView.findViewById<Button>(R.id.buttonYahoo).setOnClickListener {
            dialog.dismiss()
            Toast.makeText(this, "Yahoo login coming soon!", Toast.LENGTH_SHORT).show()
        }

        // Custom email login (to be implemented)
        dialogView.findViewById<Button>(R.id.buttonCustom).setOnClickListener {
            dialog.dismiss()
            Toast.makeText(this, "Custom email login coming soon!", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun initiateMicrosoftLogin() {
        loginButton.isEnabled = false
        Log.d(TAG, "Initiating Microsoft login...")
        
        try {
            // Add explicit configuration check
            val redirectUri = "msauth://com.example.kenza/SoSikUg9xnXLPx1YhpV7XgJwXPs%3D"
            Log.d(TAG, "Using redirect URI: $redirectUri")
            
            // Use the MainApplication's existing MSAL instance if available
            val mainApp = applicationContext as MainApplication
            val existingMsalInstance = mainApp.msalInstance
            
            if (existingMsalInstance != null) {
                Log.d(TAG, "Using existing MSAL instance")
                loadAccount()
                return
            }
            
            // Create a new MSAL instance if one doesn't exist
            PublicClientApplication.createSingleAccountPublicClientApplication(
                this,
                R.raw.auth_config_single_account,
                object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                    override fun onCreated(application: ISingleAccountPublicClientApplication) {
                        Log.d(TAG, "MSAL application created successfully")
                        // We'll use the instance created and managed by MainApplication
                        loadAccount()
                    }

                    override fun onError(exception: MsalException) {
                        Log.e(TAG, "MSAL Init Error: ${exception.message}", exception)
                        Log.e(TAG, "Error type: ${exception.javaClass.simpleName}")
                        Log.e(TAG, "Error code: ${exception.errorCode}")
                        Log.e(TAG, "Error cause: ${exception.cause}")
                        
                        // Display more details to help debug
                        val errorMsg = when {
                            exception.message?.contains("intent filter") == true -> 
                                "Intent filter error: Check AndroidManifest.xml configuration. Make sure BrowserTabActivity is properly declared with the right intent filters."
                            exception.message?.contains("redirect") == true -> 
                                "Redirect URI error: Make sure the redirect URI in Azure AD matches exactly: $redirectUri"
                            else -> "MSAL Initialization Failed: ${exception.message}"
                        }
                        
                        runOnUiThread {
                            Toast.makeText(this@LoginActivity, errorMsg, Toast.LENGTH_LONG).show()
                            
                            // Show a dialog with more detailed error information
                            AlertDialog.Builder(this@LoginActivity)
                                .setTitle("MSAL Initialization Error")
                                .setMessage("Error: ${exception.message}\n\nType: ${exception.javaClass.simpleName}\n\nCode: ${exception.errorCode}\n\nTry reinstalling the app or clearing app data.")
                                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                                .show()
                        }
                        
                        loginButton.isEnabled = true
                    }
                })
        } catch (e: Exception) {
            Log.e(TAG, "Exception during MSAL initialization", e)
            Toast.makeText(this, 
                "Error initializing MSAL: ${e.message}", 
                Toast.LENGTH_LONG).show()
            loginButton.isEnabled = true
        }
    }

    private fun loadAccount() {
        Log.d(TAG, "Loading account...")
        val app = (applicationContext as MainApplication).msalInstance
        app?.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
            override fun onAccountLoaded(activeAccount: IAccount?) {
                if (activeAccount != null) {
                    Log.d(TAG, "Account loaded: ${activeAccount.username}")
                    navigateToDashboard()
                } else {
                    Log.d(TAG, "No active account found, initiating interactive login")
                    loginButton.isEnabled = true
                    initiateInteractiveLogin()
                }
            }

            override fun onAccountChanged(priorAccount: IAccount?, currentAccount: IAccount?) {
                Log.d(TAG, "Account changed - Prior: ${priorAccount?.username}, Current: ${currentAccount?.username}")
                if (currentAccount == null) {
                    loginButton.isEnabled = true
                }
            }

            override fun onError(exception: MsalException) {
                Log.e(TAG, "Error loading account: ${exception.message}", exception)
                loginButton.isEnabled = true
            }
        })
    }

    private fun initiateInteractiveLogin() {
        Log.d(TAG, "Starting interactive login...")
        val app = (applicationContext as MainApplication).msalInstance
        app?.let {
            try {
                val parameters = AcquireTokenParameters.Builder()
                    .startAuthorizationFromActivity(this)
                    .withScopes(microsoftScopes)
                    .withPrompt(Prompt.SELECT_ACCOUNT) // Add explicit prompt to select account
                    .withCallback(getAuthInteractiveCallback())
                    .build()
                it.acquireToken(parameters)
                Log.d(TAG, "AcquireToken request sent with scopes: ${microsoftScopes.joinToString()}")
            } catch (e: Exception) {
                Log.e(TAG, "Error during acquireToken", e)
                Toast.makeText(this, 
                    "Error during login: ${e.message}", 
                    Toast.LENGTH_LONG).show()
                loginButton.isEnabled = true
            }
        } ?: run {
            Log.e(TAG, "MSAL instance is null")
            Toast.makeText(this, 
                "Error: MSAL not initialized", 
                Toast.LENGTH_LONG).show()
            loginButton.isEnabled = true
        }
    }

    private fun getAuthInteractiveCallback(): AuthenticationCallback {
        return object : AuthenticationCallback {
            override fun onSuccess(result: IAuthenticationResult) {
                Log.d(TAG, "Successfully authenticated")
                Log.d(TAG, "Account: ${result.account.username}")
                Log.d(TAG, "Scopes granted: ${result.scope.joinToString()}")
                Log.d(TAG, "Access token: ${result.accessToken.take(10)}...")
                navigateToDashboard()
            }

            override fun onError(exception: MsalException) {
                Log.e(TAG, "Authentication failed: ${exception.message}", exception)
                Log.e(TAG, "Error type: ${exception.javaClass.simpleName}")
                Log.e(TAG, "Error code: ${exception.errorCode}")
                
                val errorMessage = when (exception) {
                    is MsalServiceException -> {
                        // This is likely a problem with the Azure AD service
                        "Service error (${exception.errorCode}): ${exception.message}"
                    }
                    is MsalClientException -> {
                        // This is likely a problem with the MSAL library
                        "Client error (${exception.errorCode}): ${exception.message}"
                    }
                    is MsalUiRequiredException -> {
                        // This exception indicates a need for user interaction
                        "Interaction required (${exception.errorCode}): ${exception.message}"
                    }
                    else -> {
                        // Generic MSAL exception
                        "Authentication error: ${exception.message}"
                    }
                }
                
                Log.e(TAG, "Detailed error: $errorMessage")
                Log.e(TAG, "Error cause: ${exception.cause}")
                
                runOnUiThread {
                    Toast.makeText(this@LoginActivity, errorMessage, Toast.LENGTH_LONG).show()
                    
                    // Show a more helpful dialog for scope issues
                    if (exception.message?.contains("scope") == true || 
                        exception.message?.contains("consent") == true) {
                        AlertDialog.Builder(this@LoginActivity)
                            .setTitle("Authentication Error")
                            .setMessage("Microsoft denied access to some required permissions. " +
                                    "This may be because the app hasn't been properly configured " +
                                    "in Azure AD. Please contact the app administrator.")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                    }
                }
                
                loginButton.isEnabled = true
            }

            override fun onCancel() {
                Log.d(TAG, "User cancelled login")
                Toast.makeText(this@LoginActivity, 
                    "Login Cancelled", 
                    Toast.LENGTH_SHORT).show()
                loginButton.isEnabled = true
            }
        }
    }

    private fun navigateToDashboard() {
        Log.d(TAG, "Navigating to dashboard")
        val intent = Intent(this, DashboardActivity::class.java)
        startActivity(intent)
        finish()
    }
}