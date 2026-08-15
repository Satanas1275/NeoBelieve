package com.satanas1275.neobelieve.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.satanas1275.neobelieve.data.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Pont entre l'UI Compose et le PlaybackService. Un seul MediaController partagé
 * pour toute l'app (créé au lancement de MainActivity, libéré à sa fermeture).
 *
 * Le controller se construit de façon async (buildAsync). Avant, playQueue() faisait
 * un `controller?.apply { ... }` qui ne plantait pas mais ne faisait RIEN si le
 * controller n'était pas encore prêt (ex: premier tap sur play juste après le lancement
 * de l'app) -> on attend maintenant la connexion via un CompletableDeferred.
 */
class PlayerController(private val context: Context) {

    private var controller: MediaController? = null
    private val readyDeferred = CompletableDeferred<MediaController>()

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                val c = future.get()
                controller = c
                c.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _isPlaying.value = isPlaying
                    }
                })
                if (!readyDeferred.isCompleted) readyDeferred.complete(c)
            },
            MoreExecutors.directExecutor(),
        )
    }

    fun release() {
        controller?.release()
        controller = null
    }

    /** Attend que le MediaController soit connecté avant de continuer. */
    private suspend fun awaitController(): MediaController = readyDeferred.await()

    suspend fun playQueue(tracks: List<Track>, streamUrls: Map<String, String>, startIndex: Int = 0) {
        val items = tracks.mapNotNull { track ->
            val url = streamUrls[track.id] ?: return@mapNotNull null
            MediaItem.Builder()
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
        if (items.isEmpty()) return

        val safeStartIndex = startIndex.coerceIn(0, items.lastIndex)
        val c = awaitController()
        c.setMediaItems(items, safeStartIndex, 0L)
        c.prepare()
        c.play()

        _currentTrack.value = tracks.getOrNull(safeStartIndex)
        _isPlaying.value = true
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun skipNext() = controller?.seekToNextMediaItem()
    fun skipPrevious() = controller?.seekToPreviousMediaItem()
}
