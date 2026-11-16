package com.holotower.app.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.text.Html
import com.google.gson.Gson
import com.holotower.app.data.model.CatalogThread
import com.holotower.app.data.model.GlobalEntryStatus
import com.holotower.app.data.model.Post
import com.holotower.app.data.model.ThreadResponse
import com.holotower.app.data.local.ThreadDiskCache
import com.holotower.app.data.network.WebViewFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

class BoardRepository(context: Context? = null) {
    private val appContext = context?.applicationContext
    private val threadDiskCache = context?.let(::ThreadDiskCache)

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

    data class SavedThread(val posts: List<Post>, val savedAtMs: Long)

    /** The saved copy of a thread (up to 5 days old) and when it was downloaded. Never touches the network. */
    suspend fun savedThread(board: String, threadNo: Long): SavedThread? {
        val cache = threadDiskCache ?: return null
        val entry = cache.readEntry(board, threadNo) ?: return null
        val posts = runCatching { parseThread(entry.json) }.getOrNull()
            ?: run {
                cache.remove(board, threadNo)
                return null
            }
        return SavedThread(posts, entry.savedAtMs)
    }

    suspend fun cachedThreadPosts(board: String, threadNo: Long): List<Post>? =
        savedThread(board, threadNo)?.posts

    /** True on mobile data (or a metered hotspot); false on ordinary Wi-Fi. */
    fun isOnMeteredNetwork(): Boolean {
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return cm.isActiveNetworkMetered
    }

    /** Downloads the thread and updates the saved copy. */
    suspend fun fetchThreadPosts(board: String, threadNo: Long): List<Post> {
        val json = WebViewFetcher.fetchThreadJson(board, threadNo)
        val fresh = parseThread(json)
        threadDiskCache?.write(board, threadNo, json)
        return fresh
    }

    /** Saved copy first, else download. Used for quoted posts from other threads. */
    suspend fun getThreadPosts(board: String, threadNo: Long, forceRefresh: Boolean = false): List<Post> {
        if (!forceRefresh) cachedThreadPosts(board, threadNo)?.let { return it }
        return fetchThreadPosts(board, threadNo)
    }

    private suspend fun parseThread(json: String): List<Post> = withContext(Dispatchers.Default) {
        gson.fromJson(json, ThreadResponse::class.java).posts
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

    private suspend fun requestGlobalEntryHtml(formBody: String? = null): String =
        WebViewFetcher.fetchHtml("https://holotower.org/global-entry.php", formBody = formBody)

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
        private val gson = Gson()

        private data class CacheEntry<T>(
            val data: T,
            val timestampMs: Long
        )

        private val catalogCache = mutableMapOf<String, CacheEntry<List<CatalogThread>>>()
        private val globalEntryCacheLock = Any()
        private var globalEntryCache: CacheEntry<GlobalEntryStatus>? = null

        private fun isFresh(timestampMs: Long): Boolean {
            return System.currentTimeMillis() - timestampMs < CACHE_TTL_MS
        }

    }
}
