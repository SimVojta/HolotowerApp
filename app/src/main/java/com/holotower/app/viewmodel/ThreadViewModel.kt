package com.holotower.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.holotower.app.data.model.Post
import com.holotower.app.data.network.userMessage
import com.holotower.app.data.repository.BoardRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** [posts] is whatever can be shown right now: fresh, or the previous/saved copy while loading. */
sealed class ThreadUiState {
    abstract val posts: List<Post>?

    data class Loading(override val posts: List<Post>? = null) : ThreadUiState()
    data class Success(override val posts: List<Post>) : ThreadUiState()
    data class Error(val message: String, override val posts: List<Post>? = null) : ThreadUiState()
}

class ThreadViewModel(
    private val board: String,
    private val threadNo: Long,
    private val repo: BoardRepository = BoardRepository()
) : ViewModel() {

    private var loadJob: Job? = null
    private var releasedForMemory = false
    private val _state = MutableStateFlow<ThreadUiState>(ThreadUiState.Loading())
    val state: StateFlow<ThreadUiState> = _state

    init {
        load()
    }

    /** Shows the saved copy at once (if any), then always downloads the current thread. */
    fun load(forceRefresh: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            var shown = _state.value.posts
            if (shown == null && !forceRefresh) {
                shown = repo.cachedThreadPosts(board, threadNo)
            }
            _state.value = ThreadUiState.Loading(shown)
            try {
                _state.value = ThreadUiState.Success(repo.fetchThreadPosts(board, threadNo))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ThreadUiState.Error(e.userMessage("Failed to load thread"), shown)
            }
        }
    }

    fun releasePosts() {
        loadJob?.cancel()
        loadJob = null
        releasedForMemory = true
        _state.value = ThreadUiState.Loading()
    }

    fun reloadAfterRelease() {
        if (!releasedForMemory) return
        releasedForMemory = false
        load()
    }

    class Factory(
        private val board: String,
        private val threadNo: Long,
        private val repo: BoardRepository = BoardRepository()
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ThreadViewModel(board, threadNo, repo) as T
        }
    }
}
