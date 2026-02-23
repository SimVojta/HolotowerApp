package com.holotower.app.data.repository

import android.text.Html
import com.holotower.app.data.model.CatalogThread
import com.holotower.app.data.model.GlobalEntryStatus
import com.holotower.app.data.model.Post
import com.holotower.app.data.network.WebViewFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

class BoardRepository {

    suspend fun getCatalogThreads(board: String, forceRefresh: Boolean = false): List<CatalogThread> {
        if (!forceRefresh) {
            synchronized(catalogCache) {
                catalogCache[board]?.takeIf { isFresh(it.timestampMs) }?.let { return it.data }
            }
        }

        val fresh = WebViewFetcher.getCatalog(board).flatMap { it.threads }
        synchronized(catalogCache) {
            catalogCache[board] = CacheEntry(fresh, System.currentTimeMillis())
        }
        return fresh
    }

    suspend fun getThreadPosts(board: String, threadNo: Long, forceRefresh: Boolean = false): List<Post> {
        val key = "$board:$threadNo"
        if (!forceRefresh) {
            synchronized(threadCache) {
                threadCache[key]?.takeIf { isFresh(it.timestampMs) }?.let { return it.data }
            }
        }

        val fresh = WebViewFetcher.getThread(board, threadNo).posts
        synchronized(threadCache) {
            threadCache[key] = CacheEntry(fresh, System.currentTimeMillis())
        }
        return fresh
    }

    suspend fun getGlobalEntryStatus(forceRefresh: Boolean = false): GlobalEntryStatus {
        var cached: GlobalEntryStatus? = null
        if (!forceRefresh) {
            synchronized(globalEntryCacheLock) {
                val entry = globalEntryCache
                if (entry != null && isFresh(entry.timestampMs)) {
                    cached = entry.data
                }
            }
        }
        cached?.let { return it }

        val html = requestGlobalEntryHtml()
        val parsed = parseGlobalEntry(html)
        synchronized(globalEntryCacheLock) {
            globalEntryCache = CacheEntry(parsed, System.currentTimeMillis())
        }
        return parsed
    }

    suspend fun attachGlobalEntryToken(token: String): GlobalEntryStatus {
        val current = getGlobalEntryStatus(forceRefresh = true)
        val csrfToken = current.csrfToken.orEmpty()
        if (csrfToken.isBlank()) {
            throw IllegalStateException("Missing Global Entry CSRF token")
        }

        val formBody = buildFormBody(
            mapOf(
                "ge_csrf_token" to csrfToken,
                "token" to token.trim(),
                "attach_token" to "1"
            )
        )
        val html = requestGlobalEntryHtml(
            formBody = formBody
        )
        val parsed = parseGlobalEntry(html)
        synchronized(globalEntryCacheLock) {
            globalEntryCache = CacheEntry(parsed, System.currentTimeMillis())
        }
        return parsed
    }

    private suspend fun requestGlobalEntryHtml(formBody: String? = null): String = withContext(Dispatchers.IO) {
        val url = "https://holotower.org/global-entry.php"
        val html = WebViewFetcher.fetchHtml(url, formBody = formBody)
        if (html.contains("Cloudflare is verifying your request", ignoreCase = true)) {
            throw IllegalStateException("Cloudflare challenge required for Global Entry")
        }
        html
    }

    private fun buildFormBody(params: Map<String, String>): String {
        return params.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private fun parseGlobalEntry(html: String): GlobalEntryStatus {
        fun decode(s: String): String {
            return Html.fromHtml(s, Html.FROM_HTML_MODE_COMPACT).toString().trim()
        }

        fun findFirst(regex: Regex): String? = regex.find(html)?.groupValues?.getOrNull(1)?.let(::decode)

        fun findMessages(listClass: String): List<String> {
            val ulRegex = Regex(
                """<ul[^>]*class=["'][^"']*$listClass[^"']*["'][^>]*>(.*?)</ul>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            val liRegex = Regex("""<li[^>]*>(.*?)</li>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val block = ulRegex.find(html)?.groupValues?.getOrNull(1).orEmpty()
            return liRegex.findAll(block)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .map(::decode)
                .filter { it.isNotBlank() }
                .toList()
        }

        val statusText = findFirst(
            Regex("""<p[^>]*class=["'][^"']*ge-status[^"']*["'][^>]*>.*?<strong>(.*?)</strong>.*?</p>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        ).orEmpty()

        val countMatch = Regex(
            """Post count toward threshold:\s*<strong>(\d+)</strong>\s*/\s*<strong>(\d+)</strong>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html)

        val postCount = countMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        val threshold = countMatch?.groupValues?.getOrNull(2)?.toIntOrNull()

        val currentIp = findFirst(
            Regex("""Your current IP:\s*<strong>(.*?)</strong>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        )

        val csrfToken = Regex(
            """name=["']ge_csrf_token["'][^>]*value=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)

        val issuedToken = findFirst(
            Regex("""class=["'][^"']*ge-token-value[^"']*["'][^>]*>(.*?)</""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        )

        return GlobalEntryStatus(
            statusText = statusText,
            postCount = postCount,
            threshold = threshold,
            currentIp = currentIp,
            csrfToken = csrfToken,
            issuedToken = issuedToken,
            successMessages = findMessages("ge-messages-ok"),
            errorMessages = findMessages("ge-messages-error")
        )
    }

    companion object {
        private const val CACHE_TTL_MS = 2 * 60 * 1000L

        private data class CacheEntry<T>(
            val data: T,
            val timestampMs: Long
        )

        private val catalogCache = mutableMapOf<String, CacheEntry<List<CatalogThread>>>()
        private val threadCache = mutableMapOf<String, CacheEntry<List<Post>>>()
        private val globalEntryCacheLock = Any()
        private var globalEntryCache: CacheEntry<GlobalEntryStatus>? = null

        private fun isFresh(timestampMs: Long): Boolean {
            return System.currentTimeMillis() - timestampMs < CACHE_TTL_MS
        }
    }
}
