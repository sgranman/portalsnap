# Making a filter

PortalSnap is meant to be added to. Every filter in the app, from a pair of cartoon sunglasses to
a skydive through the clouds, is one self-contained piece of code that gets the faces in the
picture and draws on top of it. This guide covers the toolkit those filters are built from and two
ways to make a new one: with a coding agent doing the typing and you directing, or by hand.

- **Android app:** most of this guide. It's where new filters go, since it's fast enough for 3D,
  sound and full-frame effects.
- **Web app:** the original, and it still works. Its filters are covered at the end.

## The shape of a filter (Android)

A filter is a Kotlin object that extends `Filter`, and it goes in the `FILTERS` list at the bottom
of `android/app/src/main/java/net/sgransoft/portalsnap/Filters.kt`. Here's a whole one:

```kotlin
object ClownNose : Filter("clown", "Clown", "🤡", Mode.FAST) {
    override fun draw(d: Draw, f: Face) = inFaceSpace(d.c, f) {
        val r = f.earSpan * 0.09f
        d.pen.lift(0.05f)
        d.c.drawCircle(f.nose.x, f.nose.y, r, d.pen.fill(hex("#e8312f")))
        d.pen.unlift()
        d.c.drawCircle(f.nose.x - r * 0.35f, f.nose.y - r * 0.35f, r * 0.3f, d.pen.fill(rgba(255, 255, 255, 0.7f)))
    }
}
```

- **Constructor:** an id (used from adb and in settings), a chip name, an emoji, and a tracker tier.
- **Face space:** `inFaceSpace` puts the canvas in the face's own coordinates. The origin sits
  between the eyes, x runs along the eye line, and one unit is the distance between the eyes.
  Size and head tilt come for free, and the art scales with the child.
- **`lift`:** a soft drop shadow, so a sticker sits on the face rather than on the glass.
- **Everyone gets one:** `draw` runs once per face, up to three people on the fast tier.

### The hooks

Override only what you need.

| Hook | Runs | Use it for |
|---|---|---|
| `update(d, faces)` | once a frame, first | state, triggers, particles, springs |
| `draw(d, f)` | per face, over the picture | stickers, and camera patches (below) |
| `under(d, f)` | per face, under the stickers | flat art on the skin (blush), needs `usesUnder` |
| `scene(d, faces)` | once, under everything | a sky, a room; with `coversCamera` it replaces the camera picture |
| `overlay(d, faces)` | once, over everything | particles that belong to the frame, not to a face |
| `backdrop(d)` | segment tier only | the place a cut-out person is pasted into |
| `fx(d, faces, fx)` | with `usesFx` | a full-frame shader instead of the plain camera picture |
| `poke()` | on a tap on the picture | a manual trigger, and handy for testing |

The flags that go with them are `usesUnder`, `usesOver`, `coversCamera`, `keepsScene` (keep
drawing when nobody is in view), `usesFx` and `wantsMic`. For sound there's `voiceFrom(face)` (the
pitch that person's recorded voice is shifted to), `voiceFx` (an effect beyond pitch, such as
`VoiceFx.ROBOT`), `music` (an asset looped while selected) and
`ambience` (a synthesized sound looped while selected).

### Tracker tiers

Pick the cheapest tier that has what the filter needs. The tracker only runs the model for the
selected filter's tier.

| Tier | What it knows | Speed on a gen 1 Portal | Faces |
|---|---|---|---|
| `Mode.FAST` | eyes, nose, mouth, ears | 30 fps (the camera's limit) | 3 |
| `Mode.MESH` | 478 landmarks, named points like `chin`, `lipBottom`, `headTop`; blendshapes like `jawOpen`, `mouthPucker`, `eyeBlinkLeft`; head pitch and turn | about 16 fps | 2 |
| `Mode.SEGMENT` | a mask of where people are | about 25 fps | everyone |

Everything else about a face is on `Face`:
- **Position:** `cx`, `cy` and `angle` in frame pixels, and `eyeDist` for scale.
- **Turn:** `yaw`, from -1 to 1, on every tier; on the mesh tier also `turn`, the head's real turn in
  degrees from its pose, ready for `Matrix.rotateM` about y in `View3D`.
- **Features:** `nose`, `mouth`, `earL`, `earR`, `headTopY` and `headSpan`, in face units.
- **Expressions:** `bs("jawOpen")` and the other blendshapes.
- **Who's who:** `id`, which stays with a person while they're tracked, and `rank`/`count` for
  handling several people.

`toPixels(f, x, y)` turns face units back into frame pixels.

## The toolkit

Each of these building blocks is already used by a shipped filter, so there's a working example to
read for every one.

| To make | Use | See |
|---|---|---|
| Stickers: hats, ears, whiskers | `Canvas` through `d.pen` in face space | `Filters.kt` (Royal, Puppy, Kitty) |
| Something reacting to a face | blendshapes and `Nod` in `update` | `Monster.kt` (a nod flips the mood) |
| Zooms, bulging eyes, a face pasted somewhere | `Patch`: a region of the camera resampled into an ellipse, with `bulge` for a lens | Big Head, `Hamster` eyes |
| Reshaping a whole face | a warp shader of its own: smooth maps chained in face space, each easing to no change at its edge, scissored to the head | `Alien.kt` |
| Particles | `Particles` and `Particle` | Hearts, Hamster's crumbs |
| A full-frame look | a `FrameFx` shader in `Gl.kt` | Mirror, `PopArt.kt`, `Disco.kt` |
| Solid 3D props that turn with the head | `View3D`, a camera whose z = 0 plane lands exactly on frame pixels, plus `Mesh` and `Meshes.lathe` | `Glass3D.kt` (Lemonade), `Hamster3D.kt` |
| 3D props that go behind the head | the head pose's `turn`, and a head shape drawn into the depth buffer only, so parts behind it are hidden | `Aviators3D.kt` (Cool's glasses arms) |
| A textured model from elsewhere, rigged and animated | a Blender script that skins and poses it and writes a small binary, GPU skinning in the app, bones turned by springs | `tools/cat/build_cat.py`, `Cat3D.kt`, `CatHat.kt` |
| Bendy 3D: arms, tendrils, bodies | `TubeBuilder`, or `ColorGeo` tubes, ellipsoids, cones and boxes rebuilt every frame | `PeasInAPod.kt`, `BikeRide.kt` |
| A whole 3D world | your own camera and a renderer, `ColorGeo` meshes with colour per vertex (a forest in one draw call), a sky shader | `Park.kt` + `RideRenderer.kt`, `FreefallRenderer.kt` |
| Someone somewhere else | the segment tier and a picture in `assets/` | `Places.kt` |
| Sound effects | `Sfx`: sounds synthesized at start-up, played through `Mixer` so they're baked into clips | Hearts' piano, Freefall's wind |
| A filter with variants | `FilterGroup`: one chip in the bar, a second row to pick from | Faces, in `Filters.kt` |

A few rules of thumb that came out of building these:

- **Model solid things in 3D.** A glass, a carrot or a helmet faked with layered 2D art breaks
  the moment the head turns. Real meshes with a depth buffer and lighting look far better and cost
  little. Keep 2D for things that really are flat: blush, stars, stickers.
- **Draw 3D passes after the patches and before the stickers.** A new kind of 3D object needs a
  list on `Draw`, the same on `Plan`, copying across in `Painter`, and a renderer called from the
  3D block in `Compositor.composite`. Follow how `hamsters` or `rides` do it.
- **Measure before optimizing.** The HUD and the `PSNAP` stats line report render fps, frame
  time, tracker fps and jitter. Rough tracking on the mesh tier turned out to be the tracker's
  rate, not the renderer, and springs fixed it where a lower frame rate wouldn't have.
- **Art must be yours, public domain, CC0 or CC BY.** Credit anything you didn't make in
  `THIRD-PARTY.md`. CC BY needs its title, author, source link, licence and what you changed
  (see Cat Hat's kitten there). Wikimedia Commons' API can filter searches by licence.
- **Pictures of real people stay out of the repo.** Describe an effect in words, as
  `android/PHOTOBOOTH.md` does.

## Testing without a person in front of it

The debug build can feed a test portrait through the whole pipeline instead of the camera, so a
filter can be checked from a desk, by you or by an agent.

```bash
cd android
./tools/fetch-test-assets.sh                 # once: the test portrait (debug builds only)
./gradlew assembleDebug
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/psnap.apk
adb shell pm install -r -g /data/local/tmp/psnap.apk

A="adb shell am start -n net.sgransoft.portalsnap/.MainActivity"
$A --ei faces 1 --es filter clown --ez hud true   # the test portrait, your filter, the HUD
$A --ei faces 2                                    # two people
$A --ef jaw 0.8                                    # pretend the mouth is open (mesh tier); negative clears
$A --ef turn 30                                    # pretend the head is turned 30° (mesh tier); 999 clears
$A --ef rock 25                                    # tilt and sway the test portrait 25° each way; 0 stops
$A --es action poke                                # a tap on the picture
$A --ei faces 0                                    # back to the real camera

adb exec-out screencap -p > shot.png               # what's on screen
adb shell screenrecord --time-limit 4 /data/local/tmp/clip.mp4   # motion
adb logcat -s PSNAP                                # stats lines: render fps, frame time, tracker fps
```

- **Sound in clips:** `$A --es action record`, then `stop`, then copy the draft out of the app's
  cache with `adb exec-out run-as net.sgransoft.portalsnap cat cache/<vid-….mp4>`. Discard it with
  `again`.
- **Holding still:** for a filter that follows how close you are, add a debug extra that holds the
  distance, like `--ef rideDist` and `--ef fallDist` do, since the portrait never moves.
- **Wake the Portal first:** `adb shell input keyevent KEYCODE_WAKEUP`, then
  `adb shell input tap 640 60`. An app started behind the screensaver never gets the camera, and
  every stat reads 0.

`android/README.md` has the full list of extras, the bench, and the Portal's quirks.

## Making one with a coding agent

Every Photo Booth effect in this repo was rebuilt this way. A person described or recorded what
they wanted and made the calls. A coding agent (Claude Code, in this case) wrote the code, built it,
put it on a Portal over Wi-Fi adb, took screenshots and recordings, compared them against the
reference, and went round again.

### Set it up

1. **Give the agent the device.** Turn on ADB on the Portal (Settings → Debug → ADB Enabled, on
   current firmware), connect it over USB once and run `adb tcpip 5555`, then
   `adb connect <portal-ip>:5555`. After that the
   agent can install, screenshot, record and read logs without anyone touching the Portal.
   Wi-Fi adb lasts until the Portal reboots.
2. **Give it the toolchain:** JDK 17, the Android SDK and `ffmpeg`. The agent uses `ffmpeg` to take
   video apart.
3. **Point it at the docs:** this file, `android/README.md` (quirks and extras) and
   `android/PHOTOBOOTH.md` (worked examples).

### Brief it

A good request says what the effect does and hands over the evidence:

> Build a new filter for the Android app: a snorkel mask. The face shows through the glass,
> which fogs up when the mouth is closed and clears when it opens, and bubbles rise from the
> snorkel. Here's a video of the effect I'm copying: `~/Videos/snorkel.mp4`. Model the mask and
> snorkel in 3D. Test on the Portal at 192.168.1.50 with the test portrait. Ask me anything you
> can't tell from the video before you start.

Things worth deciding up front, or letting the agent ask you about:
- **Trigger:** what sets things off, like a wide-open mouth, a nod or a tap.
- **Sound:** silent, synthesized effects, or a soundtrack you've cleared.
- **Art:** drawn in code, 3D, or public-domain pictures.
- **Name:** the chip's name and emoji.
- **Groups:** whether it joins an existing group of filters.

### The loop that works

1. **Study the reference.** Pull frames with `ffmpeg` (a contact sheet at 1 fps, then 4–10 fps
   around the interesting moments, then full-size crops) and a spectrogram for sound. Write down
   what the effect does in plain words before writing code: timing, triggers, colours, what happens
   with nobody in view.
2. **Build the smallest thing that runs**, install it, and screenshot it on the test portrait.
3. **Compare side by side.** Put the screenshot and the reference crop in one image and list what
   differs. For motion, a screen recording tiled into a strip shows what a single screenshot can't.
4. **Fix the biggest difference, rebuild, screenshot again.** Each round should end with a picture,
   not a guess.
5. **Check the numbers:** frame rate and frame time from the `PSNAP` line, with one and two
   faces, and while recording.
6. **Hand it to a person.** Some things only a real face shows: tracking feel, how far away
   people really sit, whether a scream sets off the trigger. Ask them to try it and say what to
   change, and log measurements while they do.
7. **Record what was learned** in `android/PHOTOBOOTH.md`, or in comments where the numbers
   live, then commit and open a pull request.

Lessons from doing this many times:

- **Screenshots over reasoning.** Several bugs only showed up on the device, among them a sideways
  camera, a collar poking through a chin, and a helmet reading as a hard hat.
- **Don't trust synthetic tests for voices or music.** A pitch shifter that passed on a test tone
  sounded far worse on a real voice. Record a clip and listen.
- **Let the person make the calls that are taste.** Which picture, which sound, how quick, how
  far away. An agent that asks once, with a few concrete options, saves several rebuilds.
- **Keep work on a branch and commit locally** until the person has seen it. The main branch
  takes pull requests only, and CI builds the APK for each one.

## Making one by hand

Everything above applies. The difference is that you write the code and look at the Portal
yourself.

1. **Set up:**
   - **Toolchain:** install JDK 17 and the Android SDK (platform 36, build-tools 37), or Android
     Studio, and open the `android/` folder.
   - **Portal:** turn on ADB as above.
   - **Other devices:** the app is built for the Portal's camera and screen, and ordinary Android
     devices haven't been tested.
2. **Start from a neighbour.** Copy the filter closest to what you want: `Crown` for a
   sticker, `PixelHearts` for particles, `Hamster` for 3D props, `Places` for a backdrop. Give it a
   new id and add it to `FILTERS`.
3. **Iterate with the test portrait and `--ef jaw`**, then try it on real faces.

A second example, a mesh-tier trigger with particles:

```kotlin
object BlowKisses : Filter("kisses", "Kisses", "💋", Mode.MESH) {
    private val kisses = Particles()

    override fun update(d: Draw, faces: List<Face>) {
        for (f in faces) {
            if (f.bs("mouthPucker") > 0.5f && rng.nextFloat() < 0.3f) {
                val lips = toPixels(f, f.mouth.x, f.mouth.y)
                kisses.add(Particle(lips.x, lips.y, rnd(-60f, 60f), rnd(-160f, -80f), 1.2f, f.eyeDist * 0.25f, hex("#ff3d7f")))
            }
        }
        kisses.step(d.dt, gravity = -40f)
    }

    override fun overlay(d: Draw, faces: List<Face>) {
        for (p in kisses.list) {
            d.c.drawCircle(p.x, p.y, p.size * p.fade, d.pen.fill(p.color))
        }
    }
}
```

Both examples in this guide compile against the current code.

## Filters in the web app

The web app's filters are plain objects in `public/filters.js`, listed in `FILTERS` at the bottom:

```js
const clown = {
  id: "clown", name: "Clown", emoji: "🤡", needsMesh: false,
  draw(ctx, face) {
    inFaceSpace(ctx, face, c => {
      const r = face.earSpan * 0.09;
      c.fillStyle = "#e8312f";
      c.beginPath();
      c.arc(face.nose.x, face.nose.y, r, 0, Math.PI * 2);
      c.fill();
    });
  },
};
```

- **Tiers:** `needsMesh: true` asks for the mesh tier and `needsSegment: true` for the person mask.
- **Voice:** `voice` pitches the recorded voice.
- **Hooks:** `draw(ctx, face, t, video)` can take the time (for animation) and the video (to
  resample it). `scene(ctx, faces, t)` paints once under everyone.
- **No build step:** edit the file and reload.
- **Tests:** `npm test` runs the maths tests. `test/filters.mjs` renders filters over a synthetic
  face in a real browser and checks where the ink lands (see `test/README.md`).

The web app runs in the Portal's browser, which is much slower: 2D stickers are comfortable there,
and 3D scenes aren't.

## Sharing it

Open a pull request against `main`. A good one includes:
- **Where it came from:** a sentence or two on what the filter does and, if it recreates something,
  the reference.
- **Checked on a Portal:** screenshots or a clip, with frame rate on the fast tier or whichever
  tier it uses, one and two faces.
- **Credits:** anything you didn't make, in `THIRD-PARTY.md`.
- **CI:** the green "Android APK" check, which builds every pull request.

Filters that kids light up at are the whole point, so if you make one, please share it.
