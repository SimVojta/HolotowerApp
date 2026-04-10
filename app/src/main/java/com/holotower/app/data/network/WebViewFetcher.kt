package com.holotower.app.data.network

import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.holotower.app.data.model.CatalogPage
import com.holotower.app.data.model.ThreadResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "WebViewFetcher"
private val mainHandler = Handler(Looper.getMainLooper())

object WebViewBridge {
    private val callbacks = ConcurrentHashMap<Int, (String) -> Unit>()

    fun register(id: Int, callback: (String) -> Unit) {
        callbacks[id] = callback
        Log.d(TAG, "[Bridge] Registered id=$id. Pending=${callbacks.size}")
    }

    fun unregister(id: Int) {
        val removed = callbacks.remove(id)
        Log.d(TAG, "[Bridge] Unregistered id=$id (existed=${removed != null}). Pending=${callbacks.size}")
    }

    @JavascriptInterface
    fun onResult(id: Int, data: String) {
        Log.d(TAG, "[Bridge] onResult: id=$id length=${data.length} isErr=${data.startsWith("ERR:")}")
        if (data.length <= 300) Log.v(TAG, "[Bridge] data: $data")
        else Log.v(TAG, "[Bridge] preview: ${data.take(300)}...")
        val cb = callbacks.remove(id)
        if (cb == null) {
            Log.w(TAG, "[Bridge] No callback for id=$id (cancelled?)")
            return
        }
        cb(data)
    }
}

object WebViewFetcher {
    var webView: WebView? = null
    private val gson = Gson()
    private val idCounter = AtomicInteger(0)
    private const val MAX_CHALLENGE_HOPS = 5
    private val fetchMutex = Mutex()

    suspend fun fetchJson(url: String): String {
        return fetchMutex.withLock {
            val wv = webView ?: run {
                Log.e(TAG, "fetchJson: webView is NULL")
                throw IllegalStateException("WebView is null")
            }

            Log.i(TAG, "fetchJson: url=$url")
            Log.d(TAG, "fetchJson: isAttachedToWindow=${wv.isAttachedToWindow} parent=${wv.parent}")

            val id = idCounter.getAndIncrement()

            withTimeout(60_000) {
                suspendCancellableCoroutine { cont ->
                Log.i(TAG, "[id=$id] Starting nav-based fetch")

                WebViewBridge.register(id) { data ->
                    if (cont.isActive) {
                        if (data.startsWith("ERR:")) {
                            Log.e(TAG, "[id=$id] Exception: $data")
                            cont.resumeWithException(Exception(data))
                        } else {
                            Log.i(TAG, "[id=$id] Success (${data.length} chars)")
                            cont.resume(data)
                        }
                    } else {
                        Log.w(TAG, "[id=$id] Continuation inactive - dropping")
                    }
                }

                cont.invokeOnCancellation {
                    Log.w(TAG, "[id=$id] Cancelled - cleaning up")
                    WebViewBridge.unregister(id)
                    mainHandler.post {
                        wv.stopLoading()
                        wv.webViewClient = WebViewClient()
                    }
                }

                mainHandler.post {
                    if (!wv.isAttachedToWindow) {
                        Log.e(TAG, "[id=$id] WebView NOT attached to window!")
                    }

                    val sourceUrl = wv.url ?: "about:blank"
                    val restoreUrl = "about:blank"
                    val referer = if (sourceUrl == "about:blank" || sourceUrl == url) {
                        inferReferer(url)
                    } else {
                        sourceUrl
                    }

                    Log.i(TAG, "[id=$id] sourceUrl=$sourceUrl restoreUrl=$restoreUrl, navigating to $url")

                    val extraHeaders = mapOf(
                        "Referer" to referer,
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "Accept-Language" to "en-US,en;q=0.9"
                    )
                    Log.i(TAG, "[id=$id] extraHeaders=$extraHeaders")

                    var hopCount = 0
                    var sawRequestedLoad = false
                    var delivered = false

                    wv.stopLoading()
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, navUrl: String?, favicon: Bitmap?) {
                            if (navUrl == url) sawRequestedLoad = true
                            Log.d(TAG, "[id=$id] onPageStarted[$hopCount]: $navUrl sawRequestedLoad=$sawRequestedLoad")
                        }

                        override fun onPageFinished(view: WebView, navUrl: String?) {
                            Log.i(TAG, "[id=$id] onPageFinished[$hopCount]: $navUrl sawRequestedLoad=$sawRequestedLoad delivered=$delivered")

                            if (delivered && (navUrl == restoreUrl || navUrl == "about:blank")) {
                                Log.d(TAG, "[id=$id] Reached restore URL after delivery - clearing WebViewClient")
                                view.webViewClient = WebViewClient()
                                return
                            }

                            if (!sawRequestedLoad && (navUrl == restoreUrl || navUrl == "about:blank" || navUrl == sourceUrl)) {
                                Log.w(TAG, "[id=$id] Ignoring stale finish before requested load starts: $navUrl")
                                return
                            }

                            if (navUrl == restoreUrl || navUrl == "about:blank") {
                                Log.d(TAG, "[id=$id] Ignoring restore URL before delivery")
                                return
                            }

                            hopCount++

                            if (hopCount > MAX_CHALLENGE_HOPS) {
                                Log.e(TAG, "[id=$id] Exceeded $MAX_CHALLENGE_HOPS hops without JSON")
                                delivered = true
                                restoreAndDeliver(view, id, "ERR: Too many Cloudflare hops for $url", restoreUrl)
                                return
                            }

                            view.evaluateJavascript(
                                "(function() { return document.documentElement.innerText; })()"
                            ) { rawResult ->
                                Log.d(TAG, "[id=$id] innerText rawLength=${rawResult?.length} hop=$hopCount")

                                val text: String? = try {
                                    if (rawResult != null && rawResult != "null") {
                                        gson.fromJson(rawResult, String::class.java)
                                    } else {
                                        null
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "[id=$id] innerText decode failed: ${e.message}")
                                    null
                                }

                                val trimmed = text?.trim() ?: ""
                                Log.v(TAG, "[id=$id] Content preview hop=$hopCount: ${trimmed.take(200)}")

                                when {
                                    trimmed.startsWith("[") || trimmed.startsWith("{") -> {
                                        Log.i(TAG, "[id=$id] Valid JSON at hop=$hopCount (${trimmed.length} chars) - delivering")
                                        delivered = true
                                        restoreAndDeliver(view, id, trimmed, restoreUrl)
                                    }
                                    trimmed.isEmpty() -> {
                                        Log.w(TAG, "[id=$id] Empty content at hop=$hopCount url=$navUrl - waiting")
                                    }
                                    else -> {
                                        Log.w(
                                            TAG,
                                            "[id=$id] Non-JSON at hop=$hopCount url=$navUrl - challenge/HTML. Full text (${trimmed.length} chars):\n$trimmed"
                                        )
                                        Log.w(TAG, "[id=$id] Waiting for Cloudflare to auto-solve and redirect...")
                                    }
                                }
                            }
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            val errUrl = request?.url?.toString()
                            val errCode = error?.errorCode
                            val errDesc = error?.description
                            Log.e(
                                TAG,
                                "[id=$id] onReceivedError: code=$errCode desc=$errDesc url=$errUrl mainFrame=${request?.isForMainFrame}"
                            )
                            if (request?.isForMainFrame == true) {
                                delivered = true
                                restoreAndDeliver(
                                    view,
                                    id,
                                    "ERR: WebView error $errCode ($errDesc) for $errUrl",
                                    restoreUrl
                                )
                            }
                        }
                    }

                    Log.i(TAG, "[id=$id] Calling loadUrl($url) with Referer=$referer")
                    wv.loadUrl(url, extraHeaders)
                }
                }
            }
        }
    }

    suspend fun fetchHtml(url: String, formBody: String? = null): String {
        return fetchMutex.withLock {
            val wv = webView ?: run {
                Log.e(TAG, "fetchHtml: webView is NULL")
                throw IllegalStateException("WebView is null")
            }

            Log.i(TAG, "fetchHtml: url=$url method=${if (formBody == null) "GET" else "POST"}")
            Log.d(TAG, "fetchHtml: isAttachedToWindow=${wv.isAttachedToWindow} parent=${wv.parent}")

            val id = idCounter.getAndIncrement()

            withTimeout(60_000) {
                suspendCancellableCoroutine { cont ->
                Log.i(TAG, "[id=$id] Starting nav-based HTML fetch")

                WebViewBridge.register(id) { data ->
                    if (cont.isActive) {
                        if (data.startsWith("ERR:")) {
                            Log.e(TAG, "[id=$id] Exception: $data")
                            cont.resumeWithException(Exception(data))
                        } else {
                            Log.i(TAG, "[id=$id] Success HTML (${data.length} chars)")
                            cont.resume(data)
                        }
                    } else {
                        Log.w(TAG, "[id=$id] Continuation inactive - dropping")
                    }
                }

                cont.invokeOnCancellation {
                    Log.w(TAG, "[id=$id] Cancelled HTML fetch - cleaning up")
                    WebViewBridge.unregister(id)
                    mainHandler.post {
                        wv.stopLoading()
                        wv.webViewClient = WebViewClient()
                    }
                }

                mainHandler.post {
                    if (!wv.isAttachedToWindow) {
                        Log.e(TAG, "[id=$id] WebView NOT attached to window!")
                    }

                    val sourceUrl = wv.url ?: "about:blank"
                    val restoreUrl = "about:blank"
                    val referer = if (sourceUrl == "about:blank" || sourceUrl == url) {
                        inferReferer(url)
                    } else {
                        sourceUrl
                    }

                    Log.i(TAG, "[id=$id] sourceUrl=$sourceUrl restoreUrl=$restoreUrl, navigating to $url")

                    val extraHeaders = mapOf(
                        "Referer" to referer,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "en-US,en;q=0.9"
                    )
                    Log.i(TAG, "[id=$id] extraHeaders=$extraHeaders")

                    var hopCount = 0
                    var sawRequestedLoad = false
                    var delivered = false

                    wv.stopLoading()
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, navUrl: String?, favicon: Bitmap?) {
                            if (navUrl == url) sawRequestedLoad = true
                            Log.d(TAG, "[id=$id] onPageStarted[$hopCount]: $navUrl sawRequestedLoad=$sawRequestedLoad")
                        }

                        override fun onPageFinished(view: WebView, navUrl: String?) {
                            Log.i(TAG, "[id=$id] onPageFinished[$hopCount]: $navUrl sawRequestedLoad=$sawRequestedLoad delivered=$delivered")

                            if (delivered && (navUrl == restoreUrl || navUrl == "about:blank")) {
                                Log.d(TAG, "[id=$id] Reached restore URL after delivery - clearing WebViewClient")
                                view.webViewClient = WebViewClient()
                                return
                            }

                            if (!sawRequestedLoad && (navUrl == restoreUrl || navUrl == "about:blank" || navUrl == sourceUrl)) {
                                Log.w(TAG, "[id=$id] Ignoring stale finish before requested load starts: $navUrl")
                                return
                            }

                            if (navUrl == restoreUrl || navUrl == "about:blank") {
                                Log.d(TAG, "[id=$id] Ignoring restore URL before delivery")
                                return
                            }

                            hopCount++

                            if (hopCount > MAX_CHALLENGE_HOPS) {
                                Log.e(TAG, "[id=$id] Exceeded $MAX_CHALLENGE_HOPS hops without final HTML")
                                delivered = true
                                restoreAndDeliver(view, id, "ERR: Too many Cloudflare hops for $url", restoreUrl)
                                return
                            }

                            view.evaluateJavascript(
                                "(function(){ return [document.documentElement.outerHTML || '', document.documentElement.innerText || '']; })();"
                            ) { rawResult ->
                                val pair = try {
                                    gson.fromJson(rawResult, Array<String>::class.java)
                                } catch (e: Exception) {
                                    Log.e(TAG, "[id=$id] HTML decode failed: ${e.message}")
                                    null
                                }

                                val html = pair?.getOrNull(0).orEmpty()
                                val text = pair?.getOrNull(1).orEmpty().trim()

                                val challengeActive =
                                    text.contains("Cloudflare is verifying your request", ignoreCase = true) ||
                                        text.contains("Just a moment", ignoreCase = true) ||
                                        text.contains("Enable JavaScript and cookies to continue", ignoreCase = true)

                                when {
                                    html.isBlank() -> {
                                        Log.w(TAG, "[id=$id] Empty HTML at hop=$hopCount url=$navUrl - waiting")
                                    }
                                    challengeActive -> {
                                        Log.i(TAG, "[id=$id] Challenge HTML still active at hop=$hopCount - waiting")
                                    }
                                    else -> {
                                        Log.i(TAG, "[id=$id] Final HTML captured at hop=$hopCount (${html.length} chars)")
                                        delivered = true
                                        restoreAndDeliver(view, id, html, restoreUrl)
                                    }
                                }
                            }
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            val errUrl = request?.url?.toString()
                            val errCode = error?.errorCode
                            val errDesc = error?.description
                            Log.e(
                                TAG,
                                "[id=$id] onReceivedError: code=$errCode desc=$errDesc url=$errUrl mainFrame=${request?.isForMainFrame}"
                            )
                            if (request?.isForMainFrame == true) {
                                delivered = true
                                restoreAndDeliver(
                                    view,
                                    id,
                                    "ERR: WebView error $errCode ($errDesc) for $errUrl",
                                    restoreUrl
                                )
                            }
                        }
                    }

                    if (formBody == null) {
                        Log.i(TAG, "[id=$id] Calling loadUrl($url) with Referer=$referer")
                        wv.loadUrl(url, extraHeaders)
                    } else {
                        sawRequestedLoad = true
                        Log.i(TAG, "[id=$id] Calling postUrl($url) with body length=${formBody.length}")
                        wv.postUrl(url, formBody.toByteArray(Charsets.UTF_8))
                    }
                }
                }
            }
        }
    }

    private fun restoreAndDeliver(view: WebView, id: Int, data: String, restoreUrl: String) {
        Log.i(TAG, "[id=$id] restoreAndDeliver: data length=${data.length}, reloading restoreUrl=$restoreUrl")
        WebViewBridge.onResult(id, data)
        view.webViewClient = WebViewClient()
        view.loadUrl(restoreUrl)
    }

    private fun inferReferer(url: String): String {
        return runCatching {
            val uri = Uri.parse(url)
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: "holotower.org"
            val firstPath = uri.pathSegments.firstOrNull()
            if (firstPath.isNullOrBlank()) {
                "$scheme://$host/"
            } else {
                "$scheme://$host/$firstPath/"
            }
        }.getOrDefault("https://holotower.org/hlgg/")
    }

    suspend fun getCatalog(board: String): List<CatalogPage> {
        val url = "https://holotower.org/$board/catalog.json"
        Log.i(TAG, "getCatalog: $url")
        val json = fetchJson(url)
        Log.i(TAG, "getCatalog: parsing ${json.length} chars")
        val type = object : TypeToken<List<CatalogPage>>() {}.type
        val result = gson.fromJson<List<CatalogPage>>(json, type)
        Log.i(TAG, "getCatalog: ${result.size} pages, ${result.sumOf { it.threads.size }} threads")
        return result
    }

    suspend fun getThread(board: String, threadNo: Long): ThreadResponse {
        val url = "https://holotower.org/$board/res/$threadNo.json"
        Log.i(TAG, "getThread: $url")
        val json = fetchJson(url)
        Log.i(TAG, "getThread: parsing ${json.length} chars")
        val result = gson.fromJson(json, ThreadResponse::class.java)
        Log.i(TAG, "getThread: ${result.posts.size} posts")
        return result
    }
}
