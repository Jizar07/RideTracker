// ----- SettingsActivity.kt - Updated to only include Cost to Drive -----
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

    // Only cost to drive key remains in main settings.
    companion object {
        const val KEY_COST_DRIVING = "pref_cost_driving"  // default: 0.5
    }

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        // Load main preferences.
        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        // Only the cost-to-drive field is now present.
        val etCostDriving = findViewById<EditText>(R.id.etCostDriving)
        val btnSave = findViewById<Button>(R.id.btnSaveSettings)

        // Load the stored cost-to-drive value, or use the default of 0.5.
        etCostDriving.setText(prefs.getFloat(KEY_COST_DRIVING, 0.5f).toString())

        btnSave.setOnClickListener {
            // Save only the cost-to-drive value to SharedPreferences.
            val editor = prefs.edit()
            editor.putFloat(KEY_COST_DRIVING, etCostDriving.text.toString().toFloatOrNull() ?: 0.5f)
            editor.apply()
            Log.d("SettingsActivity", "Saved cost to drive: ${prefs.getFloat(KEY_COST_DRIVING, 0.5f)}")
            finish() // Close activity when done.
        }
    }
}
