package com.slp.camcast.settings

import com.slp.camcast.camera.StreamConfig
import java.security.SecureRandom

/** Everything the user can configure. The service reads one snapshot when it starts. */
data class AppSettings(
    val stream: StreamConfig = StreamConfig(),
    val port: Int = DEFAULT_PORT,
    val username: String = DEFAULT_USERNAME,
    val password: String = "",
) {
    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_USERNAME = "camcast"
    }
}

enum class SettingsError { PORT, USERNAME, PASSWORD }

object SettingsValidator {
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535
    const val MIN_PASSWORD_LENGTH = 6
    const val MAX_TEXT_LENGTH = 64

    fun validate(settings: AppSettings): Set<SettingsError> = buildSet {
        if (settings.port !in MIN_PORT..MAX_PORT) add(SettingsError.PORT)
        val user = settings.username
        if (user.isEmpty() || user.length > MAX_TEXT_LENGTH || ':' in user || user.any(Char::isISOControl)) {
            add(SettingsError.USERNAME)
        }
        val pass = settings.password
        if (pass.length !in MIN_PASSWORD_LENGTH..MAX_TEXT_LENGTH || pass.any(Char::isISOControl)) {
            add(SettingsError.PASSWORD)
        }
    }
}

object PasswordGenerator {
    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789" // no look-alike characters

    fun generate(length: Int = 10, random: SecureRandom = SecureRandom()): String =
        (1..length).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
}
