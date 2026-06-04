package com.vetramonitor

import android.content.Context
import com.jiangdg.ausbc.camera.CameraUvcStrategy
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack

/**
 * Thin wrapper around AUSBC's [CameraUvcStrategy].
 *
 * Responsibilities:
 *  - Register/unregister the USB broadcast receiver with the Activity lifecycle.
 *  - Open/close the UVC device (the HDMI capture dongle).
 *  - Deliver RGBA frames to [onFrame] from AUSBC's callback thread.
 *
 * NOTE: AUSBC 3.3.x's exact callback signature is:
 *   IPreviewDataCallBack.onPreviewData(data: ByteArray?, format: DataFormat)
 * Width/height come from CameraUvcStrategy.getCurrentPreviewSize().
 * If you upgrade AUSBC and the API shifts, only this file needs updating.
 */
class UvcBridge(
    private val context: Context,
    private val onFrame: (bytes: ByteArray, width: Int, height: Int) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
) {
    private val strategy = CameraUvcStrategy(context)

    fun register() {
        strategy.register()
        strategy.setStateCallback(object : ICameraStateCallBack {
            override fun onCameraState(
                self: com.jiangdg.ausbc.camera.CameraClient,
                code: ICameraStateCallBack.State,
                msg: String?,
            ) {
                when (code) {
                    ICameraStateCallBack.State.OPENED -> {
                        attachFrameCallback()
                        onConnected()
                    }
                    ICameraStateCallBack.State.CLOSED,
                    ICameraStateCallBack.State.ERROR -> onDisconnected()
                    else -> Unit
                }
            }
        })
    }

    fun openCamera() {
        // Pass null view — we consume raw frame bytes ourselves
        strategy.openCamera(cameraView = null)
    }

    fun closeCamera() = strategy.closeCamera()

    fun unregister() = strategy.unRegister()

    private fun attachFrameCallback() {
        strategy.addPreviewDataCallBack(object : IPreviewDataCallBack {
            override fun onPreviewData(data: ByteArray?, format: IPreviewDataCallBack.DataFormat) {
                data ?: return
                val size = strategy.getCurrentPreviewSize() ?: return
                val w = size.width; val h = size.height
                // Normalise to RGBA before handing to the GL renderer
                val rgba = when (format) {
                    IPreviewDataCallBack.DataFormat.RGBA -> data
                    else -> nv21ToRgba(data, w, h)
                }
                onFrame(rgba, w, h)
            }
        })
    }

    /**
     * Software NV21 → RGBA conversion, executed on AUSBC's callback thread.
     * Costs ~4 ms on a mid-range SoC at 1080p — acceptable for 30 fps.
     * Replace with a two-texture YUV shader path if tighter latency is needed.
     */
    private fun nv21ToRgba(nv21: ByteArray, w: Int, h: Int): ByteArray {
        val rgba = ByteArray(w * h * 4)
        val frameSize = w * h
        var yIdx = 0; var rIdx = 0
        for (j in 0 until h) {
            for (i in 0 until w) {
                val y = ((nv21[yIdx].toInt() and 0xff) - 16).coerceAtLeast(0)
                val uvIdx = frameSize + (j shr 1) * w + (i and 1.inv())
                val v = (nv21[uvIdx    ].toInt() and 0xff) - 128
                val u = (nv21[uvIdx + 1].toInt() and 0xff) - 128
                val y1 = y * 1192
                rgba[rIdx++] = ((y1 + 1634 * v).coerceIn(0, 262143) shr 10).toByte()
                rgba[rIdx++] = ((y1 -  833 * v - 400 * u).coerceIn(0, 262143) shr 10).toByte()
                rgba[rIdx++] = ((y1 + 2066 * u).coerceIn(0, 262143) shr 10).toByte()
                rgba[rIdx++] = 255.toByte()
                yIdx++
            }
        }
        return rgba
    }
}
