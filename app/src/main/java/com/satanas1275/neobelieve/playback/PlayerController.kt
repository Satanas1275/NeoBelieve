package com.satanas1275.neobelieve.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.satanas1275.neobelieve.data.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Pont entre l'UI Compose et le PlaybackService.
 *
 * Point clé pour la latence au démarrage : on ne fait QU'UN SEUL appel réseau
 * (resolveStreamUrl du titre demandé) avant de lancer `play()`. Le reste de la
 * queue (radio auto ou playlist) est résolu et ajouté en tâche de fond une fois
 * que le son a déjà démarré -> c'est le ViewModel qui orchestre ça, ce controller
 * ne fait qu'exposer les primitives (play immédiat / append en fond / seek).
 */
class PlayerController(private val context: Context) {

    private var controller: MediaController? = null
    private val readyDeferred = CompletableDeferred<MediaController>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickerJob: Job? = null

    // Media3 ne connaît que des MediaItem (id + uri + metadata) ; pour retrouver
    // le Track complet (thumbnail incluse) quand ExoPlayer avance TOUT SEUL vers
    // le titre suivant de la queue, il faut garder une table de correspondance.
    private val trackById = mutableMapOf<String, Track>()

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs

    fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                val c = future.get()
                controller = c
                c.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _isPlaying.value = isPlaying
                        if (isPlaying) startTicker() else stopTicker()
                    }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        _durationMs.value = c.duration.takeIf { it > 0 } ?: 0L
                        _positionMs.value = 0L
                        // LE bug : avant, currentTrack n'était mis à jour QUE dans
                        // playImmediate(). Quand ExoPlayer avançait tout seul dans la
                        // queue (fin de titre -> suivant), titre/artiste/pochette
                        // restaient bloqués sur l'ancien morceau alors que le son,
                        // lui, avait bien changé.
                        mediaItem?.mediaId?.let { id -> trackById[id]?.let { _currentTrack.value = it } }
                    }
                })
                if (!readyDeferred.isCompleted) readyDeferred.complete(c)
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun startTicker() {
        stopTicker()
        tickerJob = scope.launch {
            while (true) {
                val c = controller
                if (c != null) {
                    _positionMs.value = c.currentPosition.coerceAtLeast(0)
                    val d = c.duration
                    if (d > 0) _durationMs.value = d
                }
                delay(500)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    fun release() {
        stopTicker()
        controller?.release()
        controller = null
    }

    private suspend fun awaitController(): MediaController = readyDeferred.await()

    private fun buildMediaItem(track: Track, url: String): MediaItem {
        trackById[track.id] = track
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setArtworkUri(track.thumbnailUrl?.let { Uri.parse(it) })
                    .build(),
            )
            .build()
    }

    /** Lance IMMÉDIATEMENT un seul titre (déjà résolu) — c'est le chemin critique de latence. */
    suspend fun playImmediate(track: Track, url: String) {
        val c = awaitController()
        trackById.clear()
        c.setMediaItems(listOf(buildMediaItem(track, url)), 0, 0L)
        c.prepare()
        c.play()
        _currentTrack.value = track
        _isPlaying.value = true
    }

    /** Ajoute un titre déjà résolu à la fin de la queue en cours (pour la suite de la radio auto/playlist). */
    suspend fun appendToQueue(track: Track, url: String) {
        awaitController().addMediaItem(buildMediaItem(track, url))
    }

    /** Insère juste après le titre en cours (pour "ajouter en tête de file"). */
    suspend fun insertNext(track: Track, url: String) {
        val c = awaitController()
        val index = (c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount)
        c.addMediaItem(index, buildMediaItem(track, url))
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        _positionMs.value = positionMs
    }

    fun skipNext() = controller?.seekToNextMediaItem()
    fun skipPrevious() = controller?.seekToPreviousMediaItem()
}
