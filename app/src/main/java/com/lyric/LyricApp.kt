package com.lyric

import android.app.Application
import androidx.room.Room
import com.lyric.data.*
import kotlinx.coroutines.*

class LyricApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var repository: LibraryRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val database = Room.databaseBuilder(this, LibraryDatabase::class.java, "lyric.db").build()
        repository = LibraryRepository(this, database, SettingsStore(this))
        applicationScope.launch {
            repository.seed()
            repository.checkSources()
        }
    }
}
