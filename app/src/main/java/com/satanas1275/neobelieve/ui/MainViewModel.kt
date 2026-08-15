package com.satanas1275.neobelieve.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.satanas1275.neobelieve.data.model.Track
import com.satanas1275.neobelieve.data.repository.MusicRepository
import com.satanas1275.neobelieve.download.DownloadTrackWorker
import com.satanas1275.neobelieve.playback.PlayerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository.get(application)
    val player = PlayerController(application)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Track>>(emptyList())
    val searchResults: StateFlow<List<Track>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    val queue = repository.queue
    val downloads = repository.downloads

    init {
        player.connect()
    }

    fun onQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun runSearch() {
        val q = _searchQuery.value.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            _isSearching.value = true
            runCatching { repository.search(q) }
                .onSuccess { _searchResults.value = it }
                .onFailure { _searchResults.value = emptyList() }
            _isSearching.value = false
        }
    }

    /** Lance un morceau seul : la suite se peuple automatiquement (radio auto, comme sur YT Music). */
    fun playSingle(track: Track) {
        viewModelScope.launch {
            repository.playSingleWithAutoRadio(track)
            resolveAndPlay(startTrack = track)
            repository.recordHistory(track)
        }
    }

    /** Lance une vraie playlist depuis une position donnée. */
    fun playFromPlaylist(tracks: List<Track>, startIndex: Int) {
        viewModelScope.launch {
            repository.playPlaylist(tracks, startIndex)
            resolveAndPlay(startTrack = tracks[startIndex])
            repository.recordHistory(tracks[startIndex])
        }
    }

    private suspend fun resolveAndPlay(startTrack: Track) {
        val currentQueue = repository.queue.value
        val urls = mutableMapOf<String, String>()
        // On ne résout que le début de la queue tout de suite pour ne pas bloquer le play,
        // le reste peut être résolu à la volée quand on avance (simplifié ici pour le v1).
        currentQueue.take(5).forEach { t ->
            repository.resolveStreamUrl(t)?.let { urls[t.id] = it }
        }
        val startIndex = currentQueue.indexOfFirst { it.id == startTrack.id }.coerceAtLeast(0)
        player.playQueue(currentQueue, urls, startIndex)
    }

    fun download(track: Track) {
        DownloadTrackWorker.enqueue(getApplication(), track)
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
