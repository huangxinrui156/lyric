package com.lyric

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lyric.ui.*
import kotlinx.coroutines.*

class KillTestActivity : ReaderActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LyricApp
        lifecycleScope.launch {
            val row = withContext(Dispatchers.IO) { app.repository.dao.all().first { it.name == ReaderProbe.NAME } }
            setContent {
                LyricTheme {
                    val vm: ReaderViewModel = viewModel(factory = LyricFactory(app, row.id))
                    ReaderProbe.vm = vm
                    ReaderScreen(vm) { finish() }
                }
            }
        }
    }
    override fun onDestroy() {
        ReaderProbe.vm = null
        super.onDestroy()
    }
}
