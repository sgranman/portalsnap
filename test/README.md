# Tests

Two tests in the repo root have no prerequisites at all, and `npm test` runs both.
`node test-project.js` loads `public/anchors.js` in a `vm` and checks the projection maths;
`node test-pitch.js` does the same to `public/pitch.worklet.js`, shimming the two globals an
AudioWorklet needs and measuring the output frequency of a sine at each ratio. Both check
arithmetic a browser cannot see.

The app itself ships with no dependencies and no build step, and that stays true — everything
here is a development tool that happens to live in the same repo.

The rest drive a real browser, so they need `puppeteer-core` and a Chrome, neither of which
is vendored:

```bash
npm install                               # puppeteer-core, the only devDependency
npm run chrome                            # or point CHROME at a browser you already have

# anything but 8080, so it can't hit the Portal's; scratch directories so the
# suite never touches a real album or a real device list
PORTALSNAP_DATA=$TMPDIR/psnap-test PORTALSNAP_MEDIA=$TMPDIR/psnap-media \
  PORTALSNAP_CLAIM=test-only PORTALSNAP_DIAG=1 PORT=8099 node server.js &

node test/filters.mjs                      # or any other file in here
```

`CHROME` is found automatically: `harness.mjs` looks through the puppeteer cache for the
newest installed Chrome for Testing, then falls back to a system Chrome or Chromium, and
fails with an explanatory error if there is none. Set `CHROME` to skip all of that. `PORT`
defaults to 8099.

All of them launch with `--use-fake-device-for-media-stream`, so no camera or mic is needed.

`PORTALSNAP_DIAG=1` is there because `recdiag.mjs` and part of `e2e.mjs` drive the
diagnostic pages, which the server does not serve by default. `recdiag.mjs` says so and
stops if the flag is missing; `e2e.mjs` asserts the switch works either way, so it passes
with or without it.

`PORTALSNAP_MEDIA` matters more than it looks: `e2e.mjs` starts by emptying the album, and
without it that is the album on this machine.

### Getting past the door

Every route except `/pair` needs a paired device, so `harness.mjs` claims one. It posts
`PORTALSNAP_CLAIM` to `/auth/claim`, keeps the resulting cookie in `test/.session`, and puts it
on the browser rather than the page — every worker and worklet the page opens inherits it,
which is the only reason the face tracker loads at all.

Claiming works exactly once per data directory, which is why the cookie is cached: the second
run of the day reuses it, and only falls back to claiming if the server no longer accepts it.
Delete `test/.session` after wiping the data directory.

There is no way to switch authorization off for a test run, deliberately. A suite that runs
against an unlocked server proves nothing about the one that is deployed, and the obvious
shortcut — trusting loopback — would disable the lock entirely under the quick-tunnel recipe in
the main README, where every request arrives from localhost.

`harness.mjs` also exports `api()`, which is `fetch` with the cookie attached, for the handful
of tests that talk to the server from Node rather than through a page.

| | what it covers |
|---|---|
| `filters.mjs` | the drawing path, and *fit*: where the ink lands against the landmarks, still faces stop repainting, a lost face clears it, a throwing filter doesn't corrupt the context, and the two video-sampling filters repaint every frame |
| `multiface.mjs` | two children at once: a sticker on each head and nothing in the gap, track identity surviving faces that move toward each other, the per-tier cap keeping the *nearest* faces, and one sky with two skydivers in it |
| `render.mjs` | the render loop's 30Hz cap and its idle skip |
| `e2e.mjs` | capture → upload → in-app album → delete, clip previews, and `gallery.html` |
| `share.mjs` | the Send button against a browser that lies: refusal vs cancellation, link and clipboard fallbacks, and retiring itself |
| `mirror.mjs` | the preview stays mirrored, captures do not, and the overlay stays in register — against a self-made asymmetric camera |
| `album.mjs` | album layout at a size that actually overflows: no overlapping tiles, two columns on a phone |
| `rechud.mjs` | the recording HUD lines appear, read plausibly, and clear afterwards |
| `fullscreen.mjs` | the full screen button toggles both ways and doesn't cover the camera controls |
| `voice.mjs` | the encoder gets the pitch-shifted track, the ratio follows the filter, the graph parks when idle |
| `voice-gc.mjs` | clips still contain sound after a forced garbage collection — see below |
| `segment.mjs` | the segmentation tier with the **real** model: loads, swaps in and out, produces masks, composites, and a moon photo isn't a photo of the room |
| `recdiag.mjs` | `recdiag.html`'s fifteen phases really configure what they claim to |
| `auth.mjs` | the lock: what an unpaired visitor gets (302 for a navigation, JSON 401 for everything else), that the tracker still runs in the worker through a cookie, both pairing routes, that the QR's secret can't collect the token, share links, invites, and revocation |

**The fake camera has no face in it.** That is why `filters.mjs` stubs the tracker worker
instead — same three messages as `tracker.worker.js`, returning synthetic anchors the test
drives on demand. Everything downstream of a detection is testable that way; a test relying
on the fake camera alone can only ever assert that nothing is being drawn, which is how a
filter regression once shipped green.

Those anchors are 22 vertices of MediaPipe's canonical face model, so the synthetic face is
anatomically real and the fit assertions mean something: "the ears reach above the eye line"
is a claim about geometry, and it fails on the old code with the ears 0.22 face units below it.
An earlier version of this stub used made-up coordinates for a perfectly motionless face, and
that single unrealistic detail hid two shipped bugs — no jitter to defeat the still-face
threshold, no anatomy to catch ears on the cheeks.

The stub lives in `facestub.mjs` so `filters.mjs` and `multiface.mjs` drive the same one and
there is a single contract to keep in step with the worker. It takes `window.__extra` —
`[{dx, dy, scale}]` — to put more people beside the first, and caps the list at the `maxFaces`
the app asked for at init, exactly as `toFaces()` does on the device, so a test of the cap
tests the tracker's rule rather than the app's backstop.

`recdiag.mjs` takes about four minutes and asserts on *structure*, not timings — it checks
that phase L really hides the video and shows the composite, that phase I really halves the
canvas, that N and O really run the mesh with one face and then two, and so on. The timings it prints are meaningless on a Mac, where every phase lands
at 7ms; only the Portal can answer those.

`voice-gc.mjs` forces a collection through CDP between clips and then decodes each saved file
back with `decodeAudioData` to measure it, so it needs no ffmpeg. It exists because an audio
node held only by a local was collected mid-session and every later clip recorded silence: the
graph ran, the encoder ran, and the result was -inf dB. Anything that builds a Web Audio graph
here should be assumed vulnerable to that and tested this way.

`e2e.mjs` counts album tiles, so it empties the album through the API before it starts —
every other browser test saves a photo, and running them in sequence used to make it fail with
two tiles where it expected one. Clear the album that way rather than with `rm -rf media`
while the server is running: there are `.part` files mid-upload.
