package com.stoffeltech.ridetracker.uber

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.stoffeltech.ridetracker.MainActivity

class LoginActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val webView = WebView(this)
        setContentView(webView)

        val uberLoginUrl = "https://auth.uber.com/login/"

        webView.settings.javaScriptEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()

                // Log each redirect
                Log.d("UberLogin", "Redirected to: $url")

                // If redirected to Uber's main dashboard, extract cookies
                if (url.startsWith("https://m.uber.com/go/home")) {
                    extractUberSessionCookies()
                    return true
                }
                return false
            }
        }

        webView.loadUrl(uberLoginUrl)
    }

    private fun extractUberSessionCookies() {
        val cookieManager = CookieManager.getInstance()
        val uberCookies = cookieManager.getCookie("https://m.uber.com")

        if (uberCookies != null) {
            Log.d("UberCookies", "Session Cookies: $uberCookies")
            saveAuthToken(uberCookies)
            finishLogin(uberCookies)
        } else {
            Log.e("UberCookies", "No session cookies found!")
        }
    }

    // ----- UPDATED TOKEN EXTRACTION IN LoginActivity.kt -----
    // This method first tries to extract the token using "api_token=".
    // If that fails, it falls back to "jwt-session=".
    private fun saveAuthToken(cookies: String?) {
        if (!cookies.isNullOrEmpty()) {
            // Try to extract using "api_token"
            var regex = Regex("api_token=([^;]+)")
            var matchResult = regex.find(cookies)
            var newToken = matchResult?.groupValues?.get(1)

            // If not found, try "jwt-session"
            if (newToken.isNullOrEmpty()) {
                regex = Regex("jwt-session=([^;]+)")
                matchResult = regex.find(cookies)
                newToken = matchResult?.groupValues?.get(1)
            }

            if (!newToken.isNullOrEmpty()) {
                val prefs = getSharedPreferences("uber_prefs", MODE_PRIVATE)
                prefs.edit().putString("API_TOKEN", newToken).apply()
                Log.d("UberAuth", "Saved Uber API Token: $newToken")
            } else {
                Log.e("UberAuth", "API token not found in cookies!")
            }
        }
    }

    private fun finishLogin(token: String?) {
        // For example, send the user to MainActivity after login
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("API_TOKEN", token)
        startActivity(intent)
        finish()
    }


}
