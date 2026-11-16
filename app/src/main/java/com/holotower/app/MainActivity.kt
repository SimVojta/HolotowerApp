package com.holotower.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowInsetsControllerCompat
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.holotower.app.data.network.AppForeground
import com.holotower.app.data.network.CloudflareHelper
import com.holotower.app.data.network.WebViewFetcher
import com.holotower.app.navigation.NavGraph
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // Hidden WebView that loads all board data (see WebViewFetcher).
        WebViewFetcher.attach(this)

        setContent {
            setSingletonImageLoaderFactory { context ->
                val thumbHttpCacheDir = File(context.cacheDir, "thumb_http_cache").apply { mkdirs() }
                val imageClient = OkHttpClient.Builder()
                    .addInterceptor(CloudflareHelper.cloudflareInterceptor)
                    .addNetworkInterceptor { chain ->
                        val request = chain.request()
                        val response = chain.proceed(request)
                        if (request.url.encodedPath.contains("/thumb/") && response.isSuccessful) {
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
            NavGraph(board = "hlgg")
        }
    }

    override fun onStart() {
        super.onStart()
        AppForeground.onStart()
    }

    override fun onResume() {
        super.onResume()
        WebViewFetcher.onResume()
    }

    override fun onPause() {
        WebViewFetcher.onPause()
        super.onPause()
    }

    override fun onStop() {
        AppForeground.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        WebViewFetcher.detach(this)
        super.onDestroy()
    }
}
