package com.example.kenza

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.kenza.kenza.ui.activities.LoginActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Immediately redirect to LoginActivity
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}