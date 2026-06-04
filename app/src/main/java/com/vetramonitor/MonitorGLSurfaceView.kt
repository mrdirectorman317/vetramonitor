package com.vetramonitor

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent

/**
 * Full-screen GL surface. Renders only when dirty (new UVC frame or state change).
 * Touch events are forwarded to the ViewModel for CCAPI touch-AF.
 */
class MonitorGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    private var viewModel: MonitorViewModel? = null
    private var onDoubleTap: (() -> Unit)? = null
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            val xNorm = (event.x / width).coerceIn(0f, 1f)
            val yNorm = (event.y / height).coerceIn(0f, 1f)
            viewModel?.onTouchAF(xNorm, yNorm)
            return true
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            onDoubleTap?.invoke()
            return true
        }
    })

    fun init(renderer: MonitorRenderer, vm: MonitorViewModel) {
        viewModel = vm
        setEGLContextClientVersion(3)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setOnDoubleTapListener(listener: () -> Unit) {
        onDoubleTap = listener
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)
}
