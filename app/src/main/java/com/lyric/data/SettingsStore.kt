package com.lyric.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.lyric.core.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*

private val Context.dataStore by preferencesDataStore("lyric_settings")

enum class SettingsScope(val label: String) {
    GLOBAL("全局"),
    TYPE("文字书籍"),
    BOOK("本书"),
}

class SettingsStore(
    context: Context,
    private val store: DataStore<Preferences> = context.dataStore,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun key(scope: SettingsScope, id: String, type: ContentType) =
        stringPreferencesKey(
            when (scope) {
                SettingsScope.GLOBAL -> "global"
                SettingsScope.TYPE -> "type_${type.name}"
                SettingsScope.BOOK -> "book_$id"
            }
        )

    fun settings(id: String = "", type: ContentType = ContentType.TEXT): Flow<ReaderSettings> =
        store.data
            .map { prefs ->
                val fields = json.encodeToJsonElement(ReaderSettings()).jsonObject.toMutableMap()
                SettingsScope.entries.forEach { scope ->
                    prefs[key(scope, id, type)]?.let { raw ->
                        runCatching { json.parseToJsonElement(raw).jsonObject }
                            .getOrNull()
                            ?.let { fields.putAll(it) }
                    }
                }
                runCatching { json.decodeFromJsonElement<ReaderSettings>(JsonObject(fields)) }
                    .getOrDefault(ReaderSettings())
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    suspend fun update(
        scope: SettingsScope,
        id: String,
        before: ReaderSettings,
        after: ReaderSettings,
        type: ContentType = ContentType.TEXT,
    ) {
        val old = json.encodeToJsonElement(before).jsonObject
        val next = json.encodeToJsonElement(after).jsonObject
        store.edit { prefs ->
            val key = key(scope, id, type)
            val patch =
                prefs[key]?.let {
                    runCatching { json.parseToJsonElement(it).jsonObject.toMutableMap() }
                        .getOrNull()
                } ?: mutableMapOf()
            next.filter { (k, v) -> old[k] != v }.forEach { (k, v) -> patch[k] = v }
            prefs[key] = JsonObject(patch).toString()
        }
    }

    suspend fun reset(scope: SettingsScope, id: String, type: ContentType = ContentType.TEXT) {
        store.edit { it.remove(key(scope, id, type)) }
    }

    /** Me edits only the global layer, never a merged book/type value. */
    val global = store.data.map { prefs ->
        prefs[stringPreferencesKey("global")]?.let {
            runCatching { json.decodeFromString<ReaderSettings>(it) }.getOrNull()
        } ?: ReaderSettings()
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    val ownBookCount = store.data.map { prefs ->
        prefs.asMap().count { (key, value) -> key.name.startsWith("book_") && value.toString() != "{}" }
    }

    suspend fun resetBookInheritance() {
        store.edit { prefs -> prefs.asMap().keys.filter { it.name.startsWith("book_") }.forEach { prefs.remove(it) } }
    }

    suspend fun snapshot(): SettingsSnapshot {
        val map = store.data.first().asMap()
        return SettingsSnapshot(
            map.filterValues { it is String }.mapKeys { it.key.name }.mapValues { it.value as String },
            map.filterValues { it is Boolean }.mapKeys { it.key.name }.mapValues { it.value as Boolean },
        )
    }

    fun validate(snapshot: SettingsSnapshot) {
        snapshot.strings.forEach { (key, raw) ->
            if (key == "shelf_section") {
                require(ShelfSection.entries.any { it.name == raw }) { "未知分区" }
                return@forEach
            }
            require(key == "global" || key.startsWith("type_") || key.startsWith("book_")) { "未知设置项" }
            json.decodeFromString<ReaderSettings>(raw)
        }
        require(snapshot.flags.keys.all { it in setOf("grid", "sort_title", "seeded", "shelf_compact", "comic_shelf_compact", "focus_custom") }) { "未知设置项" }
    }

    suspend fun restore(snapshot: SettingsSnapshot) {
        validate(snapshot)
        store.edit { prefs ->
            prefs.clear()
            snapshot.strings.forEach { (key, value) -> prefs[stringPreferencesKey(key)] = value }
            snapshot.flags.forEach { (key, value) -> prefs[booleanPreferencesKey(key)] = value }
            // Restoring a library must not add example books on the next launch.
            prefs[booleanPreferencesKey("seeded")] = true
        }
    }

    val shelfSection = store.data.map { prefs ->
        ShelfSection.entries.firstOrNull { it.name == prefs[stringPreferencesKey("shelf_section")] }
            ?: ShelfSection.BOOK
    }.distinctUntilChanged()

    suspend fun shelfSection(section: ShelfSection) = store.edit {
        it[stringPreferencesKey("shelf_section")] = section.name
    }

    val grid = store.data.map { it[booleanPreferencesKey("grid")] ?: true }
    val sortTitle = store.data.map { it[booleanPreferencesKey("sort_title")] ?: false }

    /** 0 最近阅读 · 1 导入时间 · 2 书名 (falls back to the pre-0.5 boolean). */
    val shelfSort = store.data.map { it[intPreferencesKey("shelf_sort")] ?: if (it[booleanPreferencesKey("sort_title")] == true) 2 else 0 }
    val comicShelfSort = store.data.map { it[intPreferencesKey("comic_shelf_sort")] ?: 0 }
    suspend fun shelfSort(mode: Int, section: ShelfSection = ShelfSection.BOOK) = store.edit {
        it[intPreferencesKey(if (section == ShelfSection.COMIC) "comic_shelf_sort" else "shelf_sort")] = mode
    }

    suspend fun shelf(grid: Boolean? = null, sortTitle: Boolean? = null) =
        store.edit { p ->
            grid?.let { p[booleanPreferencesKey("grid")] = it }
            sortTitle?.let { p[booleanPreferencesKey("sort_title")] = it }
        }

    suspend fun seeded() = store.edit { it[booleanPreferencesKey("seeded")] = true }

    suspend fun isSeeded() = store.data.first()[booleanPreferencesKey("seeded")] ?: false

    private fun compactKey(section: ShelfSection) =
        booleanPreferencesKey(if (section == ShelfSection.COMIC) "comic_shelf_compact" else "shelf_compact")

    fun shelfCompact(section: ShelfSection) =
        store.data.map { it[compactKey(section)] ?: false }.distinctUntilChanged()

    suspend fun shelfCompact(section: ShelfSection, compact: Boolean) = store.edit { it[compactKey(section)] = compact }

    val focusPlannedMin = store.data.map {
        val raw = it[intPreferencesKey("focus_planned_min")] ?: 25
        val stepped = ((raw + 2) / 5) * 5
        stepped.coerceIn(5, 180)
    }.distinctUntilChanged()
    val focusCustom = store.data.map { it[booleanPreferencesKey("focus_custom")] ?: false }.distinctUntilChanged()
    suspend fun focusPlan(minutes: Int, custom: Boolean) = store.edit {
        val stepped = ((minutes + 2) / 5) * 5
        it[intPreferencesKey("focus_planned_min")] = stepped.coerceIn(5, 180)
        it[booleanPreferencesKey("focus_custom")] = custom
    }
}


@kotlinx.serialization.Serializable
data class SettingsSnapshot(val strings: Map<String, String> = emptyMap(), val flags: Map<String, Boolean> = emptyMap())
