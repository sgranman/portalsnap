# PortalSnap

A browser-based face-filter app for kids, targeting the Facebook Portal's built-in browser
(replacing the filter app Meta removed from the device).

**Status: Phase 0 — capability probe.** Nothing about the Portal's browser can be assumed,
so the first job is to find out what it actually supports before choosing a face-tracking stack.

## Confirmed device profile (measured on-device)

Probe run on the Portal, 2026-08-09, over an HTTPS quick tunnel:

| | |
|---|---|
| UA | `Mozilla/5.0 (Linux; Android 10; K) … Chrome/138.0.0.0` — reduced UA; Android version and model are masked, so "Android 10" is not the real OS version |
| Platform | `Linux armv81` (armv8l) — **32-bit ARM userland** |
| RAM | `deviceMemory: 2` GB, 8 cores |
| Screen | 1280x800 @1x; usable viewport **1280x644** (browser chrome takes 156px) |
| Camera | single front camera, `camera2 0`, **1280x720 @30fps**, also its max |
| Mic | granted, 48kHz, 3 inputs |
| Features | `getUserMedia`, `MediaRecorder`, **wasmSIMD**, `SharedArrayBuffer`, `crossOriginIsolated`, `OffscreenCanvas`, `createImageBitmap`, `requestVideoFrameCallback`, WebAudio — all yes |
| Throughput | canvas2D draw **53fps**, WebGL texture upload **60fps** |

Conclusions: camera and mic are both granted, the engine is modern, and WASM SIMD means
the **MediaPipe tier is available** — no fallback trackers needed. The binding constraints
are **2GB RAM on a 32-bit userland** (memory, not CPU) and the **30fps camera ceiling**.

## Tracker sweep results (measured on-device, GPU delegate)

| Config | 1280x720 | 640x360 | 320x180 |
|---|---|---|---|
| landmarker + blendshapes | 61.8ms | 56.9ms | 61.8ms |
| landmarker only | 57.7ms | 58.1ms | 56.6ms |
| blazeface detector | 29.9ms | 26.0ms | **23.3ms** |

100% face-detection rate in every configuration.

Three findings drive the design:

1. **Landmarker cost is flat across input resolution** (~57ms regardless). It resizes to a
   fixed internal tensor, so downscaling the input buys nothing. ~17fps is a hard ceiling
   for the mesh — no tuning will fix it.
2. **Blendshapes cost ~4ms**, about 7%. Cheap enough to leave on whenever the mesh runs,
   so expression triggers are effectively free.
3. **Blazeface scales with input and hits 43fps at 320x180** — above the camera's 30fps.
   Its 6 keypoints (eyes, nose, mouth, ears) cover hats, glasses, ears, noses and crowns.

So: **two tiers.** Blazeface at 30fps for sticker filters, the mesh at ~17fps only for
filters that need expressions or warps. A lower-resolution *capture* (640x480) saved just
7%, so the camera stays at 720p for photo quality.

## Architecture as built

- **`tracker.worker.js`** — inference in a Web Worker. Frames go over as `ImageBitmap`
  downscaled to 320x180 via `createImageBitmap`, transferred zero-copy, and closed
  immediately (2GB of RAM leaves no room for a queue). Exactly one frame is ever in
  flight: queueing adds latency without adding detections.
- **Both trackers emit the same six anchors**, so filters never learn which tier is running.
- **Track slow, render fast.** Detections arrive at 17–30fps; the overlay redraws at the
  display rate with `shown` easing toward `target`. Alpha rises with distance — snappy on
  fast head movement, still on slow — the cheap half of a one-euro filter. The overlay
  hides if no face is seen for 800ms.
- **The video is never drawn to canvas during preview.** It stays a `<video>` element the
  compositor handles, so we pay zero per-frame upload; only the transparent overlay is
  drawn. Compositing happens once, at capture — or once per camera frame while recording.
- **`filters.js`** — filters are pure `(ctx, face, t)` functions drawn in a face space
  where the origin is between the eyes, +x runs along the eye line, and one unit is the
  inter-eye distance. Scale and rotation come free. All vector art, no image assets.

## Saving a capture: the Portal can't download

Tapping Save on a data URL gets you **"File Downloads are Unavailable on the Portal"**.
The browser shell has downloads disabled outright, and there is no web API that reaches
the device's own photo album — the gallery lives behind Android's MediaStore, which the
web sandbox cannot touch. Nothing in a page can fix this. The only in-browser route that
could ever reach the album is `navigator.share({files})`, which needs the shell to
implement share targets; the app offers a **Send** button if it detects support and hides
it otherwise, so this costs nothing when it isn't there.

So the album moved to the server. **Keep it** POSTs the capture to `/media`, and the
snaps live on the home server rather than the device:

| Route | |
|---|---|
| `POST /media?ext=jpg\|png\|webm\|mp4` | Streams the raw body to `media/`, capped at 64MB. Writes to a `.part` file and renames, so a listing never shows a half-uploaded file. The server mints the filename — `pic-<UTC stamp>-<salt>.jpg` — and never trusts a client-supplied one. |
| `GET /media/list` | Newest-first JSON: name, url, kind, size, timestamp. |
| `GET /media/<name>` | Serves the file, streamed, with `Range` support so clips are scrubbable. Only server-minted names resolve. |
| `DELETE /media/<name>` | Removes it. |

Two front ends read that API:

- **In-app album** (the 🖼️ button) — for looking at snaps on the Portal itself. Tracking
  is suspended whenever it or the review screen is up, since the stage is covered anyway
  and inference would just compete with the video decoder.
- **`gallery.html`** — open it on a phone or laptop, where saving *does* work. **Save**
  goes through the share sheet when the browser has one (on iOS that's "Save Image" /
  "Save Video", straight into the camera roll) and falls back to a plain download.

`media/` is gitignored and sits inside the bind mount, so it survives redeploys.

## Video with sound

The 🎥 button records the composited view with mic audio, capped at 30 seconds.

- **The mic is acquired at startup**, alongside the camera, so there is one permission
  prompt rather than a second one mid-session. A single `getUserMedia` rejection covers
  both tracks, so a refused mic would otherwise cost us the camera — the call retries
  video-only if the first attempt fails. The track is never routed to an output, so there
  is no feedback loop.
- **Recording is the one time we do composite per frame.** Video and overlay are drawn
  into an offscreen canvas that is mirrored once at setup, and `captureStream(0)` plus
  `requestFrame()` means we encode exactly one frame per *camera* frame instead of
  resampling against a fixed clock — no duplicated work when the camera dips below 30fps.
  The loop is driven by `requestVideoFrameCallback`.
- **Format is picked at runtime, mp4 first.** H.264 is the codec most likely to have a
  hardware encoder on this SoC, and an mp4 opens anywhere the family might view it; webm
  is the fallback. Verified end to end against a fake device: H.264 720p with a 48kHz AAC
  track, correctly mirrored, overlay included.
- **Stills are JPEG now, not PNG** — a 720p frame drops from ~2MB to ~250KB, which matters
  for the upload and for a gallery that loads many at once. `toBlob` also avoids
  materialising a multi-megabyte base64 string on a 2GB device.

The HUD (tap the bottom-right corner) reports the chosen recorder format, whether a mic
track exists, and the composite rate while recording.

## Why the probe came first

Three unknowns decide the entire architecture, and only the device can answer them:

1. **Does the Portal browser grant `getUserMedia` at all?** Meta may have withheld the
   camera permission from the built-in browser. If it did, no amount of web code fixes it
   and we fall back to sideloading (see below).
2. **How old is its Chromium?** Portal OS is Android-based, and the browser has not been
   updated since the product was discontinued. Modern face trackers need WASM SIMD
   (Chrome 91+). An older engine forces an older, slower tracker.
3. **Can it sustain ~24fps?** Face tracking plus compositing on a low-power Portal SoC is
   the real risk. The probe measures canvas and WebGL throughput on live camera frames.

## Running the probe

Camera and mic access require a **secure context**. `http://192.168.x.x:8080` will be
rejected by the browser before any permission prompt appears — HTTPS is mandatory.

### Fastest path: quick tunnel from this machine

```bash
brew install cloudflared          # one time
node server.js                    # terminal 1 — serves ./public on :8080
cloudflared tunnel --url http://localhost:8080   # terminal 2 — prints an https://….trycloudflare.com URL
```

Open that HTTPS URL in the Portal's browser and tap **Camera + Mic**.

### Deployed: the home server

Live at `~/docker/portalsnap/` on **homeserver**, behind the existing Cloudflare Tunnel.

The repo is private, and the server's existing GitHub key is a deploy key scoped to a
different repo, so portalsnap got its own read-only deploy key following the established
per-repo pattern:

```bash
# on the server
ssh-keygen -t ed25519 -f ~/.ssh/portalsnap_deploy -N "" -C "portalsnap-deploy@homeserver"
cat >> ~/.ssh/config <<'EOF'

Host github.com-portalsnap
  HostName github.com
  User git
  IdentityFile ~/.ssh/portalsnap_deploy
  IdentitiesOnly yes
EOF

# from a machine with gh authenticated
gh repo deploy-key add portalsnap_deploy.pub --title "homeserver home server (read-only)"

# back on the server
git clone git@github.com-portalsnap:sgranman/portalsnap.git ~/docker/portalsnap
cd ~/docker/portalsnap && docker compose up -d
```

Redeploying after a push:

```bash
ssh homeserver 'cd ~/docker/portalsnap && git pull && docker compose restart'
```

`public/` is read per-request through the bind mount, so **page and filter edits go live on
`git pull` alone** — the restart is only needed when `server.js` changes.

Routing is a Public Hostname entry in the Cloudflare Zero Trust dashboard
(`portalsnap.<domain>` → `http://portalsnap:8080`); no ports are published on the host.

### Alternative: quick tunnel

Deploy behind the existing Cloudflare Tunnel at a stable hostname (e.g.
`portalsnap.example.net`), which is far easier to retype on a touchscreen than a random
tunnel URL. See `docker-compose.yml`; add the Public Hostname route in the Cloudflare
Zero Trust dashboard pointing at `http://portalsnap:8080`.

### Reading the results

The Portal has no devtools, so the probe reports on-screen and the **Send report** button
POSTs the full JSON back to `server.js`, which saves it to `reports/` and prints a summary.
If the POST fails it falls back to the clipboard.

## What the probe answers

| Section | What it tells us |
|---|---|
| Environment | UA, Chromium major version, screen size, cores, secure-context status |
| Features | `getUserMedia`, WASM + **SIMD**, SharedArrayBuffer, WebGL1/2, GPU renderer, `MediaRecorder` formats, WebAudio/AudioWorklet |
| Media | Granted resolution and frame rate, camera/mic labels, device counts, live mic level meter — and on failure, the exact `DOMException` name |
| Performance | Sustained fps for canvas-2D draw and WebGL texture upload of live frames |

The failure mode matters as much as success. `NotAllowedError` means a permission or policy
block; `NotFoundError` means no camera is exposed to the browser layer; `NotReadableError`
means the Portal's own software is holding the camera.

## Planned architecture (chosen after the probe reports back)

**Face tracking** — decided by the WASM SIMD result:

- *SIMD present:* MediaPipe Tasks Vision `FaceLandmarker` — 478 landmarks plus blendshapes
  (which give real expression triggers: open mouth, raised eyebrows, blink).
- *No SIMD:* TensorFlow.js `face-landmarks-detection` on the WebGL backend, at reduced
  input resolution.
- *Neither runs fast enough:* `face-api.js` tiny detector for a box + rough eye line only,
  which still supports hat/glasses/nose stickers.

**Rendering** — two tiers, built in this order:

1. Canvas 2D sticker compositing: anchor PNGs to landmark indices, deriving rotation and
   scale from the eye-to-eye vector. Covers most of what kids want (ears, hats, glasses,
   noses) and is the cheapest thing that works.
2. WebGL mesh warp: the landmark triangulation drives vertex displacement for big-eyes,
   bulge, and stretch effects. Only if the perf numbers allow it.

**Audio** — `MediaRecorder` clips with mic sound are **built** (see above). Still planned:
WebAudio pitch shifting for a voice changer, inserted between the mic track and the
recorder so the saved clip carries the altered voice rather than only the monitor.

**Assets are all self-hosted.** No CDNs: the model files, WASM binaries, and stickers ship
with the app. A discontinued device on an aging browser should not depend on third-party
hosts staying reachable, and self-hosting keeps it working if Portal cloud services die.

**UI** is full-screen and touch-first — large filter thumbnails and three 96px round
buttons (album, record, shutter), no typing anywhere, since the users are small children
on a touchscreen at arm's length. Destructive actions arm on the first tap and fire on the
second, rather than opening a `confirm()` dialog that is easy to mis-tap and hard to read.

## Vendored assets

`public/vendor/` and `public/models/` are committed on purpose — a discontinued device on
an aging browser should not depend on third-party hosts staying reachable. To refresh them:

```bash
npm pack @mediapipe/tasks-vision
tar xzf mediapipe-tasks-vision-*.tgz -C /tmp
cp /tmp/package/vision_bundle.mjs /tmp/package/vision_bundle.js public/vendor/mediapipe/
cp -r /tmp/package/wasm public/vendor/mediapipe/

curl -L -o public/models/face_landmarker.task \
  https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task
curl -L -o public/models/blaze_face_short_range.tflite \
  https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/1/blaze_face_short_range.tflite
```

Both bundle formats are needed: the worker is a **classic** worker using `importScripts`
(`vision_bundle.js`), because MediaPipe reaches for `importScripts` or `document` and a
module worker has neither. The `.mjs` build serves the main-thread fallback path.

`vision_wasm_nosimd_internal.wasm` (10MB) is unused on the Portal, which has SIMD. It is
kept only as a fallback for devices that don't, and can be deleted to shrink the repo.

## Fallback if the browser blocks the camera

Portal OS is Android underneath and supports `adb` sideloading in developer mode. If the
built-in browser refuses camera access, the same web app can be wrapped in a minimal
Android WebView APK that holds the `CAMERA` permission itself. Same code, different shell —
which is another reason to keep this a plain static web app with no build step.
