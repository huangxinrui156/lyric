package com.lyric

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.lyric.core.BookFormats
import com.lyric.data.bookUris
import com.lyric.data.isBookOpenIntent
import com.lyric.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

class MainActivity : ReaderActivity() {
    private val inbound = MutableStateFlow<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) inbound.value = intent
        enableEdgeToEdge(navigationBarStyle = androidx.activity.SystemBarStyle.auto(
            android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
        ))
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContent {
            val app = LocalContext.current.applicationContext as LyricApp
            val preferences by app.repository.settings.global.collectAsState(initial = com.lyric.core.ReaderSettings())
            val me: MeViewModel = viewModel(factory = MeViewModel.factory(app))
            // Activity-scoped so the focus session survives shelf ↔ focus ↔ reader navigation.
            val desk: DeskViewModel = viewModel(factory = DeskViewModel.factory(app))
            LyricTheme(preferences) {
                val nav = rememberNavController()
                fun openReader(route: String, builder: androidx.navigation.NavOptionsBuilder.() -> Unit = {}) {
                    com.lyric.reader.PerfLog.beginOpen()
                    nav.navigate(route, builder)
                }
                val incoming by inbound.collectAsState()
                LaunchedEffect(incoming) {
                    val request = incoming?.takeIf { it.isBookOpenIntent() } ?: return@LaunchedEffect
                    val uris = request.bookUris()
                    if (uris.isEmpty()) return@LaunchedEffect
                    withContext(Dispatchers.IO) { app.repository.importUris(uris, grantFlags = request.flags, openWith = true) }
                }
                val imports by app.repository.imports.collectAsState()
                LaunchedEffect(imports.running, imports.batchId, imports.openBookId) {
                    if (imports.running) return@LaunchedEffect
                    val id = app.repository.consumeOpenBook() ?: return@LaunchedEffect
                    openReader("reader/$id/-1/0/0") { launchSingleTop = true }
                }
                val importSnackbar = remember { SnackbarHostState() }
                LaunchedEffect(imports.running, imports.batchId, imports.message) {
                    if (!imports.running && imports.message == BookFormats.UNSUPPORTED_OPEN_WITH) {
                        importSnackbar.showSnackbar(imports.message)
                    }
                }
                Box(Modifier.fillMaxSize()) {
                NavHost(navController = nav, startDestination = "shelf") {
                    composable("shelf") {
                        val vm: ShelfViewModel = viewModel(factory = LyricFactory(app))
                        ShelfScreen(
                            vm,
                            me = { MeScreen(me) { nav.navigate(it) } },
                            desk = {
                                DeskScreen(
                                    desk,
                                    openBook = { id, loc -> openReader("reader/$id/${loc.chapterIndex}/${loc.charOffset}/${loc.page}") },
                                    openFocus = { nav.navigate("focus") { launchSingleTop = true } },
                                    openNotes = { nav.navigate("notes") { launchSingleTop = true } },
                                    // Last saved position (chapter -1) and the timer resumes in the reader route.
                                    continueReading = { id -> openReader("reader/$id/-1/0/0") },
                                )
                            },
                        ) { openReader("reader/$it/-1/0/0") }
                    }
                    composable("focus") {
                        FocusRoute(desk, back = { nav.popBackStack("shelf", inclusive = false) }) { id ->
                            // Reader replaces the timer in the stack, so Back from reader lands on 案头.
                            openReader("reader/$id/-1/0/0") { popUpTo("focus") { inclusive = true } }
                        }
                    }
                    composable("notes") {
                        DeskNotesScreen(desk, { id, loc ->
                            openReader("reader/$id/${loc.chapterIndex}/${loc.charOffset}/${loc.page}")
                        }) { nav.popBackStack() }
                    }
                    composable("bookmarks") {
                        BookmarksScreen(me, { nav.popBackStack() }) { id, loc, bookmarkId ->
                            openReader("reader/$id/${loc.chapterIndex}/${loc.charOffset}/${loc.page}?bookmark=$bookmarkId")
                        }
                    }
                    composable("stats") { StatsScreen(me) { nav.popBackStack() } }
                    composable("settings/{section}") { entry ->
                        SettingsScreen(me, entry.arguments?.getString("section").orEmpty()) { nav.popBackStack() }
                    }
                    composable("reader/{id}/{chapter}/{offset}/{page}?bookmark={bookmark}",
                        arguments = listOf(navArgument("bookmark") { type = NavType.LongType; defaultValue = 0L })) { entry ->
                        val id = requireNotNull(entry.arguments?.getString("id"))
                        val chapter = entry.arguments?.getString("chapter")?.toIntOrNull() ?: -1
                        val initial = if (chapter >= 0) com.lyric.core.Locator(chapter,
                            entry.arguments?.getString("offset")?.toIntOrNull() ?: 0,
                            entry.arguments?.getString("page")?.toIntOrNull() ?: 0) else null
                        val vm: ReaderViewModel = viewModel(key = id, factory = LyricFactory(app, id,
                            initial, entry.arguments?.getLong("bookmark") ?: 0L))
                        // 0.8.3: a focus session only counts while its book is visibly open in the reader.
                        val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                        DisposableEffect(owner, id) {
                            val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                                if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) desk.resumeFocusFor(id)
                                if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) desk.pauseFocusFor(id)
                            }
                            owner.lifecycle.addObserver(obs)
                            onDispose { owner.lifecycle.removeObserver(obs); desk.pauseFocusFor(id) }
                        }
                        ReaderScreen(vm, drawerExtra = { FocusRemainingLabel(desk, id) }) { vm.pause(); nav.popBackStack() }
                    }
                }
                SnackbarHost(importSnackbar, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        inbound.value = intent
    }
}
