# Tests

`node test-project.js` (in the repo root) is the only one with no prerequisites: it loads
`public/anchors.js` in a `vm` and checks the projection maths. The app itself ships with no
dependencies and no build step, and that stays true — everything here is a development tool
that happens to live in the same repo.

The rest drive a real browser, so they need `puppeteer-core` and a Chrome, neither of which
is vendored:

```bash
npm init -y && npm i puppeteer-core
npx puppeteer browsers install chrome     # or point CHROME at one you have

PORT=8099 node server.js &                # anything but 8080, so it can't hit the Portal's
CHROME=/path/to/chrome node test/filters.mjs
```

`CHROME` defaults to a Chrome for Testing path on this machine; `PORT` defaults to 8099.
All of them launch with `--use-fake-device-for-media-stream`, so no camera or mic is needed.

| | what it covers |
|---|---|
| `filters.mjs` | the drawing path: filters ink the canvas, still faces stop repainting, a lost face clears it, a throwing filter doesn't corrupt the context |
| `render.mjs` | the render loop's 30Hz cap and its idle skip |
| `e2e.mjs` | capture → upload → in-app album → delete, and `gallery.html` |
| `rechud.mjs` | the recording HUD lines appear, read plausibly, and clear afterwards |
| `fullscreen.mjs` | the full screen button toggles both ways and doesn't cover the camera controls |
| `recdiag.mjs` | `recdiag.html`'s twelve phases really configure what they claim to |

**The fake camera has no face in it.** That is why `filters.mjs` stubs the tracker worker
instead — same three messages as `tracker.worker.js`, returning synthetic anchors the test
drives on demand. Everything downstream of a detection is testable that way; a test relying
on the fake camera alone can only ever assert that nothing is being drawn, which is how a
filter regression once shipped green.

`recdiag.mjs` takes about three minutes and asserts on *structure*, not timings — it checks
that phase L really hides the video and shows the composite, that phase I really halves the
canvas, and so on. The timings it prints are meaningless on a Mac, where every phase lands
at 7ms; only the Portal can answer those.

`e2e.mjs` writes into `media/` and deletes what it created. Clear the album through the API
(`DELETE /media/<name>`) rather than `rm -rf media` while the server is running.
