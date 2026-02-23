package com.holotower.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.holotower.app.data.model.Post

@Composable
fun PostCard(
    post: Post,
    board: String,
    modifier: Modifier = Modifier,
    referencedBy: List<Long> = emptyList(),
    onBodyReplyClick: (postNo: Long, threadNoHint: Long?) -> Unit = { _, _ -> },
    onBacklinkClick: (Long) -> Unit = {},
    onImageClick: (postNo: Long, imageUrl: String, fileName: String) -> Unit = { _, _, _ -> },
    onExternalLinkClick: (url: String) -> Unit = {},
    onPostNumberClick: (Long) -> Unit = {}
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(4.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = post.name ?: "Anonymous",
                    color = Color(0xFF117743),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = post.formattedTime(),
                    color = Color(0xFFBDBDBD),
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "No. ${post.no}",
                    color = Color(0xFFBDBDBD),
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { onPostNumberClick(post.no) }
                )
            }

            val fileMeta = buildFileMeta(post)
            if (fileMeta != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = fileMeta,
                    color = Color(0xFF8F8F8F),
                    fontSize = 11.sp
                )
            }

            if (referencedBy.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                HtmlText(
                    html = referencedBy.joinToString(" ") { "&gt;&gt;$it" },
                    fontSize = 12.sp,
                    textColor = Color(0xFFBFD7FF),
                    replyColor = Color(0xFF3EA6FF),
                    onReplyClick = { postNo, _ -> onBacklinkClick(postNo) }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                val thumbUrls = post.thumbnailUrls(board)
                var thumbIndex by remember(post.no, board) { mutableStateOf(0) }
                var thumbExhausted by remember(post.no, board) { mutableStateOf(false) }
                val thumbUrl = thumbUrls.getOrNull(thumbIndex)
                val fullImageUrl = post.fullImageUrl(board)
                val suggestedName = suggestImageFileName(post)

                if (thumbUrl != null && !thumbExhausted) {
                    AsyncImage(
                        model = thumbUrl,
                        contentDescription = null,
                        onError = {
                            if (thumbIndex < thumbUrls.lastIndex) {
                                thumbIndex += 1
                            } else {
                                thumbExhausted = true
                            }
                        },
                        alignment = Alignment.TopCenter,
                        modifier = Modifier
                            .size(100.dp)
                            .background(Color(0xFF333333), RoundedCornerShape(4.dp))
                            .let {
                                if (fullImageUrl != null) {
                                    it.clickable { onImageClick(post.no, fullImageUrl, suggestedName) }
                                } else {
                                    it
                                }
                            },
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else if (fullImageUrl != null) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .background(Color(0xFF272727), RoundedCornerShape(4.dp))
                            .let {
                                it.clickable { onImageClick(post.no, fullImageUrl, suggestedName) }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "SPOILER",
                            color = Color(0xFFFF5A5A),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                HtmlText(
                    html = post.com ?: "",
                    modifier = Modifier.weight(1f),
                    fontSize = 14.sp,
                    textColor = Color(0xFFECECEC),
                    onReplyClick = onBodyReplyClick,
                    onLinkClick = onExternalLinkClick
                )
            }
        }
    }
}

private fun suggestImageFileName(post: Post): String {
    val base = (post.filename?.trim().takeUnless { it.isNullOrBlank() }
        ?: post.tim
        ?: "image").replace(Regex("[^A-Za-z0-9._-]"), "_")

    val ext = post.ext?.takeIf { it.startsWith(".") } ?: ".jpg"
    return "$base$ext"
}

private fun buildFileMeta(post: Post): String? {
    val type = post.ext?.removePrefix(".")?.uppercase().orEmpty()
    val size = post.fileSizeFormatted()
    val parts = listOfNotNull(
        type.takeIf { it.isNotBlank() },
        size.takeIf { it.isNotBlank() }
    )
    return if (parts.isEmpty()) null else parts.joinToString(" | ")
}
