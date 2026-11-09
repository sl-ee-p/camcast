package com.slp.camcast.server

import java.security.MessageDigest
import java.util.Base64

class Credentials(val username: String, val password: String) {
    /** Auth is mandatory: blank credentials would leave the camera open, so nothing matches them. */
    val isUsable get() = username.isNotEmpty() && password.isNotEmpty() && !username.contains(':')
}

object BasicAuth {
    const val REALM = "CamCast"

    /** True when [authorization] (the raw `Authorization` header, or null) carries [credentials]. */
    fun matches(authorization: String?, credentials: Credentials): Boolean {
        if (!credentials.isUsable || authorization == null) return false
        val trimmed = authorization.trim()
        val space = trimmed.indexOf(' ')
        if (space <= 0 || !trimmed.substring(0, space).equals("Basic", ignoreCase = true)) return false
        val decoded = try {
            Base64.getDecoder().decode(trimmed.substring(space + 1).trim())
        } catch (e: IllegalArgumentException) {
            return false
        }
        val expected = "${credentials.username}:${credentials.password}".toByteArray(Charsets.UTF_8)
        // Constant time, so response timing does not leak how much of the password was right.
        return MessageDigest.isEqual(decoded, expected)
    }
}
