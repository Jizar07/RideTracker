package com.stoffeltech.ridetracker.uber

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.stoffeltech.ridetracker.R
import com.stoffeltech.ridetracker.uber.LoginActivity

class UberActivity : AppCompatActivity() {

    // Declare UI elements at the class level
    private lateinit var btnUberLogin: Button
    private lateinit var btnUberLogout: Button

    // Declare UI elements for threshold settings
    private lateinit var etUberAcceptMile: EditText
    private lateinit var etUberDeclineMile: EditText
    private lateinit var etUberAcceptHour: EditText
    private lateinit var etUberDeclineHour: EditText
    private lateinit var etUberFareLow: EditText
    private lateinit var etUberFareHigh: EditText
    private lateinit var etUberAcceptRating: EditText  // optional, if needed
    private lateinit var btnUberSaveSettings: Button

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

        // ---------------- NEW: Uber Threshold Settings ----------------
        // Bind the threshold settings UI elements from activity_uber.xml
        etUberAcceptMile = findViewById(R.id.etUberAcceptMile)
        etUberDeclineMile = findViewById(R.id.etUberDeclineMile)
        etUberAcceptHour = findViewById(R.id.etUberAcceptHour)
        etUberDeclineHour = findViewById(R.id.etUberDeclineHour)
        etUberFareLow = findViewById(R.id.etUberFareLow)
        etUberFareHigh = findViewById(R.id.etUberFareHigh)
        etUberAcceptRating = findViewById(R.id.etUberAcceptRating)  // if used
        btnUberSaveSettings = findViewById(R.id.btnUberSaveSettings)

        // Load stored values or defaults into the EditTexts from "uber_prefs"
        etUberAcceptMile.setText(prefs.getFloat("pref_accept_mile", 1.0f).toString())
        etUberDeclineMile.setText(prefs.getFloat("pref_decline_mile", 0.75f).toString())
        etUberAcceptHour.setText(prefs.getFloat("pref_accept_hour", 25.0f).toString())
        etUberDeclineHour.setText(prefs.getFloat("pref_decline_hour", 20.0f).toString())
        etUberFareLow.setText(prefs.getFloat("pref_fare_low", 5.0f).toString())
        etUberFareHigh.setText(prefs.getFloat("pref_fare_high", 10.0f).toString())
        etUberAcceptRating.setText(prefs.getFloat("pref_rating_threshold", 4.70f).toString())

        // Set up the Save button to store the threshold settings
        btnUberSaveSettings.setOnClickListener {
            val editor = prefs.edit()
            editor.putFloat("pref_accept_mile", etUberAcceptMile.text.toString().toFloatOrNull() ?: 1.0f)
            editor.putFloat("pref_decline_mile", etUberDeclineMile.text.toString().toFloatOrNull() ?: 0.75f)
            editor.putFloat("pref_accept_hour", etUberAcceptHour.text.toString().toFloatOrNull() ?: 25.0f)
            editor.putFloat("pref_decline_hour", etUberDeclineHour.text.toString().toFloatOrNull() ?: 20.0f)
            editor.putFloat("pref_fare_low", etUberFareLow.text.toString().toFloatOrNull() ?: 5.0f)
            editor.putFloat("pref_fare_high", etUberFareHigh.text.toString().toFloatOrNull() ?: 10.0f)
            editor.putFloat("pref_rating_threshold", etUberAcceptRating.text.toString().toFloatOrNull() ?: 4.70f)
            editor.apply()
            Toast.makeText(this, "Uber settings saved", Toast.LENGTH_SHORT).show()
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
