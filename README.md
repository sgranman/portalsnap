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
  is the fallback. The Portal reports `mp4`, so clips are H.264 with a 48kHz AAC track.
- **Clips composite at 960x540, not 720p** — see the measurement below. Stills are
  unaffected and stay at the full camera resolution.
- **Stills are JPEG now, not PNG** — a 720p frame drops from ~2MB to ~250KB, which matters
  for the upload and for a gallery that loads many at once. `toBlob` also avoids
  materialising a multi-megabyte base64 string on a 2GB device.

### Clips record at full camera resolution

Recording was dropped to 960x540 to chase a detection-rate problem, and then put back,
because the measurement said it was never the variable — see below. `REC_WIDTH` in
`app.html` still scales the composite and rounds to even dimensions (H.264's 4:2:0 chroma
can't encode odd ones); it costs nothing to keep and makes the ceiling adjustable if the
camera ever delivers more than it does today.

## Chasing the detection rate on-device

Three readings, all blazeface at 320x180, and a warning about comparing them:

| | `infer` | `detect` |
|---|---|---|
| `bench.html` sweep (main thread, no app around it) | 23.3 ms | — |
| app idle, main thread | 30–40 ms | 16.5 fps |
| app idle, worker | 40–60 ms | 14.5 fps |
| app recording at 720p, worker | 96 ms | 8.5 fps |

**`bench.html` has no Worker in it.** Every number in the sweep table was measured with the
detector on the *main thread*, and with none of the app around it — no live 720p preview
being composited, no overlay redraw. The app runs the detector in a classic Web Worker.
Two readings from different execution contexts are not a baseline and a regression; they
are two different measurements. Chasing that gap cost a wrong diagnosis and a deploy.

What the on-device swap actually showed:

- **The worker costs ~10ms of inference, not 4x.** MediaPipe's `delegate: "GPU"` in a
  worker needs OffscreenCanvas and *can* fall back to CPU on an old driver — it isn't
  doing that here. The worker stays: 10ms is cheap next to keeping the main thread free,
  which recording needs.
- **Detection ran at roughly half of what inference alone allows** — 14.5fps against a
  40ms inference that permits ~25. The pump polled on a fixed `setTimeout(pump, 16)` and
  no-opped while a frame was in flight, so up to 16ms died between a result arriving and
  the next frame being grabbed, with `createImageBitmap` then serialized in front of
  inference. `onResult` now re-arms the pump immediately and the timer is only a watchdog.
  Off-device this roughly doubled the rate, with `cycle` falling to 8ms against 7ms of
  inference.
- **Recording still costs real time** (96ms vs ~50ms idle), which is what 540p compositing
  is there to reduce.

The HUD splits the cycle so the next reading is unambiguous: `grab` is the
`createImageBitmap` cost, `infer` is inference alone, and `cycle` is the whole
result-to-result interval. Whatever `cycle` has over `grab + infer` is loop overhead.

One known inefficiency, deliberately left: nothing gates detection on the camera actually
producing a new frame. On the Portal inference is slower than the 33ms frame interval so
it never happens, but on faster hardware the same frame gets detected more than once.
Gating on `requestVideoFrameCallback` would fix it and risks stalling detection if the
callback ever stops firing, which is a bad trade for a device that cannot benefit.

**To take a reading:** tap the bottom-right corner of the picture for the HUD, then the
**bottom-left** corner to swap worker/main-thread. The swap target is inert unless the HUD
is showing. Read idle *and* recording — the first on-device reading was recording-only,
which is what made it ambiguous.

### What recording actually costs

Splitting the recording loop settled it:

| | idle | recording |
|---|---|---|
| `grab` | 10–15 ms | 10 ms — *unchanged* |
| `infer` | 35–45 ms | 100 ms+ |
| `comp` | — | a couple of ms |
| `frame` | — | ~50 ms |

`frame` at ~50ms says the camera only hands over ~20fps while recording. This was first
guessed to be the *room* — cameras lengthen exposure in low light by dropping frame rate —
and that was wrong: `recdiag.html` measures a steady 33ms on the same device in the same
room, in every phase including recording. The camera delivers 30fps. The app was starving
its own video pipeline.

**`comp` is a weaker signal than it looks.** `drawImage` returns once the commands are
queued; the upload, conversion and readback happen afterwards. So `comp ≈ 2ms` shows only
that *issuing* the composite is cheap, not that performing it is. An early conclusion here
that "compositing is innocent" was over-read from the instrument.

Ruled out by measurement, in order:

1. **Composite resolution** — 720p → 540p, twice, moved neither the composite rate nor
   inference. Reverted.
2. **Worker vs main-thread inference** — the worker costs ~10ms, not 4x. It stays, since
   that is cheap next to keeping the main thread free.
3. **Pump loop overhead** — real, and fixed: ~16ms per cycle of dead polling. Worth ~2.5fps
   of detection, but present idle *and* recording, so not the recording penalty.
4. **Mic DSP** — `echoCancellation` / `noiseSuppression` off changed nothing. Restored.

What remains is resolution-independent, recording-only and CPU-bound. Rather than narrow it
one theory per round — four went by that way, each costing a trip to the device — there is
now a page that runs the controlled experiment itself.

### `recdiag.html` — where recording's cost actually goes

Open it on the Portal, tap **Start**, walk away for about a minute. It runs the real
tracker against five configurations that toggle the composite and the encoder
independently, then POSTs the numbers to `/report` — same trick the capability probe uses,
because the Portal has no devtools.

| | composite draw | canvas capture | encoder | isolates |
|---|---|---|---|---|
| A | — | — | — | idle baseline |
| B | yes | — | — | the `drawImage` of a 720p frame |
| C | yes | yes | — | the canvas-to-encoder plumbing |
| D | — | — | yes (raw camera track) | the encoder alone |
| E | yes | yes | yes | the real case |

Each phase reports median and p95 inference, detection rate, and the grab/composite/frame
intervals, after a 2s warm-up that is discarded — the first detections after a switch still
carry the previous config's scheduling. Recorded chunks are dropped on the floor; this
measures cost, it doesn't keep video. The page prints its own verdict, and the raw JSON
stays on screen if the POST fails.

### What it found

Measured on the Portal, blazeface at 320x180, worker backend:

| | config | `infer` p50 | p95 | `detect` | `frame` |
|---|---|---|---|---|---|
| A | idle, tracker only | **22 ms** | 30 | **30 fps** | — |
| B | composite draw | 23 ms | 31 | 29 fps | 33 ms |
| C | + canvas capture | 36 ms | 56 | 20.8 fps | 32 ms |
| D | encoder alone, raw camera track | 26 ms | 41 | 23.5 fps | — |
| E | everything | 47 ms | 72 | 15.8 fps | 33 ms |

**Recording's cost is the canvas capture, not the drawing.** Against the idle baseline: the
`drawImage` pair costs **+1ms**, the encoder **+4ms**, and `captureStream` plus
`requestFrame` **+14ms**. A WebGL compositor was about to be built on the theory that the
draw was expensive; it would have optimised the one stage that was already free.

**And the preview costs more than recording does.** Row A is 22ms and 30fps, against 35-45ms
and 17fps for the same tracker inside the app. The only material difference is that this
page keeps its `<video>` at `display:none` — so the preview itself became the next thing to
decompose.

### The preview, one layer at a time

Second run, same device, phases that add one thing each:

| | config | `infer` p50 | p95 | `detect` |
|---|---|---|---|---|
| A | tracker only, nothing shown | 21 ms | 29 | 30.3 fps |
| B | + video shown, unmirrored | 26 ms (**+5**) | 36 | 23.8 fps |
| C | + mirrored, as the app does it | 26 ms (**+0**) | 37 | 23.8 fps |
| D | + overlay layer present, not redrawn | 25 ms (**−1**) | 38 | 24.0 fps |
| E | + overlay redrawn every rAF | 35 ms (**+10**) | 48 | 18.5 fps |
| F | as E, redraw capped to 30fps | 32 ms | 43 | 20.5 fps |
| G | as E, overlay canvas at half res | 35 ms | 46 | 18.7 fps |
| H | as E + recording — the real case | 76 ms | 112 | 10.5 fps |

- **Redrawing the overlay is the single largest cost in the app: +10ms**, and 24fps of
  detection down to 18.5.
- **The mirror transform is free** (+0ms), and so is having an extra full-size composited
  layer present (−1ms, i.e. noise). Both were suspects; neither is guilty.
- **Displaying the video costs 5ms** and is not negotiable — it is the product.
- **Half resolution saved nothing** (G ≈ E). That is the informative one: the cost is not
  fill rate or texture upload, it is the fixed per-frame price of *dirtying* a composited
  layer. So the fix is to draw less **often**, not smaller.
- H at 76ms / 10.5fps against the app's HUD showing ~100ms / 10fps says the harness
  reproduces the app closely enough to trust the decomposition.

### What was done about it

Two changes in `render()`, one measured and one free:

1. **The redraw is capped at 30Hz** (`RENDER_MIN_DT`). Measured: 3ms of inference and
   +2fps of detection. It is also the honest ceiling — the camera delivers 30fps and
   detections arrive slower still, so drawing at the display rate redrew the same
   information twice.
2. **An idle overlay is left untouched.** The old loop cleared the canvas every frame even
   with no filter picked or no face in view, and clearing costs the same as drawing. It now
   clears once and then stops writing, so the layer stays clean and costs the compositor
   nothing. Verified off-device: canvas writes while idle went from ~60/sec to **0**.

The HUD gained a `drawing` line so this is visible, and `render` now prints `(cap 30)` so a
low number reads as intent rather than a symptom.

### Leading the target instead of chasing it

A detection describes where the face was when the frame was grabbed and lands
`grab + infer` later — ~50ms idle, ~110ms recording. Easing toward a target that stale can
only ever trail; it cannot catch up. `Anchors.project` leads each anchor along its
smoothed velocity by that measured latency.

Ungated, simulated at the on-device rates, that was **27% better on a smooth head turn,
16% worse on a fast snap, and 17% noisier when still** — velocity from 10fps detections
cannot follow a sharp reversal. So the lead is scaled by how well each new velocity agrees
in direction and magnitude with the smoothed one. Confidence collapses on a reversal and
prediction backs off to plain easing exactly where it was hurting: **-14% / -5% / -1%**.

Modest, and deliberately so — the dominant term is inference, which no filter can remove.
`project` is pure and lives in `anchors.js` precisely so the clamp and the lead can be
unit-tested without a device or a face:

```bash
node test-project.js     # no dependencies, no build step — same as the rest of this repo
```

That matters here because the fake camera used for browser testing has no face in it, so
nothing else in the automated path ever exercises the prediction maths.

## Where this stands, and what to do next

Everything asked for works and is deployed: photos and clips save to the server, clips are
1280x720 H.264 with AAC audio, and `gallery.html` gets them into a phone's camera roll.
What follows is tuning of the sticker lag, not the feature.

**Performance budget as measured**, blazeface at 320x180 in the worker:

| | `infer` | `detect` |
|---|---|---|
| floor — tracker alone, nothing displayed | 21 ms | 30 fps (camera cap) |
| with the preview, as it now ships | ~32 ms | ~20 fps |
| while recording | ~76 ms | ~10 fps |

The preview's 5ms for displaying the video cannot be recovered. The remaining known costs
are the overlay redraw (now capped, still ~7ms) and, while recording, `captureStream` at
+14ms.

**Take a reading first.** The 30Hz cap and the idle-skip are deployed but have never been
measured on the device. Bring up the HUD (bottom-right corner of the picture) and read
`infer` / `detect` / `drawing` idle and recording. Expect roughly 32ms / 20fps idle. If it
is much worse, that is new information and nothing below is worth doing yet.

**Untested ideas, best first:**

1. **Composite into an undisplayed canvas while recording, and show that instead.** D and E
   together say a composited layer is free until you write to it. During a recording the
   app writes to `fx` *and* to the recording canvas, and `fx` is a displayed layer. If
   `fx` were undisplayed and the recording canvas shown in its place, the +10ms redraw cost
   might vanish. Unverified, and it trades a video layer for a canvas layer, which B says
   costs 5ms — so it could be a wash. Add phases to `recdiag.html` before writing it.
2. **Cap the recording composite rate** below the camera's 30fps. `captureStream` costs
   +14ms per captured frame; capturing at 20fps would give roughly a third of that back, at
   the price of a 20fps clip. `frame` in row H was already 41ms (~24fps), so some of this is
   happening involuntarily.
3. **Skip the redraw when the drawing would not change.** Filters animate on `t`, so this
   needs per-filter cooperation — a filter declaring itself static when the face is still.
   Only worth it if 1 and 2 fail.

**Do not** re-try these; each was measured and is dead: composite resolution (twice), a
WebGL compositor for the composite *draw* (+1ms — there is nothing there), worker vs
main-thread inference (~10ms, worker stays), mic DSP, and low light as the explanation for
the camera's frame rate.

**Method note, learned expensively.** Four theories were deployed and disproved one per
round, each costing a trip to the device, because they were argued rather than measured. The
things that actually worked were reading the numbers already on screen (the pump loop's
16ms of dead polling) and building `recdiag.html` to run controlled A/B phases on-device and
POST them back. Measure first; the `/report` endpoint has existed since the capability probe
for exactly this.

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
