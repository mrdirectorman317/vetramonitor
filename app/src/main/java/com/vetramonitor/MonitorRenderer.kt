package com.vetramonitor

import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

data class FrameData(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val release: () -> Unit,
)

/**
 * OpenGL ES 3.0 renderer. All GL work stays on the GL thread.
 * State changes from other threads are queued via [glActions].
 * New UVC frames land in [pendingFrame]; new onion bitmaps land in [pendingOnion].
 */
class MonitorRenderer(
    private val assetLoader: (String) -> String,
) : GLSurfaceView.Renderer {

    // ── Thread-safe input queues ──────────────────────────────────────────────
    val pendingFrame  = AtomicReference<FrameData?>()
    val pendingOnion  = AtomicReference<Bitmap?>()

    // Actions queued from any thread, executed at the top of each onDrawFrame
    private val glActions = ConcurrentLinkedQueue<() -> Unit>()

    // ── GL objects ────────────────────────────────────────────────────────────
    private var program: ShaderProgram? = null
    private var vao = 0
    private var vbo = 0

    private var frameYTexId  = 0
    private var frameVuTexId = 0
    private var falseLutTexId = 0
    private var lut3dTexId   = 0
    private var onionTexId   = 0

    private var frameWidth   = 0
    private var frameHeight  = 0
    private var surfaceWidth  = 1
    private var surfaceHeight = 1

    // ── Render state (GL thread only) ─────────────────────────────────────────
    private var falseColorEnabled = false
    private var peakingEnabled    = false
    private var lutEnabled        = false
    private var onionEnabled      = false
    private var peakThreshold     = 0.08f
    private var peakColor         = floatArrayOf(0.1f, 1f, 0.15f)   // vivid green
    private var lutStrength       = 1.0f
    private var onionOpacity      = 0.4f

    private var captureCallback: ((Bitmap) -> Unit)? = null

    // Full-screen quad: 6 vertices × (xy + st) = 6 × 4 floats
    private val quadVerts = floatArrayOf(
        -1f, -1f,  0f, 0f,
         1f, -1f,  1f, 0f,
         1f,  1f,  1f, 1f,
        -1f, -1f,  0f, 0f,
         1f,  1f,  1f, 1f,
        -1f,  1f,  0f, 1f,
    )

    // ── GLSurfaceView.Renderer ────────────────────────────────────────────────
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)

        program = ShaderProgram(
            assetLoader("shaders/monitor.vert"),
            assetLoader("shaders/monitor.frag"),
        )

        // VAO + VBO
        val vaos = IntArray(1); GLES30.glGenVertexArrays(1, vaos, 0); vao = vaos[0]
        val vbos = IntArray(1); GLES30.glGenBuffers(1, vbos, 0);       vbo = vbos[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        val buf: FloatBuffer = ByteBuffer.allocateDirect(quadVerts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .also { it.put(quadVerts); it.rewind() }
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quadVerts.size * 4, buf, GLES30.GL_STATIC_DRAW)
        val stride = 4 * 4
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 2 * 4)
        GLES30.glBindVertexArray(0)

        // Live NV21 uses separate Y and interleaved VU textures.
        val ids = IntArray(5); GLES30.glGenTextures(5, ids, 0)
        frameYTexId   = ids[0]
        frameVuTexId  = ids[1]
        falseLutTexId = ids[2]
        lut3dTexId    = ids[3]
        onionTexId    = ids[4]

        initFrameTextures()
        LutLoader.buildFalseLutInto(falseLutTexId)
        initLut3dIdentity()
        initOnionTexture()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width; surfaceHeight = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Drain the action queue first
        generateSequence { glActions.poll() }.forEach { it() }

        // Upload new UVC frame if available
        pendingFrame.getAndSet(null)?.let { uploadFrame(it) }

        // Upload new onion bitmap if available
        pendingOnion.getAndSet(null)?.let { uploadOnionBitmap(it) }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        val prog = program ?: return
        prog.use()

        // Bind textures to units 0-3
        bindTex2d(0, frameYTexId);   prog.setInt("uFrameY",   0)
        bindTex2d(1, frameVuTexId);  prog.setInt("uFrameVU",  1)
        bindTex2d(2, falseLutTexId); prog.setInt("uFalseLUT", 2)
        bindTex3d(3, lut3dTexId);    prog.setInt("uLut3d",    3)
        bindTex2d(4, onionTexId);    prog.setInt("uOnionTex", 4)

        // Feature flags
        prog.setBool("uFalseColorEnabled", falseColorEnabled)
        prog.setBool("uPeakingEnabled",    peakingEnabled)
        prog.setBool("uLutEnabled",        lutEnabled)
        prog.setBool("uOnionEnabled",      onionEnabled)

        // Parameters
        prog.setFloat("uPeakThreshold", peakThreshold)
        prog.setVec3("uPeakColor",      peakColor)
        prog.setFloat("uLutStrength",   lutStrength)
        prog.setFloat("uOnionOpacity",  onionOpacity)
        if (frameWidth > 0 && frameHeight > 0) {
            prog.setVec2("uTexelSize", floatArrayOf(1f / frameWidth, 1f / frameHeight))
        }

        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glBindVertexArray(0)

        // Frame capture for onion skin "grab" feature
        captureCallback?.let { cb ->
            captureCallback = null
            cb(readPixelsToBitmap())
        }
    }

    // ── Public state setters (any thread) ────────────────────────────────────
    fun setFalseColorEnabled(v: Boolean) = post { falseColorEnabled = v }
    fun setPeakingEnabled(v: Boolean)    = post { peakingEnabled    = v }
    fun setLutEnabled(v: Boolean)        = post { lutEnabled        = v }
    fun setOnionEnabled(v: Boolean)      = post { onionEnabled      = v }
    fun setPeakThreshold(v: Float)       = post { peakThreshold     = v }
    fun setPeakColor(rgb: FloatArray)    = post { peakColor         = rgb }
    fun setLutStrength(v: Float)         = post { lutStrength       = v }
    fun setOnionOpacity(v: Float)        = post { onionOpacity      = v }

    fun submitFrame(frame: FrameData) {
        pendingFrame.getAndSet(frame)?.release?.invoke()
    }

    fun load3dLut(size: Int, data: FloatArray) =
        post { LutLoader.upload3dLutInto(lut3dTexId, size, data) }

    /** Grab the current rendered frame as a Bitmap; result delivered on GL thread via [callback]. */
    fun requestCapture(callback: (Bitmap) -> Unit) = post { captureCallback = callback }

    // ── Private helpers ───────────────────────────────────────────────────────
    private fun post(action: () -> Unit) { glActions.add(action) }

    private fun bindTex2d(unit: Int, id: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
    }

    private fun bindTex3d(unit: Int, id: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, id)
    }

    private fun initFrameTextures() {
        initFramePlaneTexture(frameYTexId, GLES30.GL_R8, GLES30.GL_RED, 2, 2)
        initFramePlaneTexture(frameVuTexId, GLES30.GL_RG8, GLES30.GL_RG, 1, 1)
    }

    private fun initFramePlaneTexture(id: Int, internalFormat: Int, format: Int, width: Int, height: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val black = ByteBuffer.allocateDirect(width * height * if (format == GLES30.GL_RG) 2 else 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, internalFormat, width, height, 0,
            format, GLES30.GL_UNSIGNED_BYTE, black,
        )
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun initOnionTexture() {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, onionTexId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        // 2×2 fully transparent placeholder
        val clear = ByteBuffer.allocateDirect(16).also { b -> repeat(16) { b.put(0) }; b.rewind() }
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 2, 2, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, clear)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun initLut3dIdentity() {
        // 2×2×2 identity LUT so the shader path is exercisable before a real LUT is loaded
        val id3 = floatArrayOf(
            0f,0f,0f,  1f,0f,0f,
            0f,1f,0f,  1f,1f,0f,
            0f,0f,1f,  1f,0f,1f,
            0f,1f,1f,  1f,1f,1f,
        )
        val buf = ByteBuffer.allocateDirect(id3.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().also { it.put(id3); it.rewind() }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lut3dTexId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, 2, 2, 2, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, buf)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
    }

    private fun uploadFrame(frame: FrameData) {
        val (bytes, w, h, release) = frame
        try {
            val yBytes = w * h
            val y = ByteBuffer.wrap(bytes, 0, yBytes)
            val vu = ByteBuffer.wrap(bytes, yBytes, yBytes / 2)
            val resize = w != frameWidth || h != frameHeight

            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameYTexId)
            if (resize) {
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, w, h, 0,
                    GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, y,
                )
            } else {
                GLES30.glTexSubImage2D(
                    GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h,
                    GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, y,
                )
            }

            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameVuTexId)
            if (resize) {
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG8, w / 2, h / 2, 0,
                    GLES30.GL_RG, GLES30.GL_UNSIGNED_BYTE, vu,
                )
                frameWidth = w
                frameHeight = h
            } else {
                GLES30.glTexSubImage2D(
                    GLES30.GL_TEXTURE_2D, 0, 0, 0, w / 2, h / 2,
                    GLES30.GL_RG, GLES30.GL_UNSIGNED_BYTE, vu,
                )
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        } finally {
            release()
        }
    }

    private fun uploadOnionBitmap(bitmap: Bitmap) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, onionTexId)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun readPixelsToBitmap(): Bitmap {
        val buf = ByteBuffer.allocateDirect(surfaceWidth * surfaceHeight * 4)
            .order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, surfaceWidth, surfaceHeight,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        buf.rewind()
        val raw = Bitmap.createBitmap(surfaceWidth, surfaceHeight, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buf)
        // glReadPixels is bottom-up; flip so the image is top-down
        val m = Matrix().also { it.postScale(1f, -1f, surfaceWidth / 2f, surfaceHeight / 2f) }
        return Bitmap.createBitmap(raw, 0, 0, surfaceWidth, surfaceHeight, m, true)
    }
}
