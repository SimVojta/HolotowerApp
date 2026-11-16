package com.holotower.app.data.local

import android.content.Context

/** Remembers how many replies each opened thread had, for the catalog's "+N new" badge. */
object ThreadSeenStore {
    private const val PREFS_NAME = "thread_seen"

    private fun prefix(board: String) = "${board.trim()}_"

    fun markSeen(context: Context, board: String, threadNo: Long, replies: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = prefix(board) + threadNo
        if (prefs.getInt(key, -1) >= replies) return
        prefs.edit().putInt(key, replies).apply()
    }

    fun load(context: Context, board: String): Map<Long, Int> {
        val p = prefix(board)
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all
            .mapNotNull { (key, value) ->
                if (!key.startsWith(p)) return@mapNotNull null
                val no = key.removePrefix(p).toLongOrNull() ?: return@mapNotNull null
                (value as? Int)?.let { no to it }
            }
            .toMap()
    }

    /** Forgets threads that are no longer in the catalog. */
    fun prune(context: Context, board: String, keep: Set<Long>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val p = prefix(board)
        val stale = prefs.all.keys.filter { key ->
            key.startsWith(p) && key.removePrefix(p).toLongOrNull()?.let { it !in keep } == true
        }
        if (stale.isEmpty()) return
        prefs.edit().apply { stale.forEach(::remove) }.apply()
    }
}
