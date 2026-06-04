# VetraMonitor Handoff

**Updated:** June 4, 2026
**Branch:** `claude/canon-r5-field-monitor-LjO0Q`
**Test device:** Samsung Galaxy S25 Ultra (`SM-S938U`), Android 16
**Capture dongle:** USB VID `0x345f` / PID `0x2130` (`13407:8496`)

## Current Status

VetraMonitor launches and the HDMI capture dongle now works on the S25.

The last installed build also contains a lower-latency frame path and a
double-tap clean-monitor mode. The clean-view gesture was verified on the S25.
The optimized video path builds and its shader loads without errors, but its
latency still needs to be compared with the Play Store UVC app while the
physical dongle is connected.

### June 4 regression repair

The first latency-optimized checkpoint incorrectly read `Size.fps`, which can
be null on this dongle, and preview failed with a null-FPS error. With no frame
available, an incorrectly initialized VU placeholder made the screen green.

The installed repair:

- no longer reads the dongle's nullable FPS array
- restores the previously proven UVC preview range of 1-31 fps
- initializes the no-frame Y/VU textures to neutral black
- uploads Y and VU using explicit buffer slices so the chroma plane starts at
  the correct offset

## User Controls

- Tap **UVC** to retry USB permission/device connection.
- Single-tap the video for Canon CCAPI touch-AF.
- Double-tap the video to hide all HUD and controls.
- Double-tap again to restore the HUD and controls.

## Problems Fixed

### Launch crashes

- AUSBC 3.2.7 used the legacy `registerReceiver()` overload, which crashes on
  modern Android without an exported/not-exported flag.
- `UvcBridge` now supplies a receiver-safe `ContextWrapper`.
- AUSBC's `USBMonitor` can invoke callbacks with null devices even though its
  Java API is not annotated correctly. Listener parameters are nullable.
- The CCAPI dialog buttons were missing required layout dimensions and crashed
  when the dialog opened.

### USB permission loop / no video

- Android requires the regular runtime `CAMERA` permission before it grants
  access to USB video-class devices. The app now declares and requests it.
- USB permission retries are de-duplicated so attaching the dongle does not
  flood Android with repeated permission requests.
- Tapping **UVC** recreates stale USB monitor state and retries cleanly.
- UVC connection state and actionable errors are shown in the bottom status
  line.

### Dongle compatibility

- `UvcBridge` uses AUSBC's low-level `USBMonitor` and `UVCCamera` APIs directly
  instead of `CameraUvcStrategy`.
- Composite USB video devices are detected by inspecting their interfaces.
- Preview modes are read from the dongle, capped at 1920x1080, and negotiated
  using MJPEG first with YUYV fallback.
- Preview negotiation currently uses the library's proven 1-31 fps range.
- The known dongle VID/PID is included in `usb_device_filter.xml`.

### Latency work

The original frame path copied NV21, converted every pixel to RGBA in Kotlin,
allocated a new RGBA frame, and then uploaded it to OpenGL. At 1080p this was
millions of CPU operations and about 11 MB of fresh allocations per frame.

The current path:

```text
UVC NV21 callback
  -> copy into one of three pooled NV21 buffers
  -> replace/drop stale pending frame
  -> upload Y and VU planes to two GL textures
  -> convert NV21 to RGB in monitor.frag
  -> LUT / peaking / false colour / onion skin
```

There is no per-pixel Kotlin conversion and no per-frame byte-array allocation.
If rendering falls behind, the pending stale frame is released and replaced by
the newest frame rather than accumulating latency.

## Important Architecture Details

### UVC and frame ownership

- `UvcBridge.kt` owns `USBMonitor`, `UVCCamera`, preview negotiation, and a
  three-buffer NV21 pool.
- The UVC callback passes a pooled buffer and a `release` callback to
  `MainActivity`.
- `MonitorRenderer.submitFrame()` atomically replaces the pending frame and
  releases any stale buffer.
- `MonitorRenderer.uploadFrame()` uploads the Y and VU planes on the GL thread,
  then releases the buffer back to `UvcBridge`.
- Never retain a `FrameData.bytes` reference after invoking `release`.

### OpenGL textures

| Unit | Uniform | Contents |
|---|---|---|
| 0 | `uFrameY` | Full-resolution NV21 luma plane (`GL_R8`) |
| 1 | `uFrameVU` | Half-resolution interleaved NV21 VU plane (`GL_RG8`) |
| 2 | `uFalseLUT` | False-colour ramp |
| 3 | `uLut3d` | Colour-grading 3D LUT |
| 4 | `uOnionTex` | Onion-skin reference frame |

`monitor.frag` converts NV21 to RGB before the assist-tool chain. Focus peaking
samples the Y texture directly for its Sobel pass.

### Touch handling

`MonitorGLSurfaceView` uses `GestureDetector`:

- `onSingleTapConfirmed` sends one CCAPI touch-AF request.
- `onDoubleTap` toggles clean view through `MainActivity`.
- The old behavior sent touch-AF repeatedly for ACTION_DOWN and ACTION_MOVE;
  that network-request flood has been removed.

## Relevant Files

| Area | File |
|---|---|
| USB/UVC permission, negotiation, pooled frames | `app/src/main/java/com/vetramonitor/UvcBridge.kt` |
| NV21 texture upload and stale-frame dropping | `app/src/main/java/com/vetramonitor/MonitorRenderer.kt` |
| GPU NV21 conversion and assist chain | `app/src/main/assets/shaders/monitor.frag` |
| Double-tap and touch-AF gestures | `app/src/main/java/com/vetramonitor/MonitorGLSurfaceView.kt` |
| Runtime permission and clean-view UI | `app/src/main/java/com/vetramonitor/MainActivity.kt` |
| Camera permission / USB auto-launch | `app/src/main/AndroidManifest.xml` |
| Dongle USB filter | `app/src/main/res/xml/usb_device_filter.xml` |

## Verification Completed

- `:app:assembleDebug` passes.
- `:app:lintDebug` passes.
- Debug APK installs and cold-launches on the S25.
- `CAMERA` runtime permission is declared and granted.
- No VetraMonitor crash or shader compilation error in final logcat check.
- After the null-FPS repair, the no-device screen was verified as neutral black
  rather than green.
- Double-tap clean view verified on-device:
  - before: eight HUD/control elements visible
  - after double-tap: zero visible
  - after second double-tap: all eight restored
- Earlier build verified that the physical capture dongle produces video.

Build command:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
~/.gradle/wrapper/dists/gradle-8.6-bin/afr5mpiioh2wthjmwnkmdsd5w/gradle-8.6/bin/gradle \
:app:assembleDebug :app:lintDebug
```

Install command:

```bash
~/Library/Android/sdk/platform-tools/adb install -r \
app/build/outputs/apk/debug/app-debug.apk
```

## Immediate Next Test

1. Disconnect the S25 from the Mac.
2. Connect the HDMI capture dongle and Canon camera.
3. Open VetraMonitor and tap **UVC** if it does not connect automatically.
4. Compare motion latency against the known low-latency Play Store UVC app.
5. Record the selected mode shown in logcat:

   ```bash
   adb logcat -d -v threadtime | rg 'Selected low-latency UVC mode|UVC: live'
   ```

If latency remains noticeably worse, the next likely step is a direct
`SurfaceTexture`/external-OES preview path to avoid the remaining native
UVC-to-NV21 conversion and CPU copy. That would require adapting the assist
shader pipeline to sample an external texture or first blit it into a 2D
texture/FBO.

## Known Remaining Gaps

- Optimized UVC latency has not yet been measured with the dongle attached.
- Touch-AF coordinates still assume the display and camera image have the same
  aspect ratio.
- Onion reference is not persisted across Activity recreation.
- LUT file picker is not implemented.
- Waveform, vectorscope, histogram, and zebras are not implemented.
- CCAPI exposure settings remain read-only.
