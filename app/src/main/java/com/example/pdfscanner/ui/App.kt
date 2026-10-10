package com.example.pdfscanner.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.pdfscanner.MainViewModel
import com.example.pdfscanner.camera.CameraScreen

@Composable
fun App(vm: MainViewModel = viewModel()) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                vm = vm,
                onOpen = { id -> nav.navigate("doc/$id") },
                onSettings = { nav.navigate("settings") },
                onCamera = { batch -> nav.navigate(if (batch) "camera/batch" else "camera/single") },
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
                onAddPages = { docId -> nav.navigate("camera/append/$docId") },
            )
        }
        composable("camera/single") {
            CameraScreen(vm = vm, batch = false, appendId = null, onClose = { nav.popBackStack() })
        }
        composable("camera/batch") {
            CameraScreen(vm = vm, batch = true, appendId = null, onClose = { nav.popBackStack() })
        }
        composable(
            route = "camera/append/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            CameraScreen(
                vm = vm,
                batch = true,
                appendId = entry.arguments?.getString("id").orEmpty(),
                onClose = { nav.popBackStack() },
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
            SettingsScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onOpenApp = { nav.navigate("settings/app") },
                onOpenScan = { nav.navigate("settings/scan") },
            )
        }
        composable("settings/app") {
            AppSettingsScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable("settings/scan") {
            ScanSettingsScreen(vm = vm, onBack = { nav.popBackStack() })
        }
    }
}
