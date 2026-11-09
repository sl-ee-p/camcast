package com.slp.camcast.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestParserTest {
    private fun parse(raw: String) = RequestParser.parse(raw.toByteArray(Charsets.ISO_8859_1).inputStream())

    private fun ok(raw: String) = (parse(raw) as ParseResult.Ok).request

    private fun bad(raw: String) = parse(raw) as ParseResult.Bad

    @Test
    fun parsesAGetWithHeaders() {
        val r = ok("GET /video HTTP/1.1\r\nHost: cam\r\nAuthorization: Basic abc\r\n\r\n")
        assertEquals("GET", r.method)
        assertEquals("/video", r.path)
        assertEquals("Basic abc", r.headers["authorization"])
        assertEquals("cam", r.headers["host"])
    }

    @Test
    fun headerNamesAreCaseInsensitiveAndValuesTrimmed() {
        val r = ok("GET / HTTP/1.1\r\nAUTHORIZATION:   Basic x  \r\n\r\n")
        assertEquals("Basic x", r.headers["authorization"])
    }

    @Test
    fun queryStringAndFragmentAreDropped() {
        assertEquals("/snapshot.jpg", ok("GET /snapshot.jpg?t=123 HTTP/1.1\r\n\r\n").path)
        assertEquals("/", ok("GET /?a=b#c HTTP/1.0\r\n\r\n").path)
    }

    @Test
    fun acceptsBareLineFeeds() {
        assertEquals("/", ok("GET / HTTP/1.1\nHost: x\n\n").path)
    }

    @Test
    fun headIsAllowed() {
        assertEquals("HEAD", ok("HEAD / HTTP/1.1\r\n\r\n").method)
    }

    @Test
    fun otherMethodsAreRejectedWith405() {
        assertEquals(405, bad("POST /video HTTP/1.1\r\n\r\n").status)
        assertEquals(405, bad("DELETE / HTTP/1.1\r\n\r\n").status)
    }

    @Test
    fun garbageIs400() {
        assertEquals(400, bad("hello\r\n\r\n").status)
        assertEquals(400, bad("GET /video\r\n\r\n").status)
        assertEquals(400, bad("GET video HTTP/1.1\r\n\r\n").status)
        assertEquals(400, bad("GET /  HTTP/1.1\r\n\r\n").status)
        assertEquals(400, bad("GET / HTTP/1.1\r\nno-colon-here\r\n\r\n").status)
    }

    @Test
    fun otherHttpVersionsAre505() {
        assertEquals(505, bad("GET / HTTP/2.0\r\n\r\n").status)
    }

    @Test
    fun truncatedHeadersAre400() {
        assertEquals(400, bad("GET / HTTP/1.1\r\nHost: x").status)
    }

    @Test
    fun emptyConnectionIsClosed() {
        assertSame(ParseResult.Closed, parse(""))
    }

    @Test
    fun overlongRequestLineIs414() {
        assertEquals(414, bad("GET /" + "a".repeat(9000) + " HTTP/1.1\r\n\r\n").status)
    }

    @Test
    fun overlongHeaderIs431() {
        assertEquals(431, bad("GET / HTTP/1.1\r\nX: " + "a".repeat(9000) + "\r\n\r\n").status)
    }

    @Test
    fun tooManyHeadersIs431() {
        val headers = (0..RequestParser.MAX_HEADERS).joinToString("") { "H$it: v\r\n" }
        assertEquals(431, bad("GET / HTTP/1.1\r\n$headers\r\n").status)
    }

    @Test
    fun exactlyTheHeaderLimitIsFine() {
        val headers = (1..RequestParser.MAX_HEADERS).joinToString("") { "H$it: v\r\n" }
        assertTrue(parse("GET / HTTP/1.1\r\n$headers\r\n") is ParseResult.Ok)
    }
}
