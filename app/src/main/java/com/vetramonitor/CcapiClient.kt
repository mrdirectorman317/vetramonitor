package com.vetramonitor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class CameraSettings(
    val iso: String,
    val aperture: String,
    val shutter: String,
)

/**
 * Thin REST client for Canon CCAPI over Wi-Fi.
 * All suspending functions dispatch to Dispatchers.IO — safe to call from any coroutine scope.
 *
 * CCAPI base URL format: "http://<camera-ip>:<port>"
 * Default port is 8080 (configured on the camera's CCAPI setup screen).
 */
class CcapiClient(baseUrl: String) {

    private val base = baseUrl.trimEnd('/')
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    /** Returns true if the camera CCAPI endpoint is reachable. */
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("$base/ccapi").get().build()
            http.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /**
     * Move the AF frame to the normalised position (0..1, 0..1).
     * Maps directly to Canon's touchafframe endpoint.
     */
    suspend fun setTouchAF(xNorm: Float, yNorm: Float) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("xposition", xNorm.coerceIn(0f, 1f).toDouble())
            .put("yposition", yNorm.coerceIn(0f, 1f).toDouble())
            .toString()
            .toRequestBody(jsonType)
        val req = Request.Builder()
            .url("$base/ccapi/ver100/shooting/settings/af/touchafframe")
            .put(body)
            .build()
        runCatching { http.newCall(req).execute().close() }
    }

    /** Poll ISO, aperture, and shutter speed in one round-trip each. */
    suspend fun fetchSettings(): CameraSettings? = withContext(Dispatchers.IO) {
        runCatching {
            fun get(endpoint: String, key: String): String {
                val req = Request.Builder()
                    .url("$base/ccapi/ver100/shooting/settings/$endpoint")
                    .get().build()
                return http.newCall(req).execute().use { resp ->
                    JSONObject(resp.body!!.string()).getString(key)
                }
            }
            CameraSettings(
                iso      = get("iso", "value"),
                aperture = get("av",  "value"),
                shutter  = get("tv",  "value"),
            )
        }.getOrNull()
    }

    suspend fun startMovieRecording() = withContext(Dispatchers.IO) {
        post("$base/ccapi/ver100/shooting/control/movierecording",
            JSONObject().put("action", "start"))
    }

    suspend fun stopMovieRecording() = withContext(Dispatchers.IO) {
        post("$base/ccapi/ver100/shooting/control/movierecording",
            JSONObject().put("action", "stop"))
    }

    suspend fun triggerShutter(withAF: Boolean = true) = withContext(Dispatchers.IO) {
        post("$base/ccapi/ver100/shooting/control/shutterbutton",
            JSONObject().put("af", withAF))
    }

    private fun post(url: String, json: JSONObject) {
        val req = Request.Builder()
            .url(url)
            .post(json.toString().toRequestBody(jsonType))
            .build()
        runCatching { http.newCall(req).execute().close() }
    }
}
