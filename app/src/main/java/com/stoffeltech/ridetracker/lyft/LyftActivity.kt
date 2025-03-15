// ----- LyftActivity.kt -----
// This activity serves as the Lyft Settings menu.
package com.stoffeltech.ridetracker.lyft

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.stoffeltech.ridetracker.R

// Import LyftKeys which holds the Lyft-specific threshold keys.
import com.stoffeltech.ridetracker.lyft.LyftKeys

class LyftActivity : AppCompatActivity() {

    // Obtain the dedicated Lyft preferences.
    private val lyftPrefs by lazy {
        getSharedPreferences("lyft_prefs", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Use the layout you created for Lyft settings.
        setContentView(R.layout.activity_lyft)

        // Bind the UI elements.
        val etAcceptMile = findViewById<EditText>(R.id.etLyftAcceptMile)
        val etDeclineMile = findViewById<EditText>(R.id.etLyftDeclineMile)
        val etAcceptHour = findViewById<EditText>(R.id.etLyftAcceptHour)
        val etDeclineHour = findViewById<EditText>(R.id.etLyftDeclineHour)
        val etFareLow = findViewById<EditText>(R.id.etLyftFareLow)
        val etFareHigh = findViewById<EditText>(R.id.etLyftFareHigh)
        val etAcceptRating = findViewById<EditText>(R.id.etLyftAcceptRating)
        val etBonusRide = findViewById<EditText>(R.id.etLyftBonusRide)
        val btnSave = findViewById<Button>(R.id.btnLyftSaveSettings)

        // Load stored values (or defaults) into the EditTexts.
        etAcceptMile.setText(lyftPrefs.getFloat(LyftKeys.KEY_ACCEPT_MILE, 1.0f).toString())
        etDeclineMile.setText(lyftPrefs.getFloat(LyftKeys.KEY_DECLINE_MILE, 0.75f).toString())
        etAcceptHour.setText(lyftPrefs.getFloat(LyftKeys.KEY_ACCEPT_HOUR, 25.0f).toString())
        etDeclineHour.setText(lyftPrefs.getFloat(LyftKeys.KEY_DECLINE_HOUR, 20.0f).toString())
        etFareLow.setText(lyftPrefs.getFloat(LyftKeys.KEY_FARE_LOW, 5.0f).toString())
        etFareHigh.setText(lyftPrefs.getFloat(LyftKeys.KEY_FARE_HIGH, 10.0f).toString())
        etAcceptRating.setText(lyftPrefs.getFloat(LyftKeys.KEY_RATING_THRESHOLD, 4.70f).toString())
        etBonusRide.setText(lyftPrefs.getFloat(LyftKeys.KEY_BONUS_RIDE, 0.0f).toString())

        // Set up the save button to store the settings.
        btnSave.setOnClickListener {
            val editor = lyftPrefs.edit()
            editor.putFloat(LyftKeys.KEY_ACCEPT_MILE, etAcceptMile.text.toString().toFloatOrNull() ?: 1.0f)
            editor.putFloat(LyftKeys.KEY_DECLINE_MILE, etDeclineMile.text.toString().toFloatOrNull() ?: 0.75f)
            editor.putFloat(LyftKeys.KEY_ACCEPT_HOUR, etAcceptHour.text.toString().toFloatOrNull() ?: 25.0f)
            editor.putFloat(LyftKeys.KEY_DECLINE_HOUR, etDeclineHour.text.toString().toFloatOrNull() ?: 20.0f)
            editor.putFloat(LyftKeys.KEY_FARE_LOW, etFareLow.text.toString().toFloatOrNull() ?: 5.0f)
            editor.putFloat(LyftKeys.KEY_FARE_HIGH, etFareHigh.text.toString().toFloatOrNull() ?: 10.0f)
            editor.putFloat(LyftKeys.KEY_RATING_THRESHOLD, etAcceptRating.text.toString().toFloatOrNull() ?: 4.70f)
            editor.putFloat(LyftKeys.KEY_BONUS_RIDE, etBonusRide.text.toString().toFloatOrNull() ?: 0.0f)
            editor.apply()
            Log.d("LyftActivity", "Lyft settings saved.")
            finish() // Close the activity after saving.
        }
    }
}
