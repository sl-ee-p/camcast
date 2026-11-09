package com.slp.camcast.server

import java.io.OutputStream

/** Writes responses. Every response closes the connection: simple, and fine for a handful of clients. */
object HttpResponse {
    fun writeHeaders(
        out: OutputStream,
        status: Int,
        reason: String,
        contentType: String? = null,
        contentLength: Int? = null,
        extra: List<String> = emptyList(),
    ) {
        val sb = StringBuilder("HTTP/1.1 $status $reason\r\n")
        contentType?.let { sb.append("Content-Type: ").append(it).append("\r\n") }
        contentLength?.let { sb.append("Content-Length: ").append(it).append("\r\n") }
        sb.append("Cache-Control: no-store\r\nConnection: close\r\n")
        extra.forEach { sb.append(it).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    /** [sendBody] is false for HEAD: same headers, no body. */
    fun write(
        out: OutputStream,
        status: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        sendBody: Boolean = true,
        extra: List<String> = emptyList(),
    ) {
        writeHeaders(out, status, reason, contentType, body.size, extra)
        if (sendBody) out.write(body)
        out.flush()
    }

    fun writeText(out: OutputStream, status: Int, reason: String, sendBody: Boolean = true, extra: List<String> = emptyList()) =
        write(out, status, reason, "text/plain; charset=utf-8", "$status $reason\n".toByteArray(), sendBody, extra)

    fun writeUnauthorized(out: OutputStream, sendBody: Boolean = true) = writeText(
        out, 401, "Unauthorized", sendBody, listOf("WWW-Authenticate: Basic realm=\"${BasicAuth.REALM}\", charset=\"UTF-8\""),
    )
}
