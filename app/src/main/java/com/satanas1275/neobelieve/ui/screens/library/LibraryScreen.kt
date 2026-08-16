package com.satanas1275.neobelieve.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.satanas1275.neobelieve.data.local.PlaylistEntity
import com.satanas1275.neobelieve.data.model.Track
import com.satanas1275.neobelieve.ui.MainViewModel
import com.satanas1275.neobelieve.ui.components.PlaylistPickerHost
import com.satanas1275.neobelieve.ui.components.TrackRow

private sealed class LibrarySection {
    data object Root : LibrarySection()
    data object Favorites : LibrarySection()
    data object History : LibrarySection()
    data object Playlists : LibrarySection()
    data object Downloads : LibrarySection()
    data class PlaylistDetail(val playlist: PlaylistEntity) : LibrarySection()
}

@Composable
fun LibraryScreen(viewModel: MainViewModel) {
    var section by remember { mutableStateOf<LibrarySection>(LibrarySection.Root) }

    when (val s = section) {
        is LibrarySection.Root -> LibraryRoot(
            viewModel = viewModel,
            onOpenFavorites = { section = LibrarySection.Favorites },
            onOpenHistory = { section = LibrarySection.History },
            onOpenPlaylists = { section = LibrarySection.Playlists },
            onOpenDownloads = { section = LibrarySection.Downloads },
        )
        is LibrarySection.Favorites -> SubSection("Favoris") { FavoritesList(viewModel) }
        is LibrarySection.History -> SubSection("Historique") { HistoryList(viewModel) }
        is LibrarySection.Playlists -> SubSection("Playlists") {
            PlaylistsList(viewModel, onOpenPlaylist = { section = LibrarySection.PlaylistDetail(it) })
        }
        is LibrarySection.Downloads -> SubSection("Téléchargements") { DownloadsList(viewModel) }
        is LibrarySection.PlaylistDetail -> SubSection(s.playlist.name) { PlaylistDetailList(viewModel, s.playlist) }
    }

    // Bouton retour matériel/geste : revient à la racine de la bibliothèque plutôt que
    // de quitter l'onglet (sauf si on est déjà à la racine).
    androidx.activity.compose.BackHandler(enabled = section != LibrarySection.Root) {
        section = if (section is LibrarySection.PlaylistDetail) LibrarySection.Playlists else LibrarySection.Root
    }
}

@Composable
private fun SubSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        content()
    }
}

@Composable
private fun LibraryRoot(
    viewModel: MainViewModel,
    onOpenFavorites: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val favorites by viewModel.favorites.collectAsState(initial = emptyList())
    val history by viewModel.history.collectAsState(initial = emptyList())
    val playlists by viewModel.playlists.collectAsState(initial = emptyList())
    val downloads by viewModel.downloads.collectAsState(initial = emptyList())

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Bibliothèque", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Favoris, historique et téléchargements",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
        item {
            NavCard(
                icon = Icons.Default.Favorite,
                iconBg = Color(0xFFFCE4EC),
                iconTint = Color(0xFFE91E63),
                title = "Favoris",
                count = favorites.size,
                onClick = onOpenFavorites,
            )
        }
        item {
            NavCard(
                icon = Icons.Default.History,
                iconBg = Color(0xFFE0F2F1),
                iconTint = Color(0xFF1C7C7B),
                title = "Historique",
                count = history.size,
                onClick = onOpenHistory,
            )
        }
        item {
            NavCard(
                icon = Icons.Default.QueueMusic,
                iconBg = Color(0xFFFFF3E0),
                iconTint = Color(0xFFFB8C00),
                title = "Playlists",
                count = playlists.size,
                onClick = onOpenPlaylists,
            )
        }
        item {
            NavCard(
                icon = Icons.Default.Download,
                iconBg = Color(0xFFE3F2FD),
                iconTint = Color(0xFF1E88E5),
                title = "Téléchargements",
                count = downloads.size,
                onClick = onOpenDownloads,
            )
        }

        item {
            Spacer(Modifier.height(24.dp))
            Text("Récemment écoutés", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
        }

        if (history.isEmpty()) {
            item {
                Text(
                    "Rien écouté pour l'instant.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        } else {
            items(history.take(10), key = { it.entryId }) { entry ->
                val track = Track(entry.trackId, entry.title, entry.artist, 0, entry.thumbnailUrl)
                Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    TrackRow(
                        track = track,
                        onPlay = { viewModel.playSingle(track) },
                        onAddNext = { viewModel.addToQueueNext(track) },
                        onAddEnd = { viewModel.addToQueueEnd(track) },
                        onDownload = { viewModel.download(track) },
                        onToggleFavorite = { viewModel.toggleFavorite(track) },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun NavCard(icon: androidx.compose.ui.graphics.vector.ImageVector, iconBg: Color, iconTint: Color, title: String, count: Int, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable(onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(iconBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconTint)
            }
            Spacer(Modifier.width(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("$count", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FavoritesList(viewModel: MainViewModel) {
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
private fun HistoryList(viewModel: MainViewModel) {
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
private fun PlaylistsList(viewModel: MainViewModel, onOpenPlaylist: (PlaylistEntity) -> Unit) {
    val playlists by viewModel.playlists.collectAsState(initial = emptyList())
    var showCreateDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
            Button(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Nouvelle playlist")
            }
        }
        Spacer(Modifier.height(8.dp))
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
private fun PlaylistDetailList(viewModel: MainViewModel, playlist: PlaylistEntity) {
    val tracksEntities by viewModel.observePlaylistTracks(playlist.id).collectAsState(initial = emptyList())
    val tracks = remember(tracksEntities) {
        tracksEntities.map { Track(it.trackId, it.title, it.artist, it.durationSeconds, it.thumbnailUrl) }
    }
    var trackForPlaylistDialog by remember { mutableStateOf<Track?>(null) }

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
private fun DownloadsList(viewModel: MainViewModel) {
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
