package com.holotower.app.data.local

import android.content.Context

data class ThreadPositionState(
    val postNo: Long,
    val index: Int,
    val offset: Int,
    val savedAtMs: Long
)

object ThreadPositionStore {
    private const val PREFS_NAME = "thread_positions"
    private const val KEY_PREFIX = "thread_pos_"
    private const val MAX_AGE_MS = 45L * 24L * 60L * 60L * 1000L

    fun load(context: Context, board: String, threadNo: Long): ThreadPositionState? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(key(board, threadNo), null) ?: return null
        val parts = raw.split('|')
        if (parts.size != 4) {
            prefs.edit().remove(key(board, threadNo)).apply()
            return null
        }

        val postNo = parts[0].toLongOrNull()
        val index = parts[1].toIntOrNull()
        val offset = parts[2].toIntOrNull()
        val savedAtMs = parts[3].toLongOrNull()
        if (postNo == null || index == null || offset == null || savedAtMs == null) {
            prefs.edit().remove(key(board, threadNo)).apply()
            return null
        }
        if (System.currentTimeMillis() - savedAtMs > MAX_AGE_MS) {
            prefs.edit().remove(key(board, threadNo)).apply()
            return null
        }

        return ThreadPositionState(
            postNo = postNo,
            index = index.coerceAtLeast(0),
            offset = offset.coerceAtLeast(0),
            savedAtMs = savedAtMs
        )
    }

    fun save(
        context: Context,
        board: String,
        threadNo: Long,
        postNo: Long,
        index: Int,
        offset: Int
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val payload = listOf(
            postNo.toString(),
            index.coerceAtLeast(0).toString(),
            offset.coerceAtLeast(0).toString(),
            System.currentTimeMillis().toString()
        ).joinToString("|")
        prefs.edit()
            .putString(key(board, threadNo), payload)
            .apply()
    }

    private fun key(board: String, threadNo: Long): String {
        return "$KEY_PREFIX${board.trim()}_$threadNo"
    }
}
