package com.slp.camcast.server

import java.io.File

/** What the server needs from motion recording: the events summary and the clip files. */
interface ClipSource {
    /** `{"detection":true,"now":…,"motion":…,"recording":…,"lastMotion":…|null,"clips":[ClipInfo…]}`, newest first. */
    fun eventsJson(nowMillis: Long): String

    /** The finished clip with this id, or null (unknown or malformed id). */
    fun clipFile(id: String): File?

    fun thumbFile(id: String): File?
}
