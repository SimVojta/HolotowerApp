package com.holotower.app

import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowInsetsControllerCompat
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.holotower.app.data.network.CloudflareHelper
import com.holotower.app.data.network.WebViewBridge
import com.holotower.app.data.network.WebViewFetcher
import com.holotower.app.navigation.NavGraph
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: starting")
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val sharedWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(Color.BLACK)

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            CloudflareHelper.userAgent = settings.userAgentString
            Log.i(TAG, "WebView created. UA=${CloudflareHelper.userAgent}")

            addJavascriptInterface(WebViewBridge, "AndroidBridge")
            Log.i(TAG, "AndroidBridge registered on WebView (before any page load)")
        }

        WebViewFetcher.webView = sharedWebView
        Log.i(TAG, "WebViewFetcher.webView assigned")

        // Pin the shared WebView first so challenge loads start only after attach.
        sharedWebView.layoutParams = FrameLayout.LayoutParams(1, 1)
        sharedWebView.visibility = View.INVISIBLE

        val decorView = window.decorView as? FrameLayout
        if (decorView == null) {
            Log.e(TAG, "decorView is not a FrameLayout! Type=${window.decorView::class.java.name}")
        } else if (sharedWebView.parent == null) {
            decorView.addView(sharedWebView)
            Log.i(TAG, "WebView pinned into decorView. isAttachedToWindow=${sharedWebView.isAttachedToWindow}")
        } else {
            Log.w(TAG, "WebView already has parent=${sharedWebView.parent} - skipping addView")
        }

        setContent {
            setSingletonImageLoaderFactory { context ->
                val thumbHttpCacheDir = File(context.cacheDir, "thumb_http_cache").apply { mkdirs() }
                val imageClient = OkHttpClient.Builder()
                    .addInterceptor(CloudflareHelper.cloudflareInterceptor)
                    .addNetworkInterceptor { chain ->
                        val request = chain.request()
                        val response = chain.proceed(request)
                        val path = request.url.encodedPath
                        if (path.contains("/thumb/")) {
                            response.newBuilder()
                                .removeHeader("Pragma")
                                .header("Cache-Control", "public, max-age=604800")
                                .build()
                        } else {
                            response
                        }
                    }
                    .cache(Cache(thumbHttpCacheDir, 64L * 1024L * 1024L))
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()

                ImageLoader.Builder(context)
                    .components {
                        add(OkHttpNetworkFetcherFactory(callFactory = imageClient))
                    }
                    .build()
            }
            NavGraph(board = "hlgg", sharedWebView = sharedWebView)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy: cleaning up")
        WebViewFetcher.webView?.let { wv ->
            (wv.parent as? FrameLayout)?.removeView(wv)
            wv.destroy()
            Log.i(TAG, "WebView destroyed")
        }
        WebViewFetcher.webView = null
    }
}
