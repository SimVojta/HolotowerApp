package com.holotower.app.ui.challenge

import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.Gson
import com.holotower.app.data.network.ChallengeGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "ChallengeOverlay"
private const val POLL_INTERVAL_MS = 700L
private val gson = Gson()
private val Accent = Color(0xFF00FF9F)

/** What the page currently shows, read with JavaScript. */
private data class PageProbe(val challenge: Boolean = true, val text: String = "")

private const val PROBE_SCRIPT = """
(function() {
  var challenge = typeof window._cf_chl_opt !== 'undefined' ||
    /just a moment/i.test(document.title || '') ||
    !!document.querySelector('#challenge-form, #challenge-stage, #challenge-running, script[src*="challenge-platform"]');
  var text = (document.body && document.body.innerText) || '';
  return JSON.stringify({ challenge: challenge, text: text.slice(0, 200) });
})();
"""

private suspend fun probePage(webView: WebView): PageProbe = suspendCancellableCoroutine { cont ->
    webView.evaluateJavascript(PROBE_SCRIPT) { raw ->
        val probe = runCatching {
            val json = gson.fromJson(raw, String::class.java)
            gson.fromJson(json, PageProbe::class.java)
        }.getOrNull() ?: PageProbe()
        if (cont.isActive) cont.resume(probe)
    }
}

private fun clearanceCookie(url: String): String? =
    CookieManager.getInstance().getCookie(url)
        ?.split(';')
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("cf_clearance=") }

/**
 * The Cloudflare check, shown on top of the app as soon as a request is challenged.
 * The page is visible from the start so Cloudflare's own check can run (and so the
 * user can tap it if it asks). It closes itself once the check passes.
 */
@Composable
fun ChallengeOverlay(request: ChallengeGate.Request) {
    val context = LocalContext.current
    var reloadToken by remember(request) { mutableIntStateOf(0) }
    var pageLoading by remember(request) { mutableStateOf(true) }
    var loadError by remember(request) { mutableStateOf<String?>(null) }
    val webView = remember(request) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(android.graphics.Color.BLACK)
            webChromeClient = WebChromeClient()
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
    }

    BackHandler { ChallengeGate.dismiss() }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    LaunchedEffect(webView, reloadToken) {
        val clearanceBefore = clearanceCookie(request.url)
        pageLoading = true
        loadError = null
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                pageLoading = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoading = false
            }

            override fun onReceivedError(view: WebView?, req: WebResourceRequest?, error: WebResourceError?) {
                if (req?.isForMainFrame == true) {
                    loadError = error?.description?.toString() ?: "Couldn't load the check"
                    pageLoading = false
                }
            }
        }
        Log.i(TAG, "Opening check for ${request.url}")
        webView.loadUrl(request.url)

        while (isActive) {
            delay(POLL_INTERVAL_MS)
            if (loadError != null) continue
            val probe = probePage(webView)
            val body = probe.text.trim()
            val looksLikeData = body.startsWith("[") || body.startsWith("{")
            val clearanceChanged = clearanceCookie(request.url).let { it != null && it != clearanceBefore }
            if (!probe.challenge && (looksLikeData || (clearanceChanged && !pageLoading))) {
                Log.i(TAG, "Check passed (data=$looksLikeData, newCookie=$clearanceChanged)")
                CookieManager.getInstance().flush()
                ChallengeGate.markSolved()
                break
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF111111))
                .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Security check", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(
                    "Cloudflare is checking this device. It usually finishes by itself.",
                    color = Color(0xFFAAB2BA),
                    fontSize = 12.sp
                )
            }
            IconButton(onClick = { reloadToken += 1 }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reload check", tint = Color.White)
            }
            IconButton(onClick = { ChallengeGate.dismiss() }) {
                Icon(Icons.Filled.Close, contentDescription = "Close check", tint = Color.White)
            }
        }
        if (pageLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Accent)
        } else {
            Spacer(modifier = Modifier.height(4.dp))
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { webView })
            loadError?.let { message ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0A0A0A)),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Couldn't load the check: $message", color = Color.White, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(onClick = { reloadToken += 1 }) { Text("Retry") }
                }
            }
        }
    }
}
