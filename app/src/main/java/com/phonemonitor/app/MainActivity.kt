package com.phonemonitor.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.phonemonitor.app.uvc.StreamSession
import com.phonemonitor.app.uvc.UvcCamera
import com.phonemonitor.app.uvc.UvcException
import com.phonemonitor.app.uvc.UvcMode
import java.util.concurrent.Executors

/**
 * Full-screen monitor for a USB Video Class source: a camera in UVC/webcam
 * mode, or (for the camera's full screen incl. menus and playback) an
 * HDMI-to-USB capture card fed by the camera's HDMI output.
 *
 * All USB work happens on [worker]; the UI thread only updates views.
 */
class MainActivity : Activity(), FrameRenderer.Listener {

    private lateinit var usbManager: UsbManager
    private lateinit var surfaceView: SurfaceView
    private lateinit var topBar: View
    private lateinit var statusText: TextView
    private lateinit var btnScale: Button
    private lateinit var messagePanel: View
    private lateinit var messageTitle: TextView
    private lateinit var messageBody: TextView
    private lateinit var renderer: FrameRenderer

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "usb-worker") }
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    // ---- state owned by the worker thread ----
    private var camera: UvcCamera? = null
    private var session: StreamSession? = null
    private var permissionPendingFor: String? = null
    private var reconnectAttempts = 0

    @Volatile
    private var started = false

    // CAMERA runtime permission state (UI thread writes, worker reads).
    @Volatile
    private var cameraPermissionRequesting = false

    @Volatile
    private var cameraPermissionDenied = false

    private val hideTopBar = Runnable { topBar.visibility = View.GONE }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    worker.execute {
                        permissionPendingFor = null
                        if (granted && device != null) {
                            if (started) openCamera(device)
                        } else {
                            showMessage(getString(R.string.msg_permission_title), getString(R.string.msg_permission_denied))
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    // The system's own "open with" dialog grants permission (and can be set to
                    // "always"), so don't stack a second permission dialog on top of it; just
                    // connect if access is already there.
                    mainHandler.postDelayed({
                        if (started) worker.execute {
                            reconnectAttempts = 0
                            connectIfPossible(requestUsbPermission = false)
                        }
                    }, 800)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> worker.execute {
                    if (device != null && camera?.device?.deviceName == device.deviceName) {
                        closeCamera()
                        showMessage(getString(R.string.msg_disconnected), getString(R.string.msg_no_device_body))
                    }
                    if (permissionPendingFor == device?.deviceName) permissionPendingFor = null
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        surfaceView = findViewById(R.id.surface)
        topBar = findViewById(R.id.topBar)
        statusText = findViewById(R.id.statusText)
        btnScale = findViewById(R.id.btnScale)
        messagePanel = findViewById(R.id.messagePanel)
        messageTitle = findViewById(R.id.messageTitle)
        messageBody = findViewById(R.id.messageBody)

        renderer = FrameRenderer(surfaceView.holder, this)
        renderer.scaleMode = runCatching { ScaleMode.valueOf(prefs.getString(PREF_SCALE, null) ?: "") }
            .getOrDefault(ScaleMode.FIT)
        updateScaleButton()

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = renderer.start()
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) = renderer.stop()
        })
        surfaceView.setOnClickListener { toggleTopBar() }

        findViewById<Button>(R.id.btnResolution).setOnClickListener { showResolutionDialog() }
        btnScale.setOnClickListener {
            renderer.scaleMode = if (renderer.scaleMode == ScaleMode.FIT) ScaleMode.FILL else ScaleMode.FIT
            prefs.edit().putString(PREF_SCALE, renderer.scaleMode.name).apply()
            updateScaleButton()
            scheduleHideTopBar()
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
        }
        findViewById<Button>(R.id.btnRetry).setOnClickListener {
            cameraPermissionDenied = false
            worker.execute {
                reconnectAttempts = 0
                permissionPendingFor = null
                closeCamera()
                connectIfPossible()
            }
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        statusText.text = getString(R.string.app_name)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Launched again by USB_DEVICE_ATTACHED: permission was granted by the system dialog.
        if (started) worker.execute { connectIfPossible() }
    }

    override fun onStart() {
        super.onStart()
        started = true
        worker.execute {
            reconnectAttempts = 0
            connectIfPossible()
        }
    }

    override fun onStop() {
        started = false
        // Release the USB device while in the background.
        worker.execute { closeCamera() }
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        worker.execute { closeCamera() }
        worker.shutdown()
        super.onDestroy()
    }

    // ---------------- worker thread ----------------

    private fun connectIfPossible(requestUsbPermission: Boolean = true) {
        if (camera != null) return
        val devices = usbManager.deviceList.values
        val video = devices.firstOrNull { UvcCamera.isVideoDevice(it) }
        if (video == null) {
            if (devices.any { UvcCamera.isStillImageDevice(it) }) {
                showMessage(getString(R.string.msg_ptp_title), getString(R.string.msg_ptp_body))
            } else {
                showMessage(getString(R.string.msg_no_device_title), getString(R.string.msg_no_device_body))
            }
            return
        }
        val needCamera = checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        val needAudio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean(PREF_AUDIO_ASKED, false)
        if (needCamera && cameraPermissionDenied) {
            showCameraPermissionDenied()
            return
        }
        if (needCamera || needAudio) {
            requestRuntimePermissions(needCamera, needAudio)
            return
        }
        if (!usbManager.hasPermission(video) && !requestUsbPermission) {
            showMessage(getString(R.string.msg_usb_confirm_title), getString(R.string.msg_usb_confirm_body))
            return
        }
        if (!usbManager.hasPermission(video)) {
            if (permissionPendingFor != video.deviceName) {
                permissionPendingFor = video.deviceName
                val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                usbManager.requestPermission(video, PendingIntent.getBroadcast(this, 0, intent, flags))
            }
            showMessage(getString(R.string.msg_permission_title), getString(R.string.msg_permission_body))
            return
        }
        openCamera(video)
    }

    private fun openCamera(device: UsbDevice) {
        if (camera != null) return
        showMessage(getString(R.string.msg_connecting), device.productName ?: device.deviceName, showRetry = false)
        try {
            val cam = UvcCamera.open(usbManager, device)
            camera = cam
            Log.i(TAG, "opened ${cam.displayName}: UVC ${"%x".format(cam.info.uvcVersion)}, modes=${cam.modes.map { it.label }}")
            val saved = prefs.getString(prefKeyMode(device), null)
            val mode = cam.modes.firstOrNull { it.key == saved } ?: cam.defaultMode()
            startMode(cam, mode)
        } catch (e: Exception) {
            Log.e(TAG, "open failed", e)
            closeCamera()
            showMessage(getString(R.string.msg_error_title), describe(e))
        }
    }

    private fun startMode(cam: UvcCamera, mode: UvcMode) {
        renderer.session = null
        session = null
        val s = cam.start(mode)
        session = s
        renderer.session = s
        runOnUiThread {
            messagePanel.visibility = View.GONE
            statusText.text = "${cam.displayName}\n${mode.label}"
            topBar.visibility = View.VISIBLE
            scheduleHideTopBar()
        }
    }

    private fun switchMode(mode: UvcMode) {
        val cam = camera ?: return
        try {
            startMode(cam, mode)
            prefs.edit().putString(prefKeyMode(cam.device), mode.key).apply()
        } catch (e: Exception) {
            Log.e(TAG, "switch mode failed", e)
            showMessage(getString(R.string.msg_error_title), describe(e))
        }
    }

    private fun closeCamera() {
        renderer.session = null
        session = null
        camera?.let {
            camera = null
            try {
                it.close()
            } catch (e: Exception) {
                Log.w(TAG, "close failed", e)
            }
        }
    }

    // ---------------- CAMERA runtime permission ----------------

    /**
     * Android 9+ silently denies USB permission for video-class devices unless the
     * app holds CAMERA, so it has to be granted before UsbManager.requestPermission.
     */
    private fun requestRuntimePermissions(camera: Boolean, audio: Boolean) {
        runOnUiThread {
            if (cameraPermissionRequesting) return@runOnUiThread
            cameraPermissionRequesting = true
            val perms = buildList {
                if (camera) add(Manifest.permission.CAMERA)
                if (audio) add(Manifest.permission.RECORD_AUDIO)
            }
            // The microphone is optional: ask for it only once.
            if (audio) prefs.edit().putBoolean(PREF_AUDIO_ASKED, true).apply()
            showMessage(getString(R.string.msg_camera_perm_title), getString(R.string.msg_camera_perm_body), showRetry = false)
            requestPermissions(perms.toTypedArray(), REQ_CAMERA)
        }
    }

    private fun showCameraPermissionDenied() {
        runOnUiThread {
            val permanentlyDenied = !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            showMessage(
                getString(R.string.msg_camera_perm_title),
                getString(if (permanentlyDenied) R.string.msg_camera_perm_settings else R.string.msg_camera_perm_denied),
                showSettings = permanentlyDenied,
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        cameraPermissionRequesting = false
        val granted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            cameraPermissionDenied = false
            worker.execute {
                permissionPendingFor = null
                if (started) connectIfPossible()
            }
        } else {
            cameraPermissionDenied = true
            showCameraPermissionDenied()
        }
    }

    // ---------------- FrameRenderer.Listener (render thread) ----------------

    override fun onFrameStats(session: StreamSession, displayFps: Double, width: Int, height: Int) {
        val name = camera?.displayName ?: return
        val text = "$name\n${width}×${height} ${session.mode.format.pixelFormat.label} · ${"%.1f".format(displayFps)} fps"
        runOnUiThread { statusText.text = text }
    }

    override fun onStreamEnded(session: StreamSession) {
        worker.execute {
            if (this.session !== session) return@execute
            val errno = session.stats().lastErrno
            Log.w(TAG, "stream ended, errno=$errno")
            closeCamera()
            if (!started) return@execute
            if (reconnectAttempts < MAX_RECONNECTS) {
                reconnectAttempts++
                showMessage(getString(R.string.msg_stream_ended), "", showRetry = false)
                mainHandler.postDelayed({ if (started) worker.execute { connectIfPossible() } }, 1000)
            } else {
                showMessage(getString(R.string.msg_disconnected), getString(R.string.msg_no_signal_hint))
            }
        }
    }

    // ---------------- UI ----------------

    private fun showResolutionDialog() {
        worker.execute {
            val cam = camera ?: return@execute
            val modes = cam.modes
            val current = modes.indexOfFirst { it.key == session?.mode?.key }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_resolution_title)
                    .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), current) { d, which ->
                        d.dismiss()
                        worker.execute { switchMode(modes[which]) }
                    }
                    .show()
            }
        }
    }

    private fun showMessage(title: String, body: String, showRetry: Boolean = true, showSettings: Boolean = false) {
        runOnUiThread {
            findViewById<View>(R.id.btnSettings).visibility = if (showSettings) View.VISIBLE else View.GONE
            messageTitle.text = title
            messageBody.text = body
            messageBody.visibility = if (body.isEmpty()) View.GONE else View.VISIBLE
            findViewById<View>(R.id.btnRetry).visibility = if (showRetry) View.VISIBLE else View.GONE
            messagePanel.visibility = View.VISIBLE
            topBar.visibility = View.GONE
        }
    }

    private fun toggleTopBar() {
        if (messagePanel.visibility == View.VISIBLE) return
        if (topBar.visibility == View.VISIBLE) {
            topBar.visibility = View.GONE
        } else {
            topBar.visibility = View.VISIBLE
            scheduleHideTopBar()
        }
    }

    private fun scheduleHideTopBar() {
        mainHandler.removeCallbacks(hideTopBar)
        mainHandler.postDelayed(hideTopBar, 4000)
    }

    private fun updateScaleButton() {
        btnScale.setText(if (renderer.scaleMode == ScaleMode.FIT) R.string.btn_scale_fit else R.string.btn_scale_fill)
    }

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun describe(e: Exception): String = when {
        e is UvcException && (e.errno == 28 || e.errno == 22) ->
            "${e.message}\n\nUSB 頻寬可能不足，請改用較低解析度或較短的線材。"
        e is UvcException && e.errno == 16 -> "${e.message}\n\nUSB 裝置忙碌中，請關閉其他使用相機的 App 後重試。"
        else -> e.message ?: e.toString()
    }

    private fun prefKeyMode(device: UsbDevice) = "mode_${device.vendorId}_${device.productId}"

    companion object {
        private const val TAG = "PhoneMonitor"
        private const val ACTION_USB_PERMISSION = "com.phonemonitor.app.USB_PERMISSION"
        private const val PREF_SCALE = "scale_mode"
        private const val MAX_RECONNECTS = 3
        private const val REQ_CAMERA = 1
        private const val PREF_AUDIO_ASKED = "record_audio_asked"
    }
}
