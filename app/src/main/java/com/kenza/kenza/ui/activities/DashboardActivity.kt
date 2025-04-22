package com.example.kenza

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.exception.MsalException

class DashboardActivity : AppCompatActivity() {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var textViewWelcome: TextView
    private lateinit var textViewEmail: TextView
    private lateinit var buttonViewProfile: MaterialButton
    private lateinit var buttonSignOut: MaterialButton
    private var mSingleAccountApp: ISingleAccountPublicClientApplication? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        // Initialize views
        toolbar = findViewById(R.id.toolbar)
        textViewWelcome = findViewById(R.id.textViewWelcome)
        textViewEmail = findViewById(R.id.textViewEmail)
        buttonViewProfile = findViewById(R.id.buttonViewProfile)
        buttonSignOut = findViewById(R.id.buttonSignOut)

        // Set up toolbar
        setSupportActionBar(toolbar)

        // Get MSAL instance from LoginActivity
        mSingleAccountApp = (application as? MainApplication)?.msalInstance

        // Load account information
        loadAccountInfo()

        // Set up click listeners
        buttonSignOut.setOnClickListener {
            signOut()
        }

        buttonViewProfile.setOnClickListener {
            // TODO: Implement profile view
        }
    }

    private fun loadAccountInfo() {
        mSingleAccountApp?.getCurrentAccountAsync(object :
            ISingleAccountPublicClientApplication.CurrentAccountCallback {
            override fun onAccountLoaded(activeAccount: IAccount?) {
                if (activeAccount != null) {
                    textViewWelcome.text = "Welcome, ${activeAccount.username.split("@")[0]}!"
                    textViewEmail.text = "Email: ${activeAccount.username}"
                }
            }

            override fun onAccountChanged(priorAccount: IAccount?, currentAccount: IAccount?) {
                if (currentAccount == null) {
                    navigateToLogin()
                }
            }

            override fun onError(exception: MsalException) {
                Log.e("DashboardActivity", "Error loading account: ${exception.message}")
            }
        })
    }

    private fun signOut() {
        mSingleAccountApp?.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
            override fun onSignOut() {
                navigateToLogin()
            }

            override fun onError(exception: MsalException) {
                Log.e("DashboardActivity", "Sign out error: ${exception.message}")
            }
        })
    }

    private fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        startActivity(intent)
        finish()
    }
}