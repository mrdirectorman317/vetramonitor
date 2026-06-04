package com.vetramonitor

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.vetramonitor.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MonitorViewModel by viewModels()
    private lateinit var uvcBridge: UvcBridge
    private var cleanView = false

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { vm.loadOnionFromUri(it) } }

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            uvcBridge.retryCameraPermission()
        } else {
            showUvcStatus("UVC: camera permission required. Tap UVC to retry.")
            Toast.makeText(
                this,
                "Allow camera permission so Android can open the HDMI capture dongle",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Build the renderer, hand it to the ViewModel, attach to the GL view
        val renderer = MonitorRenderer(
            assetLoader = { path -> assets.open(path).bufferedReader().readText() },
        )
        vm.renderer = renderer
        binding.glView.init(renderer, vm)

        // UVC bridge — frames land in the renderer's atomic reference
        uvcBridge = UvcBridge(
            context = this,
            onFrame = { bytes, w, h, release ->
                renderer.submitFrame(FrameData(bytes, w, h, release))
                binding.glView.requestRender()
            },
            onConnected    = { runOnUiThread { vm.setUvcConnected(true) } },
            onDisconnected = { runOnUiThread { vm.setUvcConnected(false) } },
            onStatus = { message ->
                runOnUiThread { binding.tvUvcStatus.text = message }
            },
            onError = { message ->
                runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
            },
        )

        setupControls()
        observeState()
    }

    override fun onStart() {
        super.onStart()
        uvcBridge.register()
        if (hasCameraPermission()) {
            uvcBridge.openCamera()
        } else {
            showUvcStatus("UVC: tap UVC and allow camera access")
        }
    }
    override fun onStop()    { super.onStop();    uvcBridge.closeCamera(); uvcBridge.unregister() }
    override fun onResume()  { super.onResume();  binding.glView.onResume() }
    override fun onPause()   { super.onPause();   binding.glView.onPause() }
    override fun onDestroy() { uvcBridge.release(); super.onDestroy() }

    // ── Controls ──────────────────────────────────────────────────────────────
    private fun setupControls() {
        binding.glView.setOnDoubleTapListener {
            setCleanView(!cleanView)
        }

        binding.btnFalseColor.setOnClickListener { vm.setFalseColorEnabled(!vm.state.value.falseColorEnabled) }
        binding.btnPeaking.setOnClickListener    { vm.setPeakingEnabled(!vm.state.value.peakingEnabled) }
        binding.btnLut.setOnClickListener        { vm.setLutEnabled(!vm.state.value.lutEnabled) }

        binding.btnOnion.setOnClickListener {
            val s = vm.state.value
            if (!s.onionEnabled && !s.onionHasFrame) showOnionSourcePicker()
            else vm.setOnionEnabled(!s.onionEnabled)
        }

        binding.btnGrabOnion.setOnClickListener {
            vm.grabOnionFrame()
            Toast.makeText(this, "Reference frame captured", Toast.LENGTH_SHORT).show()
        }
        binding.btnLoadOnion.setOnClickListener { pickImage.launch("image/*") }

        binding.sliderOnionOpacity.addOnChangeListener { _, v, _ -> vm.setOnionOpacity(v) }
        binding.sliderPeakThreshold.addOnChangeListener { _, v, _ -> vm.setPeakThreshold(v) }
        binding.sliderLutStrength.addOnChangeListener { _, v, _ -> vm.setLutStrength(v) }

        binding.btnConnect.setOnClickListener {
            ConnectFragment().show(supportFragmentManager, "connect")
        }

        binding.btnRecord.setOnClickListener {
            if (vm.state.value.ccapiConnected) vm.toggleRecording()
            else Toast.makeText(this, "Connect to CCAPI first", Toast.LENGTH_SHORT).show()
        }

        binding.btnOpenUvc.setOnClickListener {
            if (hasCameraPermission()) {
                uvcBridge.retryCameraPermission()
            } else {
                showUvcStatus("UVC: allow camera access in the Android prompt")
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            vm.state.collect { s ->
                binding.btnFalseColor.isSelected = s.falseColorEnabled
                binding.btnPeaking.isSelected    = s.peakingEnabled
                binding.btnLut.isSelected        = s.lutEnabled
                binding.btnOnion.isSelected      = s.onionEnabled
                binding.btnRecord.isSelected     = s.recording

                binding.tvIso.text      = "ISO ${s.cameraIso}"
                binding.tvAperture.text = "f/${s.cameraAperture}"
                binding.tvShutter.text  = s.cameraShutter

                binding.indicatorUvc.isActivated   = s.uvcConnected
                binding.indicatorCcapi.isActivated = s.ccapiConnected

                // Recording dot
                binding.recordingDot.visibility =
                    if (s.recording && !cleanView) View.VISIBLE else View.GONE

                // Onion controls
                val showOnion = s.onionEnabled || s.onionHasFrame
                binding.onionControls.visibility = if (showOnion) View.VISIBLE else View.GONE
                binding.sliderOnionOpacity.value = s.onionOpacity

                // Peaking controls
                binding.peakingControls.visibility =
                    if (s.peakingEnabled) View.VISIBLE else View.GONE
                binding.sliderPeakThreshold.value = s.peakThreshold

                // LUT controls
                binding.lutControls.visibility =
                    if (s.lutEnabled) View.VISIBLE else View.GONE
                binding.sliderLutStrength.value = s.lutStrength
            }
        }
    }

    private fun showOnionSourcePicker() {
        AlertDialog.Builder(this)
            .setTitle("Onion skin source")
            .setItems(arrayOf("Grab current live frame", "Load from gallery")) { _, which ->
                when (which) {
                    0 -> vm.grabOnionFrame()
                    1 -> pickImage.launch("image/*")
                }
            }
            .show()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun showUvcStatus(message: String) {
        binding.tvUvcStatus.text = message
    }

    private fun setCleanView(enabled: Boolean) {
        cleanView = enabled
        binding.hudTop.visibility = if (enabled) View.GONE else View.VISIBLE
        binding.bottomPanel.visibility = if (enabled) View.GONE else View.VISIBLE
        binding.recordingDot.visibility =
            if (!enabled && vm.state.value.recording) View.VISIBLE else View.GONE
    }
}
