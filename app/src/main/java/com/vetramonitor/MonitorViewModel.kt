package com.vetramonitor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MonitorState(
    val uvcConnected: Boolean       = false,
    val ccapiConnected: Boolean     = false,
    val ccapiUrl: String            = "",
    val falseColorEnabled: Boolean  = false,
    val peakingEnabled: Boolean     = false,
    val lutEnabled: Boolean         = false,
    val onionEnabled: Boolean       = false,
    val onionHasFrame: Boolean      = false,
    val peakThreshold: Float        = 0.08f,
    val peakColorIndex: Int         = 0,       // 0=green 1=red 2=white
    val lutStrength: Float          = 1.0f,
    val onionOpacity: Float         = 0.4f,
    val cameraIso: String           = "--",
    val cameraAperture: String      = "--",
    val cameraShutter: String       = "--",
    val recording: Boolean          = false,
)

private val PEAK_COLORS = arrayOf(
    floatArrayOf(0.1f, 1f, 0.15f),   // green
    floatArrayOf(1f, 0.1f, 0.1f),    // red
    floatArrayOf(1f, 1f, 1f),        // white
)

class MonitorViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("vetra_prefs", 0)

    private val _state = MutableStateFlow(
        MonitorState(ccapiUrl = prefs.getString("ccapi_url", "") ?: "")
    )
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    private var ccapiClient: CcapiClient? = null
    private var settingsPollJob: Job? = null

    /** Set by MainActivity after the renderer is created. */
    var renderer: MonitorRenderer? = null

    // ── CCAPI ─────────────────────────────────────────────────────────────────
    fun connectCcapi(url: String) {
        val client = CcapiClient(url)
        viewModelScope.launch {
            val reachable = client.ping()
            if (reachable) {
                ccapiClient = client
                prefs.edit().putString("ccapi_url", url).apply()
                _state.update { it.copy(ccapiConnected = true, ccapiUrl = url) }
                startPollingSettings()
            } else {
                _state.update { it.copy(ccapiConnected = false) }
            }
        }
    }

    fun disconnectCcapi() {
        settingsPollJob?.cancel()
        ccapiClient = null
        _state.update { it.copy(ccapiConnected = false) }
    }

    private fun startPollingSettings() {
        settingsPollJob?.cancel()
        settingsPollJob = viewModelScope.launch {
            while (true) {
                ccapiClient?.fetchSettings()?.let { s ->
                    _state.update { it.copy(
                        cameraIso      = s.iso,
                        cameraAperture = s.aperture,
                        cameraShutter  = s.shutter,
                    )}
                }
                delay(2_000)
            }
        }
    }

    /** Called with normalised 0-1 coords from the GL surface touch handler. */
    fun onTouchAF(xNorm: Float, yNorm: Float) = viewModelScope.launch {
        ccapiClient?.setTouchAF(xNorm, yNorm)
    }

    fun toggleRecording() = viewModelScope.launch {
        val client = ccapiClient ?: return@launch
        if (_state.value.recording) {
            client.stopMovieRecording()
            _state.update { it.copy(recording = false) }
        } else {
            client.startMovieRecording()
            _state.update { it.copy(recording = true) }
        }
    }

    // ── Assist toggles ────────────────────────────────────────────────────────
    fun setFalseColorEnabled(v: Boolean) {
        _state.update { it.copy(falseColorEnabled = v) }
        renderer?.setFalseColorEnabled(v)
    }

    fun setPeakingEnabled(v: Boolean) {
        _state.update { it.copy(peakingEnabled = v) }
        renderer?.setPeakingEnabled(v)
    }

    fun setLutEnabled(v: Boolean) {
        _state.update { it.copy(lutEnabled = v) }
        renderer?.setLutEnabled(v)
    }

    fun setOnionEnabled(v: Boolean) {
        _state.update { it.copy(onionEnabled = v) }
        renderer?.setOnionEnabled(v)
    }

    fun setPeakThreshold(v: Float) {
        _state.update { it.copy(peakThreshold = v) }
        renderer?.setPeakThreshold(v)
    }

    fun setPeakColorIndex(idx: Int) {
        _state.update { it.copy(peakColorIndex = idx) }
        renderer?.setPeakColor(PEAK_COLORS[idx.coerceIn(0, PEAK_COLORS.lastIndex)])
    }

    fun setLutStrength(v: Float) {
        _state.update { it.copy(lutStrength = v) }
        renderer?.setLutStrength(v)
    }

    fun setOnionOpacity(v: Float) {
        _state.update { it.copy(onionOpacity = v) }
        renderer?.setOnionOpacity(v)
    }

    // ── Onion skin ────────────────────────────────────────────────────────────
    /**
     * Capture the current rendered frame as the onion reference.
     * The renderer runs the callback on the GL thread; we post the bitmap
     * back into the renderer's pending queue from there.
     */
    fun grabOnionFrame() {
        renderer?.requestCapture { bitmap ->
            // Already on GL thread — push directly to pending queue
            renderer?.pendingOnion?.set(bitmap)
            _state.update { it.copy(onionHasFrame = true, onionEnabled = true) }
            renderer?.setOnionEnabled(true)
        }
    }

    /** Load a reference frame from a content URI (gallery picker). */
    fun loadOnionFromUri(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull()?.let { bitmap ->
            renderer?.pendingOnion?.set(bitmap)
            _state.update { it.copy(onionHasFrame = true) }
        }
    }

    fun loadOnionFromBitmap(bitmap: Bitmap) {
        renderer?.pendingOnion?.set(bitmap)
        _state.update { it.copy(onionHasFrame = true) }
    }

    /** Load a .cube LUT from assets and enable it. */
    fun loadLutFromAssets(assetPath: String) = viewModelScope.launch(Dispatchers.IO) {
        LutLoader.parseCube(getApplication(), assetPath)?.let { (size, data) ->
            renderer?.load3dLut(size, data)
            _state.update { it.copy(lutEnabled = true) }
            renderer?.setLutEnabled(true)
        }
    }

    fun setUvcConnected(connected: Boolean) {
        _state.update { it.copy(uvcConnected = connected) }
    }
}
