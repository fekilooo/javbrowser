package com.example.javbrowser.nativeapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.javbrowser.nativeapp.data.*
import com.example.javbrowser.nativeapp.domain.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class AppUiState(
    val query: String = "",
    val search: SearchSnapshot = SearchSnapshot(),
    val selected: JavTitle? = null,
    val detailsLoading: Boolean = false,
    val playbackLoading: Boolean = false,
    val playback: List<PlaybackVariant> = emptyList(),
    val playbackErrors: Map<String,String> = emptyMap(),
    val activePlayback: PlaybackVariant? = null,
    val favorites: List<JavTitle> = emptyList(),
)

class AppViewModel(private val repository: JavRepository, private val library: LibraryStore) : ViewModel() {
    private val _state = MutableStateFlow(AppUiState(favorites = library.favorites()))
    val state: StateFlow<AppUiState> = _state.asStateFlow()
    fun search(query: String) {
        if (query.isBlank()) return
        _state.update { it.copy(query = query, search = SearchSnapshot()) }
        viewModelScope.launch { repository.search(query).collect { snap -> _state.update { it.copy(search = snap) } } }
    }
    fun select(title: JavTitle) {
        _state.update { it.copy(selected = title, detailsLoading = true, playback = emptyList(), playbackErrors = emptyMap()) }
        viewModelScope.launch {
            val details = repository.details(title)
            _state.update { it.copy(selected = details, detailsLoading = false, playbackLoading = true) }
            val (variants, errors) = repository.playback(details)
            _state.update { it.copy(playback = variants, playbackErrors = errors, playbackLoading = false) }
        }
    }
    fun toggleFavorite(): Boolean {
        val title = _state.value.selected ?: return false
        val added = library.toggle(title); _state.update { it.copy(favorites = library.favorites()) }; return added
    }
    fun play(variant: PlaybackVariant) = _state.update { it.copy(activePlayback = variant) }
    fun refreshLibrary() = _state.update { it.copy(favorites = library.favorites()) }
}

class AppViewModelFactory(private val repository: JavRepository, private val library: LibraryStore) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(repository, library) as T
}
