package com.satanas1275.neobelieve.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.satanas1275.neobelieve.data.extractor.describeExtractionError
import com.satanas1275.neobelieve.data.local.ArtistStat
import com.satanas1275.neobelieve.data.local.TrackStat
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

    private val _loadingTrackId = MutableStateFlow<String?>(null)
    val loadingTrackId: StateFlow<String?> = _loadingTrackId.asStateFlow()

    private val _downloadingTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadingTrackIds: StateFlow<Set<String>> = _downloadingTrackIds.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Int>> = _downloadProgress.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    val queue = repository.queue
    val downloads = repository.downloads
    val favorites = repository.favorites
    val history = repository.history
    val playlists = repository.playlists

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
     * Lance un morceau seul. Chemin critique de latence : UN SEUL appel réseau
     * (resolveStreamUrl du titre demandé) avant `play()`. La radio auto (getRelated)
     * est chargée et résolue APRÈS, en tâche de fond, pour ne pas retarder le son.
     */
    fun playSingle(track: Track) {
        viewModelScope.launch {
            _loadingTrackId.value = track.id
            runCatching {
                repository.prepareSingleQueue(track)
                val url = repository.resolveStreamUrl(track)
                    ?: throw IllegalStateException("Flux audio introuvable pour ${track.title}")
                player.playImmediate(track, url)
                repository.recordHistory(track)
            }.onFailure { e ->
                Log.e(TAG, "Lecture échouée pour ${track.id} (${track.title})", e)
                _errorMessage.value = "Lecture impossible : ${e.describeExtractionError()}"
            }
            _loadingTrackId.value = null

            // Radio auto en tâche de fond, le son joue déjà.
            runCatching {
                repository.loadAutoRadio(track)
                repository.queue.value.drop(1).forEach { t ->
                    runCatching { repository.resolveStreamUrl(t) }
                        .getOrNull()
                        ?.let { url -> player.appendToQueue(t, url) }
                }
            }.onFailure { Log.e(TAG, "Radio auto échouée pour ${track.id}", it) }
        }
    }

    /** Lance une vraie playlist depuis une position donnée, même logique : 1 appel réseau puis démarrage. */
    fun playFromPlaylist(tracks: List<Track>, startIndex: Int) {
        val track = tracks.getOrNull(startIndex) ?: return
        viewModelScope.launch {
            _loadingTrackId.value = track.id
            runCatching {
                repository.preparePlaylistQueue(tracks, startIndex)
                val url = repository.resolveStreamUrl(track)
                    ?: throw IllegalStateException("Flux audio introuvable pour ${track.title}")
                player.playImmediate(track, url)
                repository.recordHistory(track)
            }.onFailure { e ->
                Log.e(TAG, "Lecture échouée pour ${track.id} (${track.title})", e)
                _errorMessage.value = "Lecture impossible : ${e.describeExtractionError()}"
            }
            _loadingTrackId.value = null

            runCatching {
                repository.queue.value.drop(1).forEach { t ->
                    runCatching { repository.resolveStreamUrl(t) }
                        .getOrNull()
                        ?.let { url -> player.appendToQueue(t, url) }
                }
            }.onFailure { Log.e(TAG, "Résolution du reste de la playlist échouée", it) }
        }
    }

    /** Bouton "aléatoire" de l'accueil : pioche un morceau et le lance direct. */
    fun playRandomDiscovery() {
        viewModelScope.launch {
            _isDiscovering.value = true
            runCatching { repository.pickRandomDiscovery() }
                .onSuccess { it?.let(::playSingle) ?: run { _errorMessage.value = "Rien à proposer pour l'instant, écoute deux-trois trucs d'abord." } }
                .onFailure { e ->
                    Log.e(TAG, "Découverte aléatoire échouée", e)
                    _errorMessage.value = "Découverte impossible : ${e.describeExtractionError()}"
                }
            _isDiscovering.value = false
        }
    }

    fun addToQueueNext(track: Track) {
        viewModelScope.launch {
            runCatching {
                val url = repository.resolveStreamUrl(track) ?: return@launch
                player.insertNext(track, url)
                repository.reflectAddToQueueNext(track, player.currentTrack.value?.id)
            }.onFailure { _errorMessage.value = "Impossible d'ajouter « ${track.title} » à la file." }
        }
    }

    fun addToQueueEnd(track: Track) {
        viewModelScope.launch {
            runCatching {
                val url = repository.resolveStreamUrl(track) ?: return@launch
                player.appendToQueue(track, url)
                repository.reflectAddToQueueEnd(track)
            }.onFailure { _errorMessage.value = "Impossible d'ajouter « ${track.title} » à la file." }
        }
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch { repository.toggleFavorite(track) }
    }

    fun createPlaylist(name: String, onCreated: (Long) -> Unit = {}) {
        viewModelScope.launch { onCreated(repository.createPlaylist(name)) }
    }

    fun addTrackToPlaylist(playlistId: Long, track: Track, atStart: Boolean) {
        viewModelScope.launch { repository.addTrackToPlaylist(playlistId, track, atStart) }
    }

    fun removeTrackFromPlaylist(playlistId: Long, trackId: String) {
        viewModelScope.launch { repository.removeTrackFromPlaylist(playlistId, trackId) }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch { repository.deletePlaylist(playlistId) }
    }

    fun observePlaylistTracks(playlistId: Long) = repository.observePlaylistTracks(playlistId)

    fun observeIsFavorite(trackId: String) = repository.observeIsFavorite(trackId)

    private val _topArtists = MutableStateFlow<List<ArtistStat>>(emptyList())
    val topArtists: StateFlow<List<ArtistStat>> = _topArtists.asStateFlow()

    private val _topTracks = MutableStateFlow<List<TrackStat>>(emptyList())
    val topTracks: StateFlow<List<TrackStat>> = _topTracks.asStateFlow()

    private val _totalPlays = MutableStateFlow(0)
    val totalPlays: StateFlow<Int> = _totalPlays.asStateFlow()

    fun refreshStats() {
        viewModelScope.launch {
            _topArtists.value = repository.topArtists()
            _topTracks.value = repository.topTracks()
            _totalPlays.value = repository.totalPlays()
        }
    }

    private val _recommendedTracks = MutableStateFlow<List<Track>>(emptyList())
    val recommendedTracks: StateFlow<List<Track>> = _recommendedTracks.asStateFlow()

    private val _isLoadingRecommendations = MutableStateFlow(false)
    val isLoadingRecommendations: StateFlow<Boolean> = _isLoadingRecommendations.asStateFlow()

    /** Titres recommandés de l'accueil = liés au titre le plus écouté. Vide tant qu'il n'y a pas d'historique. */
    fun refreshRecommendations() {
        viewModelScope.launch {
            _isLoadingRecommendations.value = true
            runCatching { repository.recommendedTracks() }
                .onSuccess { _recommendedTracks.value = it }
                .onFailure { Log.e(TAG, "Recommandations échouées", it) }
            _isLoadingRecommendations.value = false
        }
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
                        androidx.work.WorkInfo.State.RUNNING -> {
                            val percent = info.progress.getInt(DownloadTrackWorker.KEY_PROGRESS, -1)
                            if (percent >= 0) _downloadProgress.value = _downloadProgress.value + (track.id to percent)
                        }
                        androidx.work.WorkInfo.State.SUCCEEDED -> {
                            _downloadingTrackIds.value = _downloadingTrackIds.value - track.id
                            _downloadProgress.value = _downloadProgress.value - track.id
                        }
                        androidx.work.WorkInfo.State.FAILED,
                        androidx.work.WorkInfo.State.CANCELLED -> {
                            _downloadingTrackIds.value = _downloadingTrackIds.value - track.id
                            _downloadProgress.value = _downloadProgress.value - track.id
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
