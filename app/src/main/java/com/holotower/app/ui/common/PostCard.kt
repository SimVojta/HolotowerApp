package com.holotower.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            val formattedTime = remember(post.time) { post.formattedTime() }
            val fileMeta = remember(post.ext, post.fsize) { buildFileMeta(post) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = post.name ?: "Anonymous",
                    color = Color(0xFF117743),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = formattedTime,
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
                val thumbUrls = remember(post.tim, post.ext, post.spoiler, board) { post.thumbnailUrls(board) }
                val fullImageUrl = remember(post.tim, post.ext, board) { post.fullImageUrl(board) }
                val suggestedName = remember(post.filename, post.tim, post.ext) { suggestImageFileName(post) }

                if (fullImageUrl != null) {
                    PostThumbnail(
                        urls = thumbUrls,
                        ext = post.ext,
                        isSpoiler = post.isSpoiler,
                        modifier = Modifier
                            .size(100.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        onClick = { onImageClick(post.no, fullImageUrl, suggestedName) }
                    )
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
