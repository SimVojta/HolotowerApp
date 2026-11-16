package com.holotower.app.ui.catalog

import android.content.Context
import android.text.Html
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.holotower.app.R
import com.holotower.app.data.local.ThreadSeenStore
import com.holotower.app.data.model.CatalogThread
import com.holotower.app.data.network.AppForeground
import com.holotower.app.ui.common.PostThumbnail
import com.holotower.app.viewmodel.CatalogUiState
import com.holotower.app.viewmodel.CatalogViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class CatalogSortMode(val label: String) {
    BumpOrder("Bump order"),
    MostReplies("Most replies"),
    MostImages("Most images"),
    Newest("Newest"),
    Oldest("Oldest")
}

private data class CatalogFilters(
    val query: String,
    val sort: CatalogSortMode,
    val onlyWithImages: Boolean,
    val hideSticky: Boolean,
    val hideLocked: Boolean
)

private const val CATALOG_SWIPE_THRESHOLD = 90f
private const val OVERLAY_DRAG_DAMP = 0.45f
private const val OVERLAY_DRAG_CLAMP = 220f
private val Accent = Color(0xFF00FF9F)

private fun plainText(thread: CatalogThread): String =
    Html.fromHtml(
        listOfNotNull(thread.sub, thread.com).joinToString("\n"),
        Html.FROM_HTML_MODE_COMPACT
    ).toString().trim()

private fun applyFilters(
    threads: List<CatalogThread>,
    searchText: Map<Long, String>,
    filters: CatalogFilters
): List<CatalogThread> {
    val query = filters.query.trim().lowercase()
    val filtered = threads.filter { thread ->
        (!filters.onlyWithImages || thread.imageReplyCount > 0 || thread.tim != null) &&
            (!filters.hideSticky || !thread.isSticky) &&
            (!filters.hideLocked || !thread.isLocked) &&
            (query.isBlank() ||
                searchText[thread.no]?.contains(query) == true ||
                thread.no.toString().contains(query))
    }
    return when (filters.sort) {
        CatalogSortMode.BumpOrder -> filtered.sortedByDescending { it.lastModified }
        CatalogSortMode.MostReplies -> filtered.sortedWith(
            compareByDescending<CatalogThread> { it.replyCount }.thenByDescending { it.lastModified }
        )
        CatalogSortMode.MostImages -> filtered.sortedWith(
            compareByDescending<CatalogThread> { it.imageReplyCount }.thenByDescending { it.lastModified }
        )
        CatalogSortMode.Newest -> filtered.sortedByDescending { it.time }
        CatalogSortMode.Oldest -> filtered.sortedBy { it.time }
    }
}

@OptIn(ExperimentalMaterialApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    board: String = "hlgg",
    onThreadClick: (Long) -> Unit,
    onNewThreadSwipe: () -> Unit = {},
    onGlobalEntryClick: () -> Unit = {},
    isCurrentDestination: Boolean = true,
    vm: CatalogViewModel = viewModel()
) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("catalog_filters", Context.MODE_PRIVATE)
    }
    val prefKeyPrefix = remember(board) { "catalog_${board}_" }
    val state by vm.state.collectAsState()
    val isRefreshing = state is CatalogUiState.Loading
    val threads = state.threads

    val savedQuery = remember(board) { prefs.getString("${prefKeyPrefix}query", "").orEmpty() }
    var showSearch by remember(board) { mutableStateOf(savedQuery.isNotBlank()) }
    var searchQuery by remember(board) { mutableStateOf(savedQuery) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    var sortMode by remember(board) {
        mutableStateOf(
            runCatching {
                CatalogSortMode.valueOf(
                    prefs.getString("${prefKeyPrefix}sort", CatalogSortMode.BumpOrder.name)
                        ?: CatalogSortMode.BumpOrder.name
                )
            }.getOrDefault(CatalogSortMode.BumpOrder)
        )
    }
    var onlyWithImages by remember(board) { mutableStateOf(prefs.getBoolean("${prefKeyPrefix}only_images", false)) }
    var hideSticky by remember(board) { mutableStateOf(prefs.getBoolean("${prefKeyPrefix}hide_sticky", false)) }
    var hideLocked by remember(board) { mutableStateOf(prefs.getBoolean("${prefKeyPrefix}hide_locked", false)) }
    val filters = CatalogFilters(searchQuery, sortMode, onlyWithImages, hideSticky, hideLocked)

    LaunchedEffect(filters, board) {
        prefs.edit()
            .putString("${prefKeyPrefix}query", searchQuery)
            .putString("${prefKeyPrefix}sort", sortMode.name)
            .putBoolean("${prefKeyPrefix}only_images", onlyWithImages)
            .putBoolean("${prefKeyPrefix}hide_sticky", hideSticky)
            .putBoolean("${prefKeyPrefix}hide_locked", hideLocked)
            .apply()
    }

    // Plain text of every thread, built once per catalog load and only while searching.
    val searching = searchQuery.isNotBlank()
    val searchText = remember(threads, searching) {
        if (!searching) emptyMap() else threads.orEmpty().associate { it.no to plainText(it).lowercase() }
    }
    val visibleThreads = remember(threads, searchText, filters) {
        threads?.let { applyFilters(it, searchText, filters) }
    }
    val gridState = rememberLazyGridState()

    // Reply counts seen when each thread was last opened, for the "+N" badge.
    var seenReplies by remember(board) { mutableStateOf(emptyMap<Long, Int>()) }
    LaunchedEffect(board, isCurrentDestination, state is CatalogUiState.Success) {
        if (!isCurrentDestination) return@LaunchedEffect
        val success = state as? CatalogUiState.Success
        seenReplies = withContext(Dispatchers.IO) {
            if (success != null) {
                ThreadSeenStore.prune(context, board, success.threads.mapTo(HashSet()) { it.no })
            }
            ThreadSeenStore.load(context, board)
        }
    }

    LaunchedEffect(board, isCurrentDestination) {
        // Every return to the catalog: Wi-Fi downloads; mobile data reuses a list under
        // 10 minutes old (see CatalogViewModel.load). Pull-to-refresh always downloads.
        if (isCurrentDestination) vm.load(boardName = board)
    }

    // Coming back to the app after a while: refresh in place, keeping the grid visible.
    val returnToken by AppForeground.returnsAfterIdle.collectAsState()
    var handledReturnToken by rememberSaveable { mutableIntStateOf(returnToken) }
    LaunchedEffect(returnToken, isCurrentDestination) {
        if (isCurrentDestination && returnToken != handledReturnToken) {
            handledReturnToken = returnToken
            vm.load(boardName = board)
        }
    }

    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = { vm.load(boardName = board, forceRefresh = true) }
    )
    var swipeX by remember { mutableStateOf(0f) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.clickable(onClick = onGlobalEntryClick)) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_holo_logo),
                                contentDescription = "Global Entry",
                                tint = Color.Unspecified
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("/$board/ - HoloTower", color = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        showSearch = !showSearch
                        if (!showSearch) searchQuery = ""
                    }) {
                        Icon(
                            if (showSearch) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (showSearch) "Close search" else "Search",
                            tint = Color.White
                        )
                    }
                    Box {
                        IconButton(onClick = { showOptionsMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Sort and filter", tint = Color.White)
                        }
                        DropdownMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false }
                        ) {
                            CatalogSortMode.entries.forEach { mode ->
                                MenuOption(mode.label, checked = sortMode == mode) {
                                    sortMode = mode
                                    showOptionsMenu = false
                                }
                            }
                            HorizontalDivider()
                            MenuOption("Only with images", checked = onlyWithImages) { onlyWithImages = !onlyWithImages }
                            MenuOption("Hide sticky", checked = hideSticky) { hideSticky = !hideSticky }
                            MenuOption("Hide locked", checked = hideLocked) { hideLocked = !hideLocked }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF111111))
            )
        },
        containerColor = Color(0xFF0A0A0A)
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .offset {
                    val clamped = swipeX.coerceIn(-OVERLAY_DRAG_CLAMP, 0f)
                    IntOffset((clamped * OVERLAY_DRAG_DAMP).roundToInt(), 0)
                }
                .pullRefresh(pullRefreshState)
                .pointerInput(onNewThreadSwipe) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            if (dragAmount < 0f || swipeX < 0f) {
                                swipeX = (swipeX + dragAmount).coerceIn(-OVERLAY_DRAG_CLAMP, 0f)
                                change.consume()
                            }
                        },
                        onDragEnd = {
                            if (swipeX <= -CATALOG_SWIPE_THRESHOLD) onNewThreadSwipe()
                            swipeX = 0f
                        },
                        onDragCancel = { swipeX = 0f }
                    )
                }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (showSearch) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search catalog", color = Color(0xFFBDBDBD)) },
                        singleLine = true,
                        textStyle = TextStyle(color = Color(0xFFEAEAEA)),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFFEAEAEA),
                            unfocusedTextColor = Color(0xFFEAEAEA),
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Color(0xFF2A2F35),
                            focusedLabelColor = Color(0xFFBFC5CC),
                            unfocusedLabelColor = Color(0xFF9CA3AF),
                            cursorColor = Accent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }

                (state as? CatalogUiState.Error)?.takeIf { threads != null }?.let { error ->
                    ErrorBanner(error.message) { vm.load(boardName = board, forceRefresh = true) }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    val shown = visibleThreads
                    when {
                        shown != null -> CatalogGrid(shown, board, seenReplies, gridState, onThreadClick)
                        state is CatalogUiState.Error -> Column(
                            Modifier.align(Alignment.Center).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text((state as CatalogUiState.Error).message, color = Color(0xFFFF6A6A))
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = { vm.load(boardName = board, forceRefresh = true) }) {
                                Text("Retry")
                            }
                        }
                        else -> CircularProgressIndicator(color = Accent, modifier = Modifier.align(Alignment.Center))
                    }
                }
            }

            if (isRefreshing && threads != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = if (showSearch) 56.dp else 6.dp)
                        .background(Color(0xCC111111), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_holo_logo),
                        contentDescription = "Refreshing",
                        tint = Color.Unspecified
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuOption(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = {
            if (checked) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Accent)
            } else {
                Spacer(modifier = Modifier.size(24.dp))
            }
        },
        onClick = onClick
    )
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xEE1A1111))
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Refresh failed: $message",
            color = Color(0xFFFFD6D6),
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRetry) { Text("Retry", color = Accent) }
    }
}

@Composable
private fun CatalogGrid(
    threads: List<CatalogThread>,
    board: String,
    seenReplies: Map<Long, Int>,
    gridState: LazyGridState,
    onThreadClick: (Long) -> Unit
) {
    if (threads.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No threads match current filters", color = Color(0xFFBDBDBD))
        }
        return
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 165.dp),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(threads, key = { it.no }) { thread ->
            val seen = seenReplies[thread.no]
            val newReplies = if (seen != null) (thread.replyCount - seen).coerceAtLeast(0) else 0
            CatalogCard(thread, board, newReplies, onThreadClick)
        }
    }
}

@Composable
fun CatalogCard(thread: CatalogThread, board: String, newReplies: Int = 0, onClick: (Long) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(thread.no) },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(6.dp)
    ) {
        Column {
            Box {
                val thumbUrls = remember(thread.no, thread.tim, thread.ext, board) { thread.thumbnailUrls(board) }
                PostThumbnail(
                    urls = thumbUrls,
                    ext = thread.ext,
                    isSpoiler = thread.isSpoiler,
                    labelSize = 12.sp,
                    background = Color.Black,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (thread.isSticky) Badge("STICKY", Color(0xFF2E5BFF))
                    if (thread.isLocked) Badge("LOCKED", Color(0xFF8A5A00), Icons.Filled.Lock)
                }
                if (newReplies > 0) {
                    Badge(
                        "+$newReplies",
                        Color(0xFF007A4D),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                    )
                }
            }

            Column(Modifier.padding(10.dp)) {
                Text(
                    "R:${thread.replyCount}  I:${thread.imageReplyCount}",
                    color = Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))

                val plain = remember(thread.no, thread.sub, thread.com) {
                    plainText(thread).ifBlank { "Thread #${thread.no}" }
                }

                Text(
                    plain,
                    color = Color(0xFFF0F0F0),
                    fontSize = 13.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun Badge(
    text: String,
    color: Color,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(color.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
            Spacer(modifier = Modifier.width(2.dp))
        }
        Text(text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
