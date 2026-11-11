package com.slp.camcast.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.slp.camcast.camera.CameraFacing
import com.slp.camcast.camera.StreamConfig
import com.slp.camcast.camera.StreamResolution
import kotlinx.coroutines.flow.first

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("settings")

/** Persists [AppSettings]. Whatever is stored is clamped on the way out, so a bad value can't break a start. */
class SettingsStore(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    /**
     * The stored settings. The first call on a fresh install generates a random password and saves it,
     * so the camera is never open with a default password.
     */
    suspend fun load(): AppSettings {
        val prefs = dataStore.data.first()
        val stored = prefs[PASSWORD]
        if (!stored.isNullOrEmpty()) return fromPreferences(prefs)
        val settings = fromPreferences(prefs).copy(password = PasswordGenerator.generate())
        save(settings)
        return settings
    }

    suspend fun save(settings: AppSettings) {
        dataStore.edit {
            it[FACING] = settings.stream.facing.name
            it[RESOLUTION] = settings.stream.resolution.name
            it[FPS] = settings.stream.fpsCap
            it[QUALITY] = settings.stream.jpegQuality
            it[PORT] = settings.port
            it[USERNAME] = settings.username
            it[PASSWORD] = settings.password
            it[START_ON_BOOT] = settings.startOnBoot
        }
    }

    private fun fromPreferences(p: Preferences): AppSettings {
        val defaults = StreamConfig()
        val stream = StreamConfig(
            facing = enumOrDefault(p[FACING], defaults.facing),
            resolution = enumOrDefault(p[RESOLUTION], defaults.resolution),
            fpsCap = p[FPS] ?: defaults.fpsCap,
            jpegQuality = p[QUALITY] ?: defaults.jpegQuality,
        ).sanitized()
        val port = p[PORT]?.takeIf { it in SettingsValidator.MIN_PORT..SettingsValidator.MAX_PORT }
        return AppSettings(
            stream = stream,
            port = port ?: AppSettings.DEFAULT_PORT,
            username = p[USERNAME]?.takeIf { it.isNotEmpty() } ?: AppSettings.DEFAULT_USERNAME,
            password = p[PASSWORD].orEmpty(),
            startOnBoot = p[START_ON_BOOT] ?: false,
        )
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        val FACING = stringPreferencesKey("facing")
        val RESOLUTION = stringPreferencesKey("resolution")
        val FPS = intPreferencesKey("fps_cap")
        val QUALITY = intPreferencesKey("jpeg_quality")
        val PORT = intPreferencesKey("port")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD = stringPreferencesKey("password")
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
    }
}
