package com.vetramonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.Size
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Opens a UVC capture dongle and delivers RGBA frames to the monitor renderer.
 *
 * This uses AUSBC's underlying USB/UVC classes directly. CameraUvcStrategy
 * ignores many composite capture dongles and assumes the requested preview
 * size is supported, both of which can leave the monitor black without an
 * actionable error.
 */
class UvcBridge(
    context: Context,
    private val onFrame: (bytes: ByteArray, width: Int, height: Int, release: () -> Unit) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onStatus: (message: String) -> Unit,
    private val onError: (message: String) -> Unit,
) {
    private val lock = Any()
    private val firstFrame = AtomicBoolean(false)
    private val badFrameReported = AtomicBoolean(false)
    private val receiverContext = UsbReceiverContext(context)
    private val usbManager = context.applicationContext
        .getSystemService(Context.USB_SERVICE) as UsbManager

    @Volatile private var previewWidth = 0
    @Volatile private var previewHeight = 0
    private var monitor: USBMonitor? = null
    private var camera: UVCCamera? = null
    private var dummySurface: SurfaceTexture? = null
    private var activeDeviceId: Int? = null
    private var registered = false
    private var openRequested = false
    private var permissionRequestInFlight = false
    @Volatile private var framePool: ArrayBlockingQueue<ByteArray>? = null

    private val frameCallback = IFrameCallback { frame ->
        val w = previewWidth
        val h = previewHeight
        val expectedBytes = w * h * 3 / 2
        if (w <= 0 || h <= 0 || frame.capacity() < expectedBytes) {
            if (badFrameReported.compareAndSet(false, true)) {
                reportError("UVC frame size did not match the negotiated preview mode")
            }
            return@IFrameCallback
        }

        val pool = framePool ?: return@IFrameCallback
        val nv21 = pool.poll() ?: return@IFrameCallback
        try {
            frame.duplicate().apply {
                clear()
                get(nv21)
            }
        } catch (t: Throwable) {
            pool.offer(nv21)
            reportError("Unable to copy UVC frame: ${t.message ?: t.javaClass.simpleName}")
            return@IFrameCallback
        }
        if (firstFrame.compareAndSet(false, true)) {
            reportStatus("UVC: live ${w}x${h}")
            onConnected()
        }
        onFrame(nv21, w, h) { pool.offer(nv21) }
    }

    fun register() {
        synchronized(lock) {
            if (registered) return
            val currentMonitor = ensureMonitorLocked()
            runCatching { currentMonitor.register() }
                .onSuccess { registered = true }
                .onFailure { reportError("Unable to register USB monitor: ${it.message}") }
        }
    }

    fun openCamera() {
        openRequested = true
        register()
        requestAttachedCamera(showNoDeviceError = false)
    }

    /**
     * Explicit user retry. Recreating USBMonitor clears stale permission
     * requests left behind after Android's permission dialog is denied.
     */
    fun retryCameraPermission() {
        openRequested = true
        permissionRequestInFlight = false
        stopCamera()
        synchronized(lock) {
            destroyMonitorLocked()
            val currentMonitor = ensureMonitorLocked()
            runCatching { currentMonitor.register() }
                .onSuccess { registered = true }
                .onFailure { reportError("Unable to register USB monitor: ${it.message}") }
        }
        requestAttachedCamera(showNoDeviceError = true)
    }

    fun closeCamera() {
        openRequested = false
        stopCamera()
    }

    fun unregister() {
        synchronized(lock) {
            if (!registered) return
            runCatching { monitor?.unregister() }
            registered = false
        }
    }

    fun release() {
        closeCamera()
        synchronized(lock) {
            destroyMonitorLocked()
        }
    }

    private fun requestAttachedCamera(showNoDeviceError: Boolean) {
        val currentMonitor = synchronized(lock) { monitor }
        if (currentMonitor == null) {
            reportError("USB connection is not ready. Tap UVC to retry.")
            return
        }
        val device = runCatching {
            currentMonitor.deviceList.firstOrNull(::isUvcDevice)
        }.getOrNull()

        if (device == null) {
            val message = "UVC: no capture device. Connect dongle, then tap UVC."
            if (showNoDeviceError) {
                reportError(message)
            } else {
                reportStatus(message)
            }
            return
        }
        val hasPermission = usbManager.hasPermission(device)
        if (!hasPermission && permissionRequestInFlight) {
            reportStatus("UVC: waiting for USB permission")
            return
        }
        reportStatus(
            if (hasPermission) "UVC: opening capture device..."
            else "UVC: allow USB access in the Android prompt"
        )
        Log.i(TAG, "Requesting access to USB video device ${device.vendorId}:${device.productId}")
        permissionRequestInFlight = !hasPermission
        if (currentMonitor.requestPermission(device)) {
            permissionRequestInFlight = false
            reportError("USB permission request failed. Tap UVC to retry.")
        }
    }

    private fun startCamera(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
        synchronized(lock) {
            if (!openRequested || activeDeviceId == device.deviceId) return
            stopCameraLocked()

            val newCamera = UVCCamera()
            try {
                newCamera.open(ctrlBlock)
                val size = negotiatePreviewSize(newCamera)
                previewWidth = size.width
                previewHeight = size.height
                badFrameReported.set(false)
                firstFrame.set(false)
                framePool = ArrayBlockingQueue<ByteArray>(FRAME_BUFFER_COUNT).also { pool ->
                    repeat(FRAME_BUFFER_COUNT) {
                        pool.offer(ByteArray(size.width * size.height * 3 / 2))
                    }
                }

                dummySurface = SurfaceTexture(/*singleBufferMode=*/false).also {
                    it.setDefaultBufferSize(size.width, size.height)
                    newCamera.setPreviewTexture(it)
                }
                newCamera.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_NV21)
                newCamera.startPreview()

                camera = newCamera
                activeDeviceId = device.deviceId
                reportStatus("UVC: starting ${size.width}x${size.height}...")
                Log.i(TAG, "UVC preview started at ${size.width}x${size.height}")
            } catch (t: Throwable) {
                runCatching { newCamera.destroy() }
                stopCameraLocked()
                reportError("Unable to start UVC preview: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun negotiatePreviewSize(camera: UVCCamera): Size {
        val modes = listOf(UVCCamera.FRAME_FORMAT_MJPEG, UVCCamera.FRAME_FORMAT_YUYV)
            .flatMap { format ->
                camera.getSupportedSizeList(format).orEmpty().map { size ->
                    PreviewMode(size, format, size.fps.maxOrNull() ?: 30f)
                }
            }
            .filter { it.width > 0 && it.height > 0 }
            .distinctBy { Triple(it.size.width, it.size.height, it.format) }
        val candidates = modes
            .filter { it.size.width <= MAX_PREVIEW_WIDTH && it.size.height <= MAX_PREVIEW_HEIGHT }
            .ifEmpty { modes }
            .sortedWith(
                compareBy<PreviewMode> {
                    val ratio = it.size.width.toDouble() / it.size.height
                    abs(ratio - TARGET_ASPECT)
                }.thenByDescending { it.size.width * it.size.height }
                    .thenBy { if (it.format == UVCCamera.FRAME_FORMAT_MJPEG) 0 else 1 }
                    .thenByDescending { it.maxFps }
            )

        if (candidates.isEmpty()) {
            throw IllegalStateException("capture device reported no preview modes")
        }
        Log.i(TAG, "UVC preview modes: ${candidates.joinToString { it.description }}")

        var lastFailure: Throwable? = null
        for (mode in candidates) {
            try {
                camera.setPreviewSize(
                    mode.size.width,
                    mode.size.height,
                    UVCCamera.DEFAULT_PREVIEW_MIN_FPS,
                    ceil(mode.maxFps + 0.5f).toInt().coerceIn(
                        UVCCamera.DEFAULT_PREVIEW_MAX_FPS,
                        MAX_PREVIEW_FPS,
                    ),
                    mode.format,
                    UVCCamera.DEFAULT_BANDWIDTH,
                )
                Log.i(TAG, "Selected low-latency UVC mode ${mode.description}")
                return mode.size
            } catch (t: Throwable) {
                lastFailure = t
            }
        }
        throw IllegalStateException("none of the capture device preview modes could be opened", lastFailure)
    }

    private fun stopCamera() {
        synchronized(lock) {
            stopCameraLocked()
        }
        onDisconnected()
    }

    private fun stopCameraLocked() {
        camera?.let { uvc ->
            runCatching { uvc.setFrameCallback(null, UVCCamera.PIXEL_FORMAT_NV21) }
            runCatching { uvc.stopPreview() }
            runCatching { uvc.destroy() }
        }
        camera = null
        activeDeviceId = null
        previewWidth = 0
        previewHeight = 0
        firstFrame.set(false)
        framePool = null
        dummySurface?.release()
        dummySurface = null
    }

    private fun reportError(message: String) {
        Log.e(TAG, message)
        onStatus(message)
        onError(message)
    }

    private fun reportStatus(message: String) {
        Log.i(TAG, message)
        onStatus(message)
    }

    private fun ensureMonitorLocked(): USBMonitor {
        return monitor ?: USBMonitor(receiverContext, DeviceListener()).also { monitor = it }
    }

    private fun destroyMonitorLocked() {
        runCatching { monitor?.destroy() }
        monitor = null
        registered = false
    }

    private inner class DeviceListener : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice?) {
            if (openRequested && device != null && isUvcDevice(device)) {
                reportStatus("UVC: capture device detected")
                requestAttachedCamera(showNoDeviceError = false)
            }
        }

        override fun onDetach(device: UsbDevice?) {
            permissionRequestInFlight = false
            if (device?.deviceId == activeDeviceId) {
                stopCamera()
            }
        }

        override fun onConnect(
            device: UsbDevice?,
            ctrlBlock: USBMonitor.UsbControlBlock?,
            createNew: Boolean,
        ) {
            if (device != null && ctrlBlock != null && isUvcDevice(device)) {
                permissionRequestInFlight = false
                startCamera(device, ctrlBlock)
            }
        }

        override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            if (device?.deviceId == activeDeviceId) {
                stopCamera()
            }
        }

        override fun onCancel(device: UsbDevice?) {
            // USBMonitor also sends onCancel(null) while it is being destroyed.
            if (openRequested && device != null && isUvcDevice(device)) {
                permissionRequestInFlight = false
                reportError("UVC: USB permission denied. Tap UVC to ask again.")
            }
        }
    }

    private fun isUvcDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        return (0 until device.interfaceCount).any { index ->
            device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_VIDEO
        }
    }

    private data class PreviewMode(val size: Size, val format: Int, val maxFps: Float) {
        val width: Int get() = size.width
        val height: Int get() = size.height
        val description: String
            get() = "${size.width}x${size.height} ${if (format == UVCCamera.FRAME_FORMAT_MJPEG) "MJPEG" else "YUYV"} @${maxFps.toInt()}"
    }

    companion object {
        private const val TAG = "VetraUvc"
        private const val FRAME_BUFFER_COUNT = 3
        private const val TARGET_ASPECT = 16.0 / 9.0
        private const val MAX_PREVIEW_WIDTH = 1920
        private const val MAX_PREVIEW_HEIGHT = 1080
        private const val MAX_PREVIEW_FPS = 61
    }
}

/**
 * AUSBC 3.2.7 uses the legacy two-argument registerReceiver overload. Android
 * 14+ requires an explicit export flag for its combined USB event filter.
 */
private class UsbReceiverContext(base: Context) : ContextWrapper(base.applicationContext) {
    override fun getApplicationContext(): Context = this

    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            super.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            super.registerReceiver(receiver, filter)
        }
    }
}
