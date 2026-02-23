package com.holotower.app.ui.challenge

import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "CloudflareScreen"
private const val MAX_POLLS = 40
private val gson = Gson()

private fun looksLikeJson(text: String): Boolean {
    val t = text.trim()
    return t.startsWith("[") || t.startsWith("{")
}

private fun looksLikeChallenge(text: String): Boolean {
    val t = text.lowercase()
    return t.contains("just a moment") ||
        t.contains("cloudflare is verifying your request") ||
        t.contains("ray id")
}

private suspend fun readInnerText(webView: WebView): String {
    return suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript("(function() { return document.documentElement.innerText; })()") { raw ->
            val text = try {
                if (raw != null && raw != "null") gson.fromJson(raw, String::class.java) ?: ""
                else ""
            } catch (_: Exception) {
                ""
            }
            if (cont.isActive) cont.resume(text)
        }
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
    targetUrl: String = "https://holotower.org/hlgg/",
    sharedWebView: WebView,
    onChallengePassed: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var waitingForChallenge by remember { mutableStateOf(true) }
    var hintText by remember { mutableStateOf("Checking access...") }

    Log.i(TAG, "CloudflareScreen composing. targetUrl=$targetUrl")

    DisposableEffect(targetUrl) {
        var pollJob: Job? = null
        var lastTouchAtMs = 0L
        var challengeShown = false

        Log.i(TAG, "DisposableEffect: making WebView full-screen for challenge")
        Log.d(
            TAG,
            "DisposableEffect: webView.parent=${sharedWebView.parent}, isAttachedToWindow=${sharedWebView.isAttachedToWindow}, windowToken=${sharedWebView.windowToken}"
        )

        waitingForChallenge = true
        hintText = "Checking access..."

        sharedWebView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        sharedWebView.visibility = View.INVISIBLE
        sharedWebView.isFocusable = true
        sharedWebView.isFocusableInTouchMode = true
        sharedWebView.requestFocus()
        sharedWebView.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                lastTouchAtMs = android.os.SystemClock.elapsedRealtime()
                v.requestFocusFromTouch()
                Log.d(TAG, "Touch ACTION_DOWN on challenge WebView")
            }
            false
        }
        Log.i(TAG, "DisposableEffect: WebView primed as hidden fullscreen")

        sharedWebView.webChromeClient = WebChromeClient()

        sharedWebView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d(TAG, "onPageStarted: url=$url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val checkUrl = url ?: targetUrl
                Log.i(TAG, "onPageFinished: url=$url - starting JSON/challenge poll")

                pollJob?.cancel()
                pollJob = coroutineScope.launch {
                    for (i in 1..MAX_POLLS) {
                        val recentlyTouched = android.os.SystemClock.elapsedRealtime() - lastTouchAtMs < 2000L
                        if (recentlyTouched) {
                            Log.d(TAG, "Poll $i/$MAX_POLLS skipped due to recent touch interaction")
                            if (i < MAX_POLLS) delay(750)
                            continue
                        }

                        val cookies = CookieManager.getInstance().getCookie(checkUrl)
                        val body = readInnerText(sharedWebView).trim()
                        val hasClearance = cookies?.contains("cf_clearance") == true
                        val challengeActive = looksLikeChallenge(body)

                        Log.d(
                            TAG,
                            "Poll $i/$MAX_POLLS for $checkUrl - hasCf=$hasClearance challengeActive=$challengeActive preview=${body.take(120)}"
                        )

                        if (looksLikeJson(body) || (hasClearance && !challengeActive)) {
                            Log.i(TAG, "Challenge cleared on attempt $i. Flushing cookies and navigating.")
                            CookieManager.getInstance().flush()
                            waitingForChallenge = false
                            hideSharedWebView(sharedWebView)
                            onChallengePassed()
                            return@launch
                        }

                        if (challengeActive) {
                            waitingForChallenge = false
                            hintText = "Security check detected. Complete it to continue."
                            if (!challengeShown) {
                                challengeShown = true
                                showSharedWebView(sharedWebView)
                            }
                            Log.i(TAG, "Challenge page still active on attempt $i. Waiting for solve...")
                        }

                        if (i < MAX_POLLS) delay(1250)
                    }

                    Log.w(
                        TAG,
                        "Poll exhausted without JSON for $checkUrl. Final cookies=${CookieManager.getInstance().getCookie(checkUrl)}"
                    )
                    waitingForChallenge = false
                    showSharedWebView(sharedWebView)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                Log.e(TAG, "onReceivedError: code=$errorCode description=$description url=$failingUrl")
            }
        }

        Log.i(TAG, "DisposableEffect: calling loadUrl($targetUrl)")
        sharedWebView.loadUrl(targetUrl)

        onDispose {
            pollJob?.cancel()
            sharedWebView.setOnTouchListener(null)
            Log.i(TAG, "onDispose: shrinking WebView back to 1x1 invisible")
            hideSharedWebView(sharedWebView)
            Log.i(TAG, "onDispose: done - WebView is 1x1 invisible, clients preserved for shared fetch flow")
        }
    }

    if (waitingForChallenge) {
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
                    text = hintText,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
