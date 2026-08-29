# Third-party components

PortalSnap itself is MIT (see [LICENSE](LICENSE)). It ships **no runtime
dependencies** — the app is plain static files and a Node server built on the
standard library alone. What follows is vendored, committed on purpose, and
redistributed under its own terms.

Why vendored at all: a discontinued device on an aging browser should not depend
on third-party hosts staying reachable. See *Vendored assets* in the README for
the refresh recipes.

## MediaPipe Tasks Vision — Apache-2.0

Copyright 2023 The MediaPipe Authors.

| Path | What |
|---|---|
| `public/vendor/mediapipe/` | JS bundles and WASM runtimes, from npm `@mediapipe/tasks-vision` |
| `public/models/` | pre-trained `.task` / `.tflite` model files |

License text and attribution live beside the files, in
[`public/vendor/mediapipe/LICENSE`](public/vendor/mediapipe/LICENSE) +
[`NOTICE`](public/vendor/mediapipe/NOTICE) and
[`public/models/LICENSE`](public/models/LICENSE) +
[`NOTICE`](public/models/NOTICE). The bundles are minified and carry no header
of their own, which is precisely why those files exist.

The models are face-detection models with documented demographic limitations.
If you deploy this beyond your own household, read the upstream model cards.

## qrcode-generator — MIT

Copyright (c) 2009 Kazuhiko Arase. <http://www.d-project.com/>

`vendor/qrcode.js`, from npm `qrcode-generator@1.4.4`, redistributed unmodified
with its MIT header intact at the top of the file.

This is the only vendored component that runs on the **server** rather than in
the browser, which is why it sits at the repo root rather than under `public/` —
the pairing page ships no encoder, it just asks `/auth/qr.svg` for one.

"QR Code" is a registered trademark of DENSO WAVE INCORPORATED.

## Development-only

`puppeteer-core` (Apache-2.0) drives the browser tests. It is a
`devDependency` — it is never loaded by the app or the server, and nothing in
`public/` or `server.js` imports it.
