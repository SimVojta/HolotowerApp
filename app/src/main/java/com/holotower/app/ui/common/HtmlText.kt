package com.holotower.app.ui.common

import android.graphics.Typeface
import android.text.Editable
import android.text.Html
import android.text.Spannable
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import org.xml.sax.XMLReader
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.TextUnit
import java.util.LinkedHashMap

private const val REPLY_TAG = "reply_target"
private const val URL_TAG = "external_url"
private const val SPOILER_TAG = "spoiler"
private val SpoilerCover = Color(0xFF3A3A3A)
private val SpoilerRevealed = Color(0x22FFFFFF)
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
    // Big enough for a whole long thread (a 1,500-post thread is roughly 1.5M weight).
    private const val MAX_ENTRIES = 5_000
    private const val MAX_WEIGHT = 3_000_000
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
    quoteColor: Color = DefaultQuoteColor,
    replyColor: Color = DefaultReplyColor,
    linkColor: Color = DefaultLinkColor,
    onReplyClick: ((postNo: Long, threadNoHint: Long?) -> Unit)? = null,
    onLinkClick: ((url: String) -> Unit)? = null
) {
    // The text must be there on the first frame. A post drawn empty and filled in a
    // frame later changes height, which makes the list jump while scrolling (most
    // visibly when scrolling up). Threads are parsed ahead with prewarmPostHtml(), so
    // this normally hits the cache; parsing one post here is the rare fallback.
    val annotated = remember(html, quoteColor, replyColor, linkColor) {
        rememberHtmlAnnotatedString(html, quoteColor, replyColor, linkColor)
    }

    // Spoilers stay covered until tapped; each one is keyed by its start offset.
    var revealedSpoilers by remember(html) { mutableStateOf(emptySet<Int>()) }
    val display = remember(annotated, revealedSpoilers) {
        val spoilers = annotated.getStringAnnotations(SPOILER_TAG, 0, annotated.length)
        if (spoilers.isEmpty()) {
            annotated
        } else {
            AnnotatedString.Builder(annotated).apply {
                spoilers.forEach { range ->
                    val style = if (range.start in revealedSpoilers) {
                        SpanStyle(background = SpoilerRevealed)
                    } else {
                        SpanStyle(color = SpoilerCover, background = SpoilerCover)
                    }
                    addStyle(style, range.start, range.end)
                }
            }.toAnnotatedString()
        }
    }

    ClickableText(
        text = display,
        modifier = modifier,
        style = LocalTextStyle.current.merge(
            TextStyle(
                fontSize = fontSize,
                color = textColor
            )
        )
    ) { offset ->
        val hiddenSpoiler = annotated
            .getStringAnnotations(SPOILER_TAG, offset, offset)
            .firstOrNull { it.start !in revealedSpoilers }
        if (hiddenSpoiler != null) {
            revealedSpoilers = revealedSpoilers + hiddenSpoiler.start
            return@ClickableText
        }

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
    val spanned = Html.fromHtml(prepareMarkup(html), Html.FROM_HTML_MODE_COMPACT, null, SpoilerTagHandler)
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

    if (spanned is Spanned) {
        // Keep the post's own formatting (Html.fromHtml found it; the plain text above lost it).
        spanned.getSpans(0, spanned.length, StyleSpan::class.java).forEach { span ->
            val style = when (span.style) {
                Typeface.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                Typeface.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                Typeface.BOLD_ITALIC -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                else -> null
            }
            style?.let { builder.addSafeStyle(it, spanned.getSpanStart(span), spanned.getSpanEnd(span), plain.length) }
        }
        spanned.getSpans(0, spanned.length, StrikethroughSpan::class.java).forEach { span ->
            builder.addSafeStyle(
                SpanStyle(textDecoration = TextDecoration.LineThrough),
                spanned.getSpanStart(span), spanned.getSpanEnd(span), plain.length
            )
        }
        spanned.getSpans(0, spanned.length, SpoilerMark::class.java).forEach { span ->
            val start = spanned.getSpanStart(span).coerceIn(0, plain.length)
            val end = spanned.getSpanEnd(span).coerceIn(start, plain.length)
            if (start < end) builder.addStringAnnotation(SPOILER_TAG, start.toString(), start, end)
        }
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

/**
 * Parses post bodies into the cache ahead of time, with the colours PostCard uses for
 * the body, so scrolling never waits on parsing. Call off the main thread.
 */
fun prewarmPostHtml(bodies: List<String>) {
    bodies.forEach { html ->
        rememberHtmlAnnotatedString(html, DefaultQuoteColor, DefaultReplyColor, DefaultLinkColor)
    }
}

private val DefaultQuoteColor = Color(0xFF789922)
private val DefaultReplyColor = Color(0xFFDD4444)
private val DefaultLinkColor = Color(0xFF6CB6FF)

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

private fun AnnotatedString.Builder.addSafeStyle(style: SpanStyle, start: Int, end: Int, length: Int) {
    val s = start.coerceIn(0, length)
    val e = end.coerceIn(s, length)
    if (s < e) addStyle(style, s, e)
}

/** vichan writes spoilers as <span class="spoiler"> and headings as <span class="heading">. */
private fun prepareMarkup(html: String): String =
    html.replace(spoilerSpanRegex, "<spoiler>$1</spoiler>")
        .replace(headingSpanRegex, "<b>$1</b>")

private val spoilerSpanRegex = Regex("""(?is)<span\s+class=["']spoiler["']\s*>(.*?)</span>""")
private val headingSpanRegex = Regex("""(?is)<span\s+class=["']heading["']\s*>(.*?)</span>""")

private class SpoilerMark

private object SpoilerTagHandler : Html.TagHandler {
    override fun handleTag(opening: Boolean, tag: String, output: Editable, xmlReader: XMLReader?) {
        if (!tag.equals("spoiler", ignoreCase = true)) return
        if (opening) {
            output.setSpan(SpoilerMark(), output.length, output.length, Spannable.SPAN_MARK_MARK)
            return
        }
        val open = output.getSpans(0, output.length, SpoilerMark::class.java)
            .lastOrNull { output.getSpanFlags(it) == Spannable.SPAN_MARK_MARK } ?: return
        val start = output.getSpanStart(open)
        output.removeSpan(open)
        if (output.length > start) {
            output.setSpan(SpoilerMark(), start, output.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
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
