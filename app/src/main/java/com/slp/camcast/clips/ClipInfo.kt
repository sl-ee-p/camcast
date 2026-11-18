package com.slp.camcast.clips

/** One saved clip. Times are epoch milliseconds; [motionMillis] is when the movement that started it was seen. */
data class ClipInfo(
    val id: String,
    val startMillis: Long,
    val motionMillis: Long,
    val endMillis: Long,
    val frames: Int,
    val bytes: Long,
    val width: Int,
    val height: Int,
) {
    fun toJson() = "{\"id\":\"$id\",\"start\":$startMillis,\"motion\":$motionMillis,\"end\":$endMillis," +
        "\"frames\":$frames,\"bytes\":$bytes,\"width\":$width,\"height\":$height}"

    companion object {
        /** Clip ids are timestamps like 20261118-190703 (maybe with a -2 suffix): safe in paths and URLs. */
        val ID = Regex("[0-9]{8}-[0-9]{6}(-[0-9]{1,3})?")

        /** Reads what [toJson] wrote; null for anything else. Hand-rolled because org.json is not in JVM unit tests. */
        fun fromJson(json: String): ClipInfo? {
            fun num(key: String) = Regex("\"$key\":(-?[0-9]+)").find(json)?.groupValues?.get(1)?.toLongOrNull()
            val id = Regex("\"id\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)?.takeIf { ID.matches(it) } ?: return null
            return ClipInfo(
                id,
                num("start") ?: return null,
                num("motion") ?: return null,
                num("end") ?: return null,
                (num("frames") ?: return null).toInt(),
                num("bytes") ?: return null,
                (num("width") ?: 0).toInt(),
                (num("height") ?: 0).toInt(),
            )
        }
    }
}
