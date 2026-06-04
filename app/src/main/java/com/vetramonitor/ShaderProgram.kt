package com.vetramonitor

import android.opengl.GLES30
import android.util.Log

class ShaderProgram(vertSrc: String, fragSrc: String) {

    val id: Int

    init {
        val vert = compile(GLES30.GL_VERTEX_SHADER, vertSrc)
        val frag = compile(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        id = GLES30.glCreateProgram().also { prog ->
            GLES30.glAttachShader(prog, vert)
            GLES30.glAttachShader(prog, frag)
            GLES30.glBindAttribLocation(prog, 0, "aPosition")
            GLES30.glBindAttribLocation(prog, 1, "aTexCoord")
            GLES30.glLinkProgram(prog)
            val status = IntArray(1)
            GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                Log.e("ShaderProgram", GLES30.glGetProgramInfoLog(prog))
            }
        }
        GLES30.glDeleteShader(vert)
        GLES30.glDeleteShader(frag)
    }

    fun use() = GLES30.glUseProgram(id)

    fun setInt(name: String, v: Int) =
        GLES30.glUniform1i(loc(name), v)

    fun setFloat(name: String, v: Float) =
        GLES30.glUniform1f(loc(name), v)

    fun setBool(name: String, v: Boolean) =
        GLES30.glUniform1i(loc(name), if (v) 1 else 0)

    fun setVec2(name: String, v: FloatArray) =
        GLES30.glUniform2fv(loc(name), 1, v, 0)

    fun setVec3(name: String, v: FloatArray) =
        GLES30.glUniform3fv(loc(name), 1, v, 0)

    private fun loc(name: String) = GLES30.glGetUniformLocation(id, name)

    private fun compile(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e("ShaderProgram", GLES30.glGetShaderInfoLog(shader))
        }
        return shader
    }
}
