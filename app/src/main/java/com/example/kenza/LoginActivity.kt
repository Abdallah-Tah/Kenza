package com.example.kenza

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.microsoft.identity.client.AcquireTokenParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.exception.MsalException
import java.util.Arrays

class LoginActivity : AppCompatActivity() {

    private lateinit var loginButton: Button
    private val TAG = "LoginActivity"
    private val scopes = Arrays.asList("User.Read", "Mail.ReadWrite", "offline_access")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        loginButton = findViewById(R.id.buttonLogin)
        loginButton.isEnabled = false // Disable until MSAL is initialized

        // Initialize MSAL
        PublicClientApplication.createSingleAccountPublicClientApplication(this,
            R.raw.auth_config_single_account,
            object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                override fun onCreated(application: ISingleAccountPublicClientApplication) {
                    // Store MSAL instance in MainApplication
                    (applicationContext as MainApplication).msalInstance = application
                    loadAccount()
                    loginButton.isEnabled = true
                }

                override fun onError(exception: MsalException) {
                    Log.e(TAG, "MSAL Init Error: ${exception.message}")
                    Toast.makeText(this@LoginActivity, "MSAL Initialization Failed", Toast.LENGTH_SHORT).show()
                }
            })

        loginButton.setOnClickListener {
            val app = (applicationContext as MainApplication).msalInstance
            app?.let {
                val parameters = AcquireTokenParameters.Builder()
                    .startAuthorizationFromActivity(this)
                    .withScopes(scopes)
                    .withCallback(getAuthInteractiveCallback())
                    .build()
                it.acquireToken(parameters)
            }
        }
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

    private fun getAuthInteractiveCallback(): AuthenticationCallback {
        return object : AuthenticationCallback {
            override fun onSuccess(authenticationResult: IAuthenticationResult) {
                Log.d(TAG, "Successfully authenticated")
                Log.d(TAG, "Account: ${authenticationResult.account.username}")
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