package com.holotower.app.data.model

import java.util.concurrent.ConcurrentHashMap

/**
 * The API does not say how thumbnails are named, so the app guesses. Once one naming
 * works for a file type (say ".webm" -> "s.jpg"), later posts of that type try it first,
 * so most thumbnails cost one request instead of up to six.
 */
object ThumbnailResolver {
    private val learnedPattern = ConcurrentHashMap<String, String>()

    fun candidates(board: String, tim: String?, ext: String?, spoiler: Int?): List<String> {
        if (tim.isNullOrBlank() || spoiler == 1) return emptyList()
        val key = ext.orEmpty().lowercase()
        val defaults = defaultPatterns(key)
        val learned = learnedPattern[key]
        val ordered = if (learned != null) listOf(learned) + (defaults - learned) else defaults
        return ordered.map { pattern -> "https://holotower.org/$board/thumb/$tim$pattern" }
    }

    /** Call when a thumbnail URL loads, so the same naming is tried first next time. */
    fun onLoaded(url: String, ext: String?) {
        val pattern = url.substringAfterLast('/').dropWhile { it.isDigit() }
        if (pattern.isNotEmpty()) learnedPattern[ext.orEmpty().lowercase()] = pattern
    }

    private fun defaultPatterns(ext: String): List<String> {
        val types = when (ext) {
            ".png" -> listOf(".png", ".jpg", ".webp")
            else -> listOf(".jpg", ".png", ".webp")
        }
        return types.flatMap { listOf(it, "s$it") }
    }
}
