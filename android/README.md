# PortalSnap for Android

The same app as `public/app.html` (a strip of face filters, a shutter, a record button and an
album), rebuilt as a native app sideloaded onto a Meta Portal. It uses the same MediaPipe
models from `public/models`, the same filters, and the same server, pairing and album. Nothing
server-side changed.

The web app's tuning thread ended at "everything that measured anything has been pulled". What
was left was the browser itself: canvas capture, redrawing a composited layer, WASM inference
and a 32-bit 2GB tab. Native removes all four.

## Where this stands (2026-09-14)

**Working and verified on the gen 1 Portal (10", Android 9):**
- **Filters and tracking:** all 11 filters, tracking up to three faces (two on the mesh tier).
- **Photos:** unmirrored 1280x720 JPEG with the filter composited.
- **Clips:** H.264 + AAC through the pitch shifter, with a poster, recorded at 30fps with no
  tracker penalty.
- **Pairing:** QR and code against the existing `/auth/pair/*` flow, collecting the `psnap`
  cookie.
- **Server:** upload to `/media` (plus the poster via `?for=`), album grid from `/media/list`,
  viewer, and two-tap delete.

**Not yet verified:**
- **Real faces:** everything was checked against a test portrait fed through the pipeline in
  place of the camera, so all the tracking checks came from a still photo. Nobody has sat in
  front of the real camera yet.
- **Camera orientation (now fixed):** the first build was sideways. The empty-room check had
  looked plausible. Rotation 90 was confirmed with a face on 2026-09-14 and is now computed
  rather than hardcoded; see the quirk below.
- **Mustache:** "Fancy" on the fast tier sat slightly low and tilted on the drifting test
  image. That may be lag or may be real.

**Not ported:**
- **The Send/share button:** the Portal's share API was dead in the browser, and there is no
  share target natively either.
- **The full-screen toggle:** the app is always full screen.
- **The worker/main-thread diagnostic tap:** there is no worker natively.

## Measured on the device

`bench/2026-09-14-gen1-portal.json`, from `--ez bench true`. Test portrait, 3s warm-up
discarded, then 8s measured.

| | tier | `infer` p50 | p95 | detect | render | recording |
|---|---|---|---|---|---|---|
| Cool (shades), 1 face | fast, GPU | **11.8 ms** | 16.6 | **30.3 fps** (camera cap) | 30.3 | |
| Cool, 2 faces | fast, GPU | 11.4 ms | 15.8 | 30.4 fps | 30.4 | |
| Puppy, 1 face | mesh, GPU | 41.5 ms | 66.9 | 15.7 fps | 30.2 | |
| Puppy, 2 faces | mesh, GPU | 55.8 ms | 78.4 | 12.9 fps | 30.4 | |
| Skydive, 2 faces | fast, GPU | 11.7 ms | 18.2 | 30.4 fps | 30.4 | |
| Beach | segment, CPU | 25.9 ms | 35.7 | 24.4 fps | 30.2 | |
| Puppy **while recording** | mesh, GPU | 38.1 ms | 52.0 | 16.6 fps | 30.3 | **30 fps** |
| Beach **while recording** | segment, CPU | 25.8 ms | 40.1 | 25.3 fps | 30.3 | **30.5 fps** |

For comparison, the web app on its own Portal (README, "Where this stands"):

| | web: fast tier | native: fast tier |
|---|---|---|
| filter on, face moving | 29 ms, 21.7 fps | 11.8 ms, 30 fps |
| while recording | ~52 ms, 13.5–14 fps | unchanged: the encoder reads the GPU frame |
| frame grab for the tracker | 10–15 ms | 1.2–2.3 ms |

Rows A, J and K of that bench file have their tier mislabelled: "no filter" kept whatever
tier was last loaded. The bench now selects the fast tier explicitly. K ran the mesh with
nobody in front of the real camera, and 10.6ms is what the mesh costs with no face to landmark.

**Live camera, one real face** (`bench/2026-09-14-gen1-portal-camera.json`, from
`--ez bench true --ez benchCamera true`, one person at a desk in daylight):

| | tier | `infer` p50 | p95 | detect | camera | recording |
|---|---|---|---|---|---|---|
| tracker only | fast, GPU | 10.8 ms | 14.8 | 29.8 fps | 30.0 | |
| Cool (shades) | fast, GPU | **12.9 ms** | 20.9 | **29.2 fps** | 29.5 | |
| Puppy | mesh, GPU | 41.0 ms | 55.9 | 16.4 fps | 30.0 | |
| Skydive | fast, GPU | 11.8 ms | 17.7 | 29.4 fps | 30.0 | |
| Beach | segment, CPU | 22.3 ms | 33.1 | 26.0 fps | 29.5 | |
| Puppy **while recording** | mesh, GPU | 36.3 ms | 47.4 | 17.4 fps | 30.0 | **29.5 fps** |
| Beach **while recording** | segment, CPU | 19.8 ms | 25.2 | 28.2 fps | 29.5 | **30 fps** |

These match the test portrait to within a couple of milliseconds, so the portrait is a fair
stand-in. The tracker's frame grab is 1.3–1.5ms. The screen renders at the camera's 30fps in
every row. The "faces 0" on the tracker-only row is an instrument quirk: faces are counted by
the painter, which only runs with a filter on.

Model load, once per launch, on a background thread so detection never waits on it: fast
3.0s, mesh 5.0s, segment 0.05s. Models stay loaded, so switching filters is instant after the
first time.

The obvious next lever is the mesh tier's ~40ms. `outputFaceBlendshapes` is only read by Puppy
(tongue) and Big Head, so Kitty and Royal could skip it (the web app measured it at ~4ms). The
fast tier is already at the camera's ceiling.

## How it is built

Everything GL runs on one render thread (`Compositor`). Each camera frame goes through these
steps:

1. **Frame:** the camera's OES texture is drawn upright and unmirrored into a 1280x720 FBO. This
   is the single space every picture lives in.
2. **Tracker:** only if the tracker is idle (one frame in flight, as on the web). The frame is
   mipmapped down to 320x180 (256x144 for segmentation), read back (the `grab`), and handed to
   `Tracker` on its own thread.
3. **Paint:** `Painter` eases the tracks and asks the active filter to draw. Stickers go into a
   hardware-accelerated Canvas surface (`CanvasLayer`, an OES texture). Scenes and the sky go
   into a second layer beneath. Filters that sampled the video on the web become GPU patches:
   Big Head's zoomed head and the skydiver's face are an ellipse-clipped affine resample, and
   the backgrounds are the camera times the segmentation mask.
4. **Composite:** frame, under layer, mask, patches and over layer go into a second FBO.
5. **Out:** that FBO is drawn mirrored to the screen, unmirrored to the encoder's input surface
   when recording, and read back once for a still.

| file | port of |
|---|---|
| `Anchors.kt` | `public/anchors.js`: indices, face caps, result mapping |
| `Tracks.kt` | `matchTracks` / `adopt` / `easeTrack` / `buildFace` from `app.html`, same constants |
| `Filters.kt` | `public/filters.js`: all 11 filters, Canvas 2D → `android.graphics.Canvas` |
| `PitchShifter.kt` | `public/pitch.worklet.js` |
| `Painter.kt` | `render()` from `app.html` |
| `Compositor.kt` | new: the GL passes above, plus the test source |
| `Tracker.kt` | `tracker.worker.js` |
| `Recorder.kt` | new: MediaCodec H.264 + AAC → MediaMuxer |
| `Server.kt` | the fetches in `app.html`, plus `/auth/pair/*` from `pair.html`'s other side |
| `Ui.kt`, `MainActivity.kt` | the stage, bar, review, album and pairing overlays, in the web app's palette |

## Building and installing

The toolchain lives outside the repo, in `~/Development/portal-tools`: JDK 17, the Android SDK
(platform 36, build-tools 37), Gradle 9.7 and adb. None of it needs root.

```bash
export JAVA_HOME=~/Development/portal-tools/jdk17 ANDROID_HOME=~/Development/portal-tools/android-sdk
./tools/fetch-test-assets.sh           # once: the debug build's test portrait (not committed)
./gradlew assembleDebug
adb -s 192.168.1.77:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.1.77:5555 shell pm grant net.sgran.portalsnap android.permission.CAMERA
adb -s 192.168.1.77:5555 shell pm grant net.sgran.portalsnap android.permission.RECORD_AUDIO
```

`local.properties` (not committed) holds `sdk.dir`. The APK carries both `arm64-v8a` and
`armeabi-v7a`, for the second Portal's 32-bit userland.

### Driving it from adb

Everything a finger can do is also an intent extra:

```bash
A="adb -s 192.168.1.77:5555 shell am start -n net.sgran.portalsnap/.MainActivity"
$A --ei faces 1 --es filter dog --ez hud true   # test portrait instead of the camera (debug builds)
$A --ei faces 2 --es filter skydiver            # two portraits
$A --ei faces 0                                 # back to the camera
$A --es action photo|record|stop|keep|again|album|pair|close
$A --es server https://portalsnap.example.net   # set the server
$A --ei rot 180                                 # override camera rotation
$A --ez bench true                              # the bench above; results in files/bench-*.json and logcat PSNAP_BENCH
adb -s 192.168.1.77:5555 logcat -s PSNAP        # a stats line every 2s
```

The HUD also toggles with a tap on the bottom-right corner, as on the web.

## Portal quirks found while building this

Each of these cost real time, so check here first:

- **Screensaver in front.** If the Portal is dreaming (Immortal's photo frame), `am start` puts
  the app *behind* the screensaver: the camera never opens and every stat reads 0. Send
  `input keyevent KEYCODE_WAKEUP` then `input tap 640 60` first.
- **The privacy button.** With the top-edge camera/mic button on (red), the camera opens and
  the green LED lights, but no frame arrives. After ~10s the Portal kills the client with
  `CameraDevice` error 3 and logs `LedPolicy violation: Led (1) Preview (0)` in
  `dumpsys media.camera`.
- **Camera rotation.** The camera service already turns camera 0's buffers by the sensor's
  90°: the SurfaceTexture matrix arrives as `[0 -1; 1 0]`, with no mirror. The panel is
  portrait-native and the app runs at `ROTATION_270`. The correct extra rotation is therefore
  `(360 - display) % 360` = 90, not sensor + display. `Compositor` reads the quarter turn and
  any mirror off the matrix itself, so the crop stays right either way. If another Portal
  comes out sideways, `--ei rot N` fixes it and is remembered per device; `--ei rot -1`
  clears it.
- **Only camera 0.** `cameraIdList` shows apps only camera 0, Meta's 1280x720 "smart camera"
  that crops and pans in hardware. Camera 1 (the 4056x3040 sensor) needs `CAMERA_PRIV`.
- **The GPU segmenter aborts the process.** `ImageSegmenter` with a category mask on the GPU
  delegate kills the app in `convertToTaskResult`: `image_frame.cc: Check failed:
  ImageFormat::UNKNOWN != format_`. It's a native abort, so there is nothing to catch. The
  segmenter runs on the CPU instead, at 26ms.
- **Encoders get the decoder role.** `MediaCodec.configure(format, null, null, 0)` on any
  H.264 encoder, hardware or software, fails with -1010 after ACodec logs `Failed to set
  standard component role 'video_decoder.avc'`. Passing `CONFIGURE_FLAG_ENCODE` and an
  `encoder=1` format key fixes it (`Recorder.asEncoder`).
- **No parachute emoji.** Android 9's emoji font has no 🪂, so the Skydive chip falls back to 🎈.
- **Bulk transfers stall over USB.** Through usbipd into WSL, small adb commands work but
  large file transfers hang. Use adb over Wi-Fi: run `adb tcpip 5555` once over USB, then
  `adb connect`. It lasts until the Portal reboots.

## Morning checklist

1. **Wake the Portal.** Its IP is 192.168.1.77. If `adb connect 192.168.1.77:5555` is refused,
   it rebooted: redo `adb tcpip 5555` over USB.
2. **Sit in front of the camera** and launch the app normally, from its launcher tile or with
   `am start` with no extras. Check that:
   - the preview is upright and mirrored like a mirror
   - stickers land on the face (try Cool, Puppy with the mouth open, Fancy, Big Head)
   - two people get two stickers
   - Beach cuts a real person out cleanly
3. **Run `--ez bench true`** with a face in view. Rows J and K then measure the real camera
   with a real face.
4. **Pair with the real server:** tap 🖼️, type its URL, and approve from a phone.
5. **Record a clip with a silly voice** (Kitty or Googly) and play it back from the album.
6. **Try the second Portal** (the one the web app was measured on): same APK, 32-bit userland.
