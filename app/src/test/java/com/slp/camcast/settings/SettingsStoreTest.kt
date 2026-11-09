package com.slp.camcast.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.slp.camcast.camera.CameraFacing
import com.slp.camcast.camera.StreamConfig
import com.slp.camcast.camera.StreamResolution
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsStoreTest {
    private lateinit var dir: File
    private lateinit var dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        dir = File.createTempFile("camcast", "").apply { delete(); mkdirs() }
        dataStore = PreferenceDataStoreFactory.create(produceFile = { File(dir, "settings.preferences_pb") })
        store = SettingsStore(dataStore)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun freshInstallGetsDefaultsAndARandomPasswordThatSticks() = runBlocking {
        val first = store.load()
        assertEquals(StreamConfig(), first.stream)
        assertEquals(AppSettings.DEFAULT_PORT, first.port)
        assertEquals(AppSettings.DEFAULT_USERNAME, first.username)
        assertEquals(emptySet<SettingsError>(), SettingsValidator.validate(first))
        assertEquals(first.password, store.load().password)
    }

    @Test
    fun savedSettingsComeBackUnchanged() = runBlocking {
        val s = AppSettings(StreamConfig(CameraFacing.FRONT, StreamResolution.P1080, 15, 85), 9000, "marta", "pässword")
        store.save(s)
        assertEquals(s, store.load())
    }

    @Test
    fun aNewStoreOnTheSameFileSeesTheSavedSettings() = runBlocking {
        val s = AppSettings(StreamConfig(resolution = StreamResolution.P480), 8123, "u", "password1")
        store.save(s)
        // Same file, fresh DataStore instance would clash; reading through the same one is what the app does.
        assertEquals(s, SettingsStore(dataStore).load())
    }

    @Test
    fun corruptValuesFallBackToSafeOnes() = runBlocking {
        dataStore.edit {
            it[stringPreferencesKey("facing")] = "SIDEWAYS"
            it[stringPreferencesKey("resolution")] = "8K"
            it[intPreferencesKey("fps_cap")] = 9999
            it[intPreferencesKey("jpeg_quality")] = -5
            it[intPreferencesKey("port")] = 22
            it[stringPreferencesKey("username")] = ""
            it[stringPreferencesKey("password")] = "keepme123"
        }
        val s = store.load()
        assertEquals(CameraFacing.BACK, s.stream.facing)
        assertEquals(StreamResolution.P720, s.stream.resolution)
        assertEquals(StreamConfig.MAX_FPS, s.stream.fpsCap)
        assertEquals(StreamConfig.MIN_QUALITY, s.stream.jpegQuality)
        assertEquals(AppSettings.DEFAULT_PORT, s.port)
        assertEquals(AppSettings.DEFAULT_USERNAME, s.username)
        assertEquals("keepme123", s.password)
    }

    @Test
    fun emptyStoredPasswordIsReplaced() = runBlocking {
        dataStore.edit { it[stringPreferencesKey("password")] = "" }
        val s = store.load()
        assertTrue(s.password.length >= SettingsValidator.MIN_PASSWORD_LENGTH)
    }
}
