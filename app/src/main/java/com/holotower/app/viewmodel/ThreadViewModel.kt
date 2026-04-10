package com.holotower.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.holotower.app.data.model.Post
import com.holotower.app.data.repository.BoardRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class ThreadUiState {
    object Loading : ThreadUiState()
    data class Success(val posts: List<Post>) : ThreadUiState()
    data class Error(val message: String) : ThreadUiState()
}

class ThreadViewModel(
    private val board: String,
    private val threadNo: Long,
    private val repo: BoardRepository = BoardRepository()
) : ViewModel() {

    private var loadJob: Job? = null
    private val _state = MutableStateFlow<ThreadUiState>(ThreadUiState.Loading)
    val state: StateFlow<ThreadUiState> = _state

    init {
        load()
    }

    fun load(forceRefresh: Boolean = false, retryOnFailure: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = ThreadUiState.Loading
            val firstAttempt = runCatching { repo.getThreadPosts(board, threadNo, forceRefresh = forceRefresh) }
            if (firstAttempt.isSuccess) {
                _state.value = ThreadUiState.Success(firstAttempt.getOrThrow())
                return@launch
            }
            if (retryOnFailure) {
                delay(700)
                val retryAttempt = runCatching { repo.getThreadPosts(board, threadNo, forceRefresh = true) }
                if (retryAttempt.isSuccess) {
                    _state.value = ThreadUiState.Success(retryAttempt.getOrThrow())
                    return@launch
                }
                _state.value = ThreadUiState.Error(
                    retryAttempt.exceptionOrNull()?.message
                        ?: firstAttempt.exceptionOrNull()?.message
                        ?: "Unknown error"
                )
            } else {
                _state.value = ThreadUiState.Error(firstAttempt.exceptionOrNull()?.message ?: "Unknown error")
            }
        }
    }

    class Factory(
        private val board: String,
        private val threadNo: Long
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ThreadViewModel(board, threadNo) as T
        }
    }
}
