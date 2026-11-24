package org.mdpeds.logbook.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.mdpeds.logbook.domain.logic.ReferenceData
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "user_prefs")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Non-sensitive UI preferences (DataStore). */
@Singleton
class UserPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    private val themeKey = stringPreferencesKey("theme_mode")
    private val notifiedKey = stringPreferencesKey("notified_ids")
    private val remindersKey = booleanPreferencesKey("reminders_enabled")

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { p ->
        runCatching { ThemeMode.valueOf(p[themeKey] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM)
    }

    val remindersEnabled: Flow<Boolean> = context.dataStore.data.map { it[remindersKey] ?: true }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[themeKey] = mode.name }
    }

    suspend fun setRemindersEnabled(enabled: Boolean) {
        context.dataStore.edit { it[remindersKey] = enabled }
    }

    /** Ids of in-app notifications already shown as system notifications. */
    suspend fun notifiedIds(): Set<String> =
        context.dataStore.data.first()[notifiedKey]?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    suspend fun setNotifiedIds(ids: Set<String>) {
        context.dataStore.edit { it[notifiedKey] = ids.toList().takeLast(500).joinToString(",") }
    }

    suspend fun clearUserData() {
        context.dataStore.edit { it.remove(notifiedKey) }
    }
}

/** Reference data bundled in the APK (from /reference-data); used before the first sync and offline. */
@Singleton
class BundledReference @Inject constructor(@ApplicationContext private val context: Context) {
    val data: ReferenceData by lazy {
        fun read(name: String) = context.assets.open(name).bufferedReader().use { it.readText() }
        ReferenceData.parse(read("templates.json"), read("competencies.json"), read("targets.json"), read("documents.json"))
    }
}
