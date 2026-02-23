package com.holotower.app.navigation

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.holotower.app.ui.catalog.CatalogScreen
import com.holotower.app.ui.challenge.CloudflareScreen
import com.holotower.app.ui.globalentry.GlobalEntryScreen
import com.holotower.app.ui.thread.NewThreadComposerScreen
import com.holotower.app.ui.thread.ThreadScreen

@Composable
fun NavGraph(board: String = "hlgg", sharedWebView: WebView) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "cloudflare"
    ) {
        composable("cloudflare") {
            CloudflareScreen(
                targetUrl = "https://holotower.org/$board/",
                sharedWebView = sharedWebView,
                onChallengePassed = {
                    navController.navigate("catalog") {
                        popUpTo("cloudflare") { inclusive = true }
                    }
                }
            )
        }

        composable("catalog") {
            CatalogScreen(
                board = board,
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
                    navController.navigate("cloudflare") {
                        popUpTo("catalog") { inclusive = true }
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
            ThreadScreen(
                board = board,
                threadNo = threadNo,
                onBack = { navController.popBackStack() },
                onOpenThread = { targetThreadNo ->
                    navController.navigate("thread/$targetThreadNo") {
                        launchSingleTop = true
                    }
                },
                onRefreshCloudflare = {
                    navController.navigate("cloudflare") {
                        popUpTo("catalog") { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }
    }
}
