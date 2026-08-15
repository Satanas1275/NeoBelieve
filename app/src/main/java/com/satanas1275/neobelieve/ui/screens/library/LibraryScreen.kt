package com.satanas1275.neobelieve.ui.screens.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.satanas1275.neobelieve.ui.MainViewModel
import com.satanas1275.neobelieve.ui.screens.search.TrackRow

@Composable
fun LibraryScreen(viewModel: MainViewModel) {
    val downloads by viewModel.downloads.collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Téléchargements", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        if (downloads.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Rien de téléchargé pour l'instant.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn {
                items(downloads, key = { it.id }) { track ->
                    TrackRow(
                        track = track,
                        onPlay = { viewModel.playFromPlaylist(downloads, downloads.indexOf(track)) },
                        onDownload = { /* déjà téléchargé */ },
                    )
                }
            }
        }
    }
}
