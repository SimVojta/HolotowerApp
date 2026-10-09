package com.holotower.app.data.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object RetrofitClient {
    val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(CloudflareHelper.cloudflareInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
}
