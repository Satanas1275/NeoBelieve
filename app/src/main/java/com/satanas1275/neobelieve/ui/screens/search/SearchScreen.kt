package com.satanas1275.neobelieve.ui.screens.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.satanas1275.neobelieve.data.model.Track
import com.satanas1275.neobelieve.ui.MainViewModel

@Composable
fun SearchScreen(viewModel: MainViewModel) {
    val query by viewModel.searchQuery.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val loadingTrackId by viewModel.loadingTrackId.collectAsState()
    val downloadingIds by viewModel.downloadingTrackIds.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Chercher un titre, un artiste...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onSearch = { viewModel.runSearch() },
            ),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Search,
            ),
        )

        Spacer(Modifier.height(12.dp))

        if (isSearching) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        // LazyColumn : on ne compose que les lignes visibles, important pour rester
        // fluide sur du matos limité (une des consignes "opti" de base).
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(results, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    isLoading = loadingTrackId == track.id,
                    isDownloading = downloadingIds.contains(track.id),
                    onPlay = { viewModel.playSingle(track) },
                    onDownload = { viewModel.download(track) },
                )
            }
        }
    }
}

@Composable
fun TrackRow(
    track: Track,
    isLoading: Boolean = false,
    isDownloading: Boolean = false,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
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

        // Etat du bouton download : spinner pendant le téléchargement, check si déjà fait.
        when {
            isDownloading -> CircularProgressIndicator(Modifier.size(24.dp).padding(end = 12.dp), strokeWidth = 2.dp)
            track.isDownloaded -> Icon(Icons.Default.Check, contentDescription = "Téléchargé", modifier = Modifier.padding(end = 12.dp))
            else -> IconButton(onClick = onDownload) {
                Icon(Icons.Default.Download, contentDescription = "Télécharger")
            }
        }

        // Etat du bouton play : spinner pendant la résolution du flux (extraction réseau).
        if (isLoading) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Lire")
            }
        }
    }
}
