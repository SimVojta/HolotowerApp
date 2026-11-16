package com.holotower.app.data.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One shared Cloudflare check for the whole app.
 *
 * A request that gets challenged calls [awaitClearance] and suspends. The first such
 * request opens the check (the UI watches [request]); every other challenged request
 * joins the same round. When the check passes, all of them are resumed and retry.
 */
object ChallengeGate {
    data class Request(val url: String, val round: Int)

    private val lock = Any()
    private var pending: CompletableDeferred<Boolean>? = null
    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request

    /** Increments every time a check passes; lets a request see that a check finished while it was in flight. */
    @Volatile
    var generation: Int = 0
        private set

    /** Returns true when the check passed, false when the user closed it. */
    suspend fun awaitClearance(url: String): Boolean {
        val deferred = synchronized(lock) {
            pending ?: CompletableDeferred<Boolean>().also {
                pending = it
                _request.value = Request(url, generation)
            }
        }
        return deferred.await()
    }

    fun markSolved() = finish(solved = true)

    fun dismiss() = finish(solved = false)

    private fun finish(solved: Boolean) {
        val deferred = synchronized(lock) {
            if (solved) generation++
            _request.value = null
            pending.also { pending = null }
        }
        deferred?.complete(solved)
    }
}
