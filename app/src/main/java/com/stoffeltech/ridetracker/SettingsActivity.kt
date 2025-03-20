// ----- SettingsActivity.kt - Updated for Immediate Manual Revenue Input -----
package com.stoffeltech.ridetracker

import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.stoffeltech.ridetracker.utils.RevenueTracker

class SettingsActivity : AppCompatActivity() {

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

        // Cost to drive components.
        val etCostDriving = findViewById<EditText>(R.id.etCostDriving)
        val btnSave = findViewById<Button>(R.id.btnSaveSettings)
        etCostDriving.setText(prefs.getFloat(KEY_COST_DRIVING, 0.5f).toString())

        btnSave.setOnClickListener {
            val editor = prefs.edit()
            editor.putFloat(KEY_COST_DRIVING, etCostDriving.text.toString().toFloatOrNull() ?: 0.5f)
            editor.apply()
            Toast.makeText(this, "Cost of driving saved", Toast.LENGTH_SHORT).show()
        }

        // Button for Private Rides.
        val btnAddPrivateRides = findViewById<Button>(R.id.btnAddPrivateRides)
        btnAddPrivateRides.setOnClickListener {
            // Immediately show the revenue input dialog.
            showRevenueInputDialog("Private Ride") { amount ->
                RevenueTracker.addRevenue(this, amount, "Private")
                Toast.makeText(this, "Added Private Ride revenue: $$amount", Toast.LENGTH_SHORT).show()
            }
        }

        // Button for Tips.
        val btnAddTips = findViewById<Button>(R.id.btnAddTips)
        btnAddTips.setOnClickListener {
            // Immediately show the revenue input dialog.
            showRevenueInputDialog("Tip") { amount ->
                RevenueTracker.addRevenue(this, amount, "Tip")
                Toast.makeText(this, "Added Tip revenue: $$amount", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Displays an AlertDialog with an EditText for numeric revenue input.
     *
     * @param title The title of the dialog (e.g., "Private Ride" or "Tip").
     * @param onAmountEntered Callback that receives the entered amount as a Double.
     */
    // ----- Updated showRevenueInputDialog in SettingsActivity.kt -----
    private fun showRevenueInputDialog(title: String, onAmountEntered: (Double) -> Unit) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Enter $title Revenue")

        // Set up the input field with a number-style keyboard.
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        builder.setView(input)

        // Set up the buttons.
        builder.setPositiveButton("OK") { dialog, _ ->
            val amount = input.text.toString().toDoubleOrNull()
            if (amount != null && amount > 0) {
                onAmountEntered(amount)
            } else {
                Toast.makeText(this, "Enter a valid amount", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.cancel()
        }

        // Create the dialog.
        val dialog = builder.create()
        // When the dialog is shown, automatically request focus and show the keyboard.
        dialog.setOnShowListener {
            input.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
    }

}
