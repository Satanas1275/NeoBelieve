package com.satanas1275.neobelieve

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.satanas1275.neobelieve.ui.MainViewModel
import com.satanas1275.neobelieve.ui.screens.library.LibraryScreen
import com.satanas1275.neobelieve.ui.screens.player.MiniPlayerBar
import com.satanas1275.neobelieve.ui.screens.player.PlayerScreen
import com.satanas1275.neobelieve.ui.screens.search.SearchScreen
import com.satanas1275.neobelieve.ui.theme.NeoBelieveTheme

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("search", "Recherche", Icons.Default.Search),
    Tab("library", "Bibliothèque", Icons.Default.LibraryMusic),
)

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NeoBelieveTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    NeoBelieveApp(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NeoBelieveApp(viewModel: MainViewModel) {
    val navController = rememberNavController()
    var selectedIndex by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    var showFullPlayer by remember { mutableStateOf(false) }

    val currentTrack by viewModel.player.currentTrack.collectAsState()
    val isPlaying by viewModel.player.isPlaying.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.errorShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column {
                // Mini-player façon YouTube Music : toujours visible dès qu'un titre
                // est chargé, au-dessus de la nav bar, pas d'onglet dédié.
                currentTrack?.let { track ->
                    MiniPlayerBar(
                        track = track,
                        isPlaying = isPlaying,
                        onTogglePlay = { viewModel.player.togglePlayPause() },
                        onSkipNext = { viewModel.player.skipNext() },
                        onExpand = { showFullPlayer = true },
                    )
                }
                NavigationBar {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = selectedIndex == index,
                            onClick = {
                                selectedIndex = index
                                navController.navigate(tab.route) {
                                    launchSingleTop = true
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "search",
            modifier = androidx.compose.ui.Modifier.padding(innerPadding),
        ) {
            composable("search") { SearchScreen(viewModel) }
            composable("library") { LibraryScreen(viewModel) }
        }
    }

    if (showFullPlayer) {
        ModalBottomSheet(onDismissRequest = { showFullPlayer = false }) {
            PlayerScreen(viewModel)
        }
    }
}
