package com.satanas1275.neobelieve.data.repository

import android.content.Context
import com.satanas1275.neobelieve.data.extractor.MusicExtractor
import com.satanas1275.neobelieve.data.local.DownloadedTrackEntity
import com.satanas1275.neobelieve.data.local.HistoryEntity
import com.satanas1275.neobelieve.data.local.NeoBelieveDatabase
import com.satanas1275.neobelieve.data.model.QueueSource
import com.satanas1275.neobelieve.data.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
                id = it.id,
                title = it.title,
                artist = it.artist,
                durationSeconds = it.durationSeconds,
                thumbnailUrl = it.thumbnailUrl,
                isDownloaded = true,
                localFilePath = it.localFilePath,
            )
        }
    }

    val history = dao.observeHistory()

    suspend fun search(query: String): List<Track> = MusicExtractor.search(query)

    /** Lance une vraie playlist (queue figée telle quelle, pas de radio derrière). */
    fun playPlaylist(tracks: List<Track>, startIndex: Int = 0) {
        _queueSource.value = QueueSource.PLAYLIST
        _queue.value = tracks.drop(startIndex)
    }

    /** Lance un seul titre : la suite se peuple automatiquement via les recommandations. */
    suspend fun playSingleWithAutoRadio(track: Track) {
        _queueSource.value = QueueSource.AUTO_RADIO
        _queue.value = listOf(track)
        val related = MusicExtractor.getRelated(track.id)
        // On complète après coup pour ne pas bloquer le démarrage de la lecture
        _queue.value = listOf(track) + related
    }

    suspend fun resolveStreamUrl(track: Track): String? {
        if (track.isDownloaded && track.localFilePath != null) return track.localFilePath
        return MusicExtractor.getStreamUrl(track.id)
    }

    suspend fun recordHistory(track: Track) {
        dao.insertHistory(
            HistoryEntity(
                trackId = track.id,
                title = track.title,
                artist = track.artist,
                thumbnailUrl = track.thumbnailUrl,
                playedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun saveDownload(track: Track, localFilePath: String) {
        dao.insertDownload(
            DownloadedTrackEntity(
                id = track.id,
                title = track.title,
                artist = track.artist,
                durationSeconds = track.durationSeconds,
                thumbnailUrl = track.thumbnailUrl,
                localFilePath = localFilePath,
                downloadedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun isDownloaded(trackId: String): Boolean = dao.getDownload(trackId) != null

    companion object {
        @Volatile private var instance: MusicRepository? = null
        fun get(context: Context): MusicRepository =
            instance ?: synchronized(this) {
                instance ?: MusicRepository(context.applicationContext).also { instance = it }
            }
    }
}
