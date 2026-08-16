package com.satanas1275.neobelieve.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.satanas1275.neobelieve.data.model.Track
import com.satanas1275.neobelieve.ui.MainViewModel

@Composable
fun HomeScreen(viewModel: MainViewModel, onOpenSearch: () -> Unit) {
    val favorites by viewModel.favorites.collectAsState(initial = emptyList())
    val history by viewModel.history.collectAsState(initial = emptyList())
    val recommended by viewModel.recommendedTracks.collectAsState()
    val isLoadingRecs by viewModel.isLoadingRecommendations.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val playlists by viewModel.playlists.collectAsState(initial = emptyList())

    LaunchedEffect(Unit) { viewModel.refreshRecommendations() }

    // Accès rapide : favoris d'abord, puis titres récemment écoutés, dédupliqués, max 8 (la 9e case = aléatoire).
    val quickAccess = remember(favorites, history) {
        val fromHistory = history.map { Track(it.trackId, it.title, it.artist, 0, it.thumbnailUrl) }
        (favorites + fromHistory).distinctBy { it.id }.take(8)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // Barre de recherche = juste une entrée vers la vraie page de recherche.
        Surface(
            onClick = onOpenSearch,
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text("Chercher un titre, un artiste...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Accès rapide", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.heightIn(max = 400.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(quickAccess, key = { it.id }) { track ->
                QuickAccessCell(
                    title = track.title,
                    thumbnailUrl = track.thumbnailUrl,
                    onClick = { viewModel.playSingle(track) },
                )
            }
            // Complète jusqu'à 8 cases avec des placeholders vides si pas assez d'historique/favoris.
            items(8 - quickAccess.size) {
                EmptyQuickAccessCell()
            }
            // 9e case, en bas à droite : découverte aléatoire.
            item {
                RandomDiscoveryCell(isLoading = isDiscovering, onClick = { viewModel.playRandomDiscovery() })
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Titres recommandés", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        when {
            isLoadingRecs -> LinearProgressIndicator(Modifier.fillMaxWidth())
            recommended.isEmpty() -> Text(
                "Écoute quelques titres pour débloquer des recommandations.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(recommended, key = { it.id }) { track ->
                    RecommendationCard(track = track, onClick = { viewModel.playSingle(track) })
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Tes playlists", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        if (playlists.isEmpty()) {
            Text("Pas encore de playlist — t'en crées une depuis la Bibliothèque.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(playlists, key = { it.id }) { playlist ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(120.dp),
                    ) {
                        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.BottomStart) {
                            Text(playlist.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun QuickAccessCell(title: String, thumbnailUrl: String?, onClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(model = thumbnailUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(4.dp),
        ) {
            Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EmptyQuickAccessCell() {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun RandomDiscoveryCell(isLoading: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(enabled = !isLoading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Casino, contentDescription = "Découverte aléatoire", tint = MaterialTheme.colorScheme.onPrimary)
                Text("Surprends-moi", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun RecommendationCard(track: Track, onClick: () -> Unit) {
    Column(
        Modifier
            .width(120.dp)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.size(120.dp).clip(RoundedCornerShape(10.dp))) {
            if (track.thumbnailUrl != null) {
                AsyncImage(model = track.thumbnailUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MusicNote, contentDescription = null)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
