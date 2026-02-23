package com.holotower.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.holotower.app.data.model.Post
import com.holotower.app.data.repository.BoardRepository
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

    private val _state = MutableStateFlow<ThreadUiState>(ThreadUiState.Loading)
    val state: StateFlow<ThreadUiState> = _state

    init {
        load()
    }

    fun load(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _state.value = ThreadUiState.Loading
            runCatching { repo.getThreadPosts(board, threadNo, forceRefresh = forceRefresh) }
                .onSuccess { _state.value = ThreadUiState.Success(it) }
                .onFailure { _state.value = ThreadUiState.Error(it.message ?: "Unknown error") }
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
