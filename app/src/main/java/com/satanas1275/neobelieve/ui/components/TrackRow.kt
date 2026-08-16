package com.satanas1275.neobelieve.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.satanas1275.neobelieve.data.model.Track

/**
 * Ligne de morceau standard, utilisée partout (recherche, favoris, historique, playlists,
 * téléchargements, accueil). Le "..." ouvre un menu au lieu d'empiler des icônes brutes :
 * Lire, ajouter en tête/fin de file, télécharger, favori, ajouter à une playlist.
 */
@Composable
fun TrackRow(
    track: Track,
    isLoading: Boolean = false,
    isDownloading: Boolean = false,
    onPlay: () -> Unit,
    onAddNext: () -> Unit = {},
    onAddEnd: () -> Unit = {},
    onDownload: () -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    onAddToPlaylist: () -> Unit = {},
    trailingExtra: (@Composable () -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        trailingExtra?.invoke()

        when {
            isDownloading -> CircularProgressIndicator(Modifier.size(20.dp).padding(end = 8.dp), strokeWidth = 2.dp)
            isLoading -> CircularProgressIndicator(Modifier.size(20.dp).padding(end = 8.dp), strokeWidth = 2.dp)
            else -> Unit
        }

        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Options")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Lire") },
                    leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    onClick = { menuOpen = false; onPlay() },
                )
                DropdownMenuItem(
                    text = { Text("Ajouter en tête de file") },
                    leadingIcon = { Icon(Icons.Default.PlaylistPlay, contentDescription = null) },
                    onClick = { menuOpen = false; onAddNext() },
                )
                DropdownMenuItem(
                    text = { Text("Ajouter à la fin de la file") },
                    leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null) },
                    onClick = { menuOpen = false; onAddEnd() },
                )
                DropdownMenuItem(
                    text = { Text(if (track.isDownloaded) "Déjà téléchargé" else "Télécharger") },
                    leadingIcon = {
                        Icon(if (track.isDownloaded) Icons.Default.Check else Icons.Default.Download, contentDescription = null)
                    },
                    enabled = !track.isDownloaded,
                    onClick = { menuOpen = false; onDownload() },
                )
                DropdownMenuItem(
                    text = { Text(if (track.isFavorite) "Retirer des favoris" else "Ajouter aux favoris") },
                    leadingIcon = {
                        Icon(if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = null)
                    },
                    onClick = { menuOpen = false; onToggleFavorite() },
                )
                DropdownMenuItem(
                    text = { Text("Ajouter à une playlist") },
                    leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null) },
                    onClick = { menuOpen = false; onAddToPlaylist() },
                )
            }
        }
    }
}
