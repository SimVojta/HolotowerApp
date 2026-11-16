package com.holotower.app.data.network

import android.webkit.CookieManager
import okhttp3.Interceptor

/**
 * Makes OkHttp requests (images, video, saving media) look like the app's WebView:
 * same User-Agent and the same cookies, including Cloudflare's clearance cookie.
 */
object CloudflareHelper {
    /** Replaced with the real WebView User-Agent when the fetch WebView is created. */
    @Volatile
    var userAgent: String =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    val cloudflareInterceptor = Interceptor { chain ->
        val request = chain.request()
        val builder = request.newBuilder()

        if (request.header("User-Agent").isNullOrBlank()) {
            builder.header("User-Agent", userAgent)
        }
        if (request.header("Accept-Language").isNullOrBlank()) {
            builder.header("Accept-Language", "en-US,en;q=0.9")
        }
        if (request.header("Referer").isNullOrBlank()) {
            val host = request.url.host
            val firstPath = request.url.pathSegments.firstOrNull()
            builder.header(
                "Referer",
                if (firstPath.isNullOrBlank()) "https://$host/" else "https://$host/$firstPath/"
            )
        }

        val cookies = CookieManager.getInstance().getCookie(request.url.toString())
        if (!cookies.isNullOrEmpty()) {
            builder.header("Cookie", cookies)
        }

        chain.proceed(builder.build())
    }
}
