package com.example.kenza

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.microsoft.identity.client.ISingleAccountPublicClientApplication

class DashboardActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        // Get MSAL instance from MainApplication
        val msalInstance = (applicationContext as MainApplication).msalInstance
        
        // Here you can implement your dashboard functionality
        // The MSAL instance is available if you need to make authenticated requests
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up resources if needed
    }
}