package com.holotower.app.data.network

import android.webkit.CookieManager
import okhttp3.Interceptor

object CloudflareHelper {
    // Keep UA synced with the actual WebView UA to avoid fingerprint mismatches.
    var userAgent: String =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    // 2. An Interceptor is much safer than CookieJar for copying WebView cookies
    val cloudflareInterceptor = Interceptor { chain ->
        val request = chain.request()
        val urlString = request.url.toString()

        val builder = request.newBuilder()

        // Keep explicitly provided request headers (e.g. Global Entry HTML flow).
        if (request.header("User-Agent").isNullOrBlank()) {
            builder.header("User-Agent", userAgent)
        }
        // Default headers for JSON API calls only when caller did not provide specific ones.
        if (request.header("Accept").isNullOrBlank()) {
            builder.header("Accept", "application/json, text/javascript, */*; q=0.01")
        }
        if (request.header("Accept-Language").isNullOrBlank()) {
            builder.header("Accept-Language", "en-US,en;q=0.9")
        }
        if (request.header("Referer").isNullOrBlank()) {
            val host = request.url.host
            val firstPath = request.url.pathSegments.firstOrNull()
            val inferred = if (firstPath.isNullOrBlank()) {
                "https://$host/"
            } else {
                "https://$host/$firstPath/"
            }
            builder.header("Referer", inferred)
        }

        // 3. Get the exact raw cookie string from WebView and inject it directly
        val cookies = CookieManager.getInstance().getCookie(urlString)
        if (!cookies.isNullOrEmpty()) {
            builder.header("Cookie", cookies)
        }

        chain.proceed(builder.build())
    }
}
