package com.slp.camcast.clips

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The clips folder: `<id>.mjpeg` (frames), `<id>.json` ([ClipInfo]), `<id>.jpg` (the frame where the movement was seen),
 * and `<id>.mjpeg.part` while a clip is being written. Keeps the folder under [maxBytes] and the disk above
 * [minFreeBytes] by deleting the oldest clips. Thread safe (one lock; every call is short except [enforceLimits]).
 */
class ClipStore(
    val dir: File,
    private val maxBytes: Long = 2L * 1024 * 1024 * 1024,
    private val minFreeBytes: Long = 500L * 1024 * 1024,
    private val freeBytes: () -> Long = { dir.usableSpace },
) {
    private val lock = Any()

    init {
        dir.mkdirs()
    }

    fun clipFile(id: String) = File(dir, "$id.mjpeg")
    fun partFile(id: String) = File(dir, "$id.mjpeg.part")
    fun thumbFile(id: String) = File(dir, "$id.jpg")
    private fun infoFile(id: String) = File(dir, "$id.json")

    /** A free id for a clip whose movement happened at [timeMillis]. */
    fun newId(timeMillis: Long): String = synchronized(lock) {
        val base = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(timeMillis))
        var id = base
        var n = 2
        while (partFile(id).exists() || clipFile(id).exists()) id = "$base-${n++}"
        id
    }

    /** Finished clips, newest first. */
    fun list(): List<ClipInfo> = synchronized(lock) {
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> runCatching { ClipInfo.fromJson(f.readText()) }.getOrNull() }
            .filter { clipFile(it.id).exists() }
            .sortedByDescending { it.motionMillis }
    }

    fun find(id: String): ClipInfo? = if (ClipInfo.ID.matches(id)) list().firstOrNull { it.id == id } else null

    /** Makes a written `.part` a finished clip. */
    fun publish(info: ClipInfo) = synchronized(lock) {
        infoFile(info.id).writeText(info.toJson())
        partFile(info.id).renameTo(clipFile(info.id))
    }

    fun delete(id: String) = synchronized(lock) {
        listOf(clipFile(id), partFile(id), thumbFile(id), infoFile(id)).forEach { it.delete() }
    }

    /**
     * Turns `.part` files left by a crash or a kill into clips (they end at their last whole frame), so a recording that
     * was cut short is not lost. Call before recording starts.
     */
    fun recoverPartials() {
        dir.listFiles { f -> f.name.endsWith(".mjpeg.part") }.orEmpty().forEach { part ->
            val id = part.name.removeSuffix(".mjpeg.part")
            if (!ClipInfo.ID.matches(id)) return@forEach
            var frames = 0
            var first = 0L
            var last = 0L
            runCatching {
                ClipReader(part).use { r ->
                    while (true) {
                        val f = r.next() ?: break
                        if (frames++ == 0) first = f.timestampMillis
                        last = f.timestampMillis
                    }
                }
            }
            if (frames == 0) {
                part.delete()
            } else {
                publish(ClipInfo(id, first, first, last, frames, part.length(), 0, 0))
            }
        }
    }

    /** Deletes the oldest clips while the folder is too big or the disk too full. Returns the ids deleted. */
    fun enforceLimits(): List<String> {
        val deleted = mutableListOf<String>()
        val oldestFirst = list().reversed().toMutableList()
        fun used() = dir.listFiles().orEmpty().sumOf { it.length() }
        while (oldestFirst.isNotEmpty() && (used() > maxBytes || freeBytes() < minFreeBytes)) {
            val victim = oldestFirst.removeAt(0)
            delete(victim.id)
            deleted += victim.id
        }
        return deleted
    }
}
