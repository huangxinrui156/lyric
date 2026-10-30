package com.lyric

import android.app.Application
import androidx.room.Room
import com.lyric.data.*
import kotlinx.coroutines.*

class LyricApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var repository: LibraryRepository
        private set

    lateinit var backup: BackupManager
        private set
    lateinit var storage: StorageInspector
        private set

    override fun onCreate() {
        super.onCreate()
        val database = Room.databaseBuilder(this, LibraryDatabase::class.java, "lyric.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
        repository = LibraryRepository(this, database, SettingsStore(this))
        backup = BackupManager(this, database, repository.settings)
        storage = StorageInspector(this, repository)
        applicationScope.launch {
            repository.seed()
            repository.checkSources()
        }
        applicationScope.launch(Dispatchers.IO) {
            runCatching { com.lyric.reader.KillCheck.verifyAfterRestart(this@LyricApp, repository) }
        }
    }
}
