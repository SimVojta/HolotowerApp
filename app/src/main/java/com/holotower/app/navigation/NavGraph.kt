package com.holotower.app.navigation

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.navArgument
import com.holotower.app.data.repository.BoardRepository
import com.holotower.app.ui.catalog.CatalogScreen
import com.holotower.app.ui.challenge.CloudflareScreen
import com.holotower.app.ui.globalentry.GlobalEntryScreen
import com.holotower.app.ui.thread.NewThreadComposerScreen
import com.holotower.app.ui.thread.ThreadScreen
import com.holotower.app.viewmodel.ThreadViewModel

private const val ROUTE_CLOUDFLARE = "cloudflare"
private const val ROUTE_CATALOG = "catalog"
private const val RESULT_FORCE_REFRESH_AFTER_CLOUDFLARE = "force_refresh_after_cloudflare"

@Composable
fun NavGraph(board: String = "hlgg", sharedWebView: WebView) {
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()

    NavHost(
        navController = navController,
        startDestination = ROUTE_CLOUDFLARE
    ) {
        composable(ROUTE_CLOUDFLARE) {
            CloudflareScreen(
                targetUrl = "https://holotower.org/$board/catalog.json",
                sharedWebView = sharedWebView,
                onChallengePassed = {
                    val previousEntry = navController.previousBackStackEntry
                    if (previousEntry != null) {
                        previousEntry.savedStateHandle[RESULT_FORCE_REFRESH_AFTER_CLOUDFLARE] =
                            System.currentTimeMillis()
                        navController.popBackStack()
                    } else {
                        navController.navigate(ROUTE_CATALOG) {
                            popUpTo(ROUTE_CLOUDFLARE) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(ROUTE_CATALOG) { backStackEntry ->
            val refreshToken by backStackEntry.savedStateHandle
                .getStateFlow(RESULT_FORCE_REFRESH_AFTER_CLOUDFLARE, 0L)
                .collectAsState()
            LaunchedEffect(refreshToken) {
                if (refreshToken != 0L) {
                    backStackEntry.savedStateHandle[RESULT_FORCE_REFRESH_AFTER_CLOUDFLARE] = 0L
                }
            }
            CatalogScreen(
                board = board,
                refreshAfterCloudflareToken = refreshToken,
                onThreadClick = { threadNo ->
                    navController.navigate("thread/$threadNo")
                },
                onNewThreadSwipe = {
                    navController.navigate("new-thread")
                },
                onGlobalEntryClick = {
                    navController.navigate("global-entry")
                },
                onRefreshCloudflare = {
                    navController.navigate(ROUTE_CLOUDFLARE) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable("global-entry") {
            GlobalEntryScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable("new-thread") {
            NewThreadComposerScreen(
                board = board,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "thread/{threadNo}",
            arguments = listOf(navArgument("threadNo") { type = NavType.LongType })
        ) { backStackEntry ->
            val threadNo = backStackEntry.arguments?.getLong("threadNo") ?: 0L
            val context = LocalContext.current
            val threadRepo = remember(context.applicationContext, board, threadNo) {
                BoardRepository(context.applicationContext)
            }
            val threadViewModel: ThreadViewModel = viewModel(
                factory = ThreadViewModel.Factory(board, threadNo, threadRepo)
            )
            val isCurrentDestination = currentBackStackEntry?.destination?.route == "thread/{threadNo}" &&
                currentBackStackEntry?.arguments?.getLong("threadNo") == threadNo
            val refreshToken by backStackEntry.savedStateHandle
                .getStateFlow(RESULT_FORCE_REFRESH_AFTER_CLOUDFLARE, 0L)
                .collectAsState()
            LaunchedEffect(refreshToken) {
                if (refreshToken != 0L) {
                    backStackEntry.savedStateHandle[RESULT_FORCE_REFRESH_AFTER_CLOUDFLARE] = 0L
                }
            }
            ThreadScreen(
                board = board,
                threadNo = threadNo,
                vm = threadViewModel,
                isCurrentDestination = isCurrentDestination,
                refreshAfterCloudflareToken = refreshToken,
                onBack = { navController.popBackStack() },
                onOpenThread = { targetThreadNo ->
                    navController.navigate("thread/$targetThreadNo") {
                        launchSingleTop = true
                    }
                },
                onRefreshCloudflare = {
                    navController.navigate(ROUTE_CLOUDFLARE) {
                        launchSingleTop = true
                    }
                }
            )
        }
    }
}
