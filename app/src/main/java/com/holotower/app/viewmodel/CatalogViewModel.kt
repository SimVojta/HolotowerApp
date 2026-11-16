package com.holotower.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.holotower.app.data.model.CatalogThread
import com.holotower.app.data.network.userMessage
import com.holotower.app.data.repository.BoardRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.LinkedHashMap

/** [threads] is whatever can be shown right now, including the previous list while reloading. */
sealed class CatalogUiState {
    abstract val threads: List<CatalogThread>?

    data class Loading(override val threads: List<CatalogThread>? = null) : CatalogUiState()
    data class Success(override val threads: List<CatalogThread>) : CatalogUiState()
    data class Error(val message: String, override val threads: List<CatalogThread>? = null) : CatalogUiState()
}

/** Created by the default factory, which passes the Application (needed for the network check). */
class CatalogViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = BoardRepository(application)

    private var board: String = "hlgg"
    private var loadJob: Job? = null
    private var lastSuccessfulBoard: String? = null
    private var lastSuccessfulLoadAtMs: Long = 0L
    private val threadCountOverrides = LinkedHashMap<Pair<String, Long>, Pair<Int, Int>>()

    private val _state = MutableStateFlow<CatalogUiState>(CatalogUiState.Loading())
    val state: StateFlow<CatalogUiState> = _state

    /**
     * Downloads the catalog, except on mobile data when the list on screen is under
     * [MOBILE_REUSE_MS] old. On Wi-Fi every call downloads. [forceRefresh] always does.
     */
    fun load(boardName: String = board, forceRefresh: Boolean = false) {
        if (!forceRefresh && lastSuccessfulBoard == boardName &&
            _state.value is CatalogUiState.Success &&
            repo.isOnMeteredNetwork() &&
            System.currentTimeMillis() - lastSuccessfulLoadAtMs in 0 until MOBILE_REUSE_MS
        ) {
            return
        }
        // Don't let a load already running restart for nothing (e.g. quick back-and-forth).
        if (!forceRefresh && _state.value is CatalogUiState.Loading && loadJob?.isActive == true &&
            board == boardName
        ) {
            return
        }

        val previous = _state.value.threads.takeIf { lastSuccessfulBoard == boardName }
        board = boardName
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = CatalogUiState.Loading(previous)
            try {
                // The decision to download was made above; skip the repository's own short cache.
                val fresh = repo.getCatalogThreads(board, forceRefresh = true)
                lastSuccessfulBoard = board
                lastSuccessfulLoadAtMs = System.currentTimeMillis()
                _state.value = CatalogUiState.Success(applyThreadCountOverrides(board, fresh))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = CatalogUiState.Error(e.userMessage("Failed to load the catalog"), previous)
            }
        }
    }

    fun updateThreadCounts(boardName: String, threadNo: Long, replies: Int, images: Int) {
        val key = boardName to threadNo
        val current = threadCountOverrides[key]
        val updated = maxOf(current?.first ?: 0, replies.coerceAtLeast(0)) to
            maxOf(current?.second ?: 0, images.coerceAtLeast(0))
        if (updated == current) return

        threadCountOverrides[key] = updated
        while (threadCountOverrides.size > MAX_THREAD_COUNT_OVERRIDES) {
            val oldestKey = threadCountOverrides.keys.firstOrNull() ?: break
            threadCountOverrides.remove(oldestKey)
        }

        val currentState = _state.value as? CatalogUiState.Success ?: return
        if (lastSuccessfulBoard != boardName) return
        _state.value = CatalogUiState.Success(
            currentState.threads.map { thread ->
                if (thread.no == threadNo) {
                    thread.copy(
                        replies = maxOf(thread.replyCount, updated.first),
                        images = maxOf(thread.imageReplyCount, updated.second)
                    )
                } else {
                    thread
                }
            }
        )
    }

    private fun applyThreadCountOverrides(boardName: String, threads: List<CatalogThread>): List<CatalogThread> =
        threads.map { thread ->
            val override = threadCountOverrides[boardName to thread.no] ?: return@map thread
            thread.copy(
                replies = maxOf(thread.replyCount, override.first),
                images = maxOf(thread.imageReplyCount, override.second)
            )
        }

    private companion object {
        /** On mobile data, a catalog downloaded less than this long ago is reused. */
        const val MOBILE_REUSE_MS = 10 * 60 * 1000L
        const val MAX_THREAD_COUNT_OVERRIDES = 128
    }
}
