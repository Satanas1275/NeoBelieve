package com.satanas1275.neobelieve.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.satanas1275.neobelieve.data.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Pont entre l'UI Compose et le PlaybackService. Un seul MediaController partagé
 * pour toute l'app (créé au lancement de MainActivity, libéré à sa fermeture).
 */
class PlayerController(private val context: Context) {

    private var controller: MediaController? = null

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    fun connect(onReady: () -> Unit = {}) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                controller = future.get()
                onReady()
            },
            MoreExecutors.directExecutor(),
        )
    }

    fun release() {
        controller?.release()
        controller = null
    }

    fun playQueue(tracks: List<Track>, streamUrls: Map<String, String>, startIndex: Int = 0) {
        val items = tracks.mapNotNull { track ->
            val url = streamUrls[track.id] ?: return@mapNotNull null
            MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setArtworkUri(track.thumbnailUrl?.let { android.net.Uri.parse(it) })
                        .build(),
                )
                .build()
        }
        controller?.apply {
            setMediaItems(items, startIndex, 0L)
            prepare()
            play()
        }
        _currentTrack.value = tracks.getOrNull(startIndex)
        _isPlaying.value = true
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
        _isPlaying.value = c.isPlaying
    }

    fun skipNext() = controller?.seekToNextMediaItem()
    fun skipPrevious() = controller?.seekToPreviousMediaItem()
}
