package com.satanas1275.neobelieve.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.satanas1275.neobelieve.data.model.Track
import com.satanas1275.neobelieve.ui.MainViewModel

@Composable
fun PlaylistPickerHost(viewModel: MainViewModel, pendingTrack: Track?, onDismiss: () -> Unit) {
    if (pendingTrack == null) return
    val playlists by viewModel.playlists.collectAsState(initial = emptyList())
    AddToPlaylistDialog(
        playlists = playlists,
        onDismiss = onDismiss,
        onPick = { id, atStart -> viewModel.addTrackToPlaylist(id, pendingTrack, atStart); onDismiss() },
        onCreateNew = { name, atStart ->
            viewModel.createPlaylist(name) { id -> viewModel.addTrackToPlaylist(id, pendingTrack, atStart) }
            onDismiss()
        },
    )
}
