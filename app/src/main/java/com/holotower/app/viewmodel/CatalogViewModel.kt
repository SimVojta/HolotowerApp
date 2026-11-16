package com.holotower.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.holotower.app.data.model.CatalogThread
import com.holotower.app.data.repository.BoardRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.LinkedHashMap

sealed class CatalogUiState {
    object Loading : CatalogUiState()
    data class Success(val threads: List<CatalogThread>) : CatalogUiState()
    data class Error(val message: String) : CatalogUiState()
}

class CatalogViewModel(
    private val repo: BoardRepository = BoardRepository()
) : ViewModel() {

    private var board: String = "hlgg"
    private var loadJob: Job? = null
    private var lastSuccessfulBoard: String? = null
    private var lastSuccessfulLoadAtMs: Long = 0L
    private val threadCountOverrides = LinkedHashMap<Pair<String, Long>, Pair<Int, Int>>()

    private val _state = MutableStateFlow<CatalogUiState>(CatalogUiState.Loading)
    val state: StateFlow<CatalogUiState> = _state

    fun load(
        boardName: String = board,
        forceRefresh: Boolean = false,
        retryOnFailure: Boolean = false
    ) {
        if (!forceRefresh && lastSuccessfulBoard == boardName &&
            _state.value is CatalogUiState.Success &&
            System.currentTimeMillis() - lastSuccessfulLoadAtMs < AUTO_REFRESH_INTERVAL_MS
        ) {
            return
        }

        board = boardName
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = CatalogUiState.Loading
            val firstAttempt = runCatching { repo.getCatalogThreads(board, forceRefresh = forceRefresh) }
            if (firstAttempt.isSuccess) {
                lastSuccessfulBoard = board
                lastSuccessfulLoadAtMs = System.currentTimeMillis()
                _state.value = CatalogUiState.Success(applyThreadCountOverrides(board, firstAttempt.getOrThrow()))
                return@launch
            }
            if (retryOnFailure) {
                delay(700)
                val retryAttempt = runCatching { repo.getCatalogThreads(board, forceRefresh = true) }
                if (retryAttempt.isSuccess) {
                    lastSuccessfulBoard = board
                    lastSuccessfulLoadAtMs = System.currentTimeMillis()
                    _state.value = CatalogUiState.Success(applyThreadCountOverrides(board, retryAttempt.getOrThrow()))
                    return@launch
                }
                _state.value = CatalogUiState.Error(
                    retryAttempt.exceptionOrNull()?.message
                        ?: firstAttempt.exceptionOrNull()?.message
                        ?: "Unknown error"
                )
            } else {
                _state.value = CatalogUiState.Error(firstAttempt.exceptionOrNull()?.message ?: "Unknown error")
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
        const val AUTO_REFRESH_INTERVAL_MS = 2 * 60 * 1000L
        const val MAX_THREAD_COUNT_OVERRIDES = 128
    }
}
