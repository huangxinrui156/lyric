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
        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder()
                .detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())
            android.os.StrictMode.setVmPolicy(android.os.StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects().penaltyLog().build())
        }
        applicationScope.launch(Dispatchers.IO) { com.lyric.reader.PerfLog.initialize(this@LyricApp) }
        val database = Room.databaseBuilder(this, LibraryDatabase::class.java, "lyric.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build()
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
