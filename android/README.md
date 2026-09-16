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
- **Photos and clips:** kept on the Portal in `Pictures/PortalSnap` and `Movies/PortalSnap`,
  where the album reads them, with a viewer and two-tap delete.
- **Server (optional):** under Settings → Advanced. Once paired, each kept capture is also
  uploaded to `/media` (plus the poster via `?for=`), and anything that couldn't go is retried.

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

For comparison, the web app on its own Portal (BUILD-JOURNAL.md, "Where this stands"):

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
| `Ui.kt`, `MainActivity.kt` | the stage, bar, review, album, settings and pairing overlays, in the web app's palette |
| `Captures.kt` | new: photos and clips kept on the Portal, and the queue that sends them to a server |

## Installing a release on a Portal

Each release on GitHub (https://github.com/sgranman/portalsnap/releases) has one
`PortalSnap-x.y.z.apk` for both Portal generations. The Portal needs ADB unlocked first. Then:

```bash
adb connect 192.168.1.77:5555          # the Portal's address, if it's on Wi-Fi adb
adb push PortalSnap-0.3.0.apk /data/local/tmp/portalsnap.apk
adb shell pm install -r -g /data/local/tmp/portalsnap.apk
```

- **`-g`** grants the camera, microphone and storage permissions at install, so nobody has to
  answer prompts on the Portal.
- **Push, then install:** a streamed `adb install` hung for over 30 minutes on the gen 1 Portal.
- **Updates** install the same way, over the top. Photos, clips, settings and pairing all stay.
- **From a debug build:** debug builds are signed with a different key, so the first release
  needs `adb uninstall net.sgransoft.portalsnap` before it. That forgets the server pairing and
  settings. Photos and clips in Pictures and Movies stay.

Photos go to `/sdcard/Pictures/PortalSnap` and clips to `/sdcard/Movies/PortalSnap`, and
`adb pull` copies them to a computer. A server is optional, under Settings (the gear) → Advanced.

## Releases

Two GitHub Actions workflows live in `.github/workflows`:

- **Android build** (`android.yml`) builds the debug APK on every push or pull request that
  touches `android/` or the models, and keeps the APK on the run for two weeks.
- **Release APK** (`release.yml`) builds a signed APK and publishes a GitHub Release with the
  APK and its SHA-256. Start it by pushing a tag (`git tag v0.3.0 && git push origin v0.3.0`)
  or from Actions → Release APK → Run workflow, typing the version.

The version comes from the tag. The version code is major × 10000 + minor × 100 + patch, so
each release installs over the one before.

Signing uses one long-lived key that is never committed. CI reads it from four repository
secrets: `PORTALSNAP_KEYSTORE_BASE64`, `PORTALSNAP_KEYSTORE_PASSWORD`, `PORTALSNAP_KEY_ALIAS`
and `PORTALSNAP_KEY_PASSWORD`. **Keep a backup of the keystore.** Losing it means the next
release can't install over the last, and everyone has to uninstall first.

A signed release builds locally with the same variables:

```bash
PORTALSNAP_KEYSTORE=/path/to/portalsnap-release.jks PORTALSNAP_KEYSTORE_PASSWORD=… \
  PORTALSNAP_KEY_ALIAS=portalsnap PORTALSNAP_KEY_PASSWORD=… \
  ./gradlew assembleRelease -PpsnapVersionName=0.3.0 -PpsnapVersionCode=300
```

## Building and installing

The toolchain lives outside the repo, in `~/Development/portal-tools`: JDK 17, the Android SDK
(platform 36, build-tools 37), Gradle 9.7 and adb. None of it needs root.

```bash
export JAVA_HOME=~/Development/portal-tools/jdk17 ANDROID_HOME=~/Development/portal-tools/android-sdk
./tools/fetch-test-assets.sh           # once: the debug build's test portrait (not committed)
./gradlew assembleDebug
adb -s 192.168.1.77:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.1.77:5555 shell pm grant net.sgransoft.portalsnap android.permission.CAMERA
adb -s 192.168.1.77:5555 shell pm grant net.sgransoft.portalsnap android.permission.RECORD_AUDIO
adb -s 192.168.1.77:5555 shell pm grant net.sgransoft.portalsnap android.permission.READ_EXTERNAL_STORAGE
adb -s 192.168.1.77:5555 shell pm grant net.sgransoft.portalsnap android.permission.WRITE_EXTERNAL_STORAGE
```

Grant both storage permissions: Android 9 only mounts shared storage writable for an app that
holds read as well as write. With write alone, saving to Pictures fails.

`local.properties` (not committed) holds `sdk.dir`. The APK carries both `arm64-v8a` and
`armeabi-v7a`, for the second Portal's 32-bit userland.

### Driving it from adb

Everything a finger can do is also an intent extra:

```bash
A="adb -s 192.168.1.77:5555 shell am start -n net.sgransoft.portalsnap/.MainActivity"
$A --ei faces 1 --es filter dog --ez hud true   # test portrait instead of the camera (debug builds)
$A --ei faces 2 --es filter bike                # two portraits
$A --ei faces 0                                 # back to the camera
$A --es action photo|record|stop|keep|again|album|settings|pair|close|poke
$A --es filter monster --es action poke         # poke = a stage tap (Monster/Cutie flips)
$A --es music /sdcard/Android/data/net.sgransoft.portalsnap/files/beat120.wav   # loop a track for Disco / Pop Art (baked into clips); "stop" stops
$A --ef jaw 0.8                                 # force jawOpen on test faces (Hearts, Monster's mouth); negative clears
$A --es filter bike --ef rideDist 1.6           # Bike Ride: hold every rider this many metres away (the portrait never moves); negative clears
$A --es filter freefall --ef fallDist 0.75      # Freefall: hold every diver this far away; poke (or --ef jaw 0.8) makes them fall
$A --es filter places --es place moon          # Places: castle, forest, waterfall, circus, yacht, beach, northpole or moon
$A --es segModel landscape|general|multiclass   # segmentation model, applied at once
$A --ez freeze true                            # hold the picture; the segmenter keeps re-reading it
$A --ez maskView true                          # draw the cut-out itself, white on black
$A --es testRect 0,1,0.32,0.80                 # which part of the test portrait fills the frame
$A --ef cutLo 0.30 --ef cutHi 0.60             # where the cut-out's edge falls (negative resets)
$A --ef cutColour 60 --ef cutCentre 2          # colour snap, and the centre sample's weight
$A --ef maskStill 0.75 --ef maskMove 0.75      # how hard the mask is averaged over time
$A --ez segFullFrame true                      # show the segmenter the whole frame, not a crop
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
  `dumpsys media.camera`. Nothing tells the app when the button goes off again, so
  `MainActivity` retries the camera every 3s after any error. A hint over the frozen frame
  asks whether the privacy button is on.
- **Camera rotation.** The camera service already turns camera 0's buffers by the sensor's
  90°: the SurfaceTexture matrix arrives as `[0 -1; 1 0]`, with no mirror. The panel is
  portrait-native and the app runs at `ROTATION_270`. The correct extra rotation is therefore
  `(360 - display) % 360` = 90, not sensor + display. `Compositor` reads the quarter turn and
  any mirror off the matrix itself, so the crop stays right either way. If another Portal
  comes out sideways, `--ei rot N` fixes it and is remembered per device; `--ei rot -1`
  clears it.
- **The camera mirrors without saying so.** Camera 0's buffers come already mirrored, but the
  SurfaceTexture transform carries no flip (`flip=false`). The screen pass mirrors the frame,
  so for a while the screen wasn't a mirror at all: book spines read normally, and people moved
  the opposite way to what they saw. `Compositor` now undoes that hidden mirror too
  (`CAMERA_MIRRORS`), so the frame is the room as it is and the screen behaves like a mirror.
  To check on another Portal, find text in the room on the live preview: it should read
  backwards.
- **Only camera 0.** `cameraIdList` shows apps only camera 0, Meta's 1280x720 "smart camera"
  that crops and pans in hardware. Camera 1 (the 4056x3040 sensor) needs `CAMERA_PRIV`.
- **The GPU segmenter aborts the process.** `ImageSegmenter` with a category mask on the GPU
  delegate kills the app in `convertToTaskResult`: `image_frame.cc: Check failed:
  ImageFormat::UNKNOWN != format_`. It's a native abort, so there is nothing to catch. The
  segmenter runs on the CPU instead, at 26ms.

  Confidence masks abort the same way (`image_frame.cc:298 Invalid format: UNKNOWN`), tried
  2026-09-14, so this is MediaPipe's GPU output conversion on this device, not the mask type.
  A gen 2 Portal (Adreno 615, a different driver) aborts identically, tried 2026-09-16, so it is
  MediaPipe's Java-layer conversion rather than anything about the Adreno 540. The app is already
  on the newest MediaPipe (`tasks-vision:1.0.0`, July 2026), so there is no upgrade to wait for.
  The GPU is still an opt-in with `--es segDelegate gpu` (or `auto`, or `cpu`; it takes effect
  on the next launch). A note is written before each GPU attempt and cleared after 10 good
  results, so a crash puts the next launch back on the CPU. A real GPU path would mean running
  the `.tflite` through TensorFlow Lite's own GPU delegate and reading its output tensor
  directly, bypassing MediaPipe's conversion.
- **Segment edges need help.** The category mask is a hard 256x144 staircase under a 1280x720
  frame. The segmenter now returns confidence masks instead. `Compositor.uploadMask` smooths
  them over time and fixes their polarity. The `MASK` and `FX_POP_ART` shaders snap the soft edge
  to the camera image (a colour-weighted neighbourhood, then a threshold), which removes most of
  the halo of room around a person.
- **The cut-out does not boil; it erodes.** Measured with `--ez freeze` (which holds one frame
  while the segmenter keeps re-reading it) and `--ez maskView` (which draws the cut-out itself):
  on a frame that truly holds still the mask is **identical frame to frame**, 0.000% of pixels
  moving. Everything that looked like boiling was the subject moving — including the debug
  portrait, which drifts and breathes on a sine to imitate one, and which invalidated three
  earlier attempts at measuring this. Temporal smoothing therefore has nothing to fight, and
  `MASK_SMOOTH_STILL` is left equal to `MASK_SMOOTH_MOVE`.

  What the mask does instead is erode. The threshold ran 0.45-0.75, chosen against halo, and a
  thing one or two mask texels wide — a thumb on a raised hand — never reaches 0.45 once its
  neighbourhood is averaged in, so it disappears. Moving the edge to 0.30-0.60 pushes a strong
  boundary (hair against a bright window) out by only three to six pixels, measured, which is far
  less than it gives back. Raising the centre sample's weight was tried as a gentler fix and does
  the opposite — boundary length falls from 1659px to 1539px between weights of 1 and 8, because
  the colour-weighted neighbourhood is where the detail comes from. Colour snap above about 150
  starts punching holes in the person rather than following its edge.
- **A bigger segmentation model isn't the answer.** `selfie_segmenter` (the square 256x256 of the
  same family, nearly twice the pixels) costs 30-34ms against the landscape model's 21-25, taking
  a gen 1 Portal from 26fps to 20.5. On a frozen frame it buys about 1% more boundary on a head
  and 4% on a torso, and the two masks are hard to tell apart by eye. `--es segModel general`
  keeps it available; landscape stays the default. `selfie_multiclass_256x256` measures 695ms a
  frame on a gen 2 Portal's CPU, confirming the gen 1 figure below, and is not shipped.
- **The segmenter looks at the person, not the room.** Its 256x144 input is a crop around where
  the person was (`Compositor.nextSegRoi`):
  - The crop takes the person's bounds plus margins.
  - Width and height are set apart, stretching the model's view by at most 1.6x, so someone
    seated from head to frame bottom still gets a narrower crop.
  - It grows at once when the person touches its edge, and shrinks gently.
  - `Tracker.toFrame` lays the crop's confidence back over the whole frame on a 512x288 grid,
    so everything downstream stays in frame space.
  - Per-sample `FloatBuffer` reads there cost more than the model (67ms a result), so it copies
    in bulk and precomputes per-column positions. Segmentation then takes about 18ms at
    ~27Hz on Beach, where it was about 27ms at ~21Hz.
- **Cut-outs are frame-synced.** With a segment filter, each camera frame waits for its own
  mask before it's composited (`Compositor.submitHeld` and `onTrack`). Frames that arrive while
  the segmenter is busy are dropped, so the output runs at the segmentation rate (about 25fps on
  the test portrait) and the cut-out never trails a moving arm. After 250ms without a mask, the
  frame goes out anyway. The original Photo Booth recorded at about 21fps, which is probably
  the same trade.
- **The multiclass segmenter is too heavy.** `selfie_multiclass_256x256` has hair as its own
  class. It took about 830ms a frame on the gen 1 Portal's CPU (the landscape model takes 18ms),
  and MediaPipe's GPU path aborts. So it isn't shipped. `--es segModel multiclass` still works
  if its `.tflite` is dropped into `app/src/main/assets`.
- **Encoders get the decoder role.** `MediaCodec.configure(format, null, null, 0)` on any
  H.264 encoder, hardware or software, fails with -1010 after ACodec logs `Failed to set
  standard component role 'video_decoder.avc'`. Passing `CONFIGURE_FLAG_ENCODE` and an
  `encoder=1` format key fixes it (`Recorder.asEncoder`).
- **The preview stuck after Keep.** On Android 9 a `SurfaceView` (so a `VideoView`) ignores an
  ancestor going `GONE`. The review's video surface stayed composited over the camera after
  the panel closed. Hide the `VideoView` itself (`ReviewPanel.close()`,
  `AlbumPanel.closeViewer()`). To check, `dumpsys SurfaceFlinger --list` should show one
  `SurfaceView` for the app, not two.
- **Clips came out quiet.** The raw mic (`CAMCORDER` source) measured -23.9 LUFS on a real
  clip. The web app's clips were -12 to -16, because Chrome's `getUserMedia` has gain control
  on by default. `Loudness.kt` is a block AGC with a silence gate and a soft limiter. The
  Portal reports `aec=false agc=true ns=false` for the platform audio effects. With no echo
  canceller, a clip has the app's own sounds twice: the clean copy `Mixer` adds, and a faint
  one the mic hears from the speaker. It was prototyped on that clip first: target
  -16dB and a 0.8 knee gave -15.6 LUFS with peaks at 0dB.
- **The privacy button mutes the mic silently.** `AudioRecord` still starts and every read
  returns a full block, but the samples peak at 5-6 out of 32767. `MicHub` checks the first 3s
  and logs `mic reads but is silent`. The music-reactive effects (Disco, Pop Art) then fall back
  to their idle timing.
- **One AudioRecord at a time.** Android 9 hands the mic to a single client, and both the
  beat-reactive filters and the recorder want it. `MicHub` owns the one `AudioRecord` and feeds
  every block to both.
- **Bulk transfers stall over USB.** Through usbipd into WSL, small adb commands work but
  large file transfers hang. Use adb over Wi-Fi: run `adb tcpip 5555` once over USB, then
  `adb connect`. It lasts until the Portal reboots.
- **`adb install` can hang over Wi-Fi.** A streamed `adb install -r` once sat for 30+ minutes
  and never finished. `adb push app-debug.apk /data/local/tmp/psnap.apk`, then
  `adb shell pm install -r /data/local/tmp/psnap.apk`, did the same install in about 3s.

## Morning checklist

1. **Wake the Portal.** Its IP is 192.168.1.77. If `adb connect 192.168.1.77:5555` is refused,
   it rebooted: redo `adb tcpip 5555` over USB.
2. **Sit in front of the camera** and launch the app normally, from its launcher tile or with
   `am start` with no extras. Check that:
   - the preview is upright and mirrored like a mirror
   - stickers land on the face (try Cool, Puppy with the mouth open, Fancy, Big Head)
   - two people get two stickers
   - Places cuts a real person out cleanly
3. **Run `--ez bench true`** with a face in view. Rows J and K then measure the real camera
   with a real face.
4. **Pair with the real server:** tap 🖼️, type its URL, and approve from a phone.
5. **Record a clip with a silly voice** (Kitty or Googly) and play it back from the album.
6. **Try the second Portal** (the one the web app was measured on): same APK, 32-bit userland.
