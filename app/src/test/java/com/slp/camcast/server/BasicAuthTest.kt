package com.slp.camcast.server

import java.util.Base64
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BasicAuthTest {
    private val creds = Credentials("cam", "s3cret")

    private fun header(userPass: String, scheme: String = "Basic") =
        "$scheme " + Base64.getEncoder().encodeToString(userPass.toByteArray())

    @Test
    fun acceptsTheRightCredentials() {
        assertTrue(BasicAuth.matches(header("cam:s3cret"), creds))
    }

    @Test
    fun schemeIsCaseInsensitiveAndWhitespaceTolerant() {
        assertTrue(BasicAuth.matches(header("cam:s3cret", "basic"), creds))
        assertTrue(BasicAuth.matches("  " + header("cam:s3cret") + "  ", creds))
    }

    @Test
    fun rejectsWrongPasswordUserOrCase() {
        assertFalse(BasicAuth.matches(header("cam:wrong"), creds))
        assertFalse(BasicAuth.matches(header("other:s3cret"), creds))
        assertFalse(BasicAuth.matches(header("cam:S3CRET"), creds))
        assertFalse(BasicAuth.matches(header("cam:s3cre"), creds))
        assertFalse(BasicAuth.matches(header("cam:s3crets"), creds))
    }

    @Test
    fun rejectsMissingOrMalformedHeaders() {
        assertFalse(BasicAuth.matches(null, creds))
        assertFalse(BasicAuth.matches("", creds))
        assertFalse(BasicAuth.matches("Basic", creds))
        assertFalse(BasicAuth.matches("Basic !!!not-base64!!!", creds))
        assertFalse(BasicAuth.matches(header("cam:s3cret", "Bearer"), creds))
        assertFalse(BasicAuth.matches("cam:s3cret", creds))
    }

    @Test
    fun passwordMayContainColonsAndUnicode() {
        val c = Credentials("cam", "a:b:é€")
        assertTrue(BasicAuth.matches(header("cam:a:b:é€"), c))
        assertFalse(BasicAuth.matches(header("cam:a"), c))
    }

    @Test
    fun blankCredentialsNeverMatch() {
        assertFalse(BasicAuth.matches(header(":"), Credentials("", "")))
        assertFalse(BasicAuth.matches(header("cam:"), Credentials("cam", "")))
        assertFalse(BasicAuth.matches(header(":pw"), Credentials("", "pw")))
        assertFalse(BasicAuth.matches(header("a:b:pw"), Credentials("a:b", "pw")))
    }
}
