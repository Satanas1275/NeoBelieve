package com.satanas1275.neobelieve.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.satanas1275.neobelieve.data.local.PlaylistEntity

@Composable
fun AddToPlaylistDialog(
    playlists: List<PlaylistEntity>,
    onDismiss: () -> Unit,
    onPick: (playlistId: Long, atStart: Boolean) -> Unit,
    onCreateNew: (name: String, atStart: Boolean) -> Unit,
) {
    var creatingNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var addAtStart by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (creatingNew) "Nouvelle playlist" else "Ajouter à une playlist") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ajouter au début", modifier = Modifier.weight(1f))
                    Switch(checked = addAtStart, onCheckedChange = { addAtStart = it })
                }
                Spacer(Modifier.height(8.dp))

                if (creatingNew) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Nom de la playlist") },
                        singleLine = true,
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        item {
                            TextButton(onClick = { creatingNew = true }) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Créer une nouvelle playlist")
                            }
                        }
                        items(playlists, key = { it.id }) { playlist ->
                            ListItem(
                                headlineContent = { Text(playlist.name) },
                                modifier = Modifier.clickable { onPick(playlist.id, addAtStart) },
                            )
                        }
                        if (playlists.isEmpty()) {
                            item { Text("Aucune playlist pour l'instant.", modifier = Modifier.padding(8.dp)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (creatingNew) {
                TextButton(
                    onClick = { if (newName.isNotBlank()) onCreateNew(newName.trim(), addAtStart) },
                    enabled = newName.isNotBlank(),
                ) { Text("Créer et ajouter") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}
