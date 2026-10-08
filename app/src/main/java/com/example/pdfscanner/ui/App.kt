package com.example.pdfscanner.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.pdfscanner.MainViewModel

@Composable
fun App(vm: MainViewModel = viewModel()) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                vm = vm,
                onOpen = { id -> nav.navigate("doc/$id") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable(
            route = "doc/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            DocumentScreen(
                id = entry.arguments?.getString("id").orEmpty(),
                vm = vm,
                onBack = { nav.popBackStack() },
                onEditPages = { docId -> nav.navigate("pages/$docId") },
            )
        }
        composable(
            route = "pages/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            PageEditorScreen(
                id = entry.arguments?.getString("id").orEmpty(),
                vm = vm,
                onBack = { nav.popBackStack() },
            )
        }
        composable("settings") {
            SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
        }
    }
}
