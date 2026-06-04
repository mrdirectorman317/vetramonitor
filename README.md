# VetraMonitor

**Pro duplex field monitor for the Canon EOS R5 Mark II.**  
Runs on Android. Takes HDMI from the camera via a USB-C capture dongle and
adds cinematography assist tools, while simultaneously controlling the camera
wirelessly over Canon's CCAPI REST API.

Built for the real world: GPU-only video pipeline, no dropped frames, no GC
pauses in the frame path.

---

## Hardware you need

| Item | Notes |
|---|---|
| Android phone (USB host) | Samsung S25 works great |
| USB-C HDMI capture dongle | Any UVC-compliant card — Elgato Cam Link, Magewell, etc. |
| Canon EOS R5 Mark II | CCAPI must be enabled in the camera menu |
| HDMI cable | Camera → dongle |
| Wi-Fi network | Phone and camera on the same network, or camera in AP mode |

---

## What it does

### Live view
HDMI output from the R5 II flows into the USB-C dongle, which presents as a
UVC device. The app streams frames from it at up to 1080p/60 with no
intermediate render passes.

### Assist tools (all GPU, single shader pass)

| Button | What it does |
|---|---|
| **FC** | False colour — 10-zone Rec.709 luma palette. Green = 18 % grey, skin band is pink, clip is white |
| **PEAK** | Focus peaking — Sobel edge detection overlaid in your chosen colour (green / red / white). Threshold slider controls sensitivity |
| **LUT** | 3D LUT preview — load any `.cube` file; the LUT is uploaded to a `GL_TEXTURE_3D` and blended at adjustable strength |
| **ONION** | Onion skin — ghost a reference frame over the live view at adjustable opacity. Use **GRAB** to freeze the current frame, or **LOAD** to import a still from the gallery |

Multiple assists can be active simultaneously. The order in the shader is:
`LUT → Peaking → False Colour → Onion skin`.

### Onion skin — the music-video use case
The onion skin was designed for matching compositions across two different
locations (e.g. a living-room set and a warehouse recreation). Grab a frame
at the end of the first shoot, load it back at the start of the second, dial
the ghost to ~40 % opacity, and match the shot frame-for-frame.

### CCAPI camera control
Tap the **CCAPI** button, enter the camera's IP and port
(`http://192.168.x.x:8080`). Once connected:

- **Touch anywhere** on the live view → sends a touch-AF command to the camera
  with normalised coordinates
- **ISO / f / shutter** are polled every 2 s and displayed in the HUD
- **REC** button starts / stops movie recording on the camera

---

## Building and installing

### Requirements
- Android Studio (Hedgehog or newer)
- Android SDK 34
- Java 17

### Steps
1. Open Android Studio → **File → New → Project from Version Control**
2. Paste `https://github.com/mrdirectorman317/vetramonitor`
3. Select branch `claude/canon-r5-field-monitor-LjO0Q`
4. Let Gradle sync (downloads AUSBC from JitPack and OkHttp from Maven Central)
5. Enable USB Debugging on the phone
6. Press **▶ Run**

### First-time camera setup
1. On the R5 II: **Menu → Network → Camera Control API → Enable**
2. Note the IP address shown on the camera screen
3. In VetraMonitor: tap **CCAPI**, enter `http://<ip>:8080`, tap Connect

---

## Project layout

```
app/src/main/
├── assets/shaders/
│   ├── monitor.vert        Full-screen quad vertex shader
│   └── monitor.frag        All assist tools in one fragment shader
├── java/com/vetramonitor/
│   ├── MainActivity.kt     Activity: lifecycle, controls, state observation
│   ├── MonitorGLSurfaceView.kt   Custom GLSurfaceView; routes touch → CCAPI
│   ├── MonitorRenderer.kt  GLSurfaceView.Renderer: texture upload, draw call
│   ├── ShaderProgram.kt    Compile/link GL programs, uniform helpers
│   ├── LutLoader.kt        .cube parser; false-colour LUT builder
│   ├── UvcBridge.kt        AUSBC 3.3 wrapper; NV21→RGBA fallback
│   ├── CcapiClient.kt      OkHttp REST client for Canon CCAPI
│   ├── MonitorViewModel.kt StateFlow state; owns CcapiClient lifecycle
│   └── ConnectFragment.kt  Dialog for entering CCAPI URL
└── res/
    ├── layout/activity_main.xml
    ├── layout/fragment_connect.xml
    └── xml/usb_device_filter.xml   Auto-launch when UVC dongle is plugged in
```

---

## Key dependencies

| Library | Purpose |
|---|---|
| `com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.3.0` | User-space UVC stack (JitPack) |
| `com.squareup.okhttp3:okhttp:4.12.0` | CCAPI HTTP client |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3` | Async CCAPI calls |
| `androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0` | ViewModel / StateFlow |
| `com.google.android.material:material:1.11.0` | UI components |

---

## Known limitations / next steps

- LUT files must be placed in `app/src/main/assets/luts/` and loaded via
  `MonitorViewModel.loadLutFromAssets("luts/your.cube")` — there is no
  in-app file picker for LUTs yet
- Onion skin reference is lost on activity recreation (rotation); re-grab after
  rotating
- CCAPI touch-AF coordinate mapping assumes the phone display and camera frame
  have the same aspect ratio; add letterbox correction if needed
- Peaking colour is fixed to green/red/white presets; a colour picker would be
  a natural addition
