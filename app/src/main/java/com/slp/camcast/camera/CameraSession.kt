package com.slp.camcast.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** The phone has no camera facing the requested way. */
class CameraMissingException(val facing: CameraFacing) : Exception("no $facing camera")

/**
 * Runs the camera and publishes JPEG frames into [frames], at most [StreamConfig.fpsCap] a second.
 * The caller supplies the [LifecycleOwner] (an activity now, the foreground service later).
 */
class CameraSession(
    private val context: Context,
    private val frames: FrameBuffer,
    config: StreamConfig,
    private val onError: (Throwable) -> Unit,
) {
    private val config = config.sanitized()
    private val throttle = FrameThrottle(this.config.fpsCap)
    private var executor: ExecutorService? = null
    private var provider: ProcessCameraProvider? = null

    fun start(owner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                val analysisExecutor = Executors.newSingleThreadExecutor()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector())
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(analysisExecutor, ::analyze)
                val selector = when (config.facing) {
                    CameraFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
                    CameraFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
                }
                if (!cameraProvider.hasCamera(selector)) throw CameraMissingException(config.facing)
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(owner, selector, analysis)
                executor = analysisExecutor
                provider = cameraProvider
            } catch (e: Exception) {
                onError(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        provider?.unbindAll()
        provider = null
        executor?.shutdown()
        executor = null
    }

    private fun resolutionSelector(): ResolutionSelector {
        val res = config.resolution
        val aspect = if (res.aspect16x9) {
            AspectRatioStrategy(AspectRatio.RATIO_16_9, AspectRatioStrategy.FALLBACK_RULE_AUTO)
        } else {
            AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO)
        }
        return ResolutionSelector.Builder()
            .setAspectRatioStrategy(aspect)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(res.width, res.height),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            )
            .build()
    }

    private fun analyze(image: ImageProxy) {
        image.use {
            if (!throttle.accept(it.imageInfo.timestamp)) return
            try {
                val rotation = it.imageInfo.rotationDegrees
                val jpeg = JpegEncoder.encode(it.toBitmap(), rotation, config.jpegQuality)
                val upright = rotation % 180 != 0
                frames.publish(
                    jpeg,
                    if (upright) it.height else it.width,
                    if (upright) it.width else it.height,
                    System.currentTimeMillis(),
                )
            } catch (e: Exception) {
                onError(e)
            }
        }
    }
}
