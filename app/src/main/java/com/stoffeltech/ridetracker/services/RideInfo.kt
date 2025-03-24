package com.stoffeltech.ridetracker.services

data class RideInfo(
    val rideType: String?,
    val fare: Double?,
    val rating: Double?,
    val pickupTime: Double?,
    val pickupDistance: Double?,
    val tripTime: Double?,
    val tripDistance: Double?,
    val pickupLocation: String?,
    val tripLocation: String?,
    val stops: String?,
    val actionButton: String? = null,

    // -------------- NEW FIELDS --------------
    val rideSubtype: String? = null,
    val bonuses: String? = null,
    val isExclusive: Boolean,
    var riderName: String? = null,
    var requestStatus: String? = null,   // NEW: "Accepted" or "Declined"
    val timestamp: Long = System.currentTimeMillis()
)
