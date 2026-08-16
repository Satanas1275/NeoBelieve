package com.satanas1275.neobelieve.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.satanas1275.neobelieve.data.extractor.describeExtractionError
import com.satanas1275.neobelieve.data.model.Track
import com.satanas1275.neobelieve.data.repository.MusicRepository
import com.satanas1275.neobelieve.download.DownloadTrackWorker
import com.satanas1275.neobelieve.playback.PlayerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "NeoBelieve"

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository.get(application)
    val player = PlayerController(application)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Track>>(emptyList())
    val searchResults: StateFlow<List<Track>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // Etat "en cours de résolution/lecture" -> permet d'afficher un loader sur la ligne
    // cliquée au lieu de rien du tout pendant que ça extrait le flux.
    private val _loadingTrackId = MutableStateFlow<String?>(null)
    val loadingTrackId: StateFlow<String?> = _loadingTrackId.asStateFlow()

    private val _downloadingTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadingTrackIds: StateFlow<Set<String>> = _downloadingTrackIds.asStateFlow()

    // Message d'erreur ponctuel affiché en Snackbar, jamais un crash silencieux.
    // On y met le message de l'exception réelle (pas un texte générique) pour pouvoir
    // diagnostiquer sans avoir à brancher un adb logcat à chaque fois.
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

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
                .onFailure { e ->
                    Log.e(TAG, "Recherche échouée pour \"$q\"", e)
                    _searchResults.value = emptyList()
                    _errorMessage.value = "Recherche impossible : ${e.describeExtractionError()}"
                }
            _isSearching.value = false
        }
    }

    /**
     * Lance un morceau seul : la suite se peuple automatiquement (radio auto).
     * Tout est catché ici -> une extraction ratée (réseau, YouTube qui change un truc,
     * etc.) affiche une erreur au lieu de crasher toute l'app.
     */
    fun playSingle(track: Track) {
        viewModelScope.launch {
            _loadingTrackId.value = track.id
            runCatching {
                repository.playSingleWithAutoRadio(track)
                resolveAndPlay(startTrack = track)
                repository.recordHistory(track)
            }.onFailure { e ->
                Log.e(TAG, "Lecture échouée pour ${track.id} (${track.title})", e)
                _errorMessage.value = "Lecture impossible : ${e.describeExtractionError()}"
            }
            _loadingTrackId.value = null
        }
    }

    /** Lance une vraie playlist depuis une position donnée. */
    fun playFromPlaylist(tracks: List<Track>, startIndex: Int) {
        val track = tracks.getOrNull(startIndex) ?: return
        viewModelScope.launch {
            _loadingTrackId.value = track.id
            runCatching {
                repository.playPlaylist(tracks, startIndex)
                resolveAndPlay(startTrack = track)
                repository.recordHistory(track)
            }.onFailure { e ->
                Log.e(TAG, "Lecture échouée pour ${track.id} (${track.title})", e)
                _errorMessage.value = "Lecture impossible : ${e.describeExtractionError()}"
            }
            _loadingTrackId.value = null
        }
    }

    private suspend fun resolveAndPlay(startTrack: Track) {
        val currentQueue = repository.queue.value
        val urls = mutableMapOf<String, String>()
        var lastResolveError: Throwable? = null
        // On ne résout que le début de la queue tout de suite pour ne pas bloquer le play,
        // le reste peut être résolu à la volée quand on avance (simplifié ici pour le v1).
        currentQueue.take(5).forEach { t ->
            runCatching { repository.resolveStreamUrl(t) }
                .onFailure { lastResolveError = it; Log.e(TAG, "resolveStreamUrl a échoué pour ${t.id}", it) }
                .getOrNull()
                ?.let { urls[t.id] = it }
        }
        if (urls[startTrack.id] == null) {
            throw lastResolveError ?: IllegalStateException("Flux audio introuvable pour ${startTrack.title}")
        }
        val startIndex = currentQueue.indexOfFirst { it.id == startTrack.id }.coerceAtLeast(0)
        player.playQueue(currentQueue, urls, startIndex)
    }

    fun download(track: Track) {
        val workName = DownloadTrackWorker.enqueue(getApplication(), track)
        _downloadingTrackIds.value = _downloadingTrackIds.value + track.id
        viewModelScope.launch {
            androidx.work.WorkManager.getInstance(getApplication())
                .getWorkInfosForUniqueWorkFlow(workName)
                .collect { infos ->
                    val info = infos.firstOrNull() ?: return@collect
                    when (info.state) {
                        androidx.work.WorkInfo.State.SUCCEEDED -> {
                            _downloadingTrackIds.value = _downloadingTrackIds.value - track.id
                        }
                        androidx.work.WorkInfo.State.FAILED,
                        androidx.work.WorkInfo.State.CANCELLED -> {
                            _downloadingTrackIds.value = _downloadingTrackIds.value - track.id
                            val reason = info.outputData.getString(DownloadTrackWorker.KEY_ERROR)
                            Log.e(TAG, "Download échoué pour ${track.id}: $reason")
                            _errorMessage.value = "Échec du téléchargement : ${reason ?: "raison inconnue, voir logcat"}"
                        }
                        else -> Unit
                    }
                }
        }
    }

    fun errorShown() {
        _errorMessage.value = null
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
