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
- **Everyone in frame gets a filter**, up to three on the fast tier and two on the mesh.
  Each is an independent track with its own smoothing, prediction and identity — see
  [Two children at once](#two-children-at-once).
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

A clip is stored with a **preview frame** beside it, under the clip's own name with a `.jpg`
extension — `vid-…-a1b2.mp4` is previewed by `vid-…-a1b2.jpg`. Deriving the name means there is
no second namespace to keep in step, and the `vid-` prefix already tells a preview apart from a
photo, which is always `pic-`. `POST /media?ext=jpg&for=<clip>` stores one, `GET /media/list`
attaches it to its clip as `poster` rather than listing it as an entry of its own, and deleting
a clip deletes it too.

The frame is grabbed from the composited canvas at the moment recording stops, which is the one
moment it exists for free — recovering it later means downloading the clip and decoding a frame,
which is exactly what a gallery full of video tiles should not have to do. Uploading it is
deliberately not part of the save: a clip that is safely stored is never reported as failed
because its thumbnail wasn't.

Two front ends read that API:

- **In-app album** (the 🖼️ button) — for looking at snaps on the Portal itself. Tracking
  is suspended whenever it or the review screen is up, since the stage is covered anyway
  and inference would just compete with the video decoder.
- **`gallery.html`** — open it on a phone or laptop, where saving *does* work. **Save**
  goes through the share sheet when the browser has one (on iOS that's "Save Image" /
  "Save Video", straight into the camera roll) and falls back to a plain download. It also
  **backfills previews** for clips recorded before the app started saving them: it decodes one
  frame, one clip at a time, and posts it back — so the capable device does the work and the
  Portal's own album gets the thumbnails without ever decoding video itself.

### Two ways an album can look like a stack

Both were reported as "the images are stacked on top of each other", and they were unrelated.

**On a phone, the grid collapsed to one column.** `minmax(210px, 1fr)` cannot fit two columns
across 390px, so every tile went full width. It is `minmax(min(46%, 210px), 1fr)` now: two
columns on a phone, the same five on a desktop.

**In the app, the tiles overlapped each other** — cascading down each column like a fanned deck
— and only once the album was long enough to scroll. A tile's height comes from its
`aspect-ratio`, which depends on the column width, which depends on whether a scrollbar is
present; when the container genuinely overflows, Chrome breaks that circularity by leaving the
ratio out of the row's intrinsic size. Rows then collapsed to the caption's line box: **14px
under a 135px tile**. `grid-auto-rows: max-content` tells the rows to measure their contents and
restores the 134.5px they should always have had.

Eight snaps looked perfect and ninety-six were unusable, which is why it survived a screenshot
review — so `test/album.mjs` seeds forty, checks the container really overflows before
asserting anything, and then requires that no tile overlaps the one below it. Without the fix it
reports a 70px overlap on a 135px tile.

`media/` is gitignored and sits inside the bind mount, so it survives redeploys.

**None of this is public any more.** It was, for the first week: the tunnel served the whole
app to the open internet, and anyone with the hostname could open `/gallery.html` and download
every photo and clip. Now every route except the pairing page needs a paired device, and the
photos are the reason — see [Who can open any of this](#who-can-open-any-of-this). A single
capture can still be handed to someone without a device, but only through a link that has to be
minted for it.

### The Portal cannot share, and says it can

`sharetest.html` settled this on the device, since none of it is documented. Every route out of
the page was tried:

| route | result |
|---|---|
| `share({text})` / `{url}` / `{files: jpg}` / `{files: mp4}` | `AbortError: Share failed` after **16–129ms** |
| `fb-messenger://share?link=…` | nothing: no error, no app switch |
| `intent://…package=com.facebook.orca` | nothing |
| `https://www.messenger.com/` | opened, as a logged-out website |

`canShare` returned **true** for all four payload types before each of those failures. So the
Web Share API here is a stub: present, advertised, and wired to nothing. The timings are what
prove it — 16ms is not somebody dismissing a dialog, and no dialog was drawn. Neither Messenger
URL scheme is registered with the browser either, so there is no route from this page to that
app, with or without a file.

That makes the honest behaviour: try, and then stop pretending.

- **Files, then a link, then the clipboard.** Files are the only route that could ever reach the
  device's own album; a shell that will not carry a file might still hand over a URL; and a
  copied link can be pasted somewhere. The link routes need the capture saved first, because
  until then there is nothing to link to.
- **A refusal is told from a cancellation by the clock.** Both arrive as `AbortError`, so the
  only thing separating "the shell threw this away" from "the child changed their mind" is that
  the first came back in 16–129ms. Under 400ms is a refusal.
- **Once everything has refused, the button retires itself** (remembered in `localStorage`) and
  says where the photo actually is: `gallery.html`, on a phone. A button that cannot work should
  not keep offering.

`test/share.mjs` stubs both halves of the API to reproduce all four cases — instant refusal,
slow cancellation, working clipboard, working share — because the difference between them is
timing, and timing is exactly what a real browser will not reproduce on demand.

### The preview is mirrored; captures are not

A selfie view is how you expect to see yourself, so the stage stays flipped. A saved photo or
clip should look the way the room actually looked — which is what every phone does — so the
composite is not flipped. Both were mirrored until someone watched a clip back.

Nothing else has to flip with it: the overlay is drawn in the camera's own coordinates, and
video and overlay go into the same untransformed context, so they cannot come apart.

Checking it needed a camera with a left and a right. Chrome's fake device is near enough
symmetrical to hide a flip entirely, so `test/mirror.mjs` writes its own y4m — a text header and
three raw I420 planes, twenty lines and no ffmpeg — with a bright left half, then measures the
saved photo, a frame decoded back out of the recorded clip, and a mark painted on the overlay.
It fails on the old code with all three coming back reversed.

## Video with sound

The 🎥 button records the composited view with mic audio, capped at 30 seconds.

- **The mic is acquired at startup**, alongside the camera, so there is one permission
  prompt rather than a second one mid-session. A single `getUserMedia` rejection covers
  both tracks, so a refused mic would otherwise cost us the camera — the call retries
  video-only if the first attempt fails. The track is never routed to an output, so there
  is no feedback loop.
- **Recording is the one time we do composite per frame.** Video and overlay are drawn
  into an offscreen canvas, and `captureStream(0)` plus
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

## Making the filters actually fit

The filters sat badly on faces, the puppy worst of all, and the reason was not the drawing.

**The dense model was being paid for and thrown away.** `toAnchors()` extracted six landmarks
in mesh mode and never populated `mesh`, so `face.mesh` was always null. The puppy loaded a
3.7MB model and spent ~10ms a frame on 478 landmarks to read exactly one number from it
(`jawOpen`), then placed its ears from the same six blazeface-equivalent points as everything
else.

Rather than trust recollection of MediaPipe's numbering, every index used here was checked
against [`canonical_face_model.obj`](https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/modules/face_geometry/data/canonical_face_model.obj)
— a wrong index puts a sticker on the wrong part of a face and looks exactly like a maths bug.
That turned two vague complaints into numbers:

| | as drawn | measured truth |
|---|---|---|
| top of head (`headTopY`) | `-1.3 × |mouth.y|` = 0.97 above the eye line | landmark 10, the highest of the 468, at **0.63** |
| where animal ears attached | `earR/earL`, **0.22 below** the eye line — the cheeks | the hairline, **0.54 above** |

So hats floated 1.55x too high, and the puppy's ears grew out of its jaw. The invented `1.3`
is now a measured `0.84` for the fast tier, and mesh-tier filters use the landmark itself.

**Fifteen named landmarks, not a raw mesh.** `MESH_EXTRA` in `anchors.js` adds the head top,
hairline, temples, brows, chin, jaw, nostrils, lip bottom and mouth corners. Named points go
through the same easing, leading and deadband as the original six, which a flat array of 478
could not — and the drawing code stays declarative about anatomy instead of indexing a model.

**A third fault the numbers never showed.** Rendered against a real face, each puppy ear stood
taller than the whole head: `ellipse()` takes radii and the ear sizes had been written as
though it took widths. Same for the cat. Both are halved, and the cat's whiskers now stop just
past the cheek rather than a third of a head beyond it.

**Puppy, Kitty and Royal are mesh tier now**; Cool, Fancy and Googly stay on blazeface, since
an eye line and a lip are all they need. Selecting a mesh filter swaps the model, as it always
has, and costs detection rate while it is on.

### Seeing it instead of arguing about it

The fit was judged by rendering each filter over MediaPipe's own canonical face —
orthographically projected, wireframed, with the real app running behind a stubbed tracker — at
close and normal framing and with the head turned 26°. Guessing at proportions and asking
someone to go and look at a Portal is how the performance work went wrong four times over;
a picture on this machine costs nothing.

That rig is now assertions in `test/filters.mjs`, which uses the canonical geometry as its
synthetic face and checks *where the ink lands* rather than that ink exists. On the old
geometry they fail exactly as a person would describe it: **32% of the puppy below the chin**,
ears never reaching above the eye line, ink wider than the head.

### Filters that resample the camera

Two of the eight do something the others cannot: they read the video rather than
drawing over it. That needed one change to the contract — `draw(ctx, face, t, video)` — and
one to the render loop: a filter taking the video is repainted every frame, because its output
changes when the *picture* changes and not only when the face moves. Skipping those frames
would freeze a stale copy of the room on screen.

- **🤯 Big Head** — open your mouth and your head balloons; the voice drops as it grows, so
  it is one gesture. An ellipse around the head, the video redrawn zoomed about the middle of
  the face, and the boundary feathered with a `destination-out` radial gradient — otherwise the
  zoomed background inside the clip meets the real background at a visible seam and reads as a
  compositing bug. It only ever scales *up*: shrinking leaves a hole where the head was, and
  filling that convincingly is a far harder problem than this one.
- **🪂 Skydive** — your face, cut out and pasted into a cartoon's helmet as it falls through
  drifting clouds. Built around a real head first, the character came out the size of a bus with
  its parachute off the top of the frame; now it is a fixed share of the picture and your head
  *steers* it instead of sizing it, which also means it works whether a child is leaning in or
  standing back.

`Big Head` needs the dense model for `jawOpen`. `Skydive` deliberately does not: it needs a
head box and a position, and the canonical fallback proportions are exact enough once the face
is being scaled into a cartoon helmet — rendered side by side, the two tiers are
indistinguishable. A full-frame effect that repaints every frame would rather have the
detection rate.

Both are heavier than a sticker. Worth watching `detect` on the HUD while they are on; `paint`
will also read higher for any mesh filter, because a repaint fires when any tracked point
clears the deadband and the dense tier tracks 22 points where blazeface tracks 6.

## Two children at once

The app followed one face. Blazeface had been finding all of them the whole time —
`toAnchors()` read `detections[0]` and discarded the rest — so the second child was being
detected and thrown away, at no saving.

**What it costs, per tier.** The fast tier gets multiple faces for nothing: they come out of
the single pass it was already making, and only the drawing is repeated. The mesh tier runs a
landmark pass per face on a budget that is already ~57ms for one, so it is capped lower. The
segmenter never had this problem — its mask covers whoever is in the picture.

| tier | faces | what a second face costs |
|---|---|---|
| fast (blazeface) | **3** | one more set of stickers to draw |
| mesh (landmarker) | **2** | a second landmark pass, and only while a second face is in frame |
| segment | n/a | nothing — the mask is of people, not faces |

`FACE_CAP` in `anchors.js` is the single source of those numbers; the worker, the landmarker's
`numFaces` and the app's track limit all read it. The HUD line `faces  2 / 3` reports how many
are being followed against what the running tier will carry, which is the number to read
against `infer` when a second child sits down — this is a per-face cost that no measurement
taken alone can show.

**Which face is whose.** The tracker answers *where the faces are* and never *whose face this
is*: it returns a fresh unordered list every detection. Everything that only means something
per person — the smoothed `shown` anchors, the velocity estimate, its confidence, and a
filter's own state — has to be attached to a person, and the app is what decides who that is.
Get it wrong and the crown hops between two heads.

Each detection is matched to the nearest existing track, nearest pair first, gated at 1.5x that
face's own inter-eye distance — about a head width. Greedy is not a concession at this size:
three faces is nine numbers, and the only arrangement it mis-assigns is two faces that have
each moved closer to the other's last position than to their own, which is two children
swapping seats inside one detection interval. A track survives 500ms without a match, so a
missed detection does not blink a sticker off, but a child who leaves takes their filter with
them well before the 800ms the whole overlay waits on.

**What filters had to change.** Two, and both for the same reason — state that was per-app
had to become per-person or per-picture:

- **Googly** parked its pupil velocity in module variables. With two faces the second draw
  overwrote the first every frame, so both pairs of eyes swung to whichever head was drawn
  last. Keyed by `face.id` now, which is the track identity and stable for as long as that
  person keeps being matched.
- **Skydive** paints a sky, not a sticker. Drawn once per face it would have painted its sky
  over the previous jumper. It is split into a `scene(ctx, faces, t)` — sky and clouds, painted
  once — and a per-face `draw`, so two children get two skydivers in one sky. Each takes an
  equal slot of the frame in the order they are actually sitting (`face.rank` of `face.count`,
  left to right), shrinking and steering less far as they crowd. Alone, the maths collapses to
  exactly the single skydiver that was there before.

Big Head needed nothing: it already clipped and zoomed around one head, so it repeats per face.
The stickers needed nothing either — face space was already per face.

**One microphone, one voice.** A filter's pitch follows the nearest face, by inter-eye
distance: the person closest to the camera is the likeliest one talking, and there is only one
mic to shift.

`test/multiface.mjs` covers it, including the two claims that pixels cannot make on their own —
that ids survive two faces moving toward each other, and that the cap keeps the nearest faces
rather than the first ones reported.

## Somewhere else entirely

**🏖️ Beach, 🏰 Palace, 🌘 Moon** put the person in front of a different place. That needs to
know where the *person* is, not where their face is, so it is a third tracker tier rather than
another filter: `ImageSegmenter` with MediaPipe's selfie model (250KB, Apache 2.0), which
**replaces** the face model instead of running beside it. A background swap has no use for
landmarks, and this device cannot afford two models at once.

The compositing is three operations, no per-pixel work:

```
draw the video frame            everything
destination-in  the mask        leaves only the person
destination-over the scene      fills in behind them
```

Four things that were not obvious:

- **`segmentForVideo` returns before the GPU has finished**, exactly like the `drawImage` that
  misled the recording work. Timed alone it reads 0.6ms; timed with the `getAsUint8Array` that
  forces completion, **6.9ms** at 256x144 on a laptop. The second number is the real one, and
  it is the reason the mask is read back at 256 wide rather than the 1280 the model will
  happily hand you.
- **The scene is painted on its own canvas first.** Painted straight onto the overlay under
  `destination-over`, it comes out reversed — each new shape lands *behind* the last, so the sky
  covered the sea, the sand and the palm tree. One reused offscreen canvas costs a `drawImage`
  and keeps the scenes readable back-to-front.
- **Which mask category means "person" is read from the mask's own corners**, not assumed. The
  convention has changed between model releases, and cutting out the background instead of the
  person would look like a rendering bug rather than a wrong constant. The corners of a webcam
  frame are background essentially always.
- **The edge is feathered by blurring the mask as it scales up.** A category mask is hard 0/1,
  and a hard edge on a cut-out person reads as a sticker.

The scenes are vector art, like the skydiver's sky: no photographs to vendor, no licences to
track, a few kilobytes instead of a few hundred, and they match the app's look.

**What could not be tested here.** The model is trained on photographs and reports nonsense on
a drawn face, so nothing on this machine can tell you whether the cut-out is any good —
`test/segment.mjs` checks the plumbing with the real model (the tier loads, swaps, produces
masks, composites, swaps back, and a photo taken on the moon is not a photo of the room) and
the fit harness checks the compositing against a synthetic mask. Judging the segmentation needs
a real person, so the HUD reports **`seg`**, the share of the frame the model calls "person". A
child at normal framing should be a fraction of it. If that reads 0% or 99% on the device, the
model is not working and everything else about the picture is beside the point.

### What about downloadable filters?

Asked, and worth recording: there is no filter pack for a framework like this. What exists is
the **canonical face mesh UV template** — MediaPipe's `.obj` carries 468 texture coordinates
and 1,200 triangles, and ARCore ships [`canonical_face_mesh.fbx`/`.psd`](https://developers.google.com/ar/develop/c/augmented-faces/create-assets)
as a painting template (CC-BY 4.0), with more reference textures salvaged in
[Spark AR's asset repo](https://github.com/RobbieConceptuel/Spark-AR-Face-Assets). Those are
templates to paint into, not finished faces. Lens Studio, DeepAR and Banuba are proprietary
SDKs with nothing portable in them.

Consuming such a texture means drawing 1,200 textured triangles per frame, which is a
non-starter in canvas 2D on a device where the overlay redraw already costs 11ms — it would
need a WebGL overlay. That remains a real option, and the UV data is available if the hand-drawn
filters ever stop being enough.

**But most "silly" effects do not need any of that.** The ones people remember — a head that
balloons, a face pasted onto a cartoon — are *region* transforms, not per-pixel mesh warps: clip
an ellipse, redraw the video zoomed, scale a face into a helmet. Two or three `drawImage` calls,
not 1,200 triangles. WebGL is only required for warping the face along all 468 landmarks or
painting a canonical-UV texture onto it. Worth knowing before reaching for a second renderer.

### Judging a filter that resamples the camera

Chrome's fake camera is a rolling test pattern, which is no use for these: a zoomed head looks
like a zoomed green rectangle. So the harness draws a synthetic face — features placed on the
canonical landmarks, on a background with grid lines so a zoom is obvious — and feeds it in with
`--use-file-for-fake-video-capture`. That is what caught the seam at the edge of the big-head
clip, and the parachute leaving the frame, neither of which any assertion had complained about.

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
- **Every node is held in a variable that outlives the function that built it.** Not tidiness:
  a `MediaStreamAudioSourceNode` is collectable as soon as script can no longer reach it, *even
  while it is connected and producing sound*. Held only by a local, it survived a clip or two
  and then vanished on the next collection, leaving a graph that ran perfectly and recorded
  silence. It shipped that way, and the first report of it was "after one or two videos the
  audio stops".

  Reproduced by forcing a collection between clips, which turned a vague
  intermittent complaint into an exact one: **-22dB, -68dB, -inf, -inf**. With the references
  held, eight consecutive clips with a collection between each stayed at -22dB. Reverting the
  fix reproduces the failure on demand, which is the only reason to believe the diagnosis
  rather than the first plausible story about it.

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
was rewritten around the preview instead: it now runs **fifteen** phases in about four
minutes — A–H decomposing the preview layer by layer, I–M testing the candidate fixes, and
**N–O pricing a second face on the mesh tier**. The table above is kept because the numbers
under it are still the reason recording is not where the cost is.

N and O are the only rows that swap the tracker, and they are only meaningful against each
other: both run the mesh with the app's own preview settings, N asking for one face and O
for two. Run as-is, with one person in front of the camera, they answer whether asking for a
second face taxes a session that never has one. Run again with two people in shot, O prices
the second landmark pass itself. Every row records which tracker produced it, because a mesh
row read as a blazeface row is exactly the mistake that costs a trip to the device.

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

## Who can open any of this

For the first week the answer was *anyone who knows the hostname*. The tunnel is on the open
internet, and `media/` is photographs of children. Guessing a filename is not much of a defence
either: they are `pic-<UTC second>-<four base36 characters>`, which is about 1.7 million tries
per second of wall clock, and the listing endpoint would have handed over the whole album anyway.

So every route now needs a **paired device**, and the interesting problem is that the device
which most needs to stay signed in is the one you can least type on.

### No passwords, anywhere

There is no login form, because a Portal is a touchscreen across a room with a keyboard nobody
wants to use, and because a self-hosted thing with a password in its compose file is a password
that leaks. Instead each device holds 256 bits of randomness in a cookie, and gets it in exactly
one of three ways:

| | |
|---|---|
| **claim** | While nothing is paired, the server prints a one-time link to its own log. Whoever can read the log — on a home server, the person who started it — opens it on a phone and becomes device #1. Regenerated on every restart until it is used, and discarded for good the moment it is. |
| **invite** | Any paired device mints a QR from `/devices.html`. Whoever scans it is enrolled. Five minutes, single use. |
| **pair** | The Portal's route, below. |

Only the hash of each token is stored, so `data/auth.json` is a list of names and dates rather
than a ring of keys. There is no scrypt over the top and no need for one: the token is random,
not chosen, so there is nothing to grind through.

### The Portal shows a QR and waits

This is the OAuth device-authorization grant, and it fits so exactly that it is worth naming.
An unpaired browser is redirected to `/pair`, which draws a QR and a six-character code and then
polls. A phone that is *already* trusted scans the QR — or types the code, for when a camera
won't focus — and approves it. The Portal collects its cookie on the next poll and reloads into
the camera. Nobody types anything on the Portal.

Two details are load-bearing:

**The pairing has two secrets, not one.** The Portal keeps a *device* secret, which is the only
thing that can collect the token, and the QR carries a separate *approval* secret, which can do
nothing but approve. Without the split, anyone who merely saw the screen — over a video call,
or across the room — could poll alongside the Portal and take the token the moment a parent
approved. This is why the real device grant has both a device code and a user code.

**The secret rides in the URL fragment** (`/pair#approve=…`), which browsers never send to a
server. It exists in the phone's address bar and nowhere else: not in an access log, not in a
`Referer`.

What the split does *not* stop is the attack every device grant has: someone starts a pairing
of their own, sends the approve link to a person who is already trusted, and hopes they tap
*Let it in*. The only defence is that the page says plainly that a device is waiting and shows
what it claims to be — the same defence a bank has against a customer reading a code out over
the phone. On a family server whose approvers are two adults, that is the right amount of
paranoia; it is worth knowing it is the soft edge.

### Cookies, and why there was no choice

The face tracker is pulled into a worker by `importScripts`, the pitch shifter by
`audioWorklet.addModule`, and every album tile is an `<img src>`. None of those three can carry
an `Authorization` header, and all of them send a same-origin cookie — so a bearer token would
have taken the filters down with it. `test/auth.mjs` watches the HUD report `backend worker`
for exactly this reason: a cookie that fails to reach the worker looks identical to a tracking
bug, because `importScripts` reports a 401 as nothing more useful than "worker failed to load".

The other half of that is **what a refusal looks like**. A navigation gets a 302 to `/pair`;
everything else gets a 401 with a JSON body. Sending a `fetch` to an HTML login page instead
would surface in the app as "couldn't reach the server" and paint the album as broken images.

There is deliberately **no bypass for loopback**, tempting as it was for the tests. The dev
recipe further down is `cloudflared tunnel --url http://localhost:8080`, where every request
from the open internet arrives on loopback — "trusted because local" would have unlocked the
door it was holding shut. The test harness signs in properly instead, which is also the only
way the suite proves anything about the server people actually run.

### Sending one photo to someone who has no device

Grandparents do not want a paired device. `POST /share` mints `/s/<token>` for exactly one
capture, seven days by default, revocable from `/devices.html`, and served through the same
ranged reader the album uses so a shared clip still scrubs. The token is fresh randomness with
no relationship to the filename, which is the whole point — the filename is the weak thing.
Deleting a capture revokes its links. `SHARE_LINKS=off` refuses to mint any, for a host who
wants no unauthenticated route to media to exist at all.

This is also what the Send button now copies. It used to copy a `/media/…` URL, which after all
of the above is a 401 for whoever received it.

### The exchange, step by step

Time runs downward. The Portal never learns the approval secret, and the phone never learns the
device secret — that is the split described above, drawn out:

```
   PORTAL (unpaired)                SERVER                      PHONE (paired)
         │                            │                              │
         │─── GET /app.html ─────────►│                              │
         │◄────── 302 /pair ──────────│                              │
         │─── POST /auth/pair/start ─►│  mints a device secret, an   │
         │◄── id, deviceSecret, ──────│  approval secret, and a      │
         │    approveUrl, code        │  six-character code          │
         │                            │                              │
         │  shows a QR of approveUrl, which is                       │
         │  /pair#approve=<id>.<approvalSecret>                      │
         │· · · · · · · · · · · scanned · · · · · · · · · · · · · · ►│
         │                            │                              │
         │─── poll ──────────────────►│◄─── describe ────────────────│
         │◄── {pending: true} ────────│──── "Android, Chrome 138" ──►│
         │                            │                              │
         │                            │◄─── approve ─────────────────│
         │─── poll ──────────────────►│     (with the phone's        │
         │                            │      own cookie)             │
         │◄── Set-Cookie: psnap=… ────│  the device record is made   │
         │    {ok, device}            │  here, not at approval: an   │
         │                            │  abandoned pairing leaves    │
   reloads into the camera            │  nothing behind              │
```

Five minutes and the pairing is gone. The page redraws itself ten seconds before that, so a
Portal left on this screen all afternoon is never showing a QR that stopped working at lunchtime.

### Routes

Everything not listed as open needs a session cookie, including `/`, `/gallery.html`, the
diagnostics pages, `/vendor/`, `/models/` and every `/media/` route in the table further up.

| Route | | |
|---|---|---|
| `GET /pair` | **open** | The only page an unpaired browser can load. Which of the four things it does is decided by the URL fragment: `#claim=…`, `#invite=…`, `#approve=…`, or nothing at all, which is the show-a-QR-and-wait screen. |
| `GET /auth/qr.svg?t=<text>` | **open** | Renders up to 512 characters as an SVG QR. The encoder runs here so the pairing page ships none. |
| `POST /auth/claim` | **open** | `{secret, name}`. Works only while no device is paired. Sets the cookie. |
| `POST /auth/invite/redeem` | **open** | `{id, secret, name}`. Single use. Sets the cookie. Open by necessity — the device being invited has no session yet. |
| `POST /auth/pair/start` | **open** | Mints a pairing. Returns `id`, `deviceSecret`, `approveUrl`, `code`. |
| `POST /auth/pair/poll` | **open** | `{id, deviceSecret}` → `{pending:true}`, or the cookie once approved. |
| `GET /s/<id>.<secret>` | **open** | One capture, ranged, `Cache-Control: private`. |
| `POST /auth/pair/describe` | paired | `{id, secret}` → what the waiting device claims to be. |
| `POST /auth/pair/approve` | paired | `{id, secret, name}` from a scan, or `{code, name}` from typing. |
| `POST /auth/invite` | paired | Mints an invite URL for a new device. |
| `GET /auth/devices` | paired | The list, with `current` marking the caller. |
| `POST /auth/devices/rename` | paired | `{id, name}`. |
| `POST /auth/devices/revoke` | paired | `{id}`. Revoking yourself clears your own cookie; revoking the last device re-arms the claim link and prints it. |
| `GET /auth/whoami` | paired | Used by `/pair` to notice it is already signed in. |
| `POST /auth/logout` | paired | Clears the cookie without revoking the device. |
| `GET POST DELETE /share` | paired | List, mint `{name, days}`, revoke `?id=`. |

### What is stored, and what is in the cookie

The cookie is `psnap=<deviceId>.<token>` — `HttpOnly`, `SameSite=Lax`, `Path=/`, a year long, and
`Secure` whenever the request arrived over HTTPS. `SameSite=Lax` is what makes CSRF a non-issue:
a cross-site `POST` and a cross-site subresource both arrive without it. The year slides — one
request a day re-issues the cookie — so a device in daily use never pairs twice, while one that
sits in a drawer for a year falls out on its own.

`data/auth.json` is the only state, written tmp-then-rename like an upload:

```json
{ "version": 1,
  "devices": [{ "id": "…", "name": "Portal", "hash": "sha256 of the token",
                "created": 0, "lastSeen": 0, "ua": "…" }],
  "shares":  [{ "id": "…", "hash": "sha256 of the secret", "file": "pic-….jpg",
                "created": 0, "expires": 0, "by": "device id" }] }
```

Pending pairings and unredeemed invites are **not** in there. They live five minutes in memory,
because a restart cancelling one that was in flight is the correct outcome — the Portal just
draws a fresh QR. That also means the only thing an attacker gets from the file is a list of
names and dates.

The numbers, all in one place at the top of `auth.js`: sessions last 365 days and refresh at
most daily; pairings and invites last 5 minutes; a typed code gets 10 attempts before its
pairing is dead, with 30 attempts a minute server-wide; at most 200 pairings may be pending at
once, since starting one needs no session; share links default to 7 days and `SHARE_MAX_DAYS`
caps what may be asked for.

Rate limiting is counted server-wide rather than per-IP on purpose. Behind a tunnel every
request shares one source address, so an IP bucket would be one bucket for the whole internet.

### Where this lives in the code

| | |
|---|---|
| `auth.js` | All of it: sessions, devices, pairings, invites, share tokens, persistence. No HTTP in here. |
| `server.js` | One `authorize` check at the top of the request handler, before any route runs, so there is a single place to get it wrong. `openRoute` is the entire unauthenticated surface and is deliberately short. |
| `public/pair.html` | The four fragment modes, and the polling. |
| `public/devices.html` | Rename, revoke, invite QR, live share links. |
| `public/session.js` | Wraps `fetch` so a 401 anywhere sends the page to `/pair`. Loaded before everything else. Several call sites — the poster backfill especially — swallow their own errors on purpose, so without this being signed out would look like an album that quietly stops filling in. |
| `vendor/qrcode.js` | The QR encoder, server-side. |
| `test/auth.mjs` | The lock, both pairing routes, share links, invites, revocation. |

### What this does not do

No encryption at rest — the server has to read the files to serve them, so a key sitting beside
them buys nothing that disk encryption doesn't do better. No per-photo permissions, no accounts,
no password reset flow to get wrong. Revoking the last device is allowed on purpose: it is the
way back in when every trusted device is lost, and the server returns to printing a claim link.

Nor does it defend against someone who can read the server's logs or its disk: the log is where
the claim link is printed, which is the root of trust by design. On a home server that is the
person who owns the photographs anyway.

## Where this stands, and what to do next

Everything asked for works and is deployed: photos and clips save to the server, clips are
1280x720 H.264 with AAC audio, and `gallery.html` gets them into a phone's camera roll.
What follows is tuning of the sticker lag, not the feature.

**One number is outstanding**, and it is the only unmeasured thing here: what a second face
costs the mesh tier on the device. The fast tier's is known to be nothing — blazeface was
already finding every face and the extra ones were being discarded. The mesh runs a landmark
pass per face, and `numFaces: 2` is now asked for whenever a mesh filter is on. Whether that
taxes a session with only one child in front of it is what `recdiag.html` phases **N and O**
exist to answer; the HUD's `faces  n / m` line reports the same thing live. Until those are
read on the Portal, treat the mesh tier's multi-face cost as unknown rather than free.

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

## Pages

| URL | |
|---|---|
| `/` | the app — this is what the Portal's home-screen link should point at |
| `/gallery.html` | the album, for a phone or laptop where saving actually works |
| `/pair` | the only page reachable without a paired device: shows a QR, or approves one |
| `/devices.html` | what is trusted, what links are live, and how to revoke either |
| `/probe.html` | the original capability probe |
| `/sharetest.html` | what this device can and cannot share, and where |
| `/recdiag.html` | the fifteen-phase performance harness |
| `/bench.html`, `/track.html` | the tracker sweeps that chose the models |

The root used to serve the probe, which was right for the first week and confusing ever
after — tapping a bookmark should open the camera, not a diagnostics page. `/index.html`
lands on the app too, so an older bookmark still works.

## Running the probe

Camera and mic access require a **secure context**. `http://192.168.x.x:8080` will be
rejected by the browser before any permission prompt appears — HTTPS is mandatory.

### Fastest path: quick tunnel from this machine

```bash
brew install cloudflared          # one time
node server.js                    # terminal 1 — serves ./public on :8080
cloudflared tunnel --url http://localhost:8080   # terminal 2 — prints an https://….trycloudflare.com URL
```

Open that HTTPS URL in the Portal's browser. The root is the app; add `/probe.html` for the
capability probe, then tap **Camera + Mic**.

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

### Getting in the first time

A fresh installation has no trusted devices, so it prints a claim link to its own log and
waits. Set `PUBLIC_URL` in `docker-compose.yml` first and it prints one you can tap:

```bash
docker compose up -d
docker compose logs portalsnap

#   ┌─ Nothing is paired yet ────────────────────────────────
#   │  Open this on your phone to claim this server:
#   │  https://portalsnap.example.net/pair#claim=Yk3f…
#   └────────────────────────────────────────────────────────
```

Open it on a phone and that phone is device #1. From there:

- **The Portal**: open the app on it. It lands on `/pair`, shows a QR, and waits. Scan it with
  the phone and tap *Let it in* — or type the six characters into `/pair` on the phone if the
  camera won't focus.
- **Anyone else's phone**: `/devices.html` → *Add a device* → scan.

`/devices.html` is also where devices are renamed and revoked, and where live share links are
listed and killed. A revoked device is locked out on its next request.

The claim link stops working the instant the first device pairs. If every trusted device is
ever lost, revoke the last one from itself — or delete `data/auth.json` — and the server goes
back to printing a claim link on restart.

Environment worth knowing about:

| | |
|---|---|
| `PUBLIC_URL` | only used to print a tappable claim link |
| `SHARE_LINKS=off` | refuse to mint links that work without a paired device |
| `SHARE_MAX_DAYS` | longest a share link may be asked to live (default 30) |
| `PORTALSNAP_DATA` | where the device list lives (default `./data`) |
| `PORTALSNAP_MEDIA` | where captures live (default `./media`) |
| `PORTALSNAP_SECURE_COOKIE=1` | force `Secure` on the session cookie, for a proxy that doesn't set `X-Forwarded-Proto` |

The server never terminates TLS itself and never sets `Secure` on a cookie it is about to send
over plain HTTP, because a browser silently discards that cookie and the symptom is a login
that will not stick.

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

`vendor/qrcode.js` is the one dependency that runs on the *server*, which is why it sits at the
repo root rather than under `public/` — the pairing page ships no encoder, it just asks
`/auth/qr.svg` for one. It is Kazuhiko Arase's `qrcode-generator`, MIT, plain ES5 with a UMD
footer and no dependencies of its own:

```bash
curl -L -o vendor/qrcode.js https://cdn.jsdelivr.net/npm/qrcode-generator@1.4.4/qrcode.js
```

nayuki's generator is the better-known one and was the first choice, but it ships TypeScript;
using it would have meant a build step, and there isn't one here.

## Fallback if the browser blocks the camera

Portal OS is Android underneath and supports `adb` sideloading in developer mode. If the
built-in browser refuses camera access, the same web app can be wrapped in a minimal
Android WebView APK that holds the `CAMERA` permission itself. Same code, different shell —
which is another reason to keep this a plain static web app with no build step.
