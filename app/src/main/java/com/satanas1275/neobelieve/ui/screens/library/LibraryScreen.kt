package com.satanas1275.neobelieve.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.satanas1275.neobelieve.data.local.PlaylistEntity
import com.satanas1275.neobelieve.data.model.Track
import com.satanas1275.neobelieve.ui.MainViewModel
import com.satanas1275.neobelieve.ui.components.PlaylistPickerHost
import com.satanas1275.neobelieve.ui.components.TrackRow

private val tabTitles = listOf("Favoris", "Historique", "Playlists", "Téléchargements")

@Composable
fun LibraryScreen(viewModel: MainViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var openedPlaylist by remember { mutableStateOf<PlaylistEntity?>(null) }

    Column(Modifier.fillMaxSize()) {
        if (openedPlaylist == null) {
            TabRow(selectedTabIndex = selectedTab) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) })
                }
            }
            when (selectedTab) {
                0 -> FavoritesTab(viewModel)
                1 -> HistoryTab(viewModel)
                2 -> PlaylistsTab(viewModel, onOpenPlaylist = { openedPlaylist = it })
                3 -> DownloadsTab(viewModel)
            }
        } else {
            PlaylistDetailTab(
                viewModel = viewModel,
                playlist = openedPlaylist!!,
                onBack = { openedPlaylist = null },
            )
        }
    }
}

@Composable
private fun FavoritesTab(viewModel: MainViewModel) {
    val favorites by viewModel.favorites.collectAsState(initial = emptyList())
    var trackForPlaylistDialog by remember { mutableStateOf<Track?>(null) }

    if (favorites.isEmpty()) {
        EmptyState("Aucun favori pour l'instant.")
    } else {
        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
            items(favorites, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    onPlay = { viewModel.playFromPlaylist(favorites, favorites.indexOf(track)) },
                    onAddNext = { viewModel.addToQueueNext(track) },
                    onAddEnd = { viewModel.addToQueueEnd(track) },
                    onDownload = { viewModel.download(track) },
                    onToggleFavorite = { viewModel.toggleFavorite(track) },
                    onAddToPlaylist = { trackForPlaylistDialog = track },
                )
            }
        }
    }
    PlaylistPickerHost(viewModel, trackForPlaylistDialog) { trackForPlaylistDialog = null }
}

@Composable
private fun HistoryTab(viewModel: MainViewModel) {
    val history by viewModel.history.collectAsState(initial = emptyList())
    var trackForPlaylistDialog by remember { mutableStateOf<Track?>(null) }

    if (history.isEmpty()) {
        EmptyState("Rien écouté pour l'instant.")
    } else {
        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
            items(history, key = { it.entryId }) { entry ->
                val track = Track(entry.trackId, entry.title, entry.artist, 0, entry.thumbnailUrl)
                TrackRow(
                    track = track,
                    onPlay = { viewModel.playSingle(track) },
                    onAddNext = { viewModel.addToQueueNext(track) },
                    onAddEnd = { viewModel.addToQueueEnd(track) },
                    onDownload = { viewModel.download(track) },
                    onToggleFavorite = { viewModel.toggleFavorite(track) },
                    onAddToPlaylist = { trackForPlaylistDialog = track },
                )
            }
        }
    }
    PlaylistPickerHost(viewModel, trackForPlaylistDialog) { trackForPlaylistDialog = null }
}

@Composable
private fun PlaylistsTab(viewModel: MainViewModel, onOpenPlaylist: (PlaylistEntity) -> Unit) {
    val playlists by viewModel.playlists.collectAsState(initial = emptyList())
    var showCreateDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            Button(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Nouvelle playlist")
            }
        }
        if (playlists.isEmpty()) {
            EmptyState("Pas encore de playlist.")
        } else {
            LazyColumn(Modifier.padding(horizontal = 16.dp)) {
                items(playlists, key = { it.id }) { playlist ->
                    ListItem(
                        headlineContent = { Text(playlist.name) },
                        modifier = Modifier.clickable { onOpenPlaylist(playlist) },
                        trailingContent = {
                            IconButton(onClick = { viewModel.deletePlaylist(playlist.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Supprimer")
                            }
                        },
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Nouvelle playlist") },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Nom") }, singleLine = true)
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = { viewModel.createPlaylist(newName.trim()); newName = ""; showCreateDialog = false },
                ) { Text("Créer") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun PlaylistDetailTab(viewModel: MainViewModel, playlist: PlaylistEntity, onBack: () -> Unit) {
    val tracksEntities by viewModel.observePlaylistTracks(playlist.id).collectAsState(initial = emptyList())
    val tracks = remember(tracksEntities) {
        tracksEntities.map { Track(it.trackId, it.title, it.artist, it.durationSeconds, it.thumbnailUrl) }
    }
    var trackForPlaylistDialog by remember { mutableStateOf<Track?>(null) }

    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Retour") }
        Text(playlist.name, style = MaterialTheme.typography.titleLarge)
    }

    if (tracks.isEmpty()) {
        EmptyState("Playlist vide — ajoute des titres depuis leur menu \"...\".")
    } else {
        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
            items(tracks, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    onPlay = { viewModel.playFromPlaylist(tracks, tracks.indexOf(track)) },
                    onAddNext = { viewModel.addToQueueNext(track) },
                    onAddEnd = { viewModel.addToQueueEnd(track) },
                    onDownload = { viewModel.download(track) },
                    onToggleFavorite = { viewModel.toggleFavorite(track) },
                    onAddToPlaylist = { trackForPlaylistDialog = track },
                    trailingExtra = {
                        IconButton(onClick = { viewModel.removeTrackFromPlaylist(playlist.id, track.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Retirer de la playlist")
                        }
                    },
                )
            }
        }
    }
    PlaylistPickerHost(viewModel, trackForPlaylistDialog) { trackForPlaylistDialog = null }
}

@Composable
private fun DownloadsTab(viewModel: MainViewModel) {
    val downloads by viewModel.downloads.collectAsState(initial = emptyList())
    var trackForPlaylistDialog by remember { mutableStateOf<Track?>(null) }

    if (downloads.isEmpty()) {
        EmptyState("Rien de téléchargé pour l'instant.")
    } else {
        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
            items(downloads, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    onPlay = { viewModel.playFromPlaylist(downloads, downloads.indexOf(track)) },
                    onAddNext = { viewModel.addToQueueNext(track) },
                    onAddEnd = { viewModel.addToQueueEnd(track) },
                    onToggleFavorite = { viewModel.toggleFavorite(track) },
                    onAddToPlaylist = { trackForPlaylistDialog = track },
                )
            }
        }
    }
    PlaylistPickerHost(viewModel, trackForPlaylistDialog) { trackForPlaylistDialog = null }
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
