package com.lyric

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import com.lyric.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LyricTheme {
                val app = LocalContext.current.applicationContext as LyricApp
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "shelf") {
                    composable("shelf") {
                        val vm: ShelfViewModel = viewModel(factory = LyricFactory(app))
                        ShelfScreen(vm) { nav.navigate("reader/$it") }
                    }
                    composable("reader/{id}") { entry ->
                        val id = requireNotNull(entry.arguments?.getString("id"))
                        val vm: ReaderViewModel =
                            viewModel(key = id, factory = LyricFactory(app, id))
                        ReaderScreen(vm) {
                            vm.flush()
                            nav.popBackStack()
                        }
                    }
                }
            }
        }
    }
}
