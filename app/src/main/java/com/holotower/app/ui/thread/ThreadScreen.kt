package com.holotower.app.ui.thread

import android.content.Intent
import android.net.Uri
import android.text.Html
import android.webkit.CookieManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
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
import com.holotower.app.data.local.ThreadPositionStore
import com.holotower.app.data.local.ThreadSeenStore
import com.holotower.app.data.model.Post
import com.holotower.app.data.network.AppForeground
import com.holotower.app.data.network.CloudflareHelper
import com.holotower.app.data.network.RetrofitClient
import com.holotower.app.data.repository.BoardRepository
import com.holotower.app.ui.common.PostCard
import com.holotower.app.ui.common.PostThumbnail
import com.holotower.app.viewmodel.ThreadUiState
import com.holotower.app.viewmodel.ThreadViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.roundToInt

private val BackgroundColor = Color(0xFF111111)
private val TextPrimary = Color(0xFFEAEAEA)
private val AccentRed = Color(0xFFD65D5D)
internal val LoadingGreen = Color(0xFF00FF9F)

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

private const val CUE_SHOW_THRESHOLD = 28f
private const val QUICK_SWIPE_MIN_DISTANCE = 26f
private const val QUICK_SWIPE_MAX_DURATION_MS = 160L
private const val JUMP_TO_LATEST_DISTANCE_THRESHOLD = 12
private const val JUMP_TO_LATEST_VISIBLE_MS = 5_000L

private enum class MediaKind { Image, Video }
private enum class GalleryFilter { All, Images, Videos }
private enum class SwipeAxis { Undecided, Horizontal, Vertical }

private data class GalleryMediaItem(
    val postNo: Long,
    val url: String,
    val thumbnailUrls: List<String>,
    val ext: String?,
    val isSpoiler: Boolean,
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

private const val VIDEO_CONTROLS_TIMEOUT_MS = 3_000

/** [onControlsVisibilityChange] reports when the player's own controls appear or hide. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun WebmPlayer(
    url: String,
    modifier: Modifier = Modifier,
    onControlsVisibilityChange: (Boolean) -> Unit = {}
) {
    val currentOnControlsVisibilityChange by rememberUpdatedState(onControlsVisibilityChange)
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
                controllerShowTimeoutMs = VIDEO_CONTROLS_TIMEOUT_MS
                setControllerVisibilityListener(
                    PlayerView.ControllerVisibilityListener { visibility ->
                        currentOnControlsVisibilityChange(visibility == android.view.View.VISIBLE)
                    }
                )
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
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = message, color = AccentRed)
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
        ) {
            Text("Retry", color = Color.White)
        }
    }
}

@Composable
private fun NewPostsDivider(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f).height(1.dp).background(LoadingGreen))
        Text(
            text = if (count == 1) "1 new post" else "$count new posts",
            color = LoadingGreen,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Box(modifier = Modifier.weight(1f).height(1.dp).background(LoadingGreen))
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
            ext = post.ext,
            isSpoiler = post.isSpoiler,
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
    // Thread comments encode quote markers as either literal >> or &gt;&gt;.
    // Matching the raw markup avoids running Html.fromHtml across every post
    // whenever the thread's backlinks are rebuilt.
    return replyMarkupRegex.findAll(rawHtml)
        .mapNotNull { it.groupValues[1].toLongOrNull() }
        .toList()
}

private val replyMarkupRegex = Regex("(?:&gt;|>){2}(\\d+)", RegexOption.IGNORE_CASE)

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
internal fun BottomOverlayButton(
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
internal fun HorizontalSwipeLayer(
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
            .offset { IntOffset(overlayTranslationX(swipeX.floatValue).roundToInt(), 0) }
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
                        val fastSwipe = elapsed <= QUICK_SWIPE_MAX_DURATION_MS &&
                            absX >= QUICK_SWIPE_MIN_DISTANCE
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, FlowPreview::class, ExperimentalMaterialApi::class)
@Composable
fun ThreadScreen(
    board: String,
    threadNo: Long,
    onBack: () -> Unit,
    onOpenThread: (Long) -> Unit = {},
    onThreadCountsUpdated: (replies: Int, images: Int) -> Unit = { _, _ -> },
    isCurrentDestination: Boolean = true,
    vm: ThreadViewModel = viewModel(factory = ThreadViewModel.Factory(board, threadNo))
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val repo = remember(context.applicationContext) { BoardRepository(context.applicationContext) }

    val successPosts = (state as? ThreadUiState.Success)?.posts
    // The state carries the previous/saved posts while loading; cachedPosts only covers
    // the moment between a state change and the next composition.
    var cachedPosts by remember { mutableStateOf<List<Post>>(emptyList()) }
    LaunchedEffect(state.posts) { state.posts?.let { cachedPosts = it } }
    val allPosts = state.posts ?: cachedPosts
    LaunchedEffect(board, threadNo, successPosts) {
        val posts = successPosts ?: return@LaunchedEffect
        val replies = (posts.size - 1).coerceAtLeast(0)
        onThreadCountsUpdated(replies, posts.drop(1).count { !it.tim.isNullOrBlank() })
        withContext(Dispatchers.IO) { ThreadSeenStore.markSeen(context, board, threadNo, replies) }
    }

    // "N new posts" divider: posts newer than what was on screen (or saved) before a refresh.
    var lastShownPostNo by remember(threadNo) { mutableStateOf<Long?>(null) }
    var firstNewPostNo by remember(threadNo) { mutableStateOf<Long?>(null) }
    var newPostCount by remember(threadNo) { mutableIntStateOf(0) }
    LaunchedEffect(state) {
        val posts = state.posts ?: return@LaunchedEffect
        val lastNo = posts.lastOrNull()?.no ?: return@LaunchedEffect
        val previous = lastShownPostNo
        if (state is ThreadUiState.Success && previous != null) {
            val fresh = posts.filter { it.no > previous }
            if (fresh.isNotEmpty()) {
                firstNewPostNo = fresh.first().no
                newPostCount = fresh.size
            }
        }
        if (state is ThreadUiState.Success || previous == null) lastShownPostNo = lastNo
    }
    val mediaItems = remember(allPosts, board) { buildMediaItems(allPosts, board) }
    var galleryFilter by remember { mutableStateOf(GalleryFilter.All) }
    val galleryMediaItems = remember(mediaItems, galleryFilter) {
        when (galleryFilter) {
            GalleryFilter.All -> mediaItems
            GalleryFilter.Images -> mediaItems.filter { it.kind == MediaKind.Image }
            GalleryFilter.Videos -> mediaItems.filter { it.kind == MediaKind.Video }
        }
    }
    val postIndexByNo = remember(allPosts) { allPosts.mapIndexed { index, post -> post.no to index }.toMap() }
    var releasedForBackStack by remember(threadNo) { mutableStateOf(false) }

    val threadHeaderSecondLine = remember(allPosts, board, threadNo) {
        buildThreadHeaderSecondaryLine(board = board, posts = allPosts, threadNo = threadNo)
    }

    val isRefreshing = state is ThreadUiState.Loading
    var userPulledToRefresh by remember { mutableStateOf(false) }
    LaunchedEffect(isRefreshing) { if (!isRefreshing) userPulledToRefresh = false }
    val pullRefreshState = rememberPullRefreshState(
        refreshing = userPulledToRefresh && isRefreshing,
        onRefresh = {
            userPulledToRefresh = true
            vm.load(forceRefresh = true)
        }
    )
    var selectedMediaIndex by remember { mutableIntStateOf(-1) }
    var pendingSave by remember { mutableStateOf<GalleryMediaItem?>(null) }
    var selectedMiniThread by remember { mutableStateOf<MiniThreadSelection?>(null) }
    var miniThreadUiState by remember { mutableStateOf<MiniThreadUiState>(MiniThreadUiState.Idle) }
    var showGalleryList by remember { mutableStateOf(false) }
    var mediaOpenedFromGallery by remember(threadNo) { mutableStateOf(false) }
    var galleryScrollTargetPostNo by remember { mutableStateOf<Long?>(null) }
    // Lives outside the gallery overlay so its scroll position survives opening an image.
    val galleryListState = remember(threadNo) { LazyListState() }
    var replyComposerTargetNo by remember { mutableStateOf<Long?>(null) }
    val mediaOverlayOpen = selectedMediaIndex in mediaItems.indices
    val anyOverlayOpen = selectedMiniThread != null || showGalleryList || mediaOverlayOpen || replyComposerTargetNo != null
    var hasRestoredScroll by remember(threadNo) { mutableStateOf(false) }
    LaunchedEffect(threadNo, isCurrentDestination, allPosts) {
        if (!isCurrentDestination && !releasedForBackStack) {
            if (hasRestoredScroll && allPosts.isNotEmpty()) {
                val safeIndex = listState.firstVisibleItemIndex.coerceIn(0, allPosts.lastIndex)
                allPosts.getOrNull(safeIndex)?.let { post ->
                    ThreadPositionStore.save(
                        context = context,
                        board = board,
                        threadNo = threadNo,
                        postNo = post.no,
                        index = safeIndex,
                        offset = listState.firstVisibleItemScrollOffset
                    )
                }
            }
            releasedForBackStack = true
            cachedPosts = emptyList()
            selectedMediaIndex = -1
            mediaOpenedFromGallery = false
            showGalleryList = false
            pendingSave = null
            selectedMiniThread = null
            miniThreadUiState = MiniThreadUiState.Idle
            replyComposerTargetNo = null
            hasRestoredScroll = false
            vm.releasePosts()
        } else if (isCurrentDestination) {
            releasedForBackStack = false
            vm.reloadAfterRelease()
        }
    }
    val isAtBottom by remember(listState, allPosts) {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) return@derivedStateOf false
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= total - 1
        }
    }
    val distanceFromLatest by remember(listState, allPosts) {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) return@derivedStateOf 0
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            (total - 1 - lastVisible).coerceAtLeast(0)
        }
    }
    LaunchedEffect(threadNo, allPosts.size, hasRestoredScroll) {
        if (hasRestoredScroll || allPosts.isEmpty()) return@LaunchedEffect
        val saved = ThreadPositionStore.load(context, board, threadNo)
        if (saved != null) {
            val restoredIndex = allPosts.indexOfFirst { it.no == saved.postNo }
                .takeIf { it >= 0 }
                ?: allPosts.indexOfFirst { it.no > saved.postNo }
                    .takeIf { it >= 0 }
                ?: allPosts.lastIndex
            listState.scrollToItem(restoredIndex, saved.offset.coerceAtLeast(0))
        }
        hasRestoredScroll = true
    }

    LaunchedEffect(board, threadNo, allPosts, hasRestoredScroll) {
        if (!hasRestoredScroll || allPosts.isEmpty()) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .debounce(500)
            .collectLatest { (index, offset) ->
                val safeIndex = index.coerceIn(0, allPosts.lastIndex.coerceAtLeast(0))
                val postNo = allPosts.getOrNull(safeIndex)?.no ?: return@collectLatest
                ThreadPositionStore.save(
                    context = context,
                    board = board,
                    threadNo = threadNo,
                    postNo = postNo,
                    index = safeIndex,
                    offset = offset
                )
            }
    }

    DisposableEffect(board, threadNo, allPosts, hasRestoredScroll) {
        onDispose {
            // The initial list position is not the user's reading position. Never
            // let disposal during restoration overwrite the saved post with index 0.
            if (!hasRestoredScroll || allPosts.isEmpty()) return@onDispose
            val safeIndex = listState.firstVisibleItemIndex.coerceIn(0, allPosts.lastIndex.coerceAtLeast(0))
            val postNo = allPosts.getOrNull(safeIndex)?.no ?: return@onDispose
            ThreadPositionStore.save(
                context = context,
                board = board,
                threadNo = threadNo,
                postNo = postNo,
                index = safeIndex,
                offset = listState.firstVisibleItemScrollOffset
            )
        }
    }

    // Coming back to the app after a while: refresh in place, keeping the posts on screen.
    val returnToken by AppForeground.returnsAfterIdle.collectAsState()
    var handledReturnToken by rememberSaveable(threadNo) { mutableIntStateOf(returnToken) }
    LaunchedEffect(returnToken, isCurrentDestination, anyOverlayOpen) {
        if (isCurrentDestination && !anyOverlayOpen && returnToken != handledReturnToken) {
            handledReturnToken = returnToken
            vm.load(forceRefresh = true)
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
        val backlinksByPostNo by produceState<Map<Long, List<Long>>>(
            initialValue = emptyMap(),
            key1 = posts
        ) {
            value = withContext(Dispatchers.Default) { buildBacklinks(posts) }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(posts, key = { it.no }) { post ->
                // Inside the post's own item, so list indices still match post indices.
                if (post.no == firstNewPostNo && newPostCount > 0) NewPostsDivider(newPostCount)
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
                        if (idx >= 0) {
                            mediaOpenedFromGallery = false
                            selectedMediaIndex = idx
                        }
                    },
                    onExternalLinkClick = openExternalLink,
                    onPostNumberClick = { postNo ->
                        replyComposerTargetNo = postNo
                    }
                )
            }
        }
    }

    val jumpToPostInThread: (Long) -> Unit = { targetPostNo ->
        val index = postIndexByNo[targetPostNo]
        if (index != null) {
            scope.launch { listState.scrollToItem(index) }
        } else {
            Toast.makeText(context, "Post not found in loaded thread", Toast.LENGTH_SHORT).show()
        }
    }

    val jumpToLatestPost: () -> Unit = {
        if (allPosts.isNotEmpty()) {
            scope.launch { listState.scrollToItem(allPosts.lastIndex) }
        }
    }
    // After posting: once the refreshed thread arrives, show its end (the new post).
    var scrollToLatestAfterLoad by remember(threadNo) { mutableStateOf(false) }
    LaunchedEffect(successPosts, scrollToLatestAfterLoad) {
        val posts = successPosts ?: return@LaunchedEffect
        if (scrollToLatestAfterLoad && posts.isNotEmpty()) {
            scrollToLatestAfterLoad = false
            listState.scrollToItem(posts.lastIndex)
        }
    }

    var showJumpToLatestChip by remember(threadNo) { mutableStateOf(false) }
    var jumpToLatestChipToken by remember(threadNo) { mutableIntStateOf(0) }

    LaunchedEffect(threadNo, anyOverlayOpen) {
        snapshotFlow {
            Triple(listState.isScrollInProgress, distanceFromLatest, isAtBottom)
        }.collectLatest { (isScrolling, distance, atBottom) ->
            if (anyOverlayOpen || atBottom || distance < JUMP_TO_LATEST_DISTANCE_THRESHOLD) {
                showJumpToLatestChip = false
                return@collectLatest
            }
            if (isScrolling) {
                jumpToLatestChipToken += 1
                showJumpToLatestChip = true
            }
        }
    }

    LaunchedEffect(jumpToLatestChipToken) {
        if (jumpToLatestChipToken == 0) return@LaunchedEffect
        delay(JUMP_TO_LATEST_VISIBLE_MS)
        showJumpToLatestChip = false
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
                    IconButton(onClick = { replyComposerTargetNo = 0L }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Reply", tint = Color.White)
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pullRefresh(pullRefreshState, enabled = allPosts.isNotEmpty() && !anyOverlayOpen)
            ) {
                val errorState = state as? ThreadUiState.Error
                when {
                    allPosts.isNotEmpty() -> renderPostList(allPosts)
                    errorState != null -> ErrorView(
                        message = errorState.message,
                        onRetry = { vm.load(forceRefresh = true) }
                    )
                    else -> CircularProgressIndicator(
                        color = LoadingGreen,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                if (errorState != null && allPosts.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .background(Color(0xEE111111))
                            .padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Refresh failed: ${errorState.message}",
                            color = Color(0xFFE9FFF5),
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { vm.load(forceRefresh = true) }) {
                            Text("Retry", color = LoadingGreen)
                        }
                    }
                }

                PullRefreshIndicator(
                    refreshing = userPulledToRefresh && isRefreshing,
                    state = pullRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter),
                    backgroundColor = Color(0xFF1E1E1E),
                    contentColor = LoadingGreen
                )

                if (isRefreshing && allPosts.isNotEmpty() && !userPulledToRefresh) {
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

                if (!anyOverlayOpen && showJumpToLatestChip && allPosts.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 10.dp)
                            .clickable(onClick = jumpToLatestPost)
                            .background(Color(0xCC111111), RoundedCornerShape(999.dp))
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = "Jump to latest",
                            color = LoadingGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (!anyOverlayOpen && isRefreshing && allPosts.isNotEmpty()) {
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
                                scope.launch { miniListState.scrollToItem(index) }
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
                                                        mediaOpenedFromGallery = false
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
        LaunchedEffect(galleryScrollTargetPostNo, galleryMediaItems) {
            val targetPostNo = galleryScrollTargetPostNo ?: return@LaunchedEffect
            galleryScrollTargetPostNo = null
            val targetIndex = galleryMediaItems.indexOfFirst { it.postNo == targetPostNo }
            if (targetIndex < 0) return@LaunchedEffect
            // Keep the exact position the gallery had; only scroll when the item shown last
            // (e.g. after flipping to other media in the viewer) is off screen.
            val info = galleryListState.layoutInfo
            val fullyVisible = info.visibleItemsInfo.any { item ->
                item.index == targetIndex &&
                    item.offset >= info.viewportStartOffset &&
                    item.offset + item.size <= info.viewportEndOffset
            }
            if (!fullyVisible) galleryListState.scrollToItem(targetIndex)
        }
        BackHandler { showGalleryList = false }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xEE000000))
                .offset {
                    IntOffset(overlayTranslationX(swipeX).roundToInt(), 0)
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    GalleryFilter.entries.forEach { filter ->
                        val selected = galleryFilter == filter
                        TextButton(
                            onClick = { galleryFilter = filter },
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .background(
                                    color = if (selected) Color(0xFF5E4A8A) else Color(0xFF202020),
                                    shape = RoundedCornerShape(16.dp)
                                )
                        ) {
                            Text(
                                text = when (filter) {
                                    GalleryFilter.All -> "ALL"
                                    GalleryFilter.Images -> "IMG"
                                    GalleryFilter.Videos -> "VID"
                                },
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = galleryListState,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        itemsIndexed(galleryMediaItems, key = { _, item -> item.postNo }) { _, media ->
                            val mediaIndex = mediaItems.indexOfFirst { it.url == media.url }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .background(Color(0xFF1E1E1E))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                PostThumbnail(
                                    urls = media.thumbnailUrls,
                                    ext = media.ext,
                                    isSpoiler = media.isSpoiler,
                                    labelSize = 10.sp,
                                    background = Color(0xFF2A2A2A),
                                    modifier = Modifier.size(72.dp),
                                    onClick = {
                                        mediaOpenedFromGallery = true
                                        selectedMediaIndex = mediaIndex
                                        showGalleryList = false
                                    }
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Text("No. ${media.postNo}", color = Color.White, fontWeight = FontWeight.Bold)
                                    Text(media.fileName, color = Color(0xFFD6D6D6), fontSize = 12.sp)
                                    val meta = listOfNotNull(
                                        media.formatLabel.takeIf { it.isNotBlank() },
                                        media.fileSizeLabel.takeIf { it.isNotBlank() }
                                    ).joinToString(" | ")
                                    if (meta.isNotBlank()) {
                                        Text(meta, color = Color(0xFF9A9A9A), fontSize = 11.sp)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(
                                            onClick = {
                                                mediaOpenedFromGallery = true
                                                selectedMediaIndex = mediaIndex
                                                showGalleryList = false
                                            }
                                        ) {
                                            Text("Open", color = Color.White)
                                        }
                                        TextButton(
                                            onClick = {
                                                showGalleryList = false
                                                jumpToPostInThread(media.postNo)
                                            }
                                        ) {
                                            Text("Jump to post", color = LoadingGreen)
                                        }
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
            mediaOpenedFromGallery = false
        } else {
            val closeMediaViewer = {
                selectedMediaIndex = -1
                if (mediaOpenedFromGallery) {
                    galleryScrollTargetPostNo = currentMedia.postNo
                    showGalleryList = true
                }
                mediaOpenedFromGallery = false
            }
            var scale by remember(currentMedia.url) { mutableFloatStateOf(1f) }
            var offsetX by remember(currentMedia.url) { mutableFloatStateOf(0f) }
            var offsetY by remember(currentMedia.url) { mutableFloatStateOf(0f) }
            var swipeX by remember(currentMedia.url) { mutableFloatStateOf(0f) }
            var swipeY by remember(currentMedia.url) { mutableFloatStateOf(0f) }
            var swipeAxis by remember(currentMedia.url) { mutableStateOf(SwipeAxis.Undecided) }
            var videoControlsVisible by remember(currentMedia.url) { mutableStateOf(false) }

            BackHandler { closeMediaViewer() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset {
                            val translationX = overlayTranslationX(swipeX).roundToInt()
                            val translationY = (swipeY * OVERLAY_DRAG_DAMP)
                                .coerceIn(-OVERLAY_DRAG_CLAMP, OVERLAY_DRAG_CLAMP)
                                .roundToInt()
                            IntOffset(translationX, translationY)
                        }
                        .pointerInput(currentMedia.url, currentIndex) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val topExclusion = 120.dp.toPx()
                                val bottomExclusion = 170.dp.toPx()
                                val swipeEnabled = down.position.y > topExclusion &&
                                    down.position.y < (size.height - bottomExclusion)
                                var wasMultiTouch = false
                                var accumulatedDrag = Offset.Zero

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break

                                    if (pressed.size >= 2 && currentMedia.kind == MediaKind.Image) {
                                        wasMultiTouch = true
                                        val currentCentroid = Offset(
                                            pressed.map { it.position.x }.average().toFloat(),
                                            pressed.map { it.position.y }.average().toFloat()
                                        )
                                        val previousCentroid = Offset(
                                            pressed.map { it.previousPosition.x }.average().toFloat(),
                                            pressed.map { it.previousPosition.y }.average().toFloat()
                                        )
                                        fun meanRadius(positions: List<Offset>, centroid: Offset): Float =
                                            positions.map { point ->
                                                val dx = point.x - centroid.x
                                                val dy = point.y - centroid.y
                                                sqrt(dx * dx + dy * dy)
                                            }.average().toFloat()

                                        val previousPositions = pressed.map { it.previousPosition }
                                        val currentPositions = pressed.map { it.position }
                                        val oldRadius = meanRadius(previousPositions, previousCentroid)
                                        val newRadius = meanRadius(currentPositions, currentCentroid)
                                        if (oldRadius > 0f && newRadius > 0f) {
                                            val zoomChange = newRadius / oldRadius
                                            val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                                            val appliedZoom = newScale / scale
                                            offsetX = (offsetX + currentCentroid.x - previousCentroid.x +
                                                (currentCentroid.x - size.width / 2f) * (1f - appliedZoom))
                                                .coerceIn(-size.width * (newScale - 1f) / 2f, size.width * (newScale - 1f) / 2f)
                                            offsetY = (offsetY + currentCentroid.y - previousCentroid.y +
                                                (currentCentroid.y - size.height / 2f) * (1f - appliedZoom))
                                                .coerceIn(-size.height * (newScale - 1f) / 2f, size.height * (newScale - 1f) / 2f)
                                            scale = newScale
                                        }
                                        event.changes.forEach { it.consume() }
                                    } else if (pressed.size == 1 && !wasMultiTouch) {
                                        val change = pressed.first()
                                        val dragAmount = change.position - change.previousPosition
                                        if (currentMedia.kind == MediaKind.Image && scale > 1.05f) {
                                            offsetX = (offsetX + dragAmount.x)
                                                .coerceIn(-size.width * (scale - 1f) / 2f, size.width * (scale - 1f) / 2f)
                                            offsetY = (offsetY + dragAmount.y)
                                                .coerceIn(-size.height * (scale - 1f) / 2f, size.height * (scale - 1f) / 2f)
                                            change.consume()
                                        } else if (swipeEnabled &&
                                            (currentMedia.kind == MediaKind.Video || scale <= 1.05f)
                                        ) {
                                            accumulatedDrag += dragAmount
                                            if (swipeAxis == SwipeAxis.Undecided &&
                                                (abs(accumulatedDrag.x) > 6f || abs(accumulatedDrag.y) > 6f)
                                            ) {
                                                swipeAxis = if (abs(accumulatedDrag.x) >= abs(accumulatedDrag.y)) {
                                                    SwipeAxis.Horizontal
                                                } else {
                                                    SwipeAxis.Vertical
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
                                        }
                                    }
                                }

                                if (!wasMultiTouch && swipeEnabled) {
                                    when (swipeAxis) {
                                        SwipeAxis.Horizontal -> if (swipeX > GALLERY_CLOSE_THRESHOLD) {
                                            closeMediaViewer()
                                        }
                                        SwipeAxis.Vertical -> if (abs(swipeY) > GALLERY_NAV_THRESHOLD) {
                                            if (swipeY < 0f && currentIndex < mediaItems.lastIndex) currentIndex += 1
                                            else if (swipeY > 0f && currentIndex > 0) currentIndex -= 1
                                        }
                                        SwipeAxis.Undecided -> Unit
                                    }
                                }
                                swipeX = 0f
                                swipeY = 0f
                                swipeAxis = SwipeAxis.Undecided
                            }
                        }
                ) {
                    if (currentMedia.kind == MediaKind.Video) {
                        WebmPlayer(
                            url = currentMedia.url,
                            modifier = Modifier.fillMaxSize(),
                            onControlsVisibilityChange = { videoControlsVisible = it }
                        )
                    } else {
                        var fullImageLoaded by remember(currentMedia.url) { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clipToBounds()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offsetX,
                                    translationY = offsetY
                                )
                        ) {
                            // The (already cached) thumbnail stands in until the full image arrives.
                            val placeholder = currentMedia.thumbnailUrls.firstOrNull()
                            if (!fullImageLoaded && placeholder != null) {
                                AsyncImage(
                                    model = placeholder,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }
                            AsyncImage(
                                model = currentMedia.url,
                                contentDescription = null,
                                onSuccess = { fullImageLoaded = true },
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                        if (!fullImageLoaded) {
                            CircularProgressIndicator(
                                color = LoadingGreen,
                                strokeWidth = 2.dp,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(28.dp)
                            )
                        }
                    }
                }

                // While the video's own controls are up, our buttons step aside (and come
                // back when the player hides its controls after a few seconds).
                val showViewerButtons = currentMedia.kind != MediaKind.Video || !videoControlsVisible

                if (showViewerButtons && currentIndex > 0) {
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

                if (showViewerButtons && currentIndex < mediaItems.lastIndex) {
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

                if (showViewerButtons) FlowRow(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (currentMedia.kind == MediaKind.Video) {
                        BottomOverlayButton(text = "Open", onClick = { openExternalLink(currentMedia.url) })
                    }
                    BottomOverlayButton(text = "Save", onClick = {
                        pendingSave = currentMedia
                        saveMediaLauncher.launch(currentMedia.fileName)
                    })
                    BottomOverlayButton(text = "Post", onClick = {
                        selectedMediaIndex = -1
                        mediaOpenedFromGallery = false
                        showGalleryList = false
                        jumpToPostInThread(currentMedia.postNo)
                    })
                    BottomOverlayButton(text = "Gallery", onClick = {
                        val matchingFilter = when (currentMedia.kind) {
                            MediaKind.Image -> GalleryFilter.Images
                            MediaKind.Video -> GalleryFilter.Videos
                        }
                        if (galleryFilter != GalleryFilter.All && galleryFilter != matchingFilter) {
                            galleryFilter = GalleryFilter.All
                        }
                        galleryScrollTargetPostNo = currentMedia.postNo
                        selectedMediaIndex = -1
                        mediaOpenedFromGallery = false
                        showGalleryList = true
                    })
                    BottomOverlayButton(text = "Close", onClick = { closeMediaViewer() })
                }
            }
        }
    }

    replyComposerTargetNo?.let { replyNo ->
        ReplyComposerOverlay(
            board = board,
            threadNo = threadNo,
            replyToNo = replyNo.takeIf { it > 0L },
            onClose = { replyComposerTargetNo = null },
            onPosted = {
                replyComposerTargetNo = null
                scrollToLatestAfterLoad = true
                vm.load(forceRefresh = true)
            }
        )
    }
}
