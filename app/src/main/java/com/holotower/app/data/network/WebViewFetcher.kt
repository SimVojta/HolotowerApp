package com.holotower.app.data.network

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.MainThread
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.holotower.app.data.model.CatalogPage
import com.holotower.app.data.model.ThreadResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

private const val TAG = "WebViewFetcher"
private const val ORIGIN = "https://holotower.org/"
private const val FETCH_TIMEOUT_MS = 45_000L
private const val MAX_CHALLENGE_ROUNDS = 2
private const val MAX_ATTEMPTS = 4
private const val ACCEPT_JSON = "application/json, text/javascript, */*; q=0.01"
private const val ACCEPT_HTML = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

/** Result of one fetch() made inside the hidden WebView. */
private data class FetchEnvelope(
    @SerializedName("s") val status: Int = 0,
    @SerializedName("cf") val cfMitigated: String? = null,
    @SerializedName("b") val body: String? = null,
    @SerializedName("e") val error: String? = null
)

private val gson = Gson()

/** Receives fetch() results from JavaScript. Methods run on the WebView's JavaBridge thread. */
object WebViewBridge {
    private val callbacks = ConcurrentHashMap<Int, (String) -> Unit>()
    private val interruptedPayload = gson.toJson(FetchEnvelope(status = -1, error = "interrupted"))

    fun register(id: Int, callback: (String) -> Unit) {
        callbacks[id] = callback
    }

    fun unregister(id: Int) {
        callbacks.remove(id)
    }

    /** Resolves every waiting request as interrupted (the WebView went away). */
    fun failAll() {
        callbacks.keys.toList().forEach { id -> callbacks.remove(id)?.invoke(interruptedPayload) }
    }

    @JavascriptInterface
    fun onResult(id: Int, data: String) {
        callbacks.remove(id)?.invoke(data)
    }
}

/**
 * Loads site data with fetch() from inside a hidden WebView whose page has the
 * holotower.org origin. Requests therefore go out with the same browser, cookies and
 * TLS fingerprint that passed the Cloudflare check, and several can run at once.
 * Plain OkHttp is still used for images and video, which Cloudflare does not challenge.
 */
object WebViewFetcher {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val idCounter = AtomicInteger(0)

    /** The hidden WebView once its holotower.org page has loaded; null while (re)creating. */
    private val readyView = MutableStateFlow<WebView?>(null)
    private var webView: WebView? = null
    private var activityRef: WeakReference<Activity>? = null

    @MainThread
    fun attach(activity: Activity) {
        destroyWebView()
        activityRef = WeakReference(activity)
        val decorView = activity.window.decorView as? FrameLayout ?: run {
            Log.e(TAG, "decorView is not a FrameLayout; data requests cannot start")
            return
        }

        val wv = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(Color.BLACK)
            // Kept attached (1x1, invisible) so the renderer keeps running JavaScript.
            layoutParams = FrameLayout.LayoutParams(1, 1)
            visibility = View.INVISIBLE
            isFocusable = false
            addJavascriptInterface(WebViewBridge, "AndroidBridge")
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        CloudflareHelper.userAgent = wv.settings.userAgentString

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (webView === view) {
                    Log.i(TAG, "Fetch page ready ($url)")
                    readyView.value = view
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
                Log.w(TAG, "Fetch WebView renderer gone (crash=${detail?.didCrash()}); recreating")
                if (webView === view) {
                    mainHandler.post { activityRef?.get()?.let(::attach) ?: destroyWebView() }
                }
                return true
            }
        }

        webView = wv
        decorView.addView(wv)
        // A local page that counts as https://holotower.org/ for the same-origin policy,
        // without a network request of its own.
        wv.loadDataWithBaseURL(ORIGIN, "<!doctype html><title>HoloTower</title>", "text/html", "utf-8", null)
    }

    @MainThread
    fun detach(activity: Activity) {
        if (activityRef?.get() === activity) {
            destroyWebView()
            activityRef = null
        }
    }

    /** Timers are global to every WebView in the process, so this also pauses the others. */
    @MainThread
    fun onPause() {
        webView?.let {
            it.onPause()
            it.pauseTimers()
        }
    }

    @MainThread
    fun onResume() {
        webView?.let {
            it.onResume()
            it.resumeTimers()
        }
    }

    @MainThread
    private fun destroyWebView() {
        val old = webView ?: return
        webView = null
        readyView.value = null
        WebViewBridge.failAll()
        (old.parent as? FrameLayout)?.removeView(old)
        old.destroy()
    }

    suspend fun getCatalog(board: String): List<CatalogPage> {
        val json = fetchJson("${ORIGIN}$board/catalog.json")
        val type = object : TypeToken<List<CatalogPage>>() {}.type
        return withContext(Dispatchers.Default) { gson.fromJson<List<CatalogPage>>(json, type) }
    }

    suspend fun fetchThreadJson(board: String, threadNo: Long): String =
        fetchJson("${ORIGIN}$board/res/$threadNo.json")

    suspend fun getThread(board: String, threadNo: Long): ThreadResponse {
        val json = fetchThreadJson(board, threadNo)
        return withContext(Dispatchers.Default) { gson.fromJson(json, ThreadResponse::class.java) }
    }

    suspend fun fetchJson(url: String): String {
        val body = fetchText(url, accept = ACCEPT_JSON).trim()
        if (!body.startsWith("[") && !body.startsWith("{")) throw FetchException.BadResponse()
        return body
    }

    /** GET, or POST when [formBody] (x-www-form-urlencoded) is given. */
    suspend fun fetchHtml(url: String, formBody: String? = null): String =
        fetchText(url, formBody = formBody, accept = ACCEPT_HTML)

    private suspend fun fetchText(url: String, formBody: String? = null, accept: String): String {
        var challengeRounds = 0
        var interruptions = 0
        repeat(MAX_ATTEMPTS) {
            val generationAtStart = ChallengeGate.generation
            val result = runFetch(url, formBody, accept)
            when {
                result.status == -1 -> {
                    if (++interruptions > 1) throw FetchException.Interrupted()
                }
                result.status == 0 -> throw FetchException.Offline(result.error)
                result.isChallenge() -> {
                    // A check that finished while this request was in flight: just retry.
                    if (ChallengeGate.generation != generationAtStart) return@repeat
                    if (++challengeRounds > MAX_CHALLENGE_ROUNDS) throw FetchException.ChallengeRequired()
                    // The visible check opens this URL as a normal (GET) page.
                    Log.i(TAG, "Challenge for $url; waiting for the security check")
                    if (!ChallengeGate.awaitClearance(url)) throw FetchException.ChallengeRequired()
                }
                result.status in 200..299 -> return result.body.orEmpty()
                else -> throw FetchException.Http(result.status)
            }
        }
        throw FetchException.ChallengeRequired()
    }

    private suspend fun runFetch(url: String, formBody: String?, accept: String): FetchEnvelope {
        val raw = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            val wv = readyView.filterNotNull().first()
            val id = idCounter.incrementAndGet()
            suspendCancellableCoroutine<String> { cont ->
                WebViewBridge.register(id) { data -> if (cont.isActive) cont.resume(data) }
                cont.invokeOnCancellation { WebViewBridge.unregister(id) }
                val script = fetchScript(id, url, formBody, accept)
                mainHandler.post {
                    if (webView === wv) {
                        wv.evaluateJavascript(script, null)
                    } else {
                        WebViewBridge.failAll()
                    }
                }
            }
        } ?: throw FetchException.Timeout()
        return withContext(Dispatchers.Default) {
            runCatching { gson.fromJson(raw, FetchEnvelope::class.java) }.getOrNull()
                ?: FetchEnvelope(status = -1)
        }
    }

    private fun fetchScript(id: Int, url: String, formBody: String?, accept: String): String {
        val jsUrl = gson.toJson(url)
        val jsAccept = gson.toJson(accept)
        val jsBody = formBody?.let(gson::toJson) ?: "null"
        return """
            (function() {
              var id = $id;
              function send(o) { try { AndroidBridge.onResult(id, JSON.stringify(o)); } catch (e) {} }
              var body = $jsBody;
              var opts = { method: body === null ? 'GET' : 'POST', credentials: 'include',
                           cache: 'no-cache', redirect: 'follow', headers: { 'Accept': $jsAccept } };
              if (body !== null) {
                opts.headers['Content-Type'] = 'application/x-www-form-urlencoded';
                opts.body = body;
              }
              fetch($jsUrl, opts).then(function(r) {
                return r.text().then(function(t) {
                  send({ s: r.status, cf: r.headers.get('cf-mitigated') || '', b: t });
                });
              }).catch(function(e) {
                send({ s: 0, e: String((e && e.message) || e) });
              });
            })();
        """.trimIndent()
    }

    private fun FetchEnvelope.isChallenge(): Boolean {
        if (cfMitigated.equals("challenge", ignoreCase = true)) return true
        if (status != 403 && status != 503) return false
        val head = body.orEmpty().take(6000).lowercase()
        return "<title>just a moment" in head || "challenge-platform" in head || "_cf_chl_opt" in head
    }
}
