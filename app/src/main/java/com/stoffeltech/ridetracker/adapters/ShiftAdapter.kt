package com.stoffeltech.ridetracker.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.stoffeltech.ridetracker.R
import com.stoffeltech.ridetracker.utils.TimeInterval
import java.text.SimpleDateFormat
import java.util.*

class ShiftAdapter(private val shifts: List<TimeInterval>) :
    RecyclerView.Adapter<ShiftAdapter.ShiftViewHolder>() {

    inner class ShiftViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvShiftHeader: TextView = itemView.findViewById(R.id.tvShiftHeader)
        val tvShiftDetails: TextView = itemView.findViewById(R.id.tvShiftDetails)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShiftViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_shift, parent, false)
        return ShiftViewHolder(view)
    }

    override fun onBindViewHolder(holder: ShiftViewHolder, position: Int) {
        val shift = shifts[position]
        val context = holder.itemView.context

        // Format the date and times.
        val dateFormat = SimpleDateFormat("EEEE, MMM d, yyyy", Locale.getDefault())
        val timeFormat = SimpleDateFormat("h:mma", Locale.getDefault())
        val shiftDate = dateFormat.format(Date(shift.start))
        val startTime = timeFormat.format(Date(shift.start))
        val endTime = timeFormat.format(Date(shift.end))
        val durationMillis = shift.end - shift.start
        val hours = durationMillis / (1000 * 60 * 60)
        val minutes = (durationMillis / (1000 * 60)) % 60

        // For demonstration, we'll label shifts sequentially.
        holder.tvShiftHeader.text = "$shiftDate - Shift ${position + 1}"
        holder.tvShiftDetails.text = "$startTime - $endTime (${hours}h ${minutes}m)"
    }

    override fun getItemCount(): Int = shifts.size

}
