package com.lyric.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.lyric.core.*
import kotlinx.coroutines.flow.*
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

    val grid = store.data.map { it[booleanPreferencesKey("grid")] ?: true }
    val sortTitle = store.data.map { it[booleanPreferencesKey("sort_title")] ?: false }

    suspend fun shelf(grid: Boolean? = null, sortTitle: Boolean? = null) =
        store.edit { p ->
            grid?.let { p[booleanPreferencesKey("grid")] = it }
            sortTitle?.let { p[booleanPreferencesKey("sort_title")] = it }
        }

    suspend fun seeded() = store.edit { it[booleanPreferencesKey("seeded")] = true }

    suspend fun isSeeded() = store.data.first()[booleanPreferencesKey("seeded")] ?: false
}
