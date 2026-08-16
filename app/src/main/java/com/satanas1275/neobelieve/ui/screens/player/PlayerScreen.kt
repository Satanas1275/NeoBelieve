package com.satanas1275.neobelieve.ui.screens.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.satanas1275.neobelieve.data.model.Track
import com.satanas1275.neobelieve.ui.MainViewModel
import com.satanas1275.neobelieve.ui.components.PlaylistPickerHost

@Composable
fun PlayerScreen(viewModel: MainViewModel) {
    val current by viewModel.player.currentTrack.collectAsState()
    val isPlaying by viewModel.player.isPlaying.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val positionMs by viewModel.player.positionMs.collectAsState()
    val durationMs by viewModel.player.durationMs.collectAsState()
    var trackForPlaylistDialog by remember { mutableStateOf<Track?>(null) }

    // Pendant qu'on drag le slider, on affiche la position locale (pas celle du player,
    // sinon ça saute en boucle tant qu'on n'a pas relâché le doigt).
    var draggingPositionMs by remember { mutableStateOf<Long?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (current == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Rien en lecture pour l'instant, meow.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        val currentTrack = current!!
        val isFavorite by remember(currentTrack.id) { viewModel.observeIsFavorite(currentTrack.id) }
            .collectAsState(initial = false)

        AsyncImage(
            model = currentTrack.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp)),
        )

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(currentTrack.title, style = MaterialTheme.typography.titleLarge)
                Text(currentTrack.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Favori + ajout à une playlist pour le titre EN COURS, directement accessibles
            // sans repasser par le menu "..." d'une ligne de recherche.
            IconButton(onClick = { viewModel.toggleFavorite(currentTrack) }) {
                Icon(
                    if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isFavorite) "Retirer des favoris" else "Ajouter aux favoris",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
            IconButton(onClick = { trackForPlaylistDialog = currentTrack }) {
                Icon(Icons.Default.PlaylistAdd, contentDescription = "Ajouter à une playlist")
            }
        }

        Spacer(Modifier.height(12.dp))

        // Barre de progression : slider + timestamps.
        val shownPosition = draggingPositionMs ?: positionMs
        Slider(
            value = if (durationMs > 0) (shownPosition.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f,
            onValueChange = { fraction -> draggingPositionMs = (fraction * durationMs).toLong() },
            onValueChangeFinished = {
                draggingPositionMs?.let { viewModel.player.seekTo(it) }
                draggingPositionMs = null
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(shownPosition), style = MaterialTheme.typography.labelSmall)
            Text(formatMs(durationMs), style = MaterialTheme.typography.labelSmall)
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.player.skipPrevious() }) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Précédent", modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.width(24.dp))
            FilledIconButton(onClick = { viewModel.player.togglePlayPause() }, modifier = Modifier.size(64.dp)) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Lecture/Pause",
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.width(24.dp))
            IconButton(onClick = { viewModel.player.skipNext() }) {
                Icon(Icons.Default.SkipNext, contentDescription = "Suivant", modifier = Modifier.size(36.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("File d'attente", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(queue, key = { it.id }) { track ->
                ListItem(
                    headlineContent = { Text(track.title) },
                    supportingContent = { Text(track.artist) },
                    leadingContent = {
                        AsyncImage(
                            model = track.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
                        )
                    },
                )
            }
        }
    }

    PlaylistPickerHost(viewModel, trackForPlaylistDialog) { trackForPlaylistDialog = null }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
