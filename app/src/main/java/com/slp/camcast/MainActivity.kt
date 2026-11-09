package com.slp.camcast

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.slp.camcast.camera.CameraFacing
import com.slp.camcast.camera.StreamConfig
import com.slp.camcast.camera.StreamResolution
import com.slp.camcast.service.StreamController
import com.slp.camcast.service.StreamState
import com.slp.camcast.settings.AppSettings
import com.slp.camcast.settings.PasswordGenerator
import com.slp.camcast.settings.SettingsError
import com.slp.camcast.settings.SettingsStore
import com.slp.camcast.settings.SettingsValidator
import kotlinx.coroutines.launch

/** The one screen: status, start/stop and the settings. The camera and server live in [com.slp.camcast.service.StreamService]. */
class MainActivity : AppCompatActivity() {
    private lateinit var store: SettingsStore
    private lateinit var status: TextView
    private lateinit var endpoint: TextView
    private lateinit var stats: TextView
    private lateinit var startStop: MaterialButton
    private lateinit var facing: MaterialButtonToggleGroup
    private lateinit var resolution: MaterialButtonToggleGroup
    private lateinit var fps: Slider
    private lateinit var fpsLabel: TextView
    private lateinit var quality: Slider
    private lateinit var qualityLabel: TextView
    private lateinit var portLayout: TextInputLayout
    private lateinit var port: TextInputEditText
    private lateinit var usernameLayout: TextInputLayout
    private lateinit var username: TextInputEditText
    private lateinit var passwordLayout: TextInputLayout
    private lateinit var password: TextInputEditText
    private lateinit var newPassword: MaterialButton

    private var state: StreamState = StreamState.Stopped
    private var loaded = false

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, R.string.notification_denied, Toast.LENGTH_LONG).show()
        launchService()
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) askNotificationsThenStart() else Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
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
        store = SettingsStore(this)
        bindViews()

        fps.addOnChangeListener { _, value, _ -> fpsLabel.text = getString(R.string.fps_label, value.toInt()) }
        quality.addOnChangeListener { _, value, _ -> qualityLabel.text = getString(R.string.quality_label, value.toInt()) }
        port.doAfterTextChanged { portLayout.error = null }
        username.doAfterTextChanged { usernameLayout.error = null }
        password.doAfterTextChanged { passwordLayout.error = null }
        newPassword.setOnClickListener { password.setText(PasswordGenerator.generate()) }
        startStop.setOnClickListener { onStartStop() }

        lifecycleScope.launch {
            show(store.load())
            loaded = true
            render(state)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                StreamController.state.collect { render(it) }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Keep what the user typed, if it is valid and nothing is running with the old values.
        if (loaded && state !is StreamState.Running && state !is StreamState.Starting) {
            val form = readForm()
            if (SettingsValidator.validate(form).isEmpty()) lifecycleScope.launch { store.save(form) }
        }
    }

    private fun bindViews() {
        status = findViewById(R.id.status)
        endpoint = findViewById(R.id.endpoint)
        stats = findViewById(R.id.stats)
        startStop = findViewById(R.id.startStop)
        facing = findViewById(R.id.facing)
        resolution = findViewById(R.id.resolution)
        fps = findViewById(R.id.fps)
        fpsLabel = findViewById(R.id.fpsLabel)
        quality = findViewById(R.id.quality)
        qualityLabel = findViewById(R.id.qualityLabel)
        portLayout = findViewById(R.id.portLayout)
        port = findViewById(R.id.port)
        usernameLayout = findViewById(R.id.usernameLayout)
        username = findViewById(R.id.username)
        passwordLayout = findViewById(R.id.passwordLayout)
        password = findViewById(R.id.password)
        newPassword = findViewById(R.id.newPassword)
    }

    @SuppressLint("SetTextI18n") // The port is parsed back with toIntOrNull, so it must stay plain ASCII digits.
    private fun show(s: AppSettings) {
        facing.check(if (s.stream.facing == CameraFacing.FRONT) R.id.facingFront else R.id.facingBack)
        resolution.check(
            when (s.stream.resolution) {
                StreamResolution.P480 -> R.id.res480
                StreamResolution.P720 -> R.id.res720
                StreamResolution.P1080 -> R.id.res1080
            },
        )
        fps.value = s.stream.fpsCap.toFloat()
        quality.value = (s.stream.jpegQuality / 5 * 5).coerceIn(10, 100).toFloat()
        fpsLabel.text = getString(R.string.fps_label, s.stream.fpsCap)
        qualityLabel.text = getString(R.string.quality_label, quality.value.toInt())
        port.setText(s.port.toString())
        username.setText(s.username)
        password.setText(s.password)
    }

    private fun readForm() = AppSettings(
        stream = StreamConfig(
            facing = if (facing.checkedButtonId == R.id.facingFront) CameraFacing.FRONT else CameraFacing.BACK,
            resolution = when (resolution.checkedButtonId) {
                R.id.res480 -> StreamResolution.P480
                R.id.res1080 -> StreamResolution.P1080
                else -> StreamResolution.P720
            },
            fpsCap = fps.value.toInt(),
            jpegQuality = quality.value.toInt(),
        ),
        port = port.text?.toString()?.toIntOrNull() ?: -1,
        username = username.text?.toString().orEmpty(),
        password = password.text?.toString().orEmpty(),
    )

    private fun render(s: StreamState) {
        state = s
        val editable = s is StreamState.Stopped || s is StreamState.Failed
        listOf(facingBack(), facingFront(), res(R.id.res480), res(R.id.res720), res(R.id.res1080), fps, quality, port, username, password, newPassword)
            .forEach { it.isEnabled = editable && loaded }
        passwordLayout.isEnabled = editable && loaded
        startStop.isEnabled = loaded
        startStop.setText(if (editable) R.string.action_start else R.string.action_stop)
        endpoint.visibility = android.view.View.GONE
        stats.visibility = android.view.View.GONE
        when (s) {
            StreamState.Stopped -> status.setText(R.string.status_stopped)
            StreamState.Starting -> status.setText(R.string.status_starting)
            is StreamState.Failed -> status.text = s.message
            is StreamState.Running -> {
                status.setText(R.string.status_running)
                endpoint.text = getString(R.string.endpoint_format, s.videoUrl)
                endpoint.visibility = android.view.View.VISIBLE
                stats.text = getString(
                    R.string.stats_format, s.frames, s.fps, s.width, s.height,
                    resources.getQuantityString(R.plurals.viewers, s.viewers, s.viewers),
                )
                stats.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun facingBack() = findViewById<android.view.View>(R.id.facingBack)
    private fun facingFront() = findViewById<android.view.View>(R.id.facingFront)
    private fun res(id: Int) = findViewById<android.view.View>(id)

    private fun onStartStop() {
        if (state is StreamState.Running || state is StreamState.Starting) {
            StreamController.stop(this)
            return
        }
        val form = readForm()
        val errors = SettingsValidator.validate(form)
        portLayout.error = if (SettingsError.PORT in errors) getString(R.string.error_port) else null
        usernameLayout.error = if (SettingsError.USERNAME in errors) getString(R.string.error_username) else null
        passwordLayout.error = if (SettingsError.PASSWORD in errors) getString(R.string.error_password) else null
        if (errors.isNotEmpty()) return
        lifecycleScope.launch {
            store.save(form)
            if (granted(Manifest.permission.CAMERA)) askNotificationsThenStart() else cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun askNotificationsThenStart() {
        val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)
        if (needed) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else launchService()
    }

    private fun launchService() = StreamController.start(this)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
