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
import com.microsoft.identity.client.exception.MsalException
import java.util.*

class LoginActivity : AppCompatActivity() {
    private lateinit var loginButton: Button
    private val TAG = "LoginActivity"
    private val microsoftScopes = Arrays.asList("User.Read", "Mail.ReadWrite", "offline_access")

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
            PublicClientApplication.createSingleAccountPublicClientApplication(
                this,
                R.raw.auth_config_single_account,
                object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                    override fun onCreated(application: ISingleAccountPublicClientApplication) {
                        Log.d(TAG, "MSAL application created successfully")
                        (applicationContext as MainApplication).msalInstance = application
                        loadAccount()
                    }

                    override fun onError(exception: MsalException) {
                        Log.e(TAG, "MSAL Init Error: ${exception.message}", exception)
                        Log.e(TAG, "Error type: ${exception.javaClass.simpleName}")
                        Log.e(TAG, "Error cause: ${exception.cause}")
                        Toast.makeText(this@LoginActivity, 
                            "MSAL Initialization Failed: ${exception.message}", 
                            Toast.LENGTH_LONG).show()
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
                    .withCallback(getAuthInteractiveCallback())
                    .build()
                it.acquireToken(parameters)
                Log.d(TAG, "AcquireToken request sent")
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
                Log.d(TAG, "Access token: ${result.accessToken.take(10)}...")
                navigateToDashboard()
            }

            override fun onError(exception: MsalException) {
                Log.e(TAG, "Authentication failed: ${exception.message}", exception)
                Log.e(TAG, "Error type: ${exception.javaClass.simpleName}")
                Log.e(TAG, "Error cause: ${exception.cause}")
                Toast.makeText(this@LoginActivity, 
                    "Login Failed: ${exception.message}", 
                    Toast.LENGTH_LONG).show()
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