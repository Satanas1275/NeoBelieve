package com.satanas1275.neobelieve.ui.screens.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.satanas1275.neobelieve.data.model.Track
import com.satanas1275.neobelieve.ui.MainViewModel
import com.satanas1275.neobelieve.ui.components.PlaylistPickerHost
import com.satanas1275.neobelieve.ui.components.TrackRow

/** Page de recherche à part entière (pas un onglet) : ouverte depuis la barre en haut de l'accueil. */
@Composable
fun SearchScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val query by viewModel.searchQuery.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val loadingTrackId by viewModel.loadingTrackId.collectAsState()
    val downloadingIds by viewModel.downloadingTrackIds.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    var trackForPlaylistDialog by remember { mutableStateOf<Track?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Retour")
            }
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
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
        }

        Spacer(Modifier.height(12.dp))
        if (isSearching) LinearProgressIndicator(Modifier.fillMaxWidth())

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(results, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    isLoading = loadingTrackId == track.id,
                    isDownloading = downloadingIds.contains(track.id),
                    downloadProgress = downloadProgress[track.id],
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
