package com.stoffeltech.ridetracker.utils

import com.google.android.gms.maps.model.LatLng

data class Route(
    val polylinePoints: List<LatLng>,
    val steps: List<RouteStep>
)

data class RouteStep(
    val instruction: String,
    val startLocation: LatLng,
    val endLocation: LatLng
)
