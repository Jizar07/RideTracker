package com.stoffeltech.ridetracker

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.app.AppOpsManager
import android.os.Process

fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}


// Checks if the Uber Driver app ("com.ubercab.driver") is currently in the foreground.
fun isUberDriverForeground(context: Context): Boolean {
    val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val endTime = System.currentTimeMillis()
    val beginTime = endTime - 10_000 // Check usage for the last 10 seconds
    val usageStatsList = usageStatsManager.queryUsageStats(
        UsageStatsManager.INTERVAL_DAILY, beginTime, endTime
    )
    usageStatsList?.forEach { usageStats ->
        if (usageStats.packageName == "com.ubercab.driver" &&
            usageStats.lastTimeUsed > beginTime) {
            return true
        }
    }
    return false
}

// Launches the Uber Driver app if it isn't already in the foreground.
fun bringUberDriverToForeground(context: Context) {
    if (!isUberDriverForeground(context)) {
        val packageManager = context.packageManager
        val launchIntent = packageManager.getLaunchIntentForPackage("com.ubercab.driver")
        if (launchIntent != null) {
            // FLAG_ACTIVITY_NEW_TASK is required when starting an Activity from a Service
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        } else {
            Log.e("AppUtils", "Uber Driver app is not installed.")
        }
    } else {
        Log.d("AppUtils", "Uber Driver app is already in the foreground.")
    }
}
