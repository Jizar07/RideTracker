package com.stoffeltech.ridetracker.utils

import android.animation.ArgbEvaluator
import android.graphics.Color

// Data class to hold user settings for scoring.
data class RideScoreSettings(
    // Ideal values for each variable (the "green" baseline).
    val idealPMile: Float,
    val idealPHour: Float,
    val idealFare: Float,
    // Scale factors to add extra points for values above the ideal.
    val scaleFactorPMile: Float,
    val scaleFactorPHour: Float,
    val scaleFactorFare: Float,
    // Weights for each variable in the composite score (e.g., values between 0.0 and 1.0).
    val weightPMile: Float,
    val weightPHour: Float,
    val weightFare: Float
)

// Function to calculate the score for an individual variable.
// If the actual value is less than the ideal, it returns (actual/ideal)*100.
// If the actual value is equal to or above the ideal, it adds extra points based on the overshoot.
fun calculateVariableScore(actual: Float, ideal: Float, scaleFactor: Float): Float {
    return if (actual < ideal) {
        (actual / ideal) * 100f
    } else {
        100f + ((actual - ideal) * scaleFactor)
    }
}

// Function to calculate the composite ride score from the three variables.
fun calculateRideScore(
    actualPMile: Float,
    actualPHour: Float,
    actualFare: Float,
    settings: RideScoreSettings
): Float {
    val scorePMile = calculateVariableScore(actualPMile, settings.idealPMile, settings.scaleFactorPMile)
    val scorePHour = calculateVariableScore(actualPHour, settings.idealPHour, settings.scaleFactorPHour)

    // NEW: Hardcode fare's 0-100 logic with no overshoot
    val scoreFare = if (actualFare < settings.idealFare) {
        // Scale up to 100 if below ideal
        (actualFare / settings.idealFare) * 100f
    } else {
        // Cap at 100 if actualFare >= idealFare
        100f
    }

    // Weighted composite
    val compositeScore = (scorePMile * settings.weightPMile) +
            (scorePHour * settings.weightPHour) +
            (scoreFare  * settings.weightFare)
    return compositeScore
}

// Function to map a composite score to a color.
// Mapping details:
// - 0 maps to red.
// - 0-50: Interpolate from red to yellow.
// - 50-100: Interpolate from yellow to green.
// - Above 100: Interpolate from green to blue (with an upper bound for full blue, e.g., score >= 150).
fun getScoreColor(score: Float): Int {
    return when {
        score <= 0f -> Color.RED
        score in 0f..50f -> {
            // Calculate factor (0 to 1) between red and yellow.
            val factor = score / 50f
            interpolateColor(Color.RED, Color.YELLOW, factor)
        }
        score in 50f..100f -> {
            // Calculate factor (0 to 1) between yellow and green.
            val factor = (score - 50f) / 50f
            interpolateColor(Color.YELLOW, Color.GREEN, factor)
        }
        else -> {
            // For scores above 100, interpolate from green to blue.
            // Define an upper bound (e.g., score 150 or above is full blue).
            val factor = ((score - 100f) / 50f).coerceAtMost(1f)
            interpolateColor(Color.GREEN, Color.BLUE, factor)
        }
    }
}

// Helper function to interpolate between two colors using a factor in [0, 1].
// This uses Android's ArgbEvaluator for smooth interpolation.
fun interpolateColor(colorStart: Int, colorEnd: Int, factor: Float): Int {
    return ArgbEvaluator().evaluate(factor, colorStart, colorEnd) as Int
}
