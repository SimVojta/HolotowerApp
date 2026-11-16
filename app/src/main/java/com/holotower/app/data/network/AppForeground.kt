package com.holotower.app.data.network

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks time spent in the background (Activity onStop -> onStart). When the app comes
 * back after a while, [returnsAfterIdle] increments so screens can refresh in place.
 */
object AppForeground {
    private const val STALE_AFTER_MS = 3 * 60 * 1000L

    @Volatile
    private var stoppedAtElapsedMs = 0L
    private val _returnsAfterIdle = MutableStateFlow(0)
    val returnsAfterIdle: StateFlow<Int> = _returnsAfterIdle

    fun onStop() {
        stoppedAtElapsedMs = SystemClock.elapsedRealtime()
    }

    fun onStart() {
        val stoppedAt = stoppedAtElapsedMs
        stoppedAtElapsedMs = 0L
        if (stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt >= STALE_AFTER_MS) {
            _returnsAfterIdle.value += 1
        }
    }
}
