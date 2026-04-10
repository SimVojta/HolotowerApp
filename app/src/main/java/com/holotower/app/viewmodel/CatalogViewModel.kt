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

    private val _state = MutableStateFlow<CatalogUiState>(CatalogUiState.Loading)
    val state: StateFlow<CatalogUiState> = _state

    fun load(
        boardName: String = board,
        forceRefresh: Boolean = false,
        retryOnFailure: Boolean = false
    ) {
        board = boardName
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = CatalogUiState.Loading
            val firstAttempt = runCatching { repo.getCatalogThreads(board, forceRefresh = forceRefresh) }
            if (firstAttempt.isSuccess) {
                _state.value = CatalogUiState.Success(firstAttempt.getOrThrow())
                return@launch
            }
            if (retryOnFailure) {
                delay(700)
                val retryAttempt = runCatching { repo.getCatalogThreads(board, forceRefresh = true) }
                if (retryAttempt.isSuccess) {
                    _state.value = CatalogUiState.Success(retryAttempt.getOrThrow())
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
}
