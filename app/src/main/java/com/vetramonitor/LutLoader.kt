package com.vetramonitor

import android.content.Context
import android.opengl.GLES30
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

object LutLoader {

    /** Parse a .cube file from assets. Returns (size, flat RGB float array) or null. */
    fun parseCube(context: Context, assetPath: String): Pair<Int, FloatArray>? = runCatching {
        context.assets.open(assetPath).use { stream ->
            val reader = BufferedReader(InputStreamReader(stream))
            var size = 33
            val values = mutableListOf<Float>()
            reader.forEachLine { rawLine ->
                val line = rawLine.trim()
                when {
                    line.isEmpty() || line.startsWith('#') -> Unit
                    line.startsWith("LUT_3D_SIZE") ->
                        size = line.split("\\s+".toRegex()).last().toInt()
                    line[0].isDigit() || line[0] == '-' ->
                        line.split("\\s+".toRegex()).forEach { values.add(it.toFloat()) }
                }
            }
            Pair(size, values.toFloatArray())
        }
    }.getOrNull()

    /**
     * Upload a parsed LUT float array (R,G,B triples) into an already-allocated
     * GL_TEXTURE_3D whose id is [texId].
     */
    fun upload3dLutInto(texId: Int, size: Int, data: FloatArray) {
        val buf = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .also { it.put(data); it.rewind() }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, texId)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F,
            size, size, size, 0,
            GLES30.GL_RGB, GLES30.GL_FLOAT, buf
        )
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
    }

    /**
     * Build a cinema false-colour ramp into the given GL_TEXTURE_2D id.
     * 256 × 1 RGBA; x-axis maps Rec.709 luma 0–1 to exposure zones.
     *
     *   0–2%    black  — clipped shadows
     *   2–8%    purple — severe underexposure
     *   8–18%   blue   — underexposed shadows
     *   ~18%    green  — 18% grey target
     *   18–38%  cyan   — lower midtones
     *   38–48%  pink   — skin-tone reference band
     *   48–60%  yellow — upper midtones
     *   60–78%  orange — highlights
     *   78–96%  red    — near clip
     *   96–100% white  — clipped highlights
     */
    fun buildFalseLutInto(texId: Int) {
        val pixels = ByteArray(256 * 4)
        for (i in 0 until 256) {
            val f = i / 255f
            val (r, g, b) = when {
                f < 0.02f -> Triple(0,   0,   0)
                f < 0.08f -> Triple(80,  0,   140)
                f < 0.18f -> Triple(0,   0,   210)
                f < 0.21f -> Triple(0,   210, 0)
                f < 0.38f -> Triple(0,   190, 190)
                f < 0.48f -> Triple(230, 160, 150)
                f < 0.60f -> Triple(230, 230, 0)
                f < 0.78f -> Triple(230, 120, 0)
                f < 0.96f -> Triple(220, 0,   0)
                else      -> Triple(255, 255, 255)
            }
            val base = i * 4
            pixels[base]     = r.toByte()
            pixels[base + 1] = g.toByte()
            pixels[base + 2] = b.toByte()
            pixels[base + 3] = 255.toByte()
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
            256, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE,
            ByteBuffer.wrap(pixels)
        )
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }
}
