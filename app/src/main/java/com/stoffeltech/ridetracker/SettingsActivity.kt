package com.stoffeltech.ridetracker

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

class SettingsActivity : AppCompatActivity() {

    // Keys for SharedPreferences
    companion object {
        const val KEY_ACCEPT_MILE = "pref_accept_mile"       // default: 1.0
        const val KEY_DECLINE_MILE = "pref_decline_mile"       // default: 0.75
        const val KEY_ACCEPT_HOUR = "pref_accept_hour"         // default: 25.0
        const val KEY_DECLINE_HOUR = "pref_decline_hour"         // default: 20.0
        const val KEY_FARE_LOW = "pref_fare_low"               // default: 5.0
        const val KEY_FARE_HIGH = "pref_fare_high"             // default: 10.0
        const val KEY_BONUS_RIDE = "pref_bonus_ride"
        const val KEY_COST_DRIVING = "pref_cost_driving"
        const val KEY_RATING_THRESHOLD = "pref_rating_threshold" // default: 4.70

    }

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)


        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        val etAcceptMile = findViewById<EditText>(R.id.etAcceptMile)
        val etDeclineMile = findViewById<EditText>(R.id.etDeclineMile)
        val etAcceptHour = findViewById<EditText>(R.id.etAcceptHour)
        val etDeclineHour = findViewById<EditText>(R.id.etDeclineHour)
        val etFareLow = findViewById<EditText>(R.id.etFareLow)
        val etFareHigh = findViewById<EditText>(R.id.etFareHigh)
        val etAcceptRating = findViewById<EditText>(R.id.etAcceptRating)
        val etBonusRide = findViewById<EditText>(R.id.etBonusRide)
        val etCostDriving = findViewById<EditText>(R.id.etCostDriving)
        val btnSave = findViewById<Button>(R.id.btnSaveSettings)

        // Load stored values (or default ones)
        etAcceptMile.setText(prefs.getFloat(KEY_ACCEPT_MILE, 1.0f).toString())
        etDeclineMile.setText(prefs.getFloat(KEY_DECLINE_MILE, 0.75f).toString())
        etAcceptHour.setText(prefs.getFloat(KEY_ACCEPT_HOUR, 25.0f).toString())
        etDeclineHour.setText(prefs.getFloat(KEY_DECLINE_HOUR, 20.0f).toString())
        etFareLow.setText(prefs.getFloat(KEY_FARE_LOW, 5.0f).toString())
        etFareHigh.setText(prefs.getFloat(KEY_FARE_HIGH, 10.0f).toString())
        etAcceptRating.setText(prefs.getFloat(KEY_RATING_THRESHOLD, 4.70f).toString())
        etBonusRide.setText(prefs.getFloat(KEY_BONUS_RIDE, 0.0f).toString())
        etCostDriving.setText(prefs.getFloat(KEY_COST_DRIVING, 0.5f).toString())

        btnSave.setOnClickListener {
            // Save values to SharedPreferences
            val editor = prefs.edit()
            editor.putFloat(KEY_ACCEPT_MILE, etAcceptMile.text.toString().toFloatOrNull() ?: 1.0f)
            editor.putFloat(KEY_DECLINE_MILE, etDeclineMile.text.toString().toFloatOrNull() ?: 0.75f)
            editor.putFloat(KEY_ACCEPT_HOUR, etAcceptHour.text.toString().toFloatOrNull() ?: 25.0f)
            editor.putFloat(KEY_DECLINE_HOUR, etDeclineHour.text.toString().toFloatOrNull() ?: 20.0f)
            editor.putFloat(KEY_FARE_LOW, etFareLow.text.toString().toFloatOrNull() ?: 5.0f)
            editor.putFloat(KEY_FARE_HIGH, etFareHigh.text.toString().toFloatOrNull() ?: 10.0f)
            editor.putFloat(KEY_RATING_THRESHOLD, etAcceptRating.text.toString().toFloatOrNull() ?: 4.70f)
            editor.putFloat(KEY_BONUS_RIDE, etBonusRide.text.toString().toFloatOrNull() ?: 0.0f)
            editor.putFloat(KEY_COST_DRIVING, etCostDriving.text.toString().toFloatOrNull() ?: 0.5f)

            editor.apply()
            Log.d("SettingsActivity", "Saved thresholds: AcceptMile=${prefs.getFloat(KEY_ACCEPT_MILE, 1.0f)}, DeclineMile=${prefs.getFloat(KEY_DECLINE_MILE, 0.75f)}, AcceptHour=${prefs.getFloat(KEY_ACCEPT_HOUR, 25.0f)}, DeclineHour=${prefs.getFloat(KEY_DECLINE_HOUR, 20.0f)}, FareLow=${prefs.getFloat(KEY_FARE_LOW, 5.0f)}, FareHigh=${prefs.getFloat(KEY_FARE_HIGH, 10.0f)}")
            finish() // Close activity when done
        }
    }
}
