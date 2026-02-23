package com.holotower.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.holotower.app.data.model.CatalogThread
import com.holotower.app.data.repository.BoardRepository
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

    private val _state = MutableStateFlow<CatalogUiState>(CatalogUiState.Loading)
    val state: StateFlow<CatalogUiState> = _state

    fun load(boardName: String = board, forceRefresh: Boolean = false) {
        board = boardName
        viewModelScope.launch {
            _state.value = CatalogUiState.Loading
            runCatching { repo.getCatalogThreads(board, forceRefresh = forceRefresh) }
                .onSuccess { _state.value = CatalogUiState.Success(it) }
                .onFailure { _state.value = CatalogUiState.Error(it.message ?: "Unknown error") }
        }
    }
}
