package com.holotower.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import coil3.compose.AsyncImage
import com.holotower.app.data.model.ThumbnailResolver

/**
 * A thumbnail that tries each candidate URL in turn. Shows "SPOILER" for spoilered files
 * and "No preview" when no candidate loads (deleted file, offline, ...).
 */
@Composable
fun PostThumbnail(
    urls: List<String>,
    ext: String?,
    isSpoiler: Boolean,
    modifier: Modifier = Modifier,
    labelSize: TextUnit = 11.sp,
    background: Color = Color(0xFF272727),
    onClick: (() -> Unit)? = null
) {
    var index by remember(urls) { mutableIntStateOf(0) }
    val url = urls.getOrNull(index)
    Box(
        modifier = modifier
            .background(background)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                onSuccess = { ThumbnailResolver.onLoaded(url, ext) },
                onError = { index += 1 },
                alignment = Alignment.TopCenter,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (isSpoiler) {
            Text("SPOILER", color = Color(0xFFFF5A5A), fontSize = labelSize, fontWeight = FontWeight.Bold)
        } else {
            Text("No preview", color = Color(0xFF8A8A8A), fontSize = labelSize, textAlign = TextAlign.Center)
        }
    }
}
