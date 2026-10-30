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
            val app = LocalContext.current.applicationContext as LyricApp
            val preferences by app.repository.settings.global.collectAsState(initial = com.lyric.core.ReaderSettings())
            val me: MeViewModel = viewModel(factory = MeViewModel.factory(app))
            LyricTheme(preferences) {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "shelf") {
                    composable("shelf") {
                        val vm: ShelfViewModel = viewModel(factory = LyricFactory(app))
                        ShelfScreen(vm, me = { MeScreen(me) { nav.navigate(it) } }) { nav.navigate("reader/$it/-1/0/0") }
                    }
                    composable("bookmarks") {
                        BookmarksScreen(me, { nav.popBackStack() }) { id, loc ->
                            nav.navigate("reader/$id/${loc.chapterIndex}/${loc.charOffset}/${loc.page}")
                        }
                    }
                    composable("stats") { StatsScreen(me) { nav.popBackStack() } }
                    composable("settings/{section}") { entry ->
                        SettingsScreen(me, entry.arguments?.getString("section").orEmpty()) { nav.popBackStack() }
                    }
                    composable("reader/{id}/{chapter}/{offset}/{page}") { entry ->
                        val id = requireNotNull(entry.arguments?.getString("id"))
                        val vm: ReaderViewModel = viewModel(key = id, factory = LyricFactory(app, id))
                        LaunchedEffect(entry) {
                            val chapter = entry.arguments?.getString("chapter")?.toIntOrNull() ?: -1
                            if (chapter >= 0) vm.jump(com.lyric.core.Locator(chapter,
                                entry.arguments?.getString("offset")?.toIntOrNull() ?: 0,
                                entry.arguments?.getString("page")?.toIntOrNull() ?: 0))
                        }
                        ReaderScreen(vm) { vm.pause(); nav.popBackStack() }
                    }
                }
            }
        }
    }
}
