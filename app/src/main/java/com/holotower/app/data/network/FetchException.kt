package com.holotower.app.data.network

import java.io.IOException

/** Request failures with messages meant for the UI. */
sealed class FetchException(message: String) : IOException(message) {
    /** Cloudflare wanted a check and the user closed it (or it kept coming back). */
    class ChallengeRequired : FetchException(
        "The security check wasn't completed. Tap Retry to open it again."
    )

    class Offline(detail: String?) : FetchException(
        "Couldn't reach holotower.org. Check your connection." +
            (detail?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
    )

    class Http(val code: Int) : FetchException(httpMessage(code))

    class Timeout : FetchException("The site took too long to answer. Try again.")

    class BadResponse : FetchException("The site sent something other than the expected data.")

    /** The hidden browser was recreated while the request was running. */
    class Interrupted : FetchException("The request was interrupted. Try again.")

    val isNotFound: Boolean get() = this is Http && code == 404
}

private fun httpMessage(code: Int): String = when (code) {
    403 -> "Access denied (HTTP 403). You may be banned or blocked."
    404 -> "Not found (HTTP 404). The thread may have been deleted or archived."
    429 -> "Too many requests (HTTP 429). Wait a moment and retry."
    in 500..599 -> "The site is having problems (HTTP $code)."
    else -> "Request failed (HTTP $code)."
}

fun Throwable.userMessage(fallback: String = "Something went wrong"): String =
    message?.takeIf { it.isNotBlank() } ?: fallback
