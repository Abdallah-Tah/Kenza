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
        PublicClientApplication.createSingleAccountPublicClientApplication(
            this,
            R.raw.auth_config_single_account,
            object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                override fun onCreated(application: ISingleAccountPublicClientApplication) {
                    (applicationContext as MainApplication).msalInstance = application
                    loadAccount()
                    loginButton.isEnabled = true
                }

                override fun onError(exception: MsalException) {
                    Log.e(TAG, "MSAL Init Error: ${exception.message}")
                    Toast.makeText(this@LoginActivity, "MSAL Initialization Failed", Toast.LENGTH_SHORT).show()
                    loginButton.isEnabled = true
                }
            })
    }

    private fun loadAccount() {
        val app = (applicationContext as MainApplication).msalInstance
        app?.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
            override fun onAccountLoaded(activeAccount: IAccount?) {
                if (activeAccount != null) {
                    Log.d(TAG, "Account loaded: ${activeAccount.username}")
                    navigateToDashboard()
                } else {
                    Log.d(TAG, "No active account found.")
                    loginButton.isEnabled = true
                    initiateInteractiveLogin()
                }
            }

            override fun onAccountChanged(priorAccount: IAccount?, currentAccount: IAccount?) {
                if (currentAccount == null) {
                    loginButton.isEnabled = true
                }
            }

            override fun onError(exception: MsalException) {
                Log.e(TAG, "Error loading account: ${exception.message}")
                loginButton.isEnabled = true
            }
        })
    }

    private fun initiateInteractiveLogin() {
        val app = (applicationContext as MainApplication).msalInstance
        app?.let {
            val parameters = AcquireTokenParameters.Builder()
                .startAuthorizationFromActivity(this)
                .withScopes(microsoftScopes)
                .withCallback(getAuthInteractiveCallback())
                .build()
            it.acquireToken(parameters)
        }
    }

    private fun getAuthInteractiveCallback(): AuthenticationCallback {
        return object : AuthenticationCallback {
            override fun onSuccess(result: IAuthenticationResult) {
                Log.d(TAG, "Successfully authenticated")
                Log.d(TAG, "Account: ${result.account.username}")
                navigateToDashboard()
            }

            override fun onError(exception: MsalException) {
                Log.e(TAG, "Authentication failed: ${exception.localizedMessage}")
                Toast.makeText(this@LoginActivity, "Login Failed: ${exception.localizedMessage}", Toast.LENGTH_LONG).show()
                loginButton.isEnabled = true
            }

            override fun onCancel() {
                Log.d(TAG, "User cancelled login.")
                Toast.makeText(this@LoginActivity, "Login Cancelled", Toast.LENGTH_SHORT).show()
                loginButton.isEnabled = true
            }
        }
    }

    private fun navigateToDashboard() {
        val intent = Intent(this, DashboardActivity::class.java)
        startActivity(intent)
        finish()
    }
}