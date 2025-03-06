package com.stoffeltech.ridetracker.future

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

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

    private fun saveAuthToken(cookies: String?) {
        if (!cookies.isNullOrEmpty()) {
            val regex = Regex("jwt-session=([^;]+)")
            val matchResult = regex.find(cookies)
            val jwtToken = matchResult?.groupValues?.get(1)

            if (!jwtToken.isNullOrEmpty()) {
                val prefs = getSharedPreferences("uber_prefs", MODE_PRIVATE)
                prefs.edit().putString("API_TOKEN", jwtToken).apply()
                Log.d("UberAuth", "Saved Uber API Token: $jwtToken")
            } else {
                Log.e("UberAuth", "JWT token not found in cookies!")
            }
        }
    }


    private fun finishLogin(token: String?) {
        val intent = Intent(this, RideDataActivity::class.java)
        intent.putExtra("API_TOKEN", token)
        startActivity(intent)
        finish()
    }
}
