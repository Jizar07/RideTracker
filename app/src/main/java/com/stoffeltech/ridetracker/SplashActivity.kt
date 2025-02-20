package com.stoffeltech.ridetracker

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    // Duration for which the splash screen is shown (in milliseconds)
    private val splashDuration = 3000L  // 2 seconds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Set the content view to your splash layout (see next section)
        setContentView(R.layout.activity_splash)

        // Launch a coroutine on the Main dispatcher to delay and then start MainActivity
        CoroutineScope(Dispatchers.Main).launch {
            delay(splashDuration)
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            finish()
        }
    }
}
