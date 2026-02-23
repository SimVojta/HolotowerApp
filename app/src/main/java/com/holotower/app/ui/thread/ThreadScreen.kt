package com.holotower.app.ui.thread

import android.content.Intent
import android.net.Uri
import android.text.Html
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.holotower.app.R
import com.holotower.app.data.model.Post
import com.holotower.app.data.network.CloudflareHelper
import com.holotower.app.data.network.RetrofitClient
import com.holotower.app.data.repository.BoardRepository
import com.holotower.app.ui.common.PostCard
import com.holotower.app.viewmodel.ThreadUiState
import com.holotower.app.viewmodel.ThreadViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import kotlin.math.abs

private val BackgroundColor = Color(0xFF111111)
private val TextPrimary = Color(0xFFEAEAEA)
private val AccentRed = Color(0xFFD65D5D)
private val LoadingGreen = Color(0xFF00FF9F)

private val replyRegex = Regex(">>(\\d+)")
private val anchorRegex = Regex("""(?is)<a[^>]*href=['\"]([^'\"]+)['\"][^>]*>(.*?)</a>""")
private val hrefThreadRegex = Regex("/[^/]+/res/(\\d+)\\.html", RegexOption.IGNORE_CASE)
private val slashNameRegex = Regex("""/[^/\s]+/""")
private val trailingIndexRegex = Regex("""\s+\d{3,4}\s*$""")
private val suffixWordRegex = Regex("""(?i)\b(General|Global|Thread|Edition)\b""")
private val collapseWhitespaceRegex = Regex("""\s+""")

private const val MINI_MAX_ANCESTOR_DEPTH = 6
private const val MINI_CLOSE_THRESHOLD = 90f
private const val GALLERY_CLOSE_THRESHOLD = 90f
private const val GALLERY_NAV_THRESHOLD = 90f
private const val THREAD_NAV_THRESHOLD = 90f
private const val OVERLAY_DRAG_DAMP = 0.45f
private const val OVERLAY_DRAG_CLAMP = 220f
private const val COMPOSER_CLOSE_THRESHOLD = 90f
private const val CUE_SHOW_THRESHOLD = 28f
private const val QUICK_SWIPE_MIN_DISTANCE = 26f
private const val QUICK_SWIPE_MAX_DURATION_MS = 160L

private enum class MediaKind { Image, Video }
private enum class SwipeAxis { Undecided, Horizontal, Vertical }
private data class ThreadScrollPosition(val index: Int, val offset: Int)
private val threadScrollMemory = mutableMapOf<Long, ThreadScrollPosition>()

private data class GalleryMediaItem(
    val postNo: Long,
    val url: String,
    val thumbnailUrls: List<String>,
    val fileName: String,
    val kind: MediaKind,
    val formatLabel: String,
    val fileSizeLabel: String
)

private data class MiniThreadSelection(
    val sourcePostNo: Long,
    val sourceThreadNo: Long,
    val anchorNo: Long,
    val anchorThreadNo: Long
)

private data class ScopedPost(val threadNo: Long, val post: Post)

private sealed interface MiniThreadUiState {
    object Idle : MiniThreadUiState
    object Loading : MiniThreadUiState
    data class Ready(val posts: List<ScopedPost>) : MiniThreadUiState
    data class Error(val message: String) : MiniThreadUiState
}

@Composable
private fun WebmPlayer(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val cookieHeader = remember(url) { CookieManager.getInstance().getCookie(url).orEmpty() }
    val referer = remember(url) { inferReferer(url) }
    val userAgent = remember { CloudflareHelper.userAgent }

    val player = remember(url, cookieHeader, referer, userAgent) {
        val headers = mutableMapOf(
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to referer
        )
        if (cookieHeader.isNotBlank()) headers["Cookie"] = cookieHeader

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = true
                prepare()
            }
    }

    DisposableEffect(player) { onDispose { player.release() } }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                useController = true
                this.player = player
            }
        },
        update = { view -> view.player = player }
    )
}

private fun inferReferer(url: String): String {
    return runCatching {
        val uri = Uri.parse(url)
        val scheme = uri.scheme ?: "https"
        val host = uri.host ?: "holotower.org"
        val board = uri.pathSegments.firstOrNull().orEmpty()
        if (board.isBlank()) "$scheme://$host/" else "$scheme://$host/$board/"
    }.getOrDefault("https://holotower.org/hlgg/")
}

@Composable
fun ErrorView(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Error: $message", color = AccentRed)
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
        ) {
            Text("Retry", color = Color.White)
        }
    }
}

private suspend fun loadMiniThreadConversation(
    selection: MiniThreadSelection,
    board: String,
    repo: BoardRepository,
    currentThreadNo: Long,
    currentThreadPosts: List<Post>
): List<ScopedPost> {
    val threadCache = mutableMapOf<Long, List<Post>>()
    if (currentThreadPosts.isNotEmpty()) threadCache[currentThreadNo] = currentThreadPosts

    suspend fun ensureThread(threadNo: Long): List<Post> {
        return threadCache[threadNo] ?: repo.getThreadPosts(board, threadNo).also { threadCache[threadNo] = it }
    }

    suspend fun findPost(threadNo: Long, postNo: Long): Post? {
        return ensureThread(threadNo).firstOrNull { it.no == postNo }
    }

    val included = linkedSetOf<Pair<Long, Long>>()
    findPost(selection.sourceThreadNo, selection.sourcePostNo)?.let {
        included += selection.sourceThreadNo to it.no
    }

    data class Node(val threadNo: Long, val postNo: Long, val depth: Int)
    val stack = ArrayDeque<Node>()
    stack.addLast(Node(selection.sourceThreadNo, selection.sourcePostNo, 0))
    stack.addLast(Node(selection.anchorThreadNo, selection.anchorNo, 0))

    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        val key = current.threadNo to current.postNo
        if (!included.add(key)) continue
        val currentPost = findPost(current.threadNo, current.postNo) ?: continue
        if (current.depth >= MINI_MAX_ANCESTOR_DEPTH) continue

        parseReplyTargetsWithHints(currentPost.com, current.threadNo).forEach { (targetNo, targetThreadNo) ->
            if (findPost(targetThreadNo, targetNo) != null) {
                stack.addLast(Node(targetThreadNo, targetNo, current.depth + 1))
            }
        }
    }

    suspend fun addDescendants(startThreadNo: Long, startPostNo: Long) {
        val posts = ensureThread(startThreadNo)
        val backlinks = buildBacklinks(posts)
        val descendants = ArrayDeque<Long>()
        descendants.addLast(startPostNo)
        while (descendants.isNotEmpty()) {
            val sourceNo = descendants.removeLast()
            backlinks[sourceNo].orEmpty().forEach { replyNo ->
                val key = startThreadNo to replyNo
                if (included.add(key)) descendants.addLast(replyNo)
            }
        }
    }

    addDescendants(selection.anchorThreadNo, selection.anchorNo)
    addDescendants(selection.sourceThreadNo, selection.sourcePostNo)

    return included
        .mapNotNull { (th, no) ->
            threadCache[th]?.firstOrNull { it.no == no }?.let { post ->
                ScopedPost(threadNo = th, post = post)
            }
        }
        .sortedBy { it.post.time }
}

private fun parseReplyTargetsWithHints(rawHtml: String?, defaultThreadNo: Long): List<Pair<Long, Long>> {
    if (rawHtml.isNullOrBlank()) return emptyList()
    val refs = linkedSetOf<Pair<Long, Long>>()

    anchorRegex.findAll(rawHtml).forEach { match ->
        val href = match.groupValues[1]
        val hintedThread = hrefThreadRegex.find(href)?.groupValues?.getOrNull(1)?.toLongOrNull()
        val anchorText = Html.fromHtml(match.groupValues[2], Html.FROM_HTML_MODE_COMPACT).toString()
        replyRegex.findAll(anchorText).forEach { replyMatch ->
            val postNo = replyMatch.groupValues[1].toLongOrNull() ?: return@forEach
            refs += postNo to (hintedThread ?: defaultThreadNo)
        }
    }

    val plain = Html.fromHtml(rawHtml, Html.FROM_HTML_MODE_COMPACT).toString()
    replyRegex.findAll(plain).forEach { replyMatch ->
        val postNo = replyMatch.groupValues[1].toLongOrNull() ?: return@forEach
        refs += postNo to defaultThreadNo
    }

    return refs.toList()
}

private fun buildScopedBacklinks(posts: List<ScopedPost>): Map<Pair<Long, Long>, List<Pair<Long, Long>>> {
    val allowed = posts.map { it.threadNo to it.post.no }.toSet()
    val backlinks = mutableMapOf<Pair<Long, Long>, MutableList<Pair<Long, Long>>>()

    posts.forEach { scoped ->
        extractReplyTargets(scoped.post.com).distinct().forEach { targetNo ->
            val targetKey = scoped.threadNo to targetNo
            if (targetKey in allowed) {
                backlinks.getOrPut(targetKey) { mutableListOf() }
                    .add(scoped.threadNo to scoped.post.no)
            }
        }
    }

    return backlinks.mapValues { (_, refs) -> refs.distinct().sortedBy { it.second } }
}

private fun buildMediaItems(posts: List<Post>, board: String): List<GalleryMediaItem> {
    return posts.mapNotNull { post ->
        val url = post.fullImageUrl(board) ?: return@mapNotNull null
        val ext = post.ext?.lowercase() ?: ""
        val normalizedExt = ext.removePrefix(".")
        val kind = if (ext in commonVideoExtensions) MediaKind.Video else MediaKind.Image
        GalleryMediaItem(
            postNo = post.no,
            url = url,
            thumbnailUrls = post.thumbnailUrls(board),
            fileName = suggestMediaFileName(post),
            kind = kind,
            formatLabel = if (kind == MediaKind.Video && normalizedExt.isNotBlank()) {
                normalizedExt.uppercase()
            } else {
                "IMG"
            },
            fileSizeLabel = post.fileSizeFormatted()
        )
    }
}

private val commonVideoExtensions = setOf(
    ".webm", ".mp4", ".m4v", ".mov", ".mkv", ".ogv", ".3gp", ".3g2"
)

private fun buildBacklinks(posts: List<Post>): Map<Long, List<Long>> {
    val postNos = posts.map { it.no }.toSet()
    val backlinks = mutableMapOf<Long, MutableList<Long>>()
    posts.forEach { post ->
        extractReplyTargets(post.com).distinct().filter { it in postNos }.forEach { targetNo ->
            backlinks.getOrPut(targetNo) { mutableListOf() }.add(post.no)
        }
    }
    return backlinks.mapValues { (_, refs) -> refs.sorted() }
}

private fun extractReplyTargets(rawHtml: String?): List<Long> {
    if (rawHtml.isNullOrBlank()) return emptyList()
    val plain = Html.fromHtml(rawHtml, Html.FROM_HTML_MODE_COMPACT).toString()
    return replyRegex.findAll(plain).mapNotNull { it.groupValues[1].toLongOrNull() }.toList()
}

private fun suggestMediaFileName(post: Post): String {
    val base = (post.filename?.trim().takeUnless { it.isNullOrBlank() } ?: post.tim ?: "media")
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
    val ext = post.ext?.takeIf { it.startsWith(".") } ?: ".jpg"
    return "$base$ext"
}

private suspend fun saveMediaToUri(uri: Uri, mediaUrl: String, context: android.content.Context) {
    withContext(Dispatchers.IO) {
        val request = Request.Builder().url(mediaUrl).get().build()
        RetrofitClient.okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("Empty response")
            val output = context.contentResolver.openOutputStream(uri) ?: error("Unable to open destination")
            output.use { out ->
                body.byteStream().use { input -> input.copyTo(out) }
            }
        }
    }
}

private fun buildThreadHeaderSecondaryLine(board: String, posts: List<Post>, threadNo: Long): String {
    val boardTag = "/${board.trim('/')}/"
    val source = posts.asSequence()
        .filter { it.isOp }
        .plus(posts.asSequence())
        .mapNotNull { post ->
            listOf(post.sub, post.com)
                .map { Html.fromHtml(it.orEmpty(), Html.FROM_HTML_MODE_COMPACT).toString().trim() }
                .firstOrNull { it.isNotBlank() }
        }
        .firstOrNull()
        ?: return "Thread #$threadNo"

    val slashMatches = slashNameRegex.findAll(source).map { it.value }.toList()
    slashMatches.firstOrNull { !it.equals(boardTag, ignoreCase = true) }?.let { return it }

    var normalized = source
        .replace(boardTag, " ", ignoreCase = true)
        .replace(trailingIndexRegex, "")
        .replace(suffixWordRegex, " ")
        .replace(collapseWhitespaceRegex, " ")
        .trim()

    if (normalized.isBlank()) {
        normalized = slashMatches.firstOrNull() ?: "Thread #$threadNo"
    }

    return normalized
}

@Composable
private fun BottomOverlayButton(
    text: String,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.background(Color(0xCC111111), RoundedCornerShape(10.dp)),
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
    ) {
        Text(text = text)
    }
}

@Composable
private fun SideSwipeCue(
    label: String,
    progress: Float,
    modifier: Modifier = Modifier
) {
    val p = progress.coerceIn(0f, 1f)
    Column(
        modifier = modifier
            .background(Color(0xB3111111), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_holo_logo),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier
                .size(14.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = LoadingGreen,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier.graphicsLayer {
                alpha = p
                translationX = (1f - p) * -10f
            }
        )
    }
}

private fun overlayTranslationX(rawSwipeX: Float): Float {
    return (rawSwipeX * OVERLAY_DRAG_DAMP).coerceIn(-OVERLAY_DRAG_CLAMP, OVERLAY_DRAG_CLAMP)
}

@Composable
private fun HorizontalSwipeLayer(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    threshold: Float,
    allowPositive: Boolean = true,
    allowNegative: Boolean = true,
    positiveCueLabel: String? = null,
    negativeCueLabel: String? = null,
    cueStartFraction: Float = 0.3f,
    onCommit: (direction: Int) -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val swipeX = remember { mutableFloatStateOf(0f) }
    var cueDirection by remember { mutableIntStateOf(0) }
    var dragStartedAtMs by remember { mutableStateOf(0L) }
    val cueDistance = (threshold * cueStartFraction).coerceAtLeast(CUE_SHOW_THRESHOLD)

    Box(
        modifier = modifier
            .graphicsLayer { translationX = overlayTranslationX(swipeX.floatValue) }
            .pointerInput(enabled, threshold, allowPositive, allowNegative, cueDistance) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { dragStartedAtMs = System.currentTimeMillis() },
                    onHorizontalDrag = { change, dragAmount ->
                        var next = (swipeX.floatValue + dragAmount).coerceIn(-OVERLAY_DRAG_CLAMP, OVERLAY_DRAG_CLAMP)
                        if (!allowPositive && next > 0f) next = 0f
                        if (!allowNegative && next < 0f) next = 0f
                        swipeX.floatValue = next
                        cueDirection = when {
                            positiveCueLabel != null && next >= cueDistance -> 1
                            negativeCueLabel != null && next <= -cueDistance -> -1
                            else -> 0
                        }
                        change.consume()
                    },
                    onDragEnd = {
                        val dx = swipeX.floatValue
                        val absX = abs(dx)
                        val direction = when {
                            dx > 0f -> 1
                            dx < 0f -> -1
                            else -> 0
                        }
                        val elapsed = if (dragStartedAtMs == 0L) Long.MAX_VALUE else {
                            (System.currentTimeMillis() - dragStartedAtMs).coerceAtLeast(0L)
                        }
                        val fastSwipe = elapsed <= QUICK_SWIPE_MAX_DURATION_MS && absX >= QUICK_SWIPE_MIN_DISTANCE
                        if (direction != 0 && (absX >= threshold || fastSwipe)) {
                            onCommit(direction)
                        }
                        swipeX.floatValue = 0f
                        cueDirection = 0
                        dragStartedAtMs = 0L
                    },
                    onDragCancel = {
                        swipeX.floatValue = 0f
                        cueDirection = 0
                        dragStartedAtMs = 0L
                    }
                )
            }
    ) {
        if (cueDirection > 0 && positiveCueLabel != null) {
            SideSwipeCue(
                label = positiveCueLabel,
                progress = 1f,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 14.dp)
            )
        } else if (cueDirection < 0 && negativeCueLabel != null) {
            SideSwipeCue(
                label = negativeCueLabel,
                progress = 1f,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 14.dp)
            )
        }
        content()
    }
}

@Composable
private fun VerticalProgressBar(
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    val layoutInfo = listState.layoutInfo
    val total = layoutInfo.totalItemsCount
    if (total <= 0) return
    val lastVisible = (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) + 1
    val progress = (lastVisible.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier = modifier
            .width(4.dp)
            .fillMaxHeight()
            .background(Color(0x3322FFAA), RoundedCornerShape(50))
    ) {
        val fillHeight = maxHeight * progress
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(fillHeight)
                .background(LoadingGreen, RoundedCornerShape(50))
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    board: String,
    threadNo: Long,
    onBack: () -> Unit,
    onOpenThread: (Long) -> Unit = {},
    onRefreshCloudflare: (() -> Unit)? = null,
    vm: ThreadViewModel = viewModel(factory = ThreadViewModel.Factory(board, threadNo))
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val repo = remember { BoardRepository() }

    val successPosts = (state as? ThreadUiState.Success)?.posts
    var cachedPosts by remember { mutableStateOf<List<Post>>(emptyList()) }
    LaunchedEffect(successPosts) { if (successPosts != null) cachedPosts = successPosts }
    val allPosts = successPosts ?: cachedPosts
    val mediaItems = remember(allPosts, board) { buildMediaItems(allPosts, board) }
    val threadHeaderSecondLine = remember(allPosts, board, threadNo) {
        buildThreadHeaderSecondaryLine(board = board, posts = allPosts, threadNo = threadNo)
    }

    val isRefreshing = state is ThreadUiState.Loading
    val showTopRefreshBadge = isRefreshing && cachedPosts.isNotEmpty()
    var showCloudflareRefreshButton by remember { mutableStateOf(false) }
    LaunchedEffect(isRefreshing, cachedPosts.isEmpty()) {
        showCloudflareRefreshButton = false
        if (isRefreshing && cachedPosts.isEmpty()) {
            delay(2000)
            showCloudflareRefreshButton = true
        }
    }
    var selectedMediaIndex by remember { mutableIntStateOf(-1) }
    var pendingSave by remember { mutableStateOf<GalleryMediaItem?>(null) }
    var selectedMiniThread by remember { mutableStateOf<MiniThreadSelection?>(null) }
    var miniThreadUiState by remember { mutableStateOf<MiniThreadUiState>(MiniThreadUiState.Idle) }
    var showGalleryList by remember { mutableStateOf(false) }
    var replyComposerTargetNo by remember { mutableStateOf<Long?>(null) }
    val mediaOverlayOpen = selectedMediaIndex in mediaItems.indices
    val anyOverlayOpen = selectedMiniThread != null || showGalleryList || mediaOverlayOpen || replyComposerTargetNo != null
    var hasRestoredScroll by remember(threadNo) { mutableStateOf(false) }
    val isAtBottom by remember(listState, allPosts) {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) return@derivedStateOf false
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= total - 1
        }
    }
    LaunchedEffect(threadNo, allPosts.size, hasRestoredScroll) {
        if (hasRestoredScroll || allPosts.isEmpty()) return@LaunchedEffect
        val saved = threadScrollMemory[threadNo]
        if (saved != null) {
            val safeIndex = saved.index.coerceIn(0, allPosts.lastIndex.coerceAtLeast(0))
            listState.scrollToItem(safeIndex, saved.offset.coerceAtLeast(0))
        }
        hasRestoredScroll = true
    }

    DisposableEffect(threadNo) {
        onDispose {
            threadScrollMemory[threadNo] = ThreadScrollPosition(
                index = listState.firstVisibleItemIndex,
                offset = listState.firstVisibleItemScrollOffset
            )
        }
    }

    val openExternalLink: (String) -> Unit = { rawUrl ->
        val normalized = rawUrl.trim()
        if (normalized.isNotBlank()) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalized)))
            }.onFailure {
                Toast.makeText(context, "Unable to open link", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val saveMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val saveRequest = pendingSave
        pendingSave = null
        if (uri == null || saveRequest == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { saveMediaToUri(uri, saveRequest.url, context) }
                .onSuccess { Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "Save failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    val requestMiniThread: (Long, Long, Long, Long?) -> Unit = { sourcePostNo, sourceThreadNo, targetPostNo, targetThreadNoHint ->
        val targetThreadNo = targetThreadNoHint ?: sourceThreadNo
        val request = MiniThreadSelection(sourcePostNo, sourceThreadNo, targetPostNo, targetThreadNo)
        selectedMiniThread = request
        miniThreadUiState = MiniThreadUiState.Loading
        scope.launch {
            val result = runCatching {
                loadMiniThreadConversation(
                    selection = request,
                    board = board,
                    repo = repo,
                    currentThreadNo = threadNo,
                    currentThreadPosts = allPosts
                )
            }
            if (selectedMiniThread != request) return@launch
            miniThreadUiState = result.fold(
                onSuccess = { MiniThreadUiState.Ready(it) },
                onFailure = { MiniThreadUiState.Error(it.message ?: "Failed to load mini-thread") }
            )
        }
    }

    val renderPostList: @Composable (List<Post>) -> Unit = { posts ->
        val backlinksByPostNo = buildBacklinks(posts)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(posts, key = { it.no }) { post ->
                PostCard(
                    post = post,
                    board = board,
                    referencedBy = backlinksByPostNo[post.no].orEmpty(),
                    onBodyReplyClick = { targetNo, threadNoHint ->
                        requestMiniThread(post.no, threadNo, targetNo, threadNoHint)
                    },
                    onBacklinkClick = { targetNo ->
                        requestMiniThread(targetNo, threadNo, post.no, threadNo)
                    },
                    onImageClick = { postNo, _, _ ->
                        val idx = mediaItems.indexOfFirst { it.postNo == postNo }
                        if (idx >= 0) selectedMediaIndex = idx
                    },
                    onExternalLinkClick = openExternalLink,
                    onPostNumberClick = { postNo ->
                        replyComposerTargetNo = postNo
                    }
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_holo_logo),
                            contentDescription = null,
                            tint = Color.Unspecified
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "/$board/",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Text(
                                text = threadHeaderSecondLine,
                                color = Color(0xFFD8D8D8),
                                fontSize = 13.sp,
                                modifier = Modifier.offset(y = (-2).dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    TextButton(onClick = { replyComposerTargetNo = 0L }) {
                        Text("Post", color = Color.White, fontSize = 13.sp)
                    }
                    if (mediaItems.isNotEmpty()) {
                        TextButton(onClick = { showGalleryList = true }) {
                            Text("Gallery ${mediaItems.size}", color = Color.White, fontSize = 13.sp)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundColor)
            )
        },
        containerColor = BackgroundColor
    ) { padding ->
        HorizontalSwipeLayer(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            enabled = !anyOverlayOpen,
            threshold = THREAD_NAV_THRESHOLD,
            allowPositive = true,
            allowNegative = mediaItems.isNotEmpty(),
            positiveCueLabel = null,
            negativeCueLabel = null,
            cueStartFraction = 0.28f,
            onCommit = { direction ->
                when {
                    direction > 0 -> onBack()
                    direction < 0 && mediaItems.isNotEmpty() -> showGalleryList = true
                }
            }
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (val s = state) {
                    is ThreadUiState.Loading -> {
                        if (cachedPosts.isEmpty()) {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(color = LoadingGreen)
                                if (showCloudflareRefreshButton && onRefreshCloudflare != null) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Cloudflare check needed again",
                                        color = Color(0xFFB9FFD2),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Button(
                                        onClick = onRefreshCloudflare,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF00C27B),
                                            contentColor = Color(0xFF03150E)
                                        ),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Text("Re-open Cloudflare Challenge", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        } else {
                            renderPostList(cachedPosts)
                        }
                    }
                    is ThreadUiState.Error -> ErrorView(message = s.message, onRetry = { vm.load(forceRefresh = true) })
                    is ThreadUiState.Success -> renderPostList(s.posts)
                }

                if (showTopRefreshBadge) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 6.dp)
                            .background(Color(0xCC111111), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = LoadingGreen,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Refreshing thread...", color = Color(0xFFE9FFF5), fontSize = 12.sp)
                        }
                    }
                }

                if (!anyOverlayOpen && isAtBottom && !isRefreshing) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 10.dp)
                            .clickable { vm.load(forceRefresh = true) }
                            .background(Color(0xCC111111), RoundedCornerShape(999.dp))
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_holo_logo),
                                contentDescription = null,
                                tint = Color.Unspecified,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(
                                text = "Tap to refresh thread",
                                color = LoadingGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                if (!anyOverlayOpen && isRefreshing && cachedPosts.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 10.dp)
                            .background(Color(0xCC111111), RoundedCornerShape(999.dp))
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = LoadingGreen,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Refreshing thread...",
                                color = Color(0xFFE9FFF5),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            VerticalProgressBar(
                listState = listState,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 2.dp, top = 64.dp, bottom = 12.dp)
            )
        }
    }

    selectedMiniThread?.let { selection ->
        val miniListState = rememberLazyListState()

        BackHandler {
            selectedMiniThread = null
            miniThreadUiState = MiniThreadUiState.Idle
        }

        HorizontalSwipeLayer(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xEE000000)),
            enabled = true,
            threshold = MINI_CLOSE_THRESHOLD,
            allowPositive = true,
            allowNegative = false,
            positiveCueLabel = null,
            negativeCueLabel = null,
            cueStartFraction = 0.32f,
            onCommit = {
                selectedMiniThread = null
                miniThreadUiState = MiniThreadUiState.Idle
            }
        ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (val mini = miniThreadUiState) {
                    is MiniThreadUiState.Loading -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = LoadingGreen)
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("Loading mini-thread...", color = Color.White)
                        }
                    }

                    is MiniThreadUiState.Error -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(mini.message, color = AccentRed)
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(onClick = {
                                requestMiniThread(
                                    selection.sourcePostNo,
                                    selection.sourceThreadNo,
                                    selection.anchorNo,
                                    selection.anchorThreadNo
                                )
                            }) {
                                Text("Retry")
                            }
                        }
                    }

                    is MiniThreadUiState.Ready -> {
                        val miniPosts = mini.posts
                        val scopedBacklinks = buildScopedBacklinks(miniPosts)
                        val jumpToMiniPost: (Long, Long?) -> Boolean = { targetNo, hintedThreadNo ->
                            val index = miniPosts.indexOfFirst { scoped ->
                                scoped.post.no == targetNo &&
                                    (hintedThreadNo == null || scoped.threadNo == hintedThreadNo)
                            }
                            if (index >= 0) {
                                scope.launch { miniListState.animateScrollToItem(index) }
                                true
                            } else {
                                false
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .navigationBarsPadding()
                        ) {
                            Text(
                                text = "Mini-thread: >>${selection.anchorNo}",
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                fontWeight = FontWeight.Bold
                            )

                            if (miniPosts.isEmpty()) {
                                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    Text("No posts found for this chain.", color = Color.White)
                                }
                            } else {
                                Box(modifier = Modifier.weight(1f)) {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        state = miniListState,
                                        contentPadding = PaddingValues(bottom = 8.dp)
                                    ) {
                                        items(
                                            items = miniPosts,
                                            key = { "${it.threadNo}:${it.post.no}" }
                                        ) { scoped ->
                                            if (scoped.threadNo != threadNo) {
                                                Text(
                                                    text = "/$board/ - Thread #${scoped.threadNo}",
                                                    color = Color(0xFF9ED2FF),
                                                    modifier = Modifier.padding(start = 12.dp, top = 6.dp)
                                                )
                                            }

                                            val key = scoped.threadNo to scoped.post.no
                                            val sameThreadRefs = scopedBacklinks[key]
                                                .orEmpty()
                                                .filter { it.first == scoped.threadNo }
                                                .map { it.second }

                                            PostCard(
                                                post = scoped.post,
                                                board = board,
                                                referencedBy = sameThreadRefs,
                                                onBodyReplyClick = { targetNo, threadNoHint ->
                                                    if (!jumpToMiniPost(targetNo, threadNoHint)) {
                                                        requestMiniThread(
                                                            scoped.post.no,
                                                            scoped.threadNo,
                                                            targetNo,
                                                            threadNoHint
                                                        )
                                                    }
                                                },
                                                onBacklinkClick = { targetNo ->
                                                    if (!jumpToMiniPost(targetNo, scoped.threadNo)) {
                                                        requestMiniThread(
                                                            targetNo,
                                                            scoped.threadNo,
                                                            scoped.post.no,
                                                            scoped.threadNo
                                                        )
                                                    }
                                                },
                                                onImageClick = { postNo, imageUrl, _ ->
                                                    val idx = mediaItems.indexOfFirst { it.postNo == postNo }
                                                    if (idx >= 0) {
                                                        selectedMediaIndex = idx
                                                    } else {
                                                        openExternalLink(imageUrl)
                                                    }
                                                },
                                                onExternalLinkClick = openExternalLink
                                            )
                                        }
                                    }

                                    VerticalProgressBar(
                                        listState = miniListState,
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .padding(end = 2.dp, top = 8.dp, bottom = 8.dp)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                BottomOverlayButton(text = "Close", onClick = {
                                    selectedMiniThread = null
                                    miniThreadUiState = MiniThreadUiState.Idle
                                })
                            }
                        }
                    }

                    MiniThreadUiState.Idle -> Unit
                }
                }
        }
    }

    if (showGalleryList) {
        var swipeX by remember { mutableFloatStateOf(0f) }
        val galleryListState = rememberLazyListState()
        BackHandler { showGalleryList = false }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xEE000000))
                .graphicsLayer {
                    translationX = overlayTranslationX(swipeX)
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            if (dragAmount > 0f || swipeX > 0f) {
                                swipeX = (swipeX + dragAmount).coerceIn(0f, OVERLAY_DRAG_CLAMP)
                                change.consume()
                            }
                        },
                        onDragEnd = {
                            if (swipeX > GALLERY_CLOSE_THRESHOLD) showGalleryList = false
                            swipeX = 0f
                        },
                        onDragCancel = { swipeX = 0f }
                    )
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Text(
                    text = "Thread Gallery",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    fontWeight = FontWeight.Bold
                )
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = galleryListState,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        itemsIndexed(mediaItems, key = { _, item -> item.postNo }) { index, media ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .clickable {
                                        selectedMediaIndex = index
                                        showGalleryList = false
                                    }
                                    .background(Color(0xFF1E1E1E))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                var thumbIndex by remember(media.postNo) { mutableStateOf(0) }
                                var thumbExhausted by remember(media.postNo) { mutableStateOf(false) }
                                val thumbUrl = media.thumbnailUrls.getOrNull(thumbIndex)
                                if (thumbUrl != null && !thumbExhausted) {
                                    AsyncImage(
                                        model = thumbUrl,
                                        contentDescription = null,
                                        onError = {
                                            if (thumbIndex < media.thumbnailUrls.lastIndex) {
                                                thumbIndex += 1
                                            } else {
                                                thumbExhausted = true
                                            }
                                        },
                                        alignment = Alignment.TopCenter,
                                        modifier = Modifier.size(72.dp),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(72.dp)
                                            .background(Color(0xFF2A2A2A)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("SPOILER", color = Color(0xFFFF5A5A), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text("No. ${media.postNo}", color = Color.White, fontWeight = FontWeight.Bold)
                                    Text(media.fileName, color = Color(0xFFD6D6D6), fontSize = 12.sp)
                                    val meta = listOfNotNull(
                                        media.formatLabel.takeIf { it.isNotBlank() },
                                        media.fileSizeLabel.takeIf { it.isNotBlank() }
                                    ).joinToString(" | ")
                                    if (meta.isNotBlank()) {
                                        Text(meta, color = Color(0xFF9A9A9A), fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }

                    VerticalProgressBar(
                        listState = galleryListState,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 2.dp, top = 8.dp, bottom = 8.dp)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    BottomOverlayButton(text = "Close", onClick = { showGalleryList = false })
                }
            }
        }
    }

    if (selectedMediaIndex in mediaItems.indices) {
        var currentIndex by remember(selectedMediaIndex, mediaItems) {
            mutableIntStateOf(selectedMediaIndex)
        }
        val currentMedia = mediaItems.getOrNull(currentIndex)

        if (currentMedia == null) {
            selectedMediaIndex = -1
        } else {
            var scale by remember(currentMedia.url) { mutableFloatStateOf(1f) }
            var offsetX by remember(currentMedia.url) { mutableFloatStateOf(0f) }
            var offsetY by remember(currentMedia.url) { mutableFloatStateOf(0f) }
            var swipeX by remember(currentMedia.url) { mutableFloatStateOf(0f) }
            var swipeY by remember(currentMedia.url) { mutableFloatStateOf(0f) }
            var swipeAxis by remember(currentMedia.url) { mutableStateOf(SwipeAxis.Undecided) }

            BackHandler { selectedMediaIndex = -1 }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = overlayTranslationX(swipeX)
                            translationY = (swipeY * OVERLAY_DRAG_DAMP).coerceIn(-OVERLAY_DRAG_CLAMP, OVERLAY_DRAG_CLAMP)
                        }
                        .pointerInput(currentMedia.url, currentIndex, scale) {
                            var swipeEnabled = false
                            val topExclusion = 120.dp.toPx()
                            val bottomExclusion = 170.dp.toPx()
                            detectDragGestures(
                                onDragStart = { start ->
                                    swipeEnabled = start.y > topExclusion &&
                                        start.y < (size.height - bottomExclusion)
                                },
                                onDrag = { change, dragAmount ->
                                    if (!swipeEnabled) return@detectDragGestures
                                    val canSwipe = currentMedia.kind == MediaKind.Video || scale <= 1.05f
                                    if (!canSwipe) return@detectDragGestures

                                    if (swipeAxis == SwipeAxis.Undecided) {
                                        val absX = abs(dragAmount.x)
                                        val absY = abs(dragAmount.y)
                                        if (absX > 6f || absY > 6f) {
                                            swipeAxis = if (absX >= absY) SwipeAxis.Horizontal else SwipeAxis.Vertical
                                        }
                                    }

                                    when (swipeAxis) {
                                        SwipeAxis.Horizontal -> {
                                            if (dragAmount.x > 0f || swipeX > 0f) {
                                                swipeX = (swipeX + dragAmount.x).coerceIn(0f, OVERLAY_DRAG_CLAMP)
                                            }
                                            swipeY = 0f
                                            change.consume()
                                        }
                                        SwipeAxis.Vertical -> {
                                            swipeY += dragAmount.y
                                            swipeX = 0f
                                            change.consume()
                                        }
                                        SwipeAxis.Undecided -> Unit
                                    }
                                },
                                onDragEnd = {
                                    if (swipeEnabled) {
                                        when (swipeAxis) {
                                            SwipeAxis.Horizontal -> {
                                                if (swipeX > GALLERY_CLOSE_THRESHOLD) selectedMediaIndex = -1
                                            }
                                            SwipeAxis.Vertical -> {
                                                if (abs(swipeY) > GALLERY_NAV_THRESHOLD) {
                                                    if (swipeY < 0f && currentIndex < mediaItems.lastIndex) currentIndex += 1
                                                    else if (swipeY > 0f && currentIndex > 0) currentIndex -= 1
                                                }
                                            }
                                            SwipeAxis.Undecided -> Unit
                                        }
                                    }
                                    swipeX = 0f
                                    swipeY = 0f
                                    swipeAxis = SwipeAxis.Undecided
                                    swipeEnabled = false
                                },
                                onDragCancel = {
                                    swipeX = 0f
                                    swipeY = 0f
                                    swipeAxis = SwipeAxis.Undecided
                                    swipeEnabled = false
                                }
                            )
                        }
                ) {
                    if (currentMedia.kind == MediaKind.Video) {
                        WebmPlayer(url = currentMedia.url, modifier = Modifier.fillMaxSize())
                    } else {
                        AsyncImage(
                            model = currentMedia.url,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .clipToBounds()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offsetX,
                                    translationY = offsetY
                                ),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                if (currentIndex > 0) {
                    IconButton(
                        onClick = { currentIndex -= 1 },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = 12.dp)
                            .size(44.dp)
                            .background(Color(0xCC111111))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowUp,
                            contentDescription = "Previous",
                            tint = LoadingGreen
                        )
                    }
                }

                if (currentIndex < mediaItems.lastIndex) {
                    IconButton(
                        onClick = { currentIndex += 1 },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 72.dp)
                            .size(44.dp)
                            .background(Color(0xCC111111))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Next",
                            tint = LoadingGreen
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (currentMedia.kind == MediaKind.Video) {
                        BottomOverlayButton(text = "Open", onClick = { openExternalLink(currentMedia.url) })
                    }
                    BottomOverlayButton(text = "Save", onClick = {
                        pendingSave = currentMedia
                        saveMediaLauncher.launch(currentMedia.fileName)
                    })
                    BottomOverlayButton(text = "Gallery", onClick = {
                        selectedMediaIndex = -1
                        showGalleryList = true
                    })
                    BottomOverlayButton(text = "Close", onClick = { selectedMediaIndex = -1 })
                }
            }
        }
    }

    replyComposerTargetNo?.let { replyNo ->
        ReplyComposerOverlay(
            board = board,
            threadNo = threadNo,
            replyToNo = replyNo.takeIf { it > 0L },
            onClose = { replyComposerTargetNo = null }
        )
    }
}

@Composable
fun NewThreadComposerScreen(
    board: String,
    onBack: () -> Unit
) {
    ReplyComposerOverlay(
        board = board,
        threadNo = null,
        replyToNo = null,
        onClose = onBack
    )
}

@Composable
private fun ReplyComposerOverlay(
    board: String,
    threadNo: Long?,
    replyToNo: Long?,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val pageUrl = remember(board, threadNo) {
        if (threadNo != null && threadNo > 0L) {
            "https://holotower.org/$board/res/$threadNo.html"
        } else {
            "https://holotower.org/$board/index.html"
        }
    }
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(android.graphics.Color.BLACK)
            alpha = 0f
        }
    }
    var isComposerReady by remember(pageUrl, replyToNo) { mutableStateOf(false) }
    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingFileCallback
        pendingFileCallback = null
        callback?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        )
    }

    BackHandler(onBack = onClose)

    DisposableEffect(pageUrl, replyToNo) {
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = filePathCallback
                val intent = runCatching { fileChooserParams?.createIntent() }.getOrNull()
                if (intent == null) {
                    pendingFileCallback?.onReceiveValue(null)
                    pendingFileCallback = null
                    return false
                }
                filePickerLauncher.launch(intent)
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val raw = request?.url?.toString().orEmpty()
                val normalized = raw.lowercase()
                val boardRoot = "https://holotower.org/$board/"
                val boardIndex = "https://holotower.org/$board/index.html"
                val boardCatalog = "https://holotower.org/$board/catalog.html"
                if (normalized == boardRoot || normalized.startsWith(boardIndex) || normalized.startsWith(boardCatalog)) {
                    view?.loadUrl(pageUrl)
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isComposerReady = false
                view?.alpha = 0f
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                val quoteScript = if (replyToNo != null) {
                    """
                      var quote = ">>$replyToNo\n";
                      if (!ta.value || ta.value.indexOf(quote.trim()) !== 0) {
                        ta.value = quote + ta.value;
                      }
                    """.trimIndent()
                } else {
                    ""
                }
                val script = """
                    (function() {
                      document.querySelectorAll("link[rel='stylesheet'], style").forEach(function(el) {
                        el.remove();
                      });

                      var form = document.querySelector("form[name='post']");
                      if (!form) return "no-form";

                      document.body.innerHTML = "";
                      document.body.appendChild(form);
                      document.documentElement.style.background = "#0A0A0A";
                      document.body.style.background = "#0A0A0A";
                      document.body.style.color = "#EAEAEA";
                      document.body.style.margin = "0";
                      document.body.style.padding = "10px";
                      document.body.style.fontFamily = "sans-serif";

                      var style = document.createElement("style");
                      style.textContent = `
                        * { box-sizing: border-box !important; }
                        form[name="post"] { max-width: 900px !important; margin: 0 auto !important; }
                        form[name="post"] table { width: 100% !important; border-collapse: collapse !important; }
                        form[name="post"] tr { display: block !important; margin: 0 0 10px 0 !important; background: transparent !important; }
                        form[name="post"] th, form[name="post"] td {
                          display: block !important;
                          width: 100% !important;
                          margin: 0 !important;
                          padding: 0 !important;
                          border: none !important;
                          background: transparent !important;
                        }
                        form[name="post"] th {
                          color: #9CA3AF !important;
                          font-size: 12px !important;
                          font-weight: 600 !important;
                          margin-bottom: 6px !important;
                          text-align: left !important;
                        }
                        form[name="post"] input[type="text"],
                        form[name="post"] input[type="password"],
                        form[name="post"] input[type="file"],
                        form[name="post"] textarea {
                          width: 100% !important;
                          background: #16181B !important;
                          color: #EAEAEA !important;
                          border: 1px solid #2A2F35 !important;
                          border-radius: 10px !important;
                          padding: 10px 12px !important;
                          outline: none !important;
                          font-size: 14px !important;
                        }
                        form[name="post"] textarea { min-height: 220px !important; line-height: 1.4 !important; }
                        form[name="post"] input[type="submit"] {
                          background: #00FF9F !important;
                          color: #03281A !important;
                          border: none !important;
                          border-radius: 999px !important;
                          padding: 9px 16px !important;
                          font-weight: 700 !important;
                          margin-top: 8px !important;
                          cursor: pointer !important;
                        }
                        form[name="post"] .unimportant { color: #7B8088 !important; font-size: 11px !important; }
                        #upload_settings label, #upload_settings { color: #C8CDD4 !important; }
                      `;
                      document.head.appendChild(style);

                      var ta = form
                        ? form.querySelector("textarea[name='body'], textarea[name='com'], textarea#body, textarea#com, textarea")
                        : document.querySelector("textarea[name='body'], textarea[name='com'], textarea#body, textarea#com, textarea");
                      if (!ta) return;
                      $quoteScript
                      ta.focus();
                      if (ta.setSelectionRange) {
                        var len = ta.value.length;
                        ta.setSelectionRange(len, len);
                      }
                      return "ok";
                    })();
                """.trimIndent()
                view.evaluateJavascript(script) {
                    isComposerReady = true
                    view.alpha = 1f
                }
            }
        }

        webView.loadUrl(pageUrl)
        onDispose {
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = null
            webView.stopLoading()
            webView.webChromeClient = WebChromeClient()
            webView.webViewClient = WebViewClient()
        }
    }

    HorizontalSwipeLayer(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xEE000000)),
        enabled = true,
        threshold = COMPOSER_CLOSE_THRESHOLD,
        allowPositive = true,
        allowNegative = false,
        positiveCueLabel = null,
        negativeCueLabel = null,
        cueStartFraction = 0.88f,
        onCommit = { onClose() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF111111))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val title = when {
                    threadNo == null || threadNo <= 0L -> "New Thread"
                    replyToNo != null -> "Reply >>$replyToNo"
                    else -> "Reply"
                }
                Text(
                    text = title,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.weight(1f))
                BottomOverlayButton(text = "Close", onClick = onClose)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { webView },
                    update = { }
                )
                if (!isComposerReady) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0A0A0A)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = LoadingGreen)
                    }
                }
            }
        }
    }
}

