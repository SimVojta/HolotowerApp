package com.holotower.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.holotower.app.data.network.ChallengeGate
import com.holotower.app.data.repository.BoardRepository
import com.holotower.app.ui.catalog.CatalogScreen
import com.holotower.app.ui.challenge.ChallengeOverlay
import com.holotower.app.ui.globalentry.GlobalEntryScreen
import com.holotower.app.ui.thread.NewThreadComposerScreen
import com.holotower.app.ui.thread.ThreadScreen
import com.holotower.app.viewmodel.CatalogViewModel
import com.holotower.app.viewmodel.ThreadViewModel

private const val ROUTE_CATALOG = "catalog"

@Composable
fun NavGraph(board: String = "hlgg") {
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val challenge by ChallengeGate.request.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = ROUTE_CATALOG
        ) {
            composable(ROUTE_CATALOG) {
                val catalogViewModel: CatalogViewModel = viewModel()
                CatalogScreen(
                    board = board,
                    vm = catalogViewModel,
                    isCurrentDestination = currentBackStackEntry?.destination?.route == ROUTE_CATALOG,
                    onThreadClick = { threadNo -> navController.navigate("thread/$threadNo") },
                    onNewThreadSwipe = { navController.navigate("new-thread") },
                    onGlobalEntryClick = { navController.navigate("global-entry") }
                )
            }

            composable("global-entry") {
                GlobalEntryScreen(onBack = { navController.popBackStack() })
            }

            composable("new-thread") {
                val catalogBackStackEntry = remember(navController) {
                    navController.getBackStackEntry(ROUTE_CATALOG)
                }
                val catalogViewModel: CatalogViewModel = viewModel(catalogBackStackEntry)
                NewThreadComposerScreen(
                    board = board,
                    onBack = { navController.popBackStack() },
                    onPosted = { newThreadNo ->
                        catalogViewModel.load(boardName = board, forceRefresh = true)
                        if (newThreadNo != null) {
                            navController.navigate("thread/$newThreadNo") {
                                popUpTo(ROUTE_CATALOG)
                            }
                        } else {
                            navController.popBackStack()
                        }
                    }
                )
            }

            composable(
                route = "thread/{threadNo}",
                arguments = listOf(navArgument("threadNo") { type = NavType.LongType })
            ) { backStackEntry ->
                val threadNo = backStackEntry.arguments?.getLong("threadNo") ?: 0L
                val context = LocalContext.current
                val catalogBackStackEntry = remember(navController) {
                    navController.getBackStackEntry(ROUTE_CATALOG)
                }
                val catalogViewModel: CatalogViewModel = viewModel(catalogBackStackEntry)
                val threadRepo = remember(context.applicationContext, board, threadNo) {
                    BoardRepository(context.applicationContext)
                }
                val threadViewModel: ThreadViewModel = viewModel(
                    factory = ThreadViewModel.Factory(board, threadNo, threadRepo)
                )
                val isCurrentDestination = currentBackStackEntry?.destination?.route == "thread/{threadNo}" &&
                    currentBackStackEntry?.arguments?.getLong("threadNo") == threadNo
                ThreadScreen(
                    board = board,
                    threadNo = threadNo,
                    vm = threadViewModel,
                    isCurrentDestination = isCurrentDestination,
                    onThreadCountsUpdated = { replies, images ->
                        catalogViewModel.updateThreadCounts(board, threadNo, replies, images)
                    },
                    onBack = { navController.popBackStack() },
                    onOpenThread = { targetThreadNo ->
                        navController.navigate("thread/$targetThreadNo") { launchSingleTop = true }
                    }
                )
            }
        }

        // Drawn above every screen; requests that hit Cloudflare wait until it closes.
        challenge?.let { ChallengeOverlay(it) }
    }
}
