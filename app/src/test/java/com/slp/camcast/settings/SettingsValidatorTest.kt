package com.slp.camcast.settings

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsValidatorTest {
    private val good = AppSettings(port = 8080, username = "cam", password = "secret1")

    private fun errors(s: AppSettings) = SettingsValidator.validate(s)

    @Test
    fun validSettingsHaveNoErrors() {
        assertEquals(emptySet<SettingsError>(), errors(good))
    }

    @Test
    fun portMustBeUnprivilegedAndInRange() {
        assertEquals(setOf(SettingsError.PORT), errors(good.copy(port = 80)))
        assertEquals(setOf(SettingsError.PORT), errors(good.copy(port = 1023)))
        assertEquals(setOf(SettingsError.PORT), errors(good.copy(port = 65536)))
        assertEquals(setOf(SettingsError.PORT), errors(good.copy(port = 0)))
        assertEquals(emptySet<SettingsError>(), errors(good.copy(port = 1024)))
        assertEquals(emptySet<SettingsError>(), errors(good.copy(port = 65535)))
    }

    @Test
    fun usernameRules() {
        assertEquals(setOf(SettingsError.USERNAME), errors(good.copy(username = "")))
        assertEquals(setOf(SettingsError.USERNAME), errors(good.copy(username = "a:b")))
        assertEquals(setOf(SettingsError.USERNAME), errors(good.copy(username = "a\nb")))
        assertEquals(setOf(SettingsError.USERNAME), errors(good.copy(username = "x".repeat(65))))
        assertEquals(emptySet<SettingsError>(), errors(good.copy(username = "x".repeat(64))))
        assertEquals(emptySet<SettingsError>(), errors(good.copy(username = "marta é")))
    }

    @Test
    fun passwordRules() {
        assertEquals(setOf(SettingsError.PASSWORD), errors(good.copy(password = "")))
        assertEquals(setOf(SettingsError.PASSWORD), errors(good.copy(password = "short")))
        assertEquals(setOf(SettingsError.PASSWORD), errors(good.copy(password = "tab\there")))
        assertEquals(setOf(SettingsError.PASSWORD), errors(good.copy(password = "x".repeat(65))))
        assertEquals(emptySet<SettingsError>(), errors(good.copy(password = "has:colon and spaces")))
    }

    @Test
    fun everyProblemIsReported() {
        assertEquals(SettingsError.entries.toSet(), errors(AppSettings(port = 1, username = "", password = "")))
    }

    @Test
    fun generatedPasswordsAreValidAndDiffer() {
        val random = SecureRandom()
        val a = PasswordGenerator.generate(random = random)
        val b = PasswordGenerator.generate(random = random)
        assertEquals(10, a.length)
        assertTrue(a != b)
        assertEquals(emptySet<SettingsError>(), errors(good.copy(password = a)))
        assertTrue(a.none { it in "01ilo" })
    }
}
