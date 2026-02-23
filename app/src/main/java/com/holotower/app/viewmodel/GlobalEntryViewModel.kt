package com.holotower.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.holotower.app.data.model.GlobalEntryStatus
import com.holotower.app.data.repository.BoardRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class GlobalEntryUiState {
    object Loading : GlobalEntryUiState()
    data class Success(
        val status: GlobalEntryStatus,
        val attaching: Boolean = false
    ) : GlobalEntryUiState()
    data class Error(val message: String) : GlobalEntryUiState()
}

class GlobalEntryViewModel(
    private val repo: BoardRepository = BoardRepository()
) : ViewModel() {

    private val _state = MutableStateFlow<GlobalEntryUiState>(GlobalEntryUiState.Loading)
    val state: StateFlow<GlobalEntryUiState> = _state

    init {
        refresh()
    }

    fun refresh(force: Boolean = true) {
        viewModelScope.launch {
            _state.value = GlobalEntryUiState.Loading
            runCatching { repo.getGlobalEntryStatus(forceRefresh = force) }
                .onSuccess { _state.value = GlobalEntryUiState.Success(it) }
                .onFailure { _state.value = GlobalEntryUiState.Error(it.message ?: "Failed to load Global Entry") }
        }
    }

    fun attachToken(token: String) {
        val current = (_state.value as? GlobalEntryUiState.Success) ?: return
        val clean = token.trim()
        if (clean.isBlank()) return

        viewModelScope.launch {
            _state.value = current.copy(attaching = true)
            runCatching { repo.attachGlobalEntryToken(clean) }
                .onSuccess { _state.value = GlobalEntryUiState.Success(it, attaching = false) }
                .onFailure {
                    _state.value = GlobalEntryUiState.Error(it.message ?: "Failed to attach token")
                }
        }
    }
}
