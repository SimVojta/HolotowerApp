package com.holotower.app.ui.common

import android.text.Html
import android.text.Spanned
import android.text.style.URLSpan
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.TextUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

private const val REPLY_TAG = "reply_target"
private const val URL_TAG = "external_url"
private val replyRegex = Regex(">>(\\d+)")
private val urlRegex = Regex("""(?i)\bhttps?://[A-Za-z0-9./?&_#%:=+\-~;,]+""")
private val hrefThreadRegex = Regex("/[^/]+/res/(\\d+)\\.html", RegexOption.IGNORE_CASE)
private val anchorRegex = Regex("""(?is)<a[^>]*href=['\"]([^'\"]+)['\"][^>]*>(.*?)</a>""")

private data class HtmlCacheKey(
    val html: String,
    val quoteColor: Color,
    val replyColor: Color,
    val linkColor: Color
)

private data class HtmlCacheEntry(val text: AnnotatedString, val weight: Int)

private object ParsedHtmlCache {
    private const val MAX_ENTRIES = 128
    private const val MAX_WEIGHT = 350_000
    private val entries = LinkedHashMap<HtmlCacheKey, HtmlCacheEntry>(16, 0.75f, true)
    private var currentWeight = 0

    @Synchronized
    fun get(key: HtmlCacheKey): AnnotatedString? = entries[key]?.text

    @Synchronized
    fun put(key: HtmlCacheKey, text: AnnotatedString) {
        val weight = key.html.length + text.length * 2
        if (weight > MAX_WEIGHT) return
        entries.put(key, HtmlCacheEntry(text, weight))?.let { currentWeight -= it.weight }
        currentWeight += weight
        while (entries.size > MAX_ENTRIES || currentWeight > MAX_WEIGHT) {
            val eldest = entries.entries.iterator()
            if (!eldest.hasNext()) break
            currentWeight -= eldest.next().value.weight
            eldest.remove()
        }
    }
}

@Composable
fun HtmlText(
    html: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    textColor: Color = Color(0xFFECECEC),
    quoteColor: Color = Color(0xFF789922),
    replyColor: Color = Color(0xFFDD4444),
    linkColor: Color = Color(0xFF6CB6FF),
    onReplyClick: ((postNo: Long, threadNoHint: Long?) -> Unit)? = null,
    onLinkClick: ((url: String) -> Unit)? = null
) {
    val annotated by produceState(
        initialValue = AnnotatedString(""),
        key1 = html,
        key2 = quoteColor,
        key3 = replyColor to linkColor
    ) {
        value = withContext(Dispatchers.Default) {
            rememberHtmlAnnotatedString(html, quoteColor, replyColor, linkColor)
        }
    }

    ClickableText(
        text = annotated,
        modifier = modifier,
        style = LocalTextStyle.current.merge(
            TextStyle(
                fontSize = fontSize,
                color = textColor
            )
        )
    ) { offset ->
        val target = annotated
            .getStringAnnotations(REPLY_TAG, offset, offset)
            .firstOrNull()
            ?.item
        if (target != null) {
            val parts = target.split('|', limit = 2)
            val postNo = parts.getOrNull(0)?.toLongOrNull() ?: return@ClickableText
            val threadNo = parts.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?.toLongOrNull()

            onReplyClick?.invoke(postNo, threadNo)
            return@ClickableText
        }

        val url = annotated
            .getStringAnnotations(URL_TAG, offset, offset)
            .firstOrNull()
            ?.item
            ?: return@ClickableText

        onLinkClick?.invoke(url)
    }
}

fun rememberHtmlAnnotatedString(
    html: String,
    quoteColor: Color,
    replyColor: Color,
    linkColor: Color
): AnnotatedString {
    val cacheKey = HtmlCacheKey(html, quoteColor, replyColor, linkColor)
    ParsedHtmlCache.get(cacheKey)?.let { return it }

    val threadHints = buildThreadHintQueues(html)
    val spanned = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
    val plain = spanned
        .toString()
        .replace('\u00A0', ' ')

    val base = buildAnnotatedString {
        val lines = plain.lines()
        lines.forEachIndexed { idx, line ->
            val lineStyle = when {
                line.startsWith(">>") -> null
                line.startsWith(">") -> SpanStyle(color = quoteColor, fontStyle = FontStyle.Italic)
                else -> null
            }

            var cursor = 0
            for (match in replyRegex.findAll(line)) {
                val start = match.range.first
                val endExclusive = match.range.last + 1

                if (start > cursor) {
                    appendWithStyle(line.substring(cursor, start), lineStyle)
                }

                val token = line.substring(start, endExclusive)
                val postNo = match.groupValues[1].toLongOrNull()
                val threadNoHint = postNo?.let { no ->
                    threadHints[no]?.removeFirstOrNull()
                }

                val annotation = buildString {
                    append(postNo ?: "")
                    append('|')
                    append(threadNoHint ?: "")
                }

                pushStringAnnotation(REPLY_TAG, annotation)
                appendWithStyle(
                    token,
                    (lineStyle ?: SpanStyle()).merge(
                        SpanStyle(color = replyColor, fontStyle = FontStyle.Normal)
                    )
                )
                pop()

                cursor = endExclusive
            }

            if (cursor < line.length) {
                appendWithStyle(line.substring(cursor), lineStyle)
            }

            if (idx != lines.lastIndex) {
                append("\n")
            }
        }
    }

    val builder = AnnotatedString.Builder(base)
    val urlRanges = mutableListOf<Triple<Int, Int, String>>()

    if (spanned is Spanned) {
        val spans = spanned.getSpans(0, spanned.length, URLSpan::class.java)
        spans.forEach { span ->
            val start = spanned.getSpanStart(span)
            val end = spanned.getSpanEnd(span)
            val url = span.url
            if (start >= 0 && end > start && !url.isNullOrBlank()) {
                urlRanges += Triple(start, end, url)
            }
        }
    }

    urlRegex.findAll(plain).forEach { match ->
        val start = match.range.first
        val end = match.range.last + 1
        urlRanges += Triple(start, end, match.value)
    }

    urlRanges.forEach { (start, end, url) ->
        val safeStart = start.coerceAtLeast(0).coerceAtMost(plain.length)
        val safeEnd = end.coerceAtLeast(safeStart).coerceAtMost(plain.length)
        if (safeStart < safeEnd) {
            builder.addStringAnnotation(URL_TAG, url, safeStart, safeEnd)
            builder.addStyle(
                SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                safeStart,
                safeEnd
            )
        }
    }

    return builder.toAnnotatedString().also { ParsedHtmlCache.put(cacheKey, it) }
}

private fun buildThreadHintQueues(html: String): MutableMap<Long, ArrayDeque<Long?>> {
    val result = mutableMapOf<Long, ArrayDeque<Long?>>()

    anchorRegex.findAll(html).forEach { match ->
        val href = match.groupValues[1]
        val threadNo = hrefThreadRegex.find(href)?.groupValues?.getOrNull(1)?.toLongOrNull()
        val anchorText = Html.fromHtml(match.groupValues[2], Html.FROM_HTML_MODE_COMPACT).toString()

        replyRegex.findAll(anchorText).forEach { replyMatch ->
            val postNo = replyMatch.groupValues[1].toLongOrNull() ?: return@forEach
            result.getOrPut(postNo) { ArrayDeque() }.addLast(threadNo)
        }
    }

    return result
}

private fun AnnotatedString.Builder.appendWithStyle(text: String, style: SpanStyle?) {
    if (text.isEmpty()) return

    if (style != null) {
        pushStyle(style)
        append(text)
        pop()
    } else {
        append(text)
    }
}
