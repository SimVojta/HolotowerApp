package com.holotower.app.ui.challenge

import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "CloudflareScreen"
private const val HEADLESS_CHECK_MS = 10_000L
private const val CHECK_INTERVAL_MS = 1_000L
private val gson = Gson()

private fun containsExpectedCatalogJson(text: String): Boolean {
    val body = text.trim()
    return body.startsWith("[") && body.contains("threads", ignoreCase = true)
}

private suspend fun readPageText(webView: WebView): String =
    suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript("document.documentElement.innerText || ''") { raw ->
            val text = runCatching {
                if (raw.isNullOrBlank() || raw == "null") ""
                else gson.fromJson(raw, String::class.java).orEmpty()
            }.getOrDefault("")
            if (cont.isActive) cont.resume(text)
        }
    }

private fun hideSharedWebView(webView: WebView) {
    webView.layoutParams = FrameLayout.LayoutParams(1, 1)
    webView.visibility = View.INVISIBLE
}

private fun showSharedWebView(webView: WebView) {
    webView.layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
    )
    webView.visibility = View.VISIBLE
    webView.bringToFront()
}

@Composable
fun CloudflareScreen(
    targetUrl: String = "https://holotower.org/hlgg/catalog.json",
    sharedWebView: WebView,
    onChallengePassed: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var waitingForCheck by remember(targetUrl) { mutableStateOf(true) }
    var loadFailed by remember(targetUrl) { mutableStateOf(false) }
    var reloadToken by remember(targetUrl) { mutableIntStateOf(0) }

    DisposableEffect(targetUrl, reloadToken) {
        var pollJob: Job? = null
        var handoffCompleted = false
        var lastTouchAtMs = 0L
        val previousCacheMode = sharedWebView.settings.cacheMode

        waitingForCheck = true
        loadFailed = false
        sharedWebView.stopLoading()
        sharedWebView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        sharedWebView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        sharedWebView.visibility = View.INVISIBLE
        sharedWebView.isFocusable = true
        sharedWebView.isFocusableInTouchMode = true
        sharedWebView.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                lastTouchAtMs = android.os.SystemClock.elapsedRealtime()
                view.requestFocusFromTouch()
            }
            false
        }
        sharedWebView.webChromeClient = WebChromeClient()
        sharedWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (handoffCompleted) return

                pollJob?.cancel()
                val pageLoadedAt = android.os.SystemClock.elapsedRealtime()
                pollJob = coroutineScope.launch {
                    while (isActive && !handoffCompleted) {
                        val touchedRecently =
                            android.os.SystemClock.elapsedRealtime() - lastTouchAtMs < 1_000L
                        if (!touchedRecently) {
                            val body = readPageText(sharedWebView)
                            if (containsExpectedCatalogJson(body)) {
                                handoffCompleted = true
                                CookieManager.getInstance().flush()
                                waitingForCheck = false
                                sharedWebView.setOnTouchListener(null)
                                sharedWebView.stopLoading()
                                sharedWebView.webViewClient = WebViewClient()
                                sharedWebView.webChromeClient = WebChromeClient()
                                hideSharedWebView(sharedWebView)
                                // The WebView keeps its cookies; only discard the loaded page.
                                sharedWebView.loadUrl("about:blank")
                                sharedWebView.postDelayed({ onChallengePassed() }, 200L)
                                Log.i(TAG, "Catalog response confirmed; challenge check passed")
                                return@launch
                            }
                        }

                        val headlessTime = android.os.SystemClock.elapsedRealtime() - pageLoadedAt
                        if (headlessTime >= HEADLESS_CHECK_MS) {
                            // Unknown or localized challenge pages still get a human-visible
                            // WebView after one short automatic check.
                            waitingForCheck = false
                            showSharedWebView(sharedWebView)
                        }
                        delay(CHECK_INTERVAL_MS)
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true && !handoffCompleted) {
                    Log.w(TAG, "Challenge page load failed: ${error?.description}")
                    pollJob?.cancel()
                    loadFailed = true
                    waitingForCheck = true
                    hideSharedWebView(sharedWebView)
                }
            }
        }

        Log.i(TAG, "Loading challenge check: $targetUrl")
        sharedWebView.loadUrl(targetUrl)

        onDispose {
            pollJob?.cancel()
            sharedWebView.stopLoading()
            sharedWebView.setOnTouchListener(null)
            sharedWebView.settings.cacheMode = previousCacheMode
            hideSharedWebView(sharedWebView)
        }
    }

    if (waitingForCheck) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0A)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color(0xFF00FF9F))
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = if (loadFailed) "Couldn't load the check. Check your connection and retry."
                    else "Checking access...",
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { reloadToken += 1 }) {
                    Text(if (loadFailed) "Retry" else "Reload check")
                }
            }
        }
    }
}
