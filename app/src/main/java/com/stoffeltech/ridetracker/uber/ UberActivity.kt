package com.stoffeltech.ridetracker.uber

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.stoffeltech.ridetracker.R
import com.stoffeltech.ridetracker.uber.LoginActivity

class UberActivity : AppCompatActivity() {

    // Declare UI elements at the class level
    private lateinit var btnUberLogin: Button
    private lateinit var btnUberLogout: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_uber)

        // Initialize the UI elements
        btnUberLogin = findViewById(R.id.btnUberLogin)
//        tvUberStatus = findViewById(R.id.tvUberStatus)
        btnUberLogout = findViewById(R.id.btnUberLogout)

        // Always set the logout button listener
        btnUberLogout.setOnClickListener {
            Log.d("UberActivity", "Logout button clicked")
            val prefs = getSharedPreferences("uber_prefs", Context.MODE_PRIVATE)
            prefs.edit().remove("API_TOKEN").apply()
            btnUberLogin.text = "Login"
            btnUberLogin.isEnabled = true

            // Reset the login button's onClick listener after logout
            btnUberLogin.setOnClickListener {
                startActivity(Intent(this, LoginActivity::class.java))
            }

            Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()
        }

        // Check if an Uber token already exists in SharedPreferences
        val prefs = getSharedPreferences("uber_prefs", Context.MODE_PRIVATE)
        val uberToken = prefs.getString("API_TOKEN", null)

        if (!uberToken.isNullOrEmpty()) {
            btnUberLogin.text = "Already Logged In"
            btnUberLogin.isEnabled = false
        } else {
            btnUberLogin.text = "Login"
            btnUberLogin.isEnabled = true

            // Set up the login button to launch the real login flow.
            btnUberLogin.setOnClickListener {
                startActivity(Intent(this, LoginActivity::class.java))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("uber_prefs", Context.MODE_PRIVATE)
        val uberToken = prefs.getString("API_TOKEN", null)
        Log.d("UberActivity", "onResume token: $uberToken")

        if (!uberToken.isNullOrEmpty()) {
            btnUberLogin.text = "Already Logged In"
            btnUberLogin.isEnabled = false
        } else {
            btnUberLogin.text = "Login"
            btnUberLogin.isEnabled = true
            btnUberLogin.setOnClickListener {
                startActivity(Intent(this, LoginActivity::class.java))
            }
        }
    }
}
