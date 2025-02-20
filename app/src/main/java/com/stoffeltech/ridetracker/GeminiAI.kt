package com.stoffeltech.ridetracker

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GenerationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GeminiAI {
    private val apiKey: String = BuildConfig.GOOGLE_AI_API_KEY

    // Use the builder provided by GenerationConfig to create an instance.
    private val model = GenerativeModel(
        modelName = "gemini-pro",
        apiKey = apiKey,
        generationConfig = GenerationConfig.Builder()
//            .setMaxTokens(100)
            .build()
    )

    suspend fun analyzeText(inputText: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val response = model.generateContent(inputText)
                response.text ?: "No response from AI"
            } catch (e: Exception) {
                Log.e("GeminiAI", "Error: ${e.message}")
                "Error processing AI request"
            }
        }
    }
}
