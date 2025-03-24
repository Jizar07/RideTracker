package com.stoffeltech.ridetracker.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.stoffeltech.ridetracker.R
import com.stoffeltech.ridetracker.services.RideInfo
import com.stoffeltech.ridetracker.SettingsActivity
import com.stoffeltech.ridetracker.uber.UberParser
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

        // Format date/time if RideInfo has a timestamp.
        private val dateFormat = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.getDefault())

        @SuppressLint("SetTextI18n", "DefaultLocale")
        fun bind(ride: RideInfo) {
            // ========== 1) Ride Type + Timestamp ==========
            tvRideType.text = ride.rideType ?: "Unknown"
            val timeStamp = if (ride.timestamp > 0) dateFormat.format(Date(ride.timestamp)) else ""
            tvTimestamp.text = timeStamp

            // ========== 2) Passenger Rating ==========
            var ratingText = if (ride.rating != null) String.format("%.2f", ride.rating) else "--"
            if ((ride.rideType?.contains("Lyft", ignoreCase = true) == true) && !ride.riderName.isNullOrEmpty()) {
                ratingText += " - ${ride.riderName}"
            }
            tvRating.text = ratingText
            val defaultRatingThreshold = 4.70f
            val ratingColor = if ((ride.rating ?: 0.0) >= defaultRatingThreshold) Color.GREEN else Color.RED
            tvRating.setTextColor(ratingColor)

            // ========== Load Threshold Settings Based on Ride Type ==========
            val context = itemView.context
            var fareLow: Double
            var fareHigh: Double
            var acceptMile: Float
            var declineMile: Float
            var acceptHour: Float
            var declineHour: Float

            if (ride.rideType != null && UberParser.getRideTypes().any { it.equals(ride.rideType, ignoreCase = true) }) {
                val uberPrefs = context.getSharedPreferences("uber_prefs", Context.MODE_PRIVATE)
                fareLow = uberPrefs.getFloat("pref_fare_low", 5.0f).toDouble()
                fareHigh = uberPrefs.getFloat("pref_fare_high", 10.0f).toDouble()
                acceptMile = uberPrefs.getFloat("pref_accept_mile", 1.0f)
                declineMile = uberPrefs.getFloat("pref_decline_mile", 0.75f)
                acceptHour = uberPrefs.getFloat("pref_accept_hour", 25.0f)
                declineHour = uberPrefs.getFloat("pref_decline_hour", 20.0f)
            } else if (ride.rideType?.contains("Lyft", ignoreCase = true) == true) {
                val lyftPrefs = context.getSharedPreferences("lyft_prefs", Context.MODE_PRIVATE)
                fareLow = lyftPrefs.getFloat("pref_fare_low", 5.0f).toDouble()
                fareHigh = lyftPrefs.getFloat("pref_fare_high", 10.0f).toDouble()
                acceptMile = lyftPrefs.getFloat("pref_accept_mile", 1.0f)
                declineMile = lyftPrefs.getFloat("pref_decline_mile", 0.75f)
                acceptHour = lyftPrefs.getFloat("pref_accept_hour", 25.0f)
                declineHour = lyftPrefs.getFloat("pref_decline_hour", 20.0f)
            } else {
                fareLow = 5.0
                fareHigh = 10.0
                acceptMile = 1.0f
                declineMile = 0.75f
                acceptHour = 25.0f
                declineHour = 20.0f
            }

            // Load cost of driving from main settings
            val mainPrefs = PreferenceManager.getDefaultSharedPreferences(context)
            val costDriving = mainPrefs.getFloat(SettingsActivity.KEY_COST_DRIVING, 0.5f)

            // ========== 3) Price + Profit ==========
            val fare = ride.fare ?: 0.0
            val finalFare = fare // You may adjust this if needed.
            val fareColor = when {
                finalFare < fareLow -> Color.RED
                finalFare < fareHigh -> Color.YELLOW
                else -> Color.GREEN
            }
            tvPrice.text = "$%.2f".format(finalFare)
            tvPrice.setTextColor(fareColor)

            val totalMiles = (ride.pickupDistance ?: 0.0) + (ride.tripDistance ?: 0.0)
            val drivingCost = costDriving * totalMiles
            val profit = finalFare - drivingCost
            val profitColor = if (profit >= 0) Color.GREEN else Color.RED

            tvProfitLoss.text = "$%.2f".format(profit)
            tvProfitLoss.setTextColor(profitColor)

            // ========== 4) Price per Mile + Price per Hour ==========
            val pickupTime = ride.pickupTime ?: 0.0
            val tripTime = ride.tripTime ?: 0.0
            val totalMinutes = pickupTime + tripTime
            val pricePerMile = if (totalMiles > 0) finalFare / totalMiles else 0.0
            val pricePerHour = if (totalMinutes > 0) finalFare / (totalMinutes / 60.0) else 0.0

            val mileColor = when {
                pricePerMile < declineMile -> Color.RED
                pricePerMile < acceptMile -> Color.YELLOW
                else -> Color.GREEN
            }
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

            // ========== 9) Additional Fields ==========
            if (!ride.requestStatus.isNullOrBlank()) {
                tvExtras.append("\nStatus: ${ride.requestStatus}")
            }
            if (!ride.riderName.isNullOrBlank()) {
                tvExtras.append("\nRider: ${ride.riderName}")
            }
            if (!ride.rideSubtype.isNullOrBlank()) {
                tvExtras.append("\nSubtype: ${ride.rideSubtype}")
            }
        }
    }
}
