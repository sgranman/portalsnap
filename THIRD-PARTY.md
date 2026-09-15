# Third-party components

PortalSnap itself is MIT (see [LICENSE](LICENSE)). It ships **no runtime
dependencies** — the app is plain static files and a Node server built on the
standard library alone. What follows is vendored, committed on purpose, and
redistributed under its own terms.

Why vendored at all: a discontinued device on an aging browser should not depend
on third-party hosts staying reachable. See *Vendored assets* in BUILD-JOURNAL.md for
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

## USDA NAIP aerial photograph — public domain

`android/app/src/main/assets/ground/farmland.jpg` is Freefall's ground: a crop of one 2022
National Agriculture Imagery Program tile of New York State (`m_4207421_ne_18_060_20221029`),
scaled to 2048×2048. NAIP imagery is a work of the U.S. Department of Agriculture and is in
the public domain; the source asks for courtesy attribution:

> USDA Farm Service Agency, Aerial Photography Field Office, via the NOAA Office for Coastal
> Management's Digital Coast (NY_NAIP_2022_9986).

Fetched from Wikimedia Commons:
<https://commons.wikimedia.org/wiki/File:2022_USDA_NAIP_4-Band_8_Bit_Imagery,_New_York_%E2%80%93_m_4207421_ne_18_060_20221029.tif>

## Places backdrops — public domain and CC0

The eight pictures in `android/app/src/main/assets/places/` are Places' backdrops, each cropped
and scaled to 1280×720 from a file on Wikimedia Commons. All are in the public domain or
dedicated to it under CC0, so none requires attribution; they are credited here anyway.

| File | Picture | Author | Status | Source |
|---|---|---|---|---|
| `castle.jpg` | Neuschwanstein Castle, photochrom print (c. 1890–1900, Library of Congress) | unknown | public domain | <https://commons.wikimedia.org/wiki/File:Neuschwanstein_Castle_LOC_print_rotated.jpg> |
| `forest.jpg` | Forest Path Sunset | 44833 (Pixabay) | CC0 | <https://commons.wikimedia.org/wiki/File:Forest_Path_Sunset.jpg> |
| `waterfall.jpg` | Mossy rock mountain and waterfall, Iceland (Unsplash) | Jacek Smoter | CC0 | <https://commons.wikimedia.org/wiki/File:Mossy_rock_mountain_and_waterfall_(Unsplash).jpg> |
| `circus.jpg` | Chimelong International Circus arena (2025) | Renek78 | CC0 | <https://commons.wikimedia.org/wiki/File:2025_Chimelong_International_Circus_05.jpg> |
| `yacht.jpg` | Sailing at sunset (Unsplash) | Alin Meceanu | CC0 | <https://commons.wikimedia.org/wiki/File:Alin_Meceanu_2014_(Unsplash).jpg> |
| `beach.jpg` | Colourful beach huts on a beach (Unsplash) | Arno Smit | CC0 | <https://commons.wikimedia.org/wiki/File:Colorful_beach_huts_on_beach_(Unsplash).jpg> |
| `northpole.jpg` | Arctic fox in snow (Unsplash) | Jonatan Pie | CC0 | <https://commons.wikimedia.org/wiki/File:Arctic_fox_in_snow_(Unsplash).jpg> |
| `moon.jpg` | Earthrise, Apollo 8 (NASA, AS08-14-2383) | Bill Anders | public domain | <https://commons.wikimedia.org/wiki/File:Earth-moon.jpg> |

## Development-only

`puppeteer-core` (Apache-2.0) drives the browser tests. It is a
`devDependency` — it is never loaded by the app or the server, and nothing in
`public/` or `server.js` imports it.
