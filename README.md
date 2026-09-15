# PortalSnap

Face filters for kids on a Meta Portal, bringing back the fun the Portal lost when Meta removed its
filter app. Puppy ears, a bike ride through a park, a skydive through the clouds, big hamster
eyes: pick one, pull a face, take a photo or record a clip.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

There are two versions, and they share the same models, filters and optional server:

- **The Android app** ([`android/`](android/README.md)) is a native APK you sideload onto the
  Portal. It's the one to use: it runs at the camera's 30fps, and it has real 3D scenes, sound
  effects and more filters.
- **The web app** ([`public/`](public/)) runs in the Portal's own browser, with no ADB needed.
  It has the original eleven filters and needs a small server with HTTPS.

Both take photos and clips with the filter baked in.
- **Android** keeps them in an album on the Portal, and can also send them to your own server.
- **Web** keeps them on its server, since the Portal's browser can't save files.

## The filters

On Android:
- **Faces** 🐶: Puppy, Kitty, Cool, Royal, Googly, Fancy, Big Head
- **Places** 🗺️: Castle, Forest, Waterfall, Circus, Yacht, Beach, North Pole, Moon
- **Everything else:**
  - Mirror 🪞
  - Pop Art 🎨
  - Disco 🪩
  - Monster 👹
  - Hearts 💖
  - Hamster 🐹
  - Lemonade 🍋
  - Peas 🌱
  - Bike 🚲
  - Freefall ☁️

Several people can play at once.

## The Android app

### Install it

The Portal needs ADB turned on first: Settings → Debug → ADB Enabled, on current firmware.
Download `PortalSnap-x.y.z.apk` from the
[latest release](https://github.com/sgranman/portalsnap/releases/latest), then:

```bash
adb tcpip 5555                          # once, over USB: switch the Portal to Wi-Fi adb
adb connect <portal-ip>:5555
adb push PortalSnap-x.y.z.apk /data/local/tmp/portalsnap.apk
adb shell pm install -r -g /data/local/tmp/portalsnap.apk
```

- **`-g`** grants the camera, microphone and storage permissions up front, so nobody has to answer
  prompts on the Portal.
- **Push, then install:** a streamed `adb install` can hang on a Portal.
- **Updates** install the same way, and keep photos, clips and settings.
- **Which Portal:** one APK covers both Portal generations. It's tested on the gen 1 10" Portal.

### Build it

You need JDK 17 and the Android SDK (platform 36, build-tools 37), or Android Studio pointed at the
`android/` folder.

```bash
cd android
export JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk
./tools/fetch-test-assets.sh           # once: the test portrait debug builds can use instead of the camera
./gradlew assembleDebug
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/portalsnap.apk
adb shell pm install -r -g /data/local/tmp/portalsnap.apk
```

Debug builds are signed with a different key from releases, so run
`adb uninstall net.sgransoft.portalsnap` when switching between the two. That forgets settings and
server pairing, but photos and clips stay. [android/README.md](android/README.md) has the rest:
driving the app from adb, releases, and the Portal's quirks.

## The web app

It's plain static files and a Node server, with no build step and no dependencies. The camera
needs a secure context, so something has to put HTTPS in front: plain `http://192.168.x.x:8080` is
refused before the permission prompt ever appears. A Cloudflare quick tunnel is the easiest option,
and it needs no account:

```bash
git clone https://github.com/sgranman/portalsnap.git && cd portalsnap
node server.js                                   # serves ./public on :8080
cloudflared tunnel --url http://localhost:8080   # another terminal: prints an https URL
```

For Docker, run `docker compose up -d` instead of `node server.js`.

**First run:**
1. **Claim the server.** It has no trusted devices yet, so it prints a claim link to its log (set
   `PUBLIC_URL` to make that link tappable). Open it on your phone, and that phone becomes the first
   device.
2. **Pair the Portal.** Open the HTTPS URL in its browser. It shows a QR code: scan it with the
   phone and tap *Let it in*.
3. **Add other phones** from `/devices.html`.

A quick tunnel's URL is public for as long as it runs, which is why every page needs a paired
device. [SECURITY.md](SECURITY.md) covers what's stored and who can reach it. `npm test` runs the
maths tests, and [test/README.md](test/README.md) covers the browser tests.

## Make your own filter

Please do: the best filters haven't been written yet. A new filter is one self-contained piece of
code that gets the faces in the picture and draws. The toolkit covers flat stickers, particles,
camera zooms, full-frame shaders, real 3D props and scenes, cut-out backdrops and synthesized sound,
and every shipped filter is a working example of one or more of them. The debug build can feed a
test portrait through the app in place of the camera, so a filter can be built and checked from a
desk over Wi-Fi adb.

There are two ways in:

- **With a coding agent:** brief it with what the effect should do and a reference video, give it
  adb access to a Portal, and let it build, screenshot, compare and iterate while you make the
  calls. Every Photo Booth rebuild in this repo was made this way.
- **By hand:** copy the nearest existing filter, give it a new id, and iterate against the test
  portrait.

[MAKING-FILTERS.md](MAKING-FILTERS.md) walks through the filter contract, the toolkit, testing,
both workflows, and sharing what you make. When yours works, open a pull request. Filters that make
kids laugh are the whole point.

## More reading

| | |
|---|---|
| [MAKING-FILTERS.md](MAKING-FILTERS.md) | how to build a filter, with an agent or by hand |
| [android/README.md](android/README.md) | the Android app: building, adb extras, releases, Portal quirks, benchmarks |
| [android/PHOTOBOOTH.md](android/PHOTOBOOTH.md) | how each Photo Booth effect was rebuilt |
| [BUILD-JOURNAL.md](BUILD-JOURNAL.md) | the web app's build journal: measurements, decisions, pairing and security design, other ways to host it |
| [SECURITY.md](SECURITY.md) | what is stored, who can reach it, and why share links need a decision |
| [THIRD-PARTY.md](THIRD-PARTY.md) | vendored MediaPipe and qrcode-generator, and credits for artwork |
| [LICENSE](LICENSE) | MIT |
