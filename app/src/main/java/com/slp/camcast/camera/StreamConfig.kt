package com.slp.camcast.camera

/** Capture resolution presets. [aspect16x9] picks the aspect ratio CameraX should prefer. */
enum class StreamResolution(val width: Int, val height: Int, val aspect16x9: Boolean) {
    P480(640, 480, false),
    P720(1280, 720, true),
    P1080(1920, 1080, true),
}

enum class CameraFacing { BACK, FRONT }

/** What the camera pipeline needs to know; persisted by the settings in a later milestone. */
data class StreamConfig(
    val facing: CameraFacing = CameraFacing.BACK,
    val resolution: StreamResolution = StreamResolution.P720,
    val fpsCap: Int = 10,
    val jpegQuality: Int = 70,
) {
    /** The values the UI and the pipeline can rely on, whatever was stored. */
    fun sanitized() = copy(
        fpsCap = fpsCap.coerceIn(MIN_FPS, MAX_FPS),
        jpegQuality = jpegQuality.coerceIn(MIN_QUALITY, MAX_QUALITY),
    )

    companion object {
        const val MIN_FPS = 1
        const val MAX_FPS = 30
        const val MIN_QUALITY = 10
        const val MAX_QUALITY = 100
    }
}
