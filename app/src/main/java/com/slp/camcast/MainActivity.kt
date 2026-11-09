package com.slp.camcast

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.slp.camcast.camera.CameraSession
import com.slp.camcast.camera.FrameBuffer
import com.slp.camcast.camera.StreamConfig

/**
 * C2 test harness: runs the camera in the activity and shows what the JPEG pipeline produces.
 * C4 moves the camera into a foreground service and this screen becomes the settings screen.
 */
class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var frames: FrameBuffer? = null
    private var session: CameraSession? = null
    private var lastSequence = 0L
    private var lastTickMillis = 0L

    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var preview: ImageView
    private lateinit var startStop: MaterialButton

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startStreaming() else Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
    }

    private val refresh = object : Runnable {
        override fun run() {
            updateStats()
            handler.postDelayed(this, REFRESH_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // targetSdk 35+ draws edge to edge: keep the content clear of the system bars.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        status = findViewById(R.id.status)
        stats = findViewById(R.id.stats)
        preview = findViewById(R.id.preview)
        startStop = findViewById(R.id.startStop)
        startStop.setOnClickListener { if (session == null) requestStart() else stopStreaming() }
    }

    override fun onStop() {
        super.onStop()
        // The camera is bound to this activity's lifecycle; follow it.
        if (session != null) stopStreaming()
    }

    private fun requestStart() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startStreaming() else permission.launch(Manifest.permission.CAMERA)
    }

    private fun startStreaming() {
        val buffer = FrameBuffer()
        frames = buffer
        lastSequence = 0
        lastTickMillis = System.currentTimeMillis()
        session = CameraSession(applicationContext, buffer, StreamConfig()) { error ->
            runOnUiThread {
                Toast.makeText(this, getString(R.string.camera_error, error.message ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
                stopStreaming()
            }
        }.also { it.start(this) }
        status.setText(R.string.status_streaming)
        startStop.setText(R.string.action_stop)
        stats.visibility = View.VISIBLE
        preview.visibility = View.VISIBLE
        handler.post(refresh)
    }

    private fun stopStreaming() {
        handler.removeCallbacks(refresh)
        session?.stop()
        session = null
        frames?.close()
        frames = null
        status.setText(R.string.status_stopped)
        startStop.setText(R.string.action_start)
        stats.visibility = View.GONE
        preview.visibility = View.GONE
    }

    private fun updateStats() {
        val frame = frames?.latest() ?: return
        val now = System.currentTimeMillis()
        val seconds = (now - lastTickMillis) / 1000.0
        val fps = if (seconds > 0) (frame.sequence - lastSequence) / seconds else 0.0
        lastSequence = frame.sequence
        lastTickMillis = now
        stats.text = getString(R.string.stats_format, frame.sequence, fps, frame.jpeg.size / 1024, frame.width, frame.height)
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        preview.setImageBitmap(BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size, options))
    }

    private companion object {
        const val REFRESH_MILLIS = 1000L
    }
}
