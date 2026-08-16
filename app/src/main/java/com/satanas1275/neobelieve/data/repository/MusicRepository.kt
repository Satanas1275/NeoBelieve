package com.satanas1275.neobelieve.data.repository

import android.content.Context
import com.satanas1275.neobelieve.data.extractor.MusicExtractor
import com.satanas1275.neobelieve.data.local.DownloadedTrackEntity
import com.satanas1275.neobelieve.data.local.FavoriteTrackEntity
import com.satanas1275.neobelieve.data.local.HistoryEntity
import com.satanas1275.neobelieve.data.local.NeoBelieveDatabase
import com.satanas1275.neobelieve.data.local.PlaylistEntity
import com.satanas1275.neobelieve.data.local.PlaylistTrackEntity
import com.satanas1275.neobelieve.data.model.QueueSource
import com.satanas1275.neobelieve.data.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class MusicRepository(context: Context) {

    private val dao = NeoBelieveDatabase.get(context).dao()

    // --- Queue en cours : soit une vraie playlist, soit "radio auto" après un titre lancé seul ---
    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue

    private val _queueSource = MutableStateFlow(QueueSource.AUTO_RADIO)
    val queueSource: StateFlow<QueueSource> = _queueSource

    val downloads = dao.observeDownloads().map { list ->
        list.map {
            Track(
                id = it.id, title = it.title, artist = it.artist,
                durationSeconds = it.durationSeconds, thumbnailUrl = it.thumbnailUrl,
                isDownloaded = true, localFilePath = it.localFilePath,
            )
        }
    }

    val favorites = dao.observeFavorites().map { list ->
        list.map {
            Track(
                id = it.id, title = it.title, artist = it.artist,
                durationSeconds = 0, thumbnailUrl = it.thumbnailUrl, isFavorite = true,
            )
        }
    }

    val history = dao.observeHistory()
    val playlists = dao.observePlaylists()

    fun observeIsFavorite(trackId: String): Flow<Boolean> = dao.observeIsFavorite(trackId)

    fun observePlaylistTracks(playlistId: Long) = dao.observePlaylistTracks(playlistId)

    suspend fun search(query: String): List<Track> = MusicExtractor.search(query)

    // --- Lecture : mise en place de queue SANS réseau, résolution faite par le ViewModel/PlayerController ---

    /** Prépare une vraie playlist (queue figée telle quelle, pas de radio derrière). Pas d'appel réseau. */
    fun preparePlaylistQueue(tracks: List<Track>, startIndex: Int = 0) {
        _queueSource.value = QueueSource.PLAYLIST
        _queue.value = tracks.drop(startIndex)
    }

    /** Prépare la queue pour un titre seul (juste lui pour l'instant, la radio se charge après). Pas d'appel réseau. */
    fun prepareSingleQueue(track: Track) {
        _queueSource.value = QueueSource.AUTO_RADIO
        _queue.value = listOf(track)
    }

    /** Charge la radio auto en tâche de fond et l'ajoute à la queue existante (appelé APRÈS le début du son). */
    suspend fun loadAutoRadio(seed: Track) {
        val related = MusicExtractor.getRelated(seed.id)
        if (_queueSource.value == QueueSource.AUTO_RADIO) {
            _queue.value = listOf(seed) + related
        }
    }

    /** Reflète dans la liste affichée un ajout "en fin de file" fait côté lecteur. */
    fun reflectAddToQueueEnd(track: Track) {
        if (_queue.value.none { it.id == track.id }) _queue.value = _queue.value + track
    }

    /** Reflète dans la liste affichée un ajout "juste après le titre en cours" fait côté lecteur. */
    fun reflectAddToQueueNext(track: Track, currentTrackId: String?) {
        if (_queue.value.any { it.id == track.id }) return
        val list = _queue.value.toMutableList()
        val currentIndex = list.indexOfFirst { it.id == currentTrackId }
        val insertAt = if (currentIndex >= 0) currentIndex + 1 else list.size
        list.add(insertAt, track)
        _queue.value = list
    }

    suspend fun resolveStreamUrl(track: Track): String? {
        if (track.isDownloaded && track.localFilePath != null) return track.localFilePath
        return MusicExtractor.getStreamUrl(track.id)
    }

    suspend fun recordHistory(track: Track) {
        dao.insertHistory(
            HistoryEntity(
                trackId = track.id, title = track.title, artist = track.artist,
                thumbnailUrl = track.thumbnailUrl, playedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun saveDownload(track: Track, localFilePath: String) {
        dao.insertDownload(
            DownloadedTrackEntity(
                id = track.id, title = track.title, artist = track.artist,
                durationSeconds = track.durationSeconds, thumbnailUrl = track.thumbnailUrl,
                localFilePath = localFilePath, downloadedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun isDownloaded(trackId: String): Boolean = dao.getDownload(trackId) != null

    // --- Favoris ---
    suspend fun toggleFavorite(track: Track) {
        val isFav = dao.observeIsFavorite(track.id).first()
        if (isFav) {
            dao.deleteFavorite(track.id)
        } else {
            dao.insertFavorite(
                FavoriteTrackEntity(
                    id = track.id, title = track.title, artist = track.artist,
                    thumbnailUrl = track.thumbnailUrl, favoritedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    // --- Playlists ---
    suspend fun createPlaylist(name: String): Long =
        dao.insertPlaylist(PlaylistEntity(name = name, createdAtEpochMs = System.currentTimeMillis()))

    suspend fun deletePlaylist(playlistId: Long) = dao.deletePlaylist(playlistId)

    suspend fun addTrackToPlaylist(playlistId: Long, track: Track, atStart: Boolean) {
        val position = if (atStart) {
            (dao.minPosition(playlistId) ?: 0) - 1
        } else {
            (dao.maxPosition(playlistId) ?: 0) + 1
        }
        dao.insertPlaylistTrack(
            PlaylistTrackEntity(
                playlistId = playlistId, trackId = track.id, title = track.title,
                artist = track.artist, thumbnailUrl = track.thumbnailUrl,
                durationSeconds = track.durationSeconds, position = position,
                addedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: String) =
        dao.removePlaylistTrack(playlistId, trackId)

    // --- Stats (onglet Compte) ---
    suspend fun topArtists(limit: Int = 5) = dao.topArtists(limit)
    suspend fun topTracks(limit: Int = 5) = dao.topTracks(limit)
    suspend fun totalPlays() = dao.totalPlays()

    /** Titres "recommandés" pour l'accueil : morceaux liés au titre le plus écouté (heuristique locale, pas un vrai moteur de reco serveur). */
    suspend fun recommendedTracks(limit: Int = 10): List<Track> {
        val top = dao.topTracks(1).firstOrNull() ?: return emptyList()
        return MusicExtractor.getRelated(top.trackId).take(limit)
    }

    /**
     * "Découverte" façon roue de la fortune : part d'un titre au hasard parmi l'historique/favoris
     * (ou une recherche générique si l'utilisateur n'a encore rien écouté), et pioche un morceau
     * lié au hasard dedans. Pas un vrai moteur de reco -> juste une heuristique locale pour le v1.
     */
    suspend fun pickRandomDiscovery(): Track? {
        val recent = dao.observeHistory().first()
        val seed = recent.randomOrNull()?.let {
            Track(it.trackId, it.title, it.artist, 0, it.thumbnailUrl)
        } ?: MusicExtractor.search("musique populaire").randomOrNull()
        ?: return null

        val related = MusicExtractor.getRelated(seed.id)
        return related.randomOrNull() ?: seed
    }

    companion object {
        @Volatile private var instance: MusicRepository? = null
        fun get(context: Context): MusicRepository =
            instance ?: synchronized(this) {
                instance ?: MusicRepository(context.applicationContext).also { instance = it }
            }
    }
}
