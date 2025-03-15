package com.stoffeltech.ridetracker.adapters

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.stoffeltech.ridetracker.R
import com.stoffeltech.ridetracker.services.RideInfo
import java.text.SimpleDateFormat
import java.util.*

class RequestHistoryAdapter(
    private var rideList: List<RideInfo>
) : RecyclerView.Adapter<RequestHistoryAdapter.RequestHistoryViewHolder>() {

    @SuppressLint("NotifyDataSetChanged")
    fun updateData(newData: List<RideInfo>) {
        rideList = newData
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RequestHistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_request_history, parent, false)
        return RequestHistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: RequestHistoryViewHolder, position: Int) {
        val ride = rideList[position]
        holder.bind(ride)
    }

    override fun getItemCount(): Int = rideList.size

    class RequestHistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        // Row 1: Ride type + Timestamp
        private val tvRideType: TextView = itemView.findViewById(R.id.tvRideType)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)

        // Passenger Rating
        private val tvRating: TextView = itemView.findViewById(R.id.tvRating)

        // Price (left) + Profit (right)
        private val tvPrice: TextView = itemView.findViewById(R.id.tvPrice)
        private val tvProfitLoss: TextView = itemView.findViewById(R.id.tvProfitLoss)

        // Price per Mile (left) + Price per Hour (right)
        private val tvPricePerMile: TextView = itemView.findViewById(R.id.tvPricePerMile)
        private val tvPricePerHour: TextView = itemView.findViewById(R.id.tvPricePerHour)

        // Distance (left) + Time (right)
        private val tvDistance: TextView = itemView.findViewById(R.id.tvDistance)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)

        // Pickup + Dropoff info
        private val tvPickupInfo: TextView = itemView.findViewById(R.id.tvPickupInfo)
        private val tvDropoffInfo: TextView = itemView.findViewById(R.id.tvDropoffInfo)

        private val tvPickupLocation: TextView = itemView.findViewById(R.id.tvPickupLocation)
        private val tvDropoffLocation: TextView = itemView.findViewById(R.id.tvDropoffLocation)


        // Extras: bonuses, stops, etc.
        private val tvExtras: TextView = itemView.findViewById(R.id.tvExtras)

        // Format date/time if RideInfo has a timestamp
        private val dateFormat = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.getDefault())

        @SuppressLint("SetTextI18n", "DefaultLocale")
        fun bind(ride: RideInfo) {
            // ========== 1) Ride Type + Timestamp (no slash) ==========
            tvRideType.text = ride.rideType ?: "Unknown"

            // If you store a timestamp in RideInfo, use it:
            val timeStamp = if (ride.timestamp > 0) {
                dateFormat.format(Date(ride.timestamp))
            } else {
                // fallback if no timestamp
                ""
            }
            tvTimestamp.text = timeStamp

            // ========== 2) Passenger Rating (with color + verified + rider name if Lyft) ==========
            // Suppose you store a boolean `verified` somewhere; or check if rating was verified
            // If you have "verified" in ride or not, adapt accordingly:
            var ratingText = ""
            if (ride.rating != null) {
                ratingText += String.format("%.2f", ride.rating)
            } else {
                ratingText += "--"
            }
//            // If verified
//            if (ride.isVerified) {
//                ratingText += " (Verified)"
//            }
            // If Lyft + riderName is not blank, add it
            if ((ride.rideType?.contains("Lyft", ignoreCase = true) == true) &&
                !ride.riderName.isNullOrEmpty()) {
                ratingText += " -  ${ride.riderName}"
            }
            tvRating.text = ratingText

            // Apply color logic for rating
            // Example thresholds from your overlay code
            val ratingThreshold = 4.70f
            val ratingColor = if ((ride.rating ?: 0.0) >= ratingThreshold) Color.GREEN else Color.RED
            tvRating.setTextColor(ratingColor)

            // ========== 3) Price + Profit (color-coded) ==========
            // If you want the same thresholds from your overlay, replicate them here or store them
            val fare = ride.fare ?: 0.0
            // Suppose you also store finalFare or bonus, do that logic:
            // For simplicity, let's assume "finalFare = ride.fare"
            val finalFare = fare

            // Basic color logic from overlay:
            val fareLow = 5.0
            val fareHigh = 10.0
            val fareColor = when {
                finalFare < fareLow -> Color.RED
                finalFare < fareHigh -> Color.YELLOW
                else -> Color.GREEN
            }
            tvPrice.text = "$%.2f".format(finalFare)
            tvPrice.setTextColor(fareColor)

            // Profit - you might store it in ride or compute it
            // For now let's do a simple guess
            val drivingCost = 0.20 * (ride.tripDistance ?: (0.0 + ride.pickupDistance!!) ?: 0.0)
            val profit = finalFare - drivingCost
            val profitColor = if (profit >= 0) Color.GREEN else Color.RED

            tvProfitLoss.text = "$%.2f".format(profit)
            tvProfitLoss.setTextColor(profitColor)

            // ========== 4) Price per Mile + Price per Hour (color-coded) ==========
            val totalMiles = (ride.pickupDistance ?: 0.0) + (ride.tripDistance ?: 0.0)
            val totalMinutes = (ride.pickupTime ?: 0.0) + (ride.tripTime ?: 0.0)
            val pricePerMile = if (totalMiles > 0) finalFare / totalMiles else 0.0
            val pricePerHour = if (totalMinutes > 0) finalFare / (totalMinutes / 60.0) else 0.0

            // Suppose acceptMile=1.0, declineMile=0.75, etc.
            val acceptMile = 1.0
            val declineMile = 0.75
            val mileColor = when {
                pricePerMile < declineMile -> Color.RED
                pricePerMile < acceptMile -> Color.YELLOW
                else -> Color.GREEN
            }

            // Suppose acceptHour=25.0, declineHour=20.0
            val acceptHour = 25.0
            val declineHour = 20.0
            val hourColor = when {
                pricePerHour < declineHour -> Color.RED
                pricePerHour < acceptHour -> Color.YELLOW
                else -> Color.GREEN
            }

            tvPricePerMile.text = "$%.2f".format(pricePerMile)
            tvPricePerMile.setTextColor(mileColor)

            tvPricePerHour.text = "$%.2f".format(pricePerHour)
            tvPricePerHour.setTextColor(hourColor)

            // ========== 5) Total Distance + Total Time ==========
            tvDistance.text = "Total Distance: %.1f mi".format(totalMiles)
            tvTime.text = "Total Time: %.1f min".format(totalMinutes)

            // ========== 6) Pickup info on one line, location on another ==========
            val pickupTxt = "Pickup: ${ride.pickupTime ?: "--"} min, ${ride.pickupDistance ?: "--"} mi away"
            val pickupLoc = "[${ride.pickupLocation ?: "N/A"}]"
            tvPickupInfo.text = pickupTxt
            tvPickupLocation.text = pickupLoc

            // ========== 7) Dropoff info on one line, location on another ==========
            val dropoffTxt = "Dropoff: ${ride.tripTime ?: "--"} min, ${ride.tripDistance ?: "--"} mi trip"
            val dropoffLoc = "[${ride.tripLocation ?: "N/A"}]"
            tvDropoffInfo.text = dropoffTxt
            tvDropoffLocation.text = dropoffLoc


            // ========== 8) Extras: bonus, stops, etc. ==========
            val extras = StringBuilder()
            if (!ride.bonuses.isNullOrBlank()) {
                extras.append("Bonus: ${ride.bonuses}, ")
            }
            if (!ride.stops.isNullOrBlank()) {
                extras.append("Stops: ${ride.stops}")
            }
            if (extras.isEmpty()) {
                tvExtras.visibility = View.GONE
            } else {
                tvExtras.visibility = View.VISIBLE
                tvExtras.text = extras.toString().trim().trimEnd(',')
            }
        }
    }
}
