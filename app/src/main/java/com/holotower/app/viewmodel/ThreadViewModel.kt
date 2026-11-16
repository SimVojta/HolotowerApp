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

    /** When the posts on screen were downloaded (from the saved copy's timestamp, or a fetch). */
    private var shownDownloadedAtMs = 0L

    /**
     * Shows the saved copy at once (if any), then downloads the current thread, except on
     * mobile data when what's shown is under [SAVED_COPY_FRESH_MS] old. [forceRefresh]
     * (pull-to-refresh, the refresh button, retry, after posting) always downloads.
     */
    fun load(forceRefresh: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            var shown = _state.value.posts
            if (shown == null && !forceRefresh) {
                repo.savedThread(board, threadNo)?.let { saved ->
                    shown = saved.posts
                    shownDownloadedAtMs = saved.savedAtMs
                }
            }
            val current = shown
            if (!forceRefresh && current != null && repo.isOnMeteredNetwork() &&
                System.currentTimeMillis() - shownDownloadedAtMs in 0 until SAVED_COPY_FRESH_MS
            ) {
                _state.value = ThreadUiState.Success(current)
                return@launch
            }
            _state.value = ThreadUiState.Loading(current)
            try {
                val fresh = repo.fetchThreadPosts(board, threadNo)
                shownDownloadedAtMs = System.currentTimeMillis()
                _state.value = ThreadUiState.Success(fresh)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ThreadUiState.Error(e.userMessage("Failed to load thread"), current)
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

    private companion object {
        /** On mobile data, a thread downloaded less than this long ago is not downloaded again. */
        const val SAVED_COPY_FRESH_MS = 10 * 60 * 1000L
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
