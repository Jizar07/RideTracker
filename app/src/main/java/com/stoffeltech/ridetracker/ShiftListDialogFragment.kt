package com.stoffeltech.ridetracker

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.stoffeltech.ridetracker.adapters.ShiftAdapter
import com.stoffeltech.ridetracker.utils.TimeTracker

class ShiftListDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val recyclerView = RecyclerView(requireContext())
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // Get a copy of the saved intervals (shifts)
        val shifts = TimeTracker.getShifts()  // We'll add a helper function in TimeTracker.
        recyclerView.adapter = ShiftAdapter(shifts)

        return AlertDialog.Builder(requireContext())
            .setTitle("Shift Details")
            .setView(recyclerView)
            .setPositiveButton("Close") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
    }
}
