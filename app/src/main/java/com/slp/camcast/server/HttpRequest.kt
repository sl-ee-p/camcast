package com.slp.camcast.server

import java.io.ByteArrayOutputStream
import java.io.InputStream

class HttpRequest(
    val method: String,
    /** The path without the query string, e.g. `/video`. */
    val path: String,
    /** Header names are lower-cased. */
    val headers: Map<String, String>,
)

sealed interface ParseResult {
    class Ok(val request: HttpRequest) : ParseResult

    /** The request cannot be served; answer with [status] and close. */
    class Bad(val status: Int, val reason: String) : ParseResult

    /** The peer closed the connection before sending anything. */
    object Closed : ParseResult
}

/** A minimal HTTP/1.x request reader: GET and HEAD only, no body, strict size limits. */
object RequestParser {
    const val MAX_LINE_BYTES = 8192
    const val MAX_HEADERS = 64

    private val allowedMethods = setOf("GET", "HEAD")

    /** Throws the stream's own exceptions (e.g. a socket timeout) unchanged. */
    fun parse(input: InputStream): ParseResult {
        val requestLine = readLine(input) ?: return ParseResult.Closed
        if (requestLine == LINE_TOO_LONG) return ParseResult.Bad(414, "URI Too Long")

        val parts = requestLine.split(' ')
        if (parts.size != 3 || parts.any { it.isEmpty() }) return ParseResult.Bad(400, "Bad Request")
        val (method, target, version) = parts
        if (!version.startsWith("HTTP/1.")) return ParseResult.Bad(505, "HTTP Version Not Supported")
        if (!target.startsWith("/")) return ParseResult.Bad(400, "Bad Request")

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return ParseResult.Bad(400, "Bad Request")
            if (line == LINE_TOO_LONG) return ParseResult.Bad(431, "Request Header Fields Too Large")
            if (line.isEmpty()) break
            if (headers.size >= MAX_HEADERS) return ParseResult.Bad(431, "Request Header Fields Too Large")
            val colon = line.indexOf(':')
            if (colon <= 0) return ParseResult.Bad(400, "Bad Request")
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        if (method !in allowedMethods) return ParseResult.Bad(405, "Method Not Allowed")
        val path = target.substringBefore('?').substringBefore('#')
        return ParseResult.Ok(HttpRequest(method, path, headers))
    }

    private const val LINE_TOO_LONG = "\u0000too-long"

    /** One line without its CRLF/LF; null at end of stream. */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else String(buf.toByteArray(), Charsets.ISO_8859_1)
            if (b == '\n'.code) {
                val bytes = buf.toByteArray()
                val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, end, Charsets.ISO_8859_1)
            }
            if (buf.size() >= MAX_LINE_BYTES) return LINE_TOO_LONG
            buf.write(b)
        }
    }
}
