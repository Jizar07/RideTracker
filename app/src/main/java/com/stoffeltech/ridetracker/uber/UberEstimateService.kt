package com.stoffeltech.ridetracker.uber

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

// ----- LogBridge: JavaScript Interface to forward JS logs to Android Logcat -----
class LogBridge(private val context: Service) {
    @JavascriptInterface
    fun log(message: String) {
        Log.d("JSLog", message)
    }
}

// ----- UberEstimateService.kt -----
// This service uses a headless WebView to load the Uber home page,
// waits for the page to load, injects the pickup/dropoff data,
// simulates a click on the "Search" button, and logs every step.
class UberEstimateService : Service() {

    private lateinit var headlessWebView: WebView
    private var isPageLoaded = false
    private val injectionRetryDelay: Long = 1000 // Retry every 1 second if page not loaded

    companion object {
        // Public instance reference for external access.
        var instance: UberEstimateService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d("UberEstimateService", "Service onCreate: Initializing headless WebView.")
        initHeadlessWebView()
    }

    private fun initHeadlessWebView() {
        headlessWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            // Enable debugging so you can see JS logs via remote debugging.
            WebView.setWebContentsDebuggingEnabled(true)
            addJavascriptInterface(LogBridge(this@UberEstimateService), "LogBridge")
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    Log.d("UberEstimateService", "WebChromeClient: Page progress: $newProgress%")
                }
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    Log.d("UberEstimateService", "WebChromeClient: Page title: $title")
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    Log.d("UberEstimateService", "WebViewClient: Page started loading: $url")
                    evaluateJavascript("window.LogBridge.log('Page started loading: ' + '$url');", null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    isPageLoaded = true
                    Log.d("UberEstimateService", "WebViewClient: Page finished loading: $url")
                    evaluateJavascript("window.LogBridge.log('Page finished loading: ' + '$url');", null)
                }
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    Log.e("UberEstimateService", "WebViewClient: Error loading page: ${error?.description}")
                    evaluateJavascript("window.LogBridge.log('Error loading page: ' + '${error?.description}');", null)
                    super.onReceivedError(view, request, error)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("UberEstimateService", "onStartCommand: Starting service and loading Uber home page.")
        headlessWebView.loadUrl("https://m.uber.com/go/home")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d("UberEstimateService", "onDestroy: Cleaning up headless WebView and service instance.")
        headlessWebView.destroy()
        instance = null
        super.onDestroy()
    }

    /**
     * Injects pickup and dropoff data into the loaded Uber page.
     * If the page is not loaded yet, it schedules a retry until ready.
     */
    fun injectFormData(pickupLocation: String, dropoffLocation: String) {
        if (!isPageLoaded) {
            Log.d("UberEstimateService", "injectFormData: Page not loaded yet. Retrying in $injectionRetryDelay ms.")
            Handler(Looper.getMainLooper()).postDelayed({
                injectFormData(pickupLocation, dropoffLocation)
            }, injectionRetryDelay)
            return
        }
        Log.d("UberEstimateService", "injectFormData: Executing with pickup='$pickupLocation' and dropoff='$dropoffLocation'")
        val jsScript = """
            (function() {
                window.LogBridge.log("Script started: injecting form data");
                
                // ----- Fill Pickup Field -----
                var pickupInput = document.querySelector('[data-testid="enhancer-container-pickup"] input[role="combobox"]');
                if (pickupInput) {
                    pickupInput.value = "$pickupLocation";
                    var pickupEvent = new Event('input', { bubbles: true });
                    pickupInput.dispatchEvent(pickupEvent);
                    window.LogBridge.log("Pickup field filled with: $pickupLocation");
                } else {
                    window.LogBridge.log("Error: Pickup input not found");
                }
                
                // ----- Fill Dropoff Field -----
                var dropoffInput = document.querySelector('[data-testid="enhancer-container-drop0"] input[role="combobox"]');
                if (dropoffInput) {
                    dropoffInput.value = "$dropoffLocation";
                    var dropoffEvent = new Event('input', { bubbles: true });
                    dropoffInput.dispatchEvent(dropoffEvent);
                    window.LogBridge.log("Dropoff field filled with: $dropoffLocation");
                } else {
                    window.LogBridge.log("Error: Dropoff input not found");
                }
                
                // ----- Trigger the Search Action -----
                var buttons = document.querySelectorAll('button');
                var searchButton = null;
                for (var i = 0; i < buttons.length; i++) {
                    if (buttons[i].innerText && buttons[i].innerText.trim() === "Search") {
                        searchButton = buttons[i];
                        break;
                    }
                }
                if (searchButton) {
                    window.LogBridge.log("Search button found, clicking it.");
                    searchButton.click();
                } else {
                    window.LogBridge.log("Error: Search button not found");
                }
                
                window.LogBridge.log("Script completed.");
            })();
        """.trimIndent()

        headlessWebView.evaluateJavascript(jsScript) { result ->
            Log.d("UberEstimateService", "Injection script executed, result: $result")
        }
    }
}
