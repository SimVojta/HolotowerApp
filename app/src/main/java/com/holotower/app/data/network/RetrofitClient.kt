package com.holotower.app.data.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private const val BASE_URL = "https://holotower.org/"

    // 1. Create the logger and set it to print EVERYTHING (headers, URLs, and JSON/HTML bodies)
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY 
    }

    // 2. Attach the interceptors in the correct order
    val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(CloudflareHelper.cloudflareInterceptor) // Injects cookies first
        .addInterceptor(loggingInterceptor)                     // Logs the final request second
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val api: HoloTowerApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(HoloTowerApi::class.java)
    }
}