# Contributing

PortalSnap is a hobby project maintained in spare time — expect a considered reply, not a fast
one. Contributions are welcome all the same, and three kinds especially:

- **Bug fixes.** It runs on discontinued hardware in real living rooms. Things break.
- **Performance.** The Portal has a fixed budget and no headroom to waste.
- **New filters.** The best ones haven't been written yet, and this is the easiest way in.

By contributing you agree your work ships under the [MIT licence](LICENSE).

## Before you start

- **Small and clear** — a crash, a sticker landing in the wrong place, a typo: just open a pull
  request. No issue needed.
- **Bigger or opinionated** — a new tracker tier, a dependency, restructuring, anything touching
  pairing, storage or share links: open an issue first so you don't spend an evening on a
  direction that won't land.
- **A new filter** needs no permission whatsoever. Read
  [MAKING-FILTERS.md](MAKING-FILTERS.md) and go.

## The two codebases

| | |
|---|---|
| [`android/`](android/README.md) | the native Kotlin app. The one to work on: camera-rate 30fps, real 3D, sound, most of the filters. |
| [`public/`](public/) | the web app — plain static files and a Node server, the original eleven filters. |

They share the models in `public/models` and the same optional server. A new filter usually lands
on Android alone; *Filters in the web app* in [MAKING-FILTERS.md](MAKING-FILTERS.md) covers the
other side if you want both.

## Three constraints that shape everything

1. **No runtime dependencies, and no build step.** The web app and server are built on the Node
   standard library alone, and third-party code is vendored on purpose — a discontinued device on
   an aging browser should not depend on someone else's host staying reachable. Development tools
   as `devDependencies` are fine. On Android, weigh a new Gradle dependency against APK size
   before reaching for it.
2. **It has to run on a Portal.** `minSdk` 28 (gen 1) and `targetSdk` 29 — the Portal tops out
   there, so no API above 29. The APK carries both `arm64-v8a` and `armeabi-v7a`.
3. **Art must be yours, public domain, CC0 or CC BY.** Credit anything you didn't make in
   [THIRD-PARTY.md](THIRD-PARTY.md); CC BY needs its title, author, source link, licence and what
   you changed — Cat Hat's kitten is the worked example. **Pictures of real people stay out of the
   repo**: describe an effect in words, as [android/PHOTOBOOTH.md](android/PHOTOBOOTH.md) does.

## Reporting a bug

Open an [issue](https://github.com/sgranman/portalsnap/issues) with:

- **Which Portal** generation, and the app version:
  `adb shell dumpsys package net.sgransoft.portalsnap | grep -E 'versionName|versionCode'`
- **What you did and what happened**, including the filter and how many faces were in frame.
- **The log.** `adb logcat -s PSNAP` prints a stats line every two seconds; paste a few from
  around the problem. For a crash, the stack trace from plain `adb logcat` is the useful part.

Not a bug: a security issue. See below.

## Performance work

A performance change needs **a number before and a number after**, measured on the same device
under the same conditions. Two tools, both in [android/README.md](android/README.md):

- `adb logcat -s PSNAP` — the live stats line. `renderFps` is the headline.
- `--ez bench true` — the scripted bench, with a warm-up discarded. *Measured on the device* in
  that README shows the table it produces and how existing filters score.

Say **which Portal you measured on** and **which tracker tier** the filter uses. The two
generations differ enough that a shader which is comfortable on one can be slow on the other, so a
number without a device attached doesn't say much. Give a new build a minute to settle before
trusting its first readings.

## New filters

[MAKING-FILTERS.md](MAKING-FILTERS.md) is the real guide: the filter contract, the toolkit, the
tracker tiers, testing against a portrait instead of a live camera, and building one either by hand
or by briefing a coding agent. Its *Sharing it* section is the checklist for a filter pull request —
what it does, where it came from, screenshots or a clip from a real Portal with frame rates at one
and two faces, and credits.

## Pull requests

- **Open it against `main`.** That branch takes pull requests only — no direct pushes — and merges
  are squashed.
- **The `Android APK` check has to be green.** It builds every pull request into `main`, whatever
  the change touches, so a web-only pull request still gets a check rather than waiting forever on
  one that never runs.
- **First time here? Your checks will sit and wait.** Workflows on pull requests from forks need a
  maintainer's go-ahead for first-time contributors. Nothing is broken; it just needs a click.
- **The title becomes the commit subject**, since the merge is squashed. Match the log: a lowercase
  area, a colon, then what changed.

  ```
  faces: Cat Hat, a 3D kitten that balances on your head
  android: bake Pop Art's ground once, 6fps to 21 on a gen 2 Portal
  record: clips run to a minute instead of 30 seconds
  docs: CC BY art is fine for filters, credited in THIRD-PARTY.md
  ```

- **One thing per pull request.** A fix and a refactor in one branch are hard to review and harder
  to revert.
- **Match the code around yours** — its naming, its comment density, its idiom. Please don't
  reformat lines you didn't otherwise need to touch.
- **Tests:** `npm test` runs the maths tests and `npm run test:browser` the browser ones
  ([test/README.md](test/README.md)). The Android app has no unit tests, so check it on a real
  Portal and say in the pull request what you saw.
- **Show it.** Screenshots or a short clip for anything that changes what's on screen.

## Security

Don't open a public issue. Report it as a
[private security advisory](https://github.com/sgranman/portalsnap/security/advisories/new).
[SECURITY.md](SECURITY.md) covers what the project stores, who can reach it, and why share links
deserve a decision.
