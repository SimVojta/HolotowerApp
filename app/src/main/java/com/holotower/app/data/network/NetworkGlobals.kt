package com.holotower.app.data.network

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object NetworkGlobals {
    var userAgent: String = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    var cookies: String = ""
    
    // Simple state to track if we are ready
    var isChallengePassed by mutableStateOf(false)
}