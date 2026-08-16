package com.satanas1275.neobelieve.ui.screens.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.satanas1275.neobelieve.ui.MainViewModel

/**
 * Onglet Compte : pas de vrai système de comptes rocknite-studio pour l'instant
 * (ça, c'est la phase backend). En attendant, juste les stats d'écoute locales.
 *
 * Note : pas de "style musical le plus écouté" -> l'extraction YouTube ne fournit
 * pas de genre fiable par morceau. L'artiste le plus écouté est le proxy le plus honnête.
 */
@Composable
fun AccountScreen(viewModel: MainViewModel) {
    val topArtists by viewModel.topArtists.collectAsState()
    val topTracks by viewModel.topTracks.collectAsState()
    val totalPlays by viewModel.totalPlays.collectAsState()

    LaunchedEffect(Unit) { viewModel.refreshStats() }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Satanas1275", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Compte rocknite-studio à venir",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("$totalPlays écoutes au total", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Text("Artistes les plus écoutés", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
        }

        if (topArtists.isEmpty()) {
            item { Text("Pas encore de stats, écoute des trucs !", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(topArtists) { stat ->
                ListItem(
                    headlineContent = { Text(stat.artist) },
                    trailingContent = { Text("${stat.plays} écoute${if (stat.plays > 1) "s" else ""}") },
                )
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            Text("Titres les plus écoutés", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
        }

        if (topTracks.isEmpty()) {
            item { Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(topTracks) { stat ->
                ListItem(
                    headlineContent = { Text(stat.title) },
                    supportingContent = { Text(stat.artist) },
                    trailingContent = { Text("${stat.plays}×") },
                )
            }
        }
    }
}
