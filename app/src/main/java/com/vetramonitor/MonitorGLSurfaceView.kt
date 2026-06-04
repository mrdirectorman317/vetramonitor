package com.vetramonitor

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
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

    fun init(renderer: MonitorRenderer, vm: MonitorViewModel) {
        viewModel = vm
        setEGLContextClientVersion(3)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val xNorm = (event.x / width).coerceIn(0f, 1f)
            val yNorm = (event.y / height).coerceIn(0f, 1f)
            viewModel?.onTouchAF(xNorm, yNorm)
        }
        return true
    }
}
