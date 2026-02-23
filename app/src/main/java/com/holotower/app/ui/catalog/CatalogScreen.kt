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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.holotower.app.R
import com.holotower.app.data.model.CatalogThread
import com.holotower.app.viewmodel.CatalogUiState
import com.holotower.app.viewmodel.CatalogViewModel
import kotlinx.coroutines.delay

private enum class CatalogSortMode {
    BumpOrder,
    MostReplies,
    MostImages,
    Newest,
    Oldest
}

private const val CATALOG_SWIPE_THRESHOLD = 90f
private const val OVERLAY_DRAG_DAMP = 0.45f
private const val OVERLAY_DRAG_CLAMP = 220f

@OptIn(ExperimentalMaterialApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    board: String = "hlgg",
    onThreadClick: (Long) -> Unit,
    onNewThreadSwipe: () -> Unit = {},
    onGlobalEntryClick: () -> Unit = {},
    onRefreshCloudflare: (() -> Unit)? = null,
    vm: CatalogViewModel = viewModel()
) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("catalog_filters", Context.MODE_PRIVATE)
    }
    val prefKeyPrefix = remember(board) { "catalog_${board}_" }
    val state by vm.state.collectAsState()
    val isRefreshing = state is CatalogUiState.Loading
    val successThreads = (state as? CatalogUiState.Success)?.threads
    var cachedThreads by remember { mutableStateOf<List<CatalogThread>>(emptyList()) }
    LaunchedEffect(successThreads) {
        if (successThreads != null) cachedThreads = successThreads
    }

    val isBlockingLoad = isRefreshing && cachedThreads.isEmpty()
    var showCloudflareRefreshButton by remember { mutableStateOf(false) }
    LaunchedEffect(isBlockingLoad) {
        showCloudflareRefreshButton = false
        if (isBlockingLoad) {
            delay(2000)
            showCloudflareRefreshButton = true
        }
    }

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

    val showTopRefreshBadge = isRefreshing && cachedThreads.isNotEmpty()
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = { vm.load(boardName = board, forceRefresh = true) }
    )

    LaunchedEffect(searchQuery, sortMode, onlyWithImages, hideSticky, hideLocked, board) {
        prefs.edit()
            .putString("${prefKeyPrefix}query", searchQuery)
            .putString("${prefKeyPrefix}sort", sortMode.name)
            .putBoolean("${prefKeyPrefix}only_images", onlyWithImages)
            .putBoolean("${prefKeyPrefix}hide_sticky", hideSticky)
            .putBoolean("${prefKeyPrefix}hide_locked", hideLocked)
            .apply()
    }

    fun applyCatalogViewOptions(threads: List<CatalogThread>): List<CatalogThread> {
        val query = searchQuery.trim().lowercase()
        val filtered = threads.asSequence()
            .filter { if (onlyWithImages) it.images > 0 else true }
            .filter { if (hideSticky) !it.isSticky else true }
            .filter { if (hideLocked) !it.isLocked else true }
            .filter { thread ->
                if (query.isBlank()) return@filter true
                val cleanText = Html.fromHtml(
                    "${thread.sub.orEmpty()}\n${thread.com.orEmpty()}",
                    Html.FROM_HTML_MODE_COMPACT
                ).toString().lowercase()
                cleanText.contains(query) || thread.no.toString().contains(query)
            }
            .toList()

        return when (sortMode) {
            CatalogSortMode.BumpOrder -> filtered.sortedByDescending { it.lastModified }
            CatalogSortMode.MostReplies -> filtered.sortedWith(
                compareByDescending<CatalogThread> { it.replies }.thenByDescending { it.lastModified }
            )
            CatalogSortMode.MostImages -> filtered.sortedWith(
                compareByDescending<CatalogThread> { it.images }.thenByDescending { it.lastModified }
            )
            CatalogSortMode.Newest -> filtered.sortedByDescending { it.time }
            CatalogSortMode.Oldest -> filtered.sortedBy { it.time }
        }
    }

    LaunchedEffect(board) {
        vm.load(boardName = board)
    }

    var swipeX by remember { mutableStateOf(0f) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.clickable(onClick = onGlobalEntryClick)
                        ) {
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
                    TextButton(
                        onClick = {
                            showSearch = !showSearch
                            if (!showSearch) searchQuery = ""
                        }
                    ) {
                        Text(if (showSearch) "Hide" else "Search", color = Color.White, fontSize = 13.sp)
                    }
                    Box {
                        TextButton(onClick = { showOptionsMenu = true }) {
                            Text("Sort/Filter", color = Color.White, fontSize = 13.sp)
                        }
                        DropdownMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text((if (sortMode == CatalogSortMode.BumpOrder) "[x] " else "[ ] ") + "Bump order") },
                                onClick = {
                                    sortMode = CatalogSortMode.BumpOrder
                                    showOptionsMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text((if (sortMode == CatalogSortMode.MostReplies) "[x] " else "[ ] ") + "Most replies") },
                                onClick = {
                                    sortMode = CatalogSortMode.MostReplies
                                    showOptionsMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text((if (sortMode == CatalogSortMode.MostImages) "[x] " else "[ ] ") + "Most images") },
                                onClick = {
                                    sortMode = CatalogSortMode.MostImages
                                    showOptionsMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text((if (sortMode == CatalogSortMode.Newest) "[x] " else "[ ] ") + "Newest") },
                                onClick = {
                                    sortMode = CatalogSortMode.Newest
                                    showOptionsMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text((if (sortMode == CatalogSortMode.Oldest) "[x] " else "[ ] ") + "Oldest") },
                                onClick = {
                                    sortMode = CatalogSortMode.Oldest
                                    showOptionsMenu = false
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text((if (onlyWithImages) "[x] " else "[ ] ") + "Only with images") },
                                onClick = { onlyWithImages = !onlyWithImages }
                            )
                            DropdownMenuItem(
                                text = { Text((if (hideSticky) "[x] " else "[ ] ") + "Hide sticky") },
                                onClick = { hideSticky = !hideSticky }
                            )
                            DropdownMenuItem(
                                text = { Text((if (hideLocked) "[x] " else "[ ] ") + "Hide locked") },
                                onClick = { hideLocked = !hideLocked }
                            )
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
                .graphicsLayer {
                    val clamped = swipeX.coerceIn(-OVERLAY_DRAG_CLAMP, 0f)
                    translationX = (clamped * OVERLAY_DRAG_DAMP).coerceIn(-OVERLAY_DRAG_CLAMP, OVERLAY_DRAG_CLAMP)
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
                            focusedBorderColor = Color(0xFF00FF9F),
                            unfocusedBorderColor = Color(0xFF2A2F35),
                            focusedLabelColor = Color(0xFFBFC5CC),
                            unfocusedLabelColor = Color(0xFF9CA3AF),
                            cursorColor = Color(0xFF00FF9F)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (val s = state) {
                        is CatalogUiState.Loading -> {
                            if (cachedThreads.isEmpty()) {
                                Column(
                                    modifier = Modifier.align(Alignment.Center),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    CircularProgressIndicator(color = Color(0xFF00FF9F))
                                    if (showCloudflareRefreshButton) {
                                        Spacer(modifier = Modifier.height(14.dp))
                                        Button(
                                            onClick = {
                                                onRefreshCloudflare?.invoke()
                                                    ?: vm.load(boardName = board, forceRefresh = true)
                                            }
                                        ) {
                                            Text("Refresh for Cloudflare")
                                        }
                                    }
                                }
                            } else {
                                CatalogGrid(applyCatalogViewOptions(cachedThreads), board, onThreadClick)
                            }
                        }

                        is CatalogUiState.Error -> {
                            Column(
                                Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("Error: ${s.message}", color = Color.Red)
                                Button(onClick = { vm.load(boardName = board, forceRefresh = true) }) {
                                    Text("Retry")
                                }
                            }
                        }

                        is CatalogUiState.Success -> {
                            CatalogGrid(applyCatalogViewOptions(s.threads), board, onThreadClick)
                        }
                    }
                }
            }

            if (showTopRefreshBadge) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = if (showSearch) 56.dp else 6.dp)
                        .background(Color(0xCC111111), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_holo_logo),
                        contentDescription = null,
                        tint = Color.Unspecified
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogGrid(
    threads: List<CatalogThread>,
    board: String,
    onThreadClick: (Long) -> Unit
) {
    if (threads.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No threads match current filters", color = Color(0xFFBDBDBD))
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(threads) { thread ->
            CatalogCard(thread, board, onThreadClick)
        }
    }
}

@Composable
fun CatalogCard(thread: CatalogThread, board: String, onClick: (Long) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(thread.no) },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(6.dp)
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val thumbUrls = thread.thumbnailUrls(board)
                var thumbIndex by remember(thread.no, board) { mutableStateOf(0) }
                var thumbExhausted by remember(thread.no, board) { mutableStateOf(false) }
                thumbUrls.getOrNull(thumbIndex)?.takeUnless { thumbExhausted }?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        onError = {
                            if (thumbIndex < thumbUrls.lastIndex) {
                                thumbIndex += 1
                            } else {
                                thumbExhausted = true
                            }
                        },
                        alignment = Alignment.TopCenter,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                    )
                } ?: Text("SPOILER", color = Color(0xFFFF5A5A), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Column(Modifier.padding(10.dp)) {
                val totalImages = (thread.images + thread.omittedImages).coerceAtLeast(0)
                Text(
                    "R:${thread.replies}  I:$totalImages",
                    color = Color(0xFF00FF9F),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))

                val plain = remember(thread.no, thread.sub, thread.com) {
                    Html.fromHtml(
                        listOfNotNull(thread.sub, thread.com).joinToString("\n"),
                        Html.FROM_HTML_MODE_COMPACT
                    ).toString().trim().ifBlank { "Thread #${thread.no}" }
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
