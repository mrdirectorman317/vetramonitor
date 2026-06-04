# CLAUDE.md — VetraMonitor

This file is read automatically by Claude Code at the start of every session.
It gives you everything you need to understand the codebase and make changes
without reading every file first.

---

## What this app is

VetraMonitor is an Android field monitor for the Canon EOS R5 Mark II.
A USB-C HDMI capture dongle delivers frames from the camera's HDMI output.
Those frames are rendered full-screen with GPU-only assist tools (false colour,
focus peaking, 3D LUT, onion skin). Simultaneously, the app talks to the
camera over Wi-Fi using Canon's CCAPI REST API for touch-to-focus, exposure
readout, and record control.

The primary user is a cinematographer / director shooting a music video.
The onion skin feature exists specifically to match compositions across two
locations (living-room set → warehouse recreation).

---

## Architecture in one paragraph

`UvcBridge` (wraps AUSBC 3.3 library) pulls RGBA frames off the USB dongle on
a background thread and drops them into `MonitorRenderer.pendingFrame`
(AtomicReference). `MonitorRenderer` (GLSurfaceView.Renderer) picks them up
at the top of each `onDrawFrame`, uploads to a `GL_TEXTURE_2D`, and runs a
single fragment shader pass that chains LUT → peaking → false colour → onion
skin. All renderer state changes from other threads go through a
`ConcurrentLinkedQueue<() -> Unit>` drained at draw time — no locks, no GC.
`MonitorViewModel` owns a `CcapiClient` (OkHttp + coroutines) that fires
CCAPI REST calls in response to touch events and polls camera settings every
2 s. `MainActivity` observes `MonitorViewModel.state: StateFlow<MonitorState>`
and updates the UI and renderer accordingly.

---

## File map — go here to change X

| What you want to change | File |
|---|---|
| Add / modify an assist tool | `assets/shaders/monitor.frag` |
| Change the false-colour palette | `LutLoader.kt` → `buildFalseLutInto()` |
| Add a new CCAPI endpoint | `CcapiClient.kt` |
| Add a new piece of camera state to the HUD | `MonitorViewModel.kt` → `MonitorState`, then `MainActivity.kt` → `observeState()` |
| Change the UI layout | `res/layout/activity_main.xml` |
| Change button styles / colours | `res/values/themes.xml`, `res/values/colors.xml` |
| Fix UVC frame format / dongle compatibility | `UvcBridge.kt` → `attachFrameCallback()` / `nv21ToRgba()` |
| Change GL texture upload or rendering | `MonitorRenderer.kt` |
| Add a new screen / dialog | Create a `Fragment`, add to `res/layout/`, show from `MainActivity` |
| Change app permissions | `AndroidManifest.xml` |
| Add / change dependencies | `app/build.gradle` |

---

## The fragment shader (`monitor.frag`) — how assists chain

```
uFrame (live RGBA texture)
  │
  ▼
[if uLutEnabled]       → sample uLut3d (GL_TEXTURE_3D, 33³ .cube file)
                          mix(color, graded, uLutStrength)
  │
  ▼
[if uPeakingEnabled]   → Sobel 3×3 on luma; if mag > uPeakThreshold
                          mix(color, uPeakColor, intensity)
  │
  ▼
[if uFalseColorEnabled]→ luma → sample uFalseLUT (256×1 RGBA ramp)
                          replaces RGB entirely
  │
  ▼
[if uOnionEnabled]     → sample uOnionTex (reference frame bitmap)
                          desaturate ghost 55%, mix at uOnionOpacity
  │
  ▼
fragColor
```

All five stages share one draw call. Add a new stage by adding a `uniform bool`
flag, a `uniform` for its parameters, and a new `if` block in `main()`.

---

## Uniforms reference

| Uniform | Type | Default | Meaning |
|---|---|---|---|
| `uFrame` | `sampler2D` | unit 0 | Live RGBA frame |
| `uFalseLUT` | `sampler2D` | unit 1 | 256×1 false-colour ramp |
| `uLut3d` | `lowp sampler3D` | unit 2 | 33³ colour-grading LUT |
| `uOnionTex` | `sampler2D` | unit 3 | Onion skin reference frame |
| `uFalseColorEnabled` | `bool` | false | Enable false colour |
| `uPeakingEnabled` | `bool` | false | Enable focus peaking |
| `uLutEnabled` | `bool` | false | Enable 3D LUT |
| `uOnionEnabled` | `bool` | false | Enable onion skin |
| `uPeakThreshold` | `float` | 0.08 | Sobel cutoff (0.02–0.30) |
| `uPeakColor` | `vec3` | (0.1,1,0.15) | Peak highlight colour |
| `uLutStrength` | `float` | 1.0 | LUT mix (0–1) |
| `uOnionOpacity` | `float` | 0.4 | Ghost opacity (0–1) |
| `uTexelSize` | `vec2` | 1/w, 1/h | Frame texel size for Sobel |

---

## Threading model

| Thread | What runs there |
|---|---|
| Main (UI) | Activity, ViewModel, StateFlow collectors |
| GL thread | `MonitorRenderer.onDrawFrame`, all `GLES30.*` calls |
| AUSBC callback thread | `UvcBridge.attachFrameCallback()` → writes `pendingFrame` |
| Coroutine (IO) | `CcapiClient` HTTP calls, LUT file parsing |

**Rule:** never call `GLES30.*` outside the GL thread. Use `MonitorRenderer.glActions` queue or `pendingFrame` / `pendingOnion` AtomicReferences to cross into the GL thread.

---

## CCAPI quick reference (Canon R5 Mark II)

Base URL: `http://<camera-ip>:8080` (configured in camera menu)

| Endpoint | Method | Body / response |
|---|---|---|
| `/ccapi` | GET | Ping / version info |
| `/ccapi/ver100/shooting/settings/af/touchafframe` | PUT | `{"xposition":0.5,"yposition":0.5}` |
| `/ccapi/ver100/shooting/settings/iso` | GET | `{"value":"800"}` |
| `/ccapi/ver100/shooting/settings/av` | GET | `{"value":"2.8"}` |
| `/ccapi/ver100/shooting/settings/tv` | GET | `{"value":"1/100"}` |
| `/ccapi/ver100/shooting/control/movierecording` | POST | `{"action":"start"}` or `{"action":"stop"}` |
| `/ccapi/ver100/shooting/control/shutterbutton` | POST | `{"af":true}` |

To add a new endpoint, add a `suspend fun` to `CcapiClient.kt` and call it
from a `viewModelScope.launch` in `MonitorViewModel.kt`.

---

## UVC / dongle notes

The app uses **AUSBC 3.3.0** (`com.github.jiangdongguo.AndroidUSBCamera:libausbc`
from JitPack). The key class is `CameraUvcStrategy`. If AUSBC's API changes on
a version bump, only `UvcBridge.kt` needs updating.

AUSBC can deliver frames as RGBA or NV21. `UvcBridge.nv21ToRgba()` handles the
conversion when the dongle negotiates NV21. If you need better performance,
replace the software conversion with a two-texture YUV shader path in
`monitor.frag`.

---

## Adding a LUT file

1. Put `your_lut.cube` in `app/src/main/assets/luts/`
2. Call from anywhere in the app:
   ```kotlin
   viewModel.loadLutFromAssets("luts/your_lut.cube")
   ```
3. The LUT is parsed on an IO coroutine, uploaded to GL, and enabled automatically.
   A LUT file picker UI is a natural next feature.

---

## Known gaps to address

- **LUT file picker** — no in-app UI to browse and load `.cube` files; files
  must currently be bundled in assets
- **Onion skin lost on rotation** — the reference bitmap is not persisted in
  the ViewModel; re-grab after device rotation
- **Aspect ratio mismatch** — touch-AF coordinates assume phone display and
  camera frame have the same aspect; add letterbox offset math in
  `MonitorGLSurfaceView.onTouchEvent` if needed
- **Waveform / vectorscope** — common monitor tools not yet implemented; would
  be a second render pass (FBO → analysis shader → overlay)
- **Histogram** — same; compute in a fragment shader over a downsampled frame
- **Zebras** — single threshold highlight; easy to add as another shader stage
- **CCAPI settings write** — ISO / aperture / shutter display is read-only;
  picker UI + PUT calls would complete the loop
