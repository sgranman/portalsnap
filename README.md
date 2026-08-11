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

## Silly voices

Four filters change how you sound in a clip: **Puppy** growls (0.78), **Fancy** goes plummy
(0.8), **Kitty** squeaks (1.42) and **Googly** is a chipmunk (1.75). Cool and Royal are left
alone, so the effect reads as deliberate rather than as something wrong with the microphone.

The ratio lives on the filter in `filters.js`, next to `needsMesh`, because it is part of what
the filter *is*.

- **It is heard on playback, never live.** Routing a processed microphone to speakers two feet
  from that same microphone is a feedback loop, and this device has no headphones. So the
  effect goes into the recording, and the children hear themselves when they watch it back —
  which is the funnier half anyway.
- **`pitch.worklet.js` is a delay-line granular shifter**, about forty lines on the audio
  render thread. Hold the recent input in a ring; read it back with a delay that slides
  linearly. A delay shrinking by 0.4 samples per output sample means the read head advances
  1.4 samples per output sample — the voice played 1.4x faster, a fifth up, without the clip
  getting shorter. The delay must wrap, and a wrap is a discontinuity, so two read heads half
  a grain apart are crossfaded with the one at the wrap point always faded out.
- **It is not a phase vocoder and does not pretend to be.** Two correlated copies of a voice
  summed together comb-filter slightly, which reads as a faint warble. For a puppy and a
  kitten that is a feature, and it costs a handful of multiply-adds per sample instead of an
  FFT — this device spends everything it has on face detection.
- **The mic always routes through the shifter while recording, even at a ratio of 1**, which is
  a bit-exact passthrough. That is what lets a child change filter half way through a clip and
  have the voice follow. Outside recording the graph is suspended: a connected worklet runs 375
  blocks a second whether or not anyone is listening.
- **A missing voice never costs a recording.** If `AudioWorklet` is absent, the module fails to
  load, or there is no mic, the bare microphone track is used and the HUD says `voice 1.42
  (off)`. Same rule as the camera: a refused mic is not allowed to cost the video.

Verified two ways, because the browser and the arithmetic answer different questions.
`node test-pitch.js` drives the worklet straight from Node — it needs only two globals to
exist — and measures the output frequency of a synthetic sine at each ratio, plus that a ratio
of 1 is bit-exact. `test/voice.mjs` checks the wiring: that the encoder is handed the
*processed* track and not the bare mic, that the ratio follows a mid-clip filter change, and
that the graph is parked afterwards. End to end, recording Chrome's fake tone as Kitty moved
its spectral peak from 398Hz to 546Hz.

## Full screen

The Portal's browser chrome takes 156 of the panel's 800 pixels — the viewport measures
1280x644 against a 1280x800 screen — so this is worth about a quarter more picture. Measured
off-device at the Portal's two sizes, the picture goes from 930x523 to **1207x679**, 68% more
area.

- **The whole document goes full screen, not the stage.** Full screen with no reachable
  shutter would be a worse app, so the filter strip and the three camera buttons stay put and
  the picture takes the space the browser chrome gave up.
- **The button hides itself if the browser won't do it.** `fullscreenEnabled` is checked
  before it is shown, so there is no dead control to poke at; a *refusal* at click time is
  different from being unsupported, and that path hints instead.
- **The icon is an inline SVG, not a glyph.** There is no webfont here by policy and the
  corner-bracket characters aren't in this device's emoji set, so the one control without an
  obvious emoji draws its own.
- It listens for `fullscreenchange` rather than only tracking its own clicks, so leaving by
  Escape or by the shell's own gesture still flips the icon back. Prefixed spellings are kept
  throughout: this is a vendor shell, and the cost of guessing wrong is a button that
  silently does nothing.

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

That was the first version. What it found sent the investigation somewhere else, so the page
was rewritten around the preview instead: it now runs **thirteen** phases in about three
minutes — A–H decomposing the preview layer by layer, and I–M testing the candidate fixes.
The table above is kept because the numbers under it are still the reason recording is not
where the cost is.

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

**The same phases run again the next morning**, which is the only reason the last bullet
above is qualified below rather than deleted:

| | A | B | C | D | E | F | G | H |
|---|---|---|---|---|---|---|---|---|
| first run | 21 | 26 | 26 | 25 | 35 | 32 | 35 | 76 |
| second run | 22 | 25 | 25 | 24 | 35 | 30 | 32 | 76 |

Everything reproduced within a millisecond or two except G: **half resolution recovered 3ms
of the 11**, where the first run put it at zero. So the redraw cost is mostly the fixed
per-frame price, but not entirely — there is a fill-rate component worth about a quarter of
it. Drawing less *often* is still the lever that matters most; drawing *smaller* is a real
but secondary one, and phase I now tests whether the two savings are the same saving twice.

### What was done about it

Three changes in `render()`, all of them the same idea — a frame not drawn is the only frame
that is free:

1. **The redraw is capped at 30Hz** (`RENDER_MIN_DT`). Measured on-device: 5ms of inference
   and +2.7fps of detection. It is also the honest ceiling — the camera delivers 30fps and
   detections arrive slower still, so drawing at the display rate redrew the same
   information twice.
2. **An idle overlay is left untouched.** The old loop cleared the canvas every frame even
   with no filter picked or no face in view, and clearing costs the same as drawing. It now
   clears once and then stops writing, so the layer stays clean and costs the compositor
   nothing. Verified off-device: canvas writes while idle went from ~60/sec to **0**.
3. **A still face is not repainted at all.** Once `shown` has converged, successive frames
   differ by a fraction of a pixel, and the picture that results is identical. `needsPaint()`
   compares the anchors against the last *painted* state — not the previous frame, so a slow
   drift still repaints once it adds up to something visible — and skips the redraw when
   nothing has moved by more than 0.6px across the frame.

   Three of the six filters animate on their own clock (the puppy's ear sway, the crown's
   sparkle, the googly pupils' bob) and cannot be skipped outright. They are read off the
   `draw` signature: a filter that declares the time argument uses it. Keeping that in the
   signature rather than in a separate flag means it cannot drift out of step with the filter
   it describes — worth caring about, because getting it wrong in the other direction freezes
   a filter, and getting it wrong in this direction silently costs the saving. Those three
   repaint at 15Hz instead of 30 while the face is still, since the slowest thing any of them
   animates cycles in about a second.

   Off-device: a static filter on a still face paints **0** times per second, a moving face
   paints 25–30, and an animated filter on a still face paints 13–15.

   **On-device it fired exactly never**, which is the interesting part. `paint` read 20–22/s
   against a `render` of about 21 — every single frame counted as changed. A real face is
   never still: blazeface regresses its keypoints afresh from each frame, so they jitter by a
   few pixels with nobody moving, and a 0.6px threshold cannot survive that. Nothing was
   wrong with the skip; the number it compared against was fiction, because the fake camera
   used to test it reports a *motionless* face and no real tracker ever does.

   So `ease()` gained a deadband of one pixel of the tracker's own input — `1/320`
   normalized, about 4px across the overlay, 1% of a face's width. Below the resolution the
   model is actually fed, a movement cannot be distinguished from noise, which makes that a
   principled bound rather than a tuned one. It is applied to the distance from `shown` to
   the target, not to each detection's step, so genuinely slow motion still tracks — it
   accumulates until it clears the deadband and then moves, staircasing at 4px rather than
   freezing. Sticker jitter on a motionless face goes away as a side effect, which is
   arguably the more visible improvement.

   Off-device with ±3px of injected tracker noise: **1–6 paints/sec**, against ~30 before.
   The HUD gained `jit`, the median largest anchor step between detections, so the noise
   floor is now something the device can be asked about instead of assumed.

   **Confirmed on the device**: sitting still with a filter on, `jit` reads near zero and
   `paint` fell from 20–22/s to **5–10/s**. That is the end of this thread rather than a
   partial result — row J measured a 15Hz redraw at 24ms, the same as never redrawing at all,
   so anything at or below 15 paints/sec is already at the floor and driving it to zero would
   buy nothing. The residual is the 15Hz animation gate on the three filters with clocks of
   their own, plus the tail of the noise distribution on the three without.

The HUD gained a `drawing` line and a `paint /s` line, and `render` prints `(cap 30)` so a
low number reads as intent rather than a symptom. `paint` well under `render` means the skip
is earning its keep; equal to it means every frame genuinely changed something.

### Which fix to take: rows I to M

Four candidates, measured the same way. The winner was not the one that had been ranked
first, and the one ranked first is dead:

| | config | `infer` p50 | `detect` | vs |
|---|---|---|---|---|
| D | overlay present, never redrawn — the floor | 24 ms | 25.2 fps | |
| E | redrawn every rAF | 36 ms | 18.5 fps | |
| F | capped to 30fps — as shipped | 29 ms | 21.7 fps | −7 vs E |
| G | half res, uncapped | 32 ms | 19.3 fps | −4 vs E |
| I | **capped to 30fps *and* half res** | 26 ms | 25.8 fps | **−10 vs E** |
| J | **capped to 15fps, full res** | 24 ms | 26.7 fps | **−12 vs E** |
| H | recording, uncapped composite | 63 ms | 12.0 fps | |
| K | **recording, composite capped to 15fps** | 47 ms | 16.0 fps | **−16 vs H** |
| L | recording, preview *is* the composite | 78 ms | 10.5 fps | +15 vs H — worse |

- **The cap and the resolution are two different savings, and they add up** (I saves 10ms
  where the two measured 11ms apart). So the first run's "half resolution saved nothing" was
  simply wrong, twice over.
- **The redraw cost is not linear in the rate.** 15fps costs *nothing at all* — J and D are
  the same 24ms — while 15→30fps costs 5ms and 30→60fps another 7ms. Whatever the mechanism,
  redraws below about 15Hz are free on this device, which is what makes the still-face skip
  worth having rather than merely tidy.
- **Displaying the composite instead of the video and overlay is dead.** It was the top-ranked
  idea in this file yesterday, on the reasoning that it trades two live layers for one. It
  composited 133 frames against H's 146, so it ran — it is just 15ms *worse*. One live canvas
  the size of the screen costs more than a video element plus an overlay, and the theory that
  a composited layer is cheap "until you write to it" does not survive being the only layer.
- **Capping the recording composite is the largest single win in the whole exercise.** Half
  the captured frames, and inference goes 63 → 47ms with detection 12 → 16fps.

One caveat that matters for reading these: **the recording rows drift between runs** — H
measured 76ms, 76ms and 63ms on three separate runs of the same page, where the preview rows
reproduce within 1-2ms. Encoder and thermal state presumably. So a recording row is only
comparable to *the H in its own run*, which is how the verdict computes it.

### What was taken from that

**The recording composite is capped at 20fps** (`REC_COMPOSITE_HZ`), not the 15 that was
measured. K's trade is worth taking — in a face filter, a sticker that tracks properly is
more of the point than the frame rate is — but 15fps is visibly steppy in a clip of a child
moving, and the camera only delivers about 26fps under recording load anyway (`frame` measured
39ms), so 20 gives up much less than it sounds. Verified off-device: the mp4 comes out at
20.07fps with sound, and the on-device saving should land near half of K's 16ms. Raise the
constant to 30 to remove the cap.

**The redraw stays capped at 30Hz, not 15.** J is 5ms cheaper and the temptation is obvious,
but the arithmetic goes the other way: dropping to 15Hz costs 33ms of extra quantization in
where the sticker is drawn, and buys back only about 9ms of detection latency
(1/21.7 → 1/26.7 of a second). Smoothness is what that 5ms is paying for, and the still-face
skip already collects it whenever nobody is moving.

**Half resolution is left on the table, deliberately.** It is a real 3-4ms and it is additive,
but it is the one lever here with a cost I cannot measure — softer sticker edges on a 1280
display. Worth trying as a judgement call, not as a performance fix: it needs `fx` sized from
a scale factor and the photo and clip composite paths checked at the new size, since both
draw `fx` into a full-resolution canvas.

### The overlay froze: two bugs behind one symptom

The morning after the 30Hz cap shipped, filters stopped drawing on the device entirely. Two
separate faults, and the first was mine from the night before:

- **`wantDraw` required `shown`, but only `ease()` ever assigns `shown`, and `ease()` only
  ran when `wantDraw` was true.** A deadlock: from a cold start nothing could ever begin
  drawing. It is gated on `target` now, and `ease()` seeds `shown` on its first call.
- **A filter that throws part-way through a draw left the canvas context transformed.**
  `inFaceSpace` does `save()`, transform, `fn()`, `restore()` — an exception skips the
  `restore()`, so every later `clearRect(0, 0, w, h)` cleared a rotated, scaled sliver
  instead of the canvas. One bad frame in one filter froze the overlay for the rest of the
  session, and the `catch` around the draw made it look survivable. The clear is now
  `wipe()`, which prefers `ctx.reset()` — that puts the transform and the save stack back as
  well as clearing.

**Why the test suite missed the first one.** Chrome's fake camera shows a rolling test
pattern with no face in it, so no automated test had ever reached the drawing path — the
suite asserted `drawing: no` and passed while the app was broken. The fix is not another
assertion but a different fake: `filters.mjs` replaces the tracker worker with a stub that
speaks the same three messages and returns synthetic anchors the test drives on demand,
still or moving or absent. Everything downstream of a detection is then testable without a
device or a face, and it asserts on pixels: that a picked filter inks the canvas, that all
six filters draw, that a still face stops repainting without the drawing disappearing, that
a lost face clears it, and — for the second bug — that breaking one draw on purpose does not
stop later clears from covering the whole canvas.

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

That matters because the browser tests can put a face in front of the tracker but cannot
assert on where a hat *should* have landed; the clamp and the lead need arithmetic, not
pixels. See `test/README.md` for the rest of the suite and what each part covers.

## Where this stands, and what to do next

Everything asked for works and is deployed: photos and clips save to the server, clips are
1280x720 H.264 with AAC audio, and `gallery.html` gets them into a phone's camera roll.
What follows is tuning of the sticker lag, not the feature.

**Performance budget as measured**, blazeface at 320x180 in the worker:

| | `infer` | `detect` |
|---|---|---|
| floor — tracker alone, nothing displayed | 22 ms | 30 fps (camera cap) |
| overlay present but never redrawn — the practical floor | 24 ms | 25 fps |
| preview with a filter on, face moving | 29 ms | 21.7 fps |
| preview with a filter on, face still (redraws skipped) | ~24 ms | ~25 fps |
| while recording, composite capped at 20fps | ~52 ms | **13.5–14 fps** (measured) |

Displaying the video costs 3-5ms and that is not recoverable — it is the product. Everything
else that was worth taking has been taken.

**The tuning is finished.** Every lever that measured anything has been pulled, and the two
that remain are judgement calls rather than measurements — see the end of this section. The
last three readings on the device confirmed all of it: `detect` at 13.5–14fps while recording
(from 10), `jit` near zero when still, and `paint` at 5–10/s (from 20–22). Since row J priced
a 15Hz redraw at the same 24ms as no redraw at all, anything at or under 15 paints/sec is at
the floor.

Should it need re-opening, the HUD is the cheap instrument and it now reports enough to
diagnose without a harness: `grab` / `infer` / `cycle` split the tracker's loop, `render` and
`paint` separate frames offered from frames drawn, `jit` gives the tracker's noise floor in
overlay pixels, and `rec` / `comp` / `frame` appear while recording. `recdiag.html` row M —
the 20fps composite the app ships, as against K's measured 15 — is the one row never run.

**Do not** re-try these; each was measured and is dead: composite resolution (twice), a
WebGL compositor for the composite *draw* (+1ms — there is nothing there), worker vs
main-thread inference (~10ms, worker stays), mic DSP, low light as the explanation for the
camera's frame rate, and **displaying the composite canvas in place of the video and overlay
while recording** (row L: 15ms *worse* than what it replaced).

What is genuinely left is small and mostly judgement rather than measurement: the half
resolution overlay described above (3-4ms, at a cost in sticker crispness), and the fact that
`fx` is a 1280x720 canvas displayed in a box about 1145x644 on this device — so it is already
rendering more pixels than the Portal ever shows.

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
