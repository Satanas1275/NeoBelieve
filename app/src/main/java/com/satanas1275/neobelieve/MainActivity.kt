package com.satanas1275.neobelieve

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.satanas1275.neobelieve.ui.MainViewModel
import com.satanas1275.neobelieve.ui.screens.account.AccountScreen
import com.satanas1275.neobelieve.ui.screens.home.HomeScreen
import com.satanas1275.neobelieve.ui.screens.library.LibraryScreen
import com.satanas1275.neobelieve.ui.screens.player.MiniPlayerBar
import com.satanas1275.neobelieve.ui.screens.player.PlayerScreen
import com.satanas1275.neobelieve.ui.screens.search.SearchScreen
import com.satanas1275.neobelieve.ui.theme.NeoBelieveTheme

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

// Recherche volontairement absente d'ici : c'est une page à part, pas un onglet
// (ouverte depuis la barre de recherche de l'accueil).
private val tabs = listOf(
    Tab("home", "Accueil", Icons.Default.Home),
    Tab("library", "Bibliothèque", Icons.Default.LibraryMusic),
    Tab("account", "Compte", Icons.Default.Person),
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
    val snackbarHostState = remember { SnackbarHostState() }
    var showFullPlayer by remember { mutableStateOf(false) }

    val currentTrack by viewModel.player.currentTrack.collectAsState()
    val isPlaying by viewModel.player.isPlaying.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.errorShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // Pas de bottom bar du tout sur la page de recherche : elle prend tout l'écran.
            if (currentRoute != "search") {
                Column {
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
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = currentRoute == tab.route,
                                onClick = { navigateToTab(navController, tab.route) },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = androidx.compose.ui.Modifier.padding(innerPadding),
        ) {
            composable("home") { HomeScreen(viewModel, onOpenSearch = { navController.navigate("search") }) }
            composable("library") { LibraryScreen(viewModel) }
            composable("account") { AccountScreen(viewModel) }
            composable("search") { SearchScreen(viewModel, onBack = { navController.popBackStack() }) }
        }
    }

    if (showFullPlayer) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showFullPlayer = false }, sheetState = sheetState) {
            PlayerScreen(viewModel)
        }
    }
}

private fun navigateToTab(navController: NavHostController, route: String) {
    navController.navigate(route) {
        launchSingleTop = true
        popUpTo(navController.graph.startDestinationId) { saveState = true }
        restoreState = true
    }
}
