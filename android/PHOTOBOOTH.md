# Photo Booth catalog

Meta's Portal Photo Booth app was removed on 2025-04-04. Its effects were Spark AR effects
downloaded from Meta's servers, so nothing survives on the device. This catalog rebuilds them
from memory, working from frames of videos the kids recorded with it. The effects are
described here in words only: the source videos are family recordings, kept on the NAS, and
are not part of this repo. Each entry names the source video by the ID the Portal gave it, so
the clip can be found again.

None of Meta's assets are reused. Every effect gets new art, drawn in code like the existing
filters where that works, or a supplied image where a photographic look is the point.

## At a glance

| # | Working name | Source video | Tracker tier | How it's built | Effort |
|---|---|---|---|---|---|
| 1 | Mirror | 1603104213652867 | none | shader | small |
| 2 | Disco Dots | (screenshot 103638) | none or segment | shader | small |
| 3 | Pop Silhouette | 946857547560491 | segment | beat-switched looks: mask shaders + Canvas doodles + mask history | medium–large |
| 4 | Monster / Cutie | 936589334645536 | mesh + blendshapes | Canvas doodles, switched by expression | small–medium |
| 5 | Pixel Hearts | 483283294836303 | mesh + blendshapes | Canvas sprites + particles from an open mouth | medium |
| 6 | Hamster | 589562750311569 | mesh | eye-enlarge warp patch + soft "3D" props | medium |
| 7 | Pink Palace | (screenshot 103721) | segment | person cut-out over a picture of a chosen place (built as Places) | small, plus art |
| 8 | Lemonade | (screenshot 103801) | mesh | face patch warped into a glass, tinted and blurred | medium |
| 9 | Peas in a Pod | 1434519407443964 | mesh | the face cloned into three peas | medium |
| 10 | Bike Ride | 3745986732290546 | fast | real 3D park, riders and helmets, face on the head (built) | medium–large |
| 11 | Freefall | 897386285564659 | mesh + blendshapes | real 3D diver and helmet, sky and ground shader, falls on an open mouth (built) | large |

### Status (2026-09-14)

Effects 1–6 are built (`PhotoBooth.kt`, the `FX_*` shaders in `Gl.kt`, `MicHub.kt`). They're
the first six chips: Mirror, Pop Art, Disco, Monster, Hearts, Hamster.

Tested on the gen 1 Portal with a real person:

- **Monster / Cutie:** a nod flips between them, and the voice pitch follows. A nod is a head
  pitch swing of more than 10° that comes back within 1.1s.
- **Disco and Pop Art:** both react to music in the room. With no beats they fall back to idle
  timing: Disco keeps its looks moving on a 612ms clock (it has since been rebuilt as Disco Star;
  see below), and Pop Art changes look every 2s.
- **Recording:** works with a mic-reactive filter on.

**Hamster** is so far checked only on the test portrait. The eyes use a new `bulge` lens patch
(2.2x at the centre, easing to no change at the rim). The props are drawn with gradients. The
two open questions above were answered by guessing, so check both against the videos:

- **Nibbling:** yes. The carrot rides the lower lip and wiggles while the mouth is open. At the
  user's request it also gets eaten: each open-then-close takes a scalloped bite and drops
  crumbs, three bites finish it, and a fresh one pops in 1.3s later.
- **Cheek puff:** no warp yet. A pair of gentle `bulge` patches on the cheeks would add one.

**Peas in a Pod** (`PeasInAPod.kt`, the "Peas" chip) was first built in 2D, with circular face
patches in a Canvas pod. After the user's review it was **rebuilt in real 3D** from its
reference video (1434519407443964). What the video showed:

- **No face:** a short pod, closed round one plain pea, the lips meeting in a V and a seam below.
- **A face arrives:** it lands on that pea. About 0.7s later the pod stretches and a second pea
  pops in, and a third about 3s after the first. Losing the face snaps back to the closed pod.
- **Faces:** every pea shows the live face at once, with no delay between them. The face sits in
  a rounded window about half the pea's width, brow to chin, with pink blush on the cheeks.
- **Tendrils:** each pea wears different yellow-green tendrils: hands meeting under the chin on
  the top pea, arms reaching out over the pod's lips on the middle one, and on the bottom one
  arms reaching in from the sides with Y-shaped hands spread over the eyes (peekaboo). Beside each
  face a little heart made of two leaves grows, holds a moment, poofs away and grows back
  nearby. At the user's word, the hearts are about as big as the bottom pea's old static one. A first guess drew lashes from the nose outward, which the user
  rightly called weird.
- **Scene:** a flat lime ground (#c4e665), white stars twinkling in and out (more round the
  shadow), and a soft shadow under the floating pod. The pod turns and slides a little with the
  head.
- **Sound:** only voices. The original has no creak or other effects.

How the 3D version works (the same pass as Lemonade's glass, `Pod3D` in `Draw.pods`):

- **Pod:** one grid mesh bent into shape in the vertex shader: pointed ends, a pinch between
  peas, an opening that runs down past the last pea and closes into a seam, a thickness, and
  rolled lips. Its length is a uniform, so it stretches on a spring as peas arrive. Normals come
  from neighbouring points, so the lighting follows every bulge.
- **Peas:** spheres that pop in past full size and settle, bob and wobble. The face is laid on
  each sphere's front in its own coordinates, so it turns with the pea.
- **Tendrils:** round-ended tubes rebuilt every frame from a few control points, so hands wave,
  arms swing and the peekaboo hands peek.
- **Kept from the 2D version:** the cradle rock the user asked for (now ±5° on a 3s swing), and
  two people taking turns down the pod.
- **Cost:** 30fps on the test portrait, frames about 11ms, paint about 5.5ms.
- **No sound:** the 2D version creaked at each end of the swing. The user found it bad, and
  after hearing synthesized stick-slip replacements (cradle, rope, hinge, floorboard, knock)
  asked for no creak at all, which matches the original.

Every app sound, effects and music alike, plays through `Mixer.kt`. The mixer feeds the speaker
and also adds the same samples to recordings after the voice effect, matched to the mic's
capture times. So clips carry the sounds cleanly and at their own pitch, instead of the faint
copy the mic hears in the room. Beat detection hears the mix directly too.

**Lemonade** (`Lemonade.kt`) is checked only on the test portrait. It was rebuilt after the
details above:

- **Motion:** the glass chases the head around the screen on a spring and leans against its
  own motion.
- **3D pose:** it turns and tips with head yaw and pitch through an `android.graphics.Camera`.
  Its contents sit on planes at different depths (far wall, ice, face, straw, near highlights),
  so they shift against each other as it turns. The rim and liquid surface open up as it tips.
- **Face:** the patch is projective. `Patch.local` takes a frame pixel into the glass's plane,
  where these apply before the tint, blur and ripple:
  - the tumbler mask
  - a cylindrical lens that magnifies the middle of the face and squeezes the sides
- **Ice:** it floats free. It slides with the tilt, lags the glass's acceleration, bounces off
  the round wall and off other cubes, and clinks on hard hits with three synthesized clinks,
  baked into clips. The straw sways on a spring.
- **Mouth:** the open question was guessed: yes, it reacts. A pucker, funnel or wide-open jaw
  blows bubbles up the straw with a bloop. A gentle fizz runs all the time.

Polish pass after the user's review:

- **Losing tracking:** it no longer drops back to the camera. The new `Filter.keepsScene` has
  the Painter keep drawing the scene with no faces. The user's second review settled it: the
  glass disappears and only the background stays.
- **Face (second review):** the lemonade is made of the head. The patch samples just the
  features, centred a little under the eye line, and the shader maps glass units through
  `sin(g·π/2)`. So the features sit magnified in the middle and the face smears out to every
  edge (the slope is 0 at the rim), with no room showing. The patch is opaque, so the straw
  doesn't show under the liquid.
- **Liquid surface:** an opaque lid. The face mask's top follows the lid's front arc
  (`Patch.surface`), so the face never shows above the lemonade when the glass tips toward the
  camera. The lid is drawn on the under layer, which lets the ice's face reflections show over
  it.
- **Glass front:** stronger reflections, meaning a broad soft band with a crisp streak on the
  left, a fainter band on the right, and a sheen along the bottom.
- **Ice:** real 3D blocks with soft rounded corners (`CornerPathEffect`). Eight corners are
  projected through the glass pose at their own depths, with clear faces drawn far to near, lit
  from above. A shine streak and a glint sit on the top face, and a mirrored face patch sits in
  the nearest side. They aren't clipped, because a clip in the middle plane sliced their tops
  off when the glass tipped.
- **Straw:** longer, and showing only above the lemonade.
- **Lemon:** a realistic slice with a waxy rind, a pith ring, ten translucent segments with
  juice streaks, a pale centre, and a gloss.

**Rebuilt in real 3D** (`Glass3D.kt`), after the user pointed out that the layered 2D version was
cheating in ways that showed: tilted flat cards, a rim ellipse tuned by hand that drifted from
the tilt, and draw order standing in for depth. It now works like this:

- **The pass:** a GLES 3 pass inside the composite, between the face patches and the over layer,
  into the composite framebuffer with a depth buffer. `View3D` is a perspective camera whose
  z = 0 plane lands exactly on frame pixels, so 3D lines up with the 2D layers.
- **Meshes:**
  - **Glass:** a revolved outline with a real wall, a rounded lip and a thick rounded base.
  - **Liquid:** a volume, cut in the shader by the surface plane. The inside of the far wall
    under the cut is shaded as the surface, at the depth where the view ray actually crosses the
    plane, so ice and straw meet it correctly.
  - **Others:** rounded-box ice cubes, a straw tube and a lemon disc.
- **Face on the liquid:** each point on the liquid looks up the face by its angle around the
  glass as seen from the eye, through `sin`, so the features sit magnified in the middle and the
  face smears to the silhouette. It's muted, cast pink, lightly blurred and rippled.
- **Glass and ice shading:** see-through, brightening toward the edges, reflecting a small
  studio: a warm room, a bright backdrop behind that gives pale edges, and a tall soft light
  for a side streak. The ice reflects the face (mirrored) by its reflection direction, with a
  glint.
- **Pose:** from the head. Roll comes from the eye line, the turn from yaw, and the nod from the
  pose matrix (tipping the head forward tips the glass's top toward you), on a 24° base tip.
- **Surface:** tips with the glass and leans with its acceleration. A level-in-the-world surface
  was tried, and it hides itself whenever the glass sits above the camera's eye line.
- **Kept from before:** the spring, sloshing, clinks and bubbles. Bubbles are placed on the
  liquid's front in 3D and drawn flat over the pass.
- **Fallback:** if the pass throws (for example a shader the driver rejects), it logs once and
  turns 3D off rather than failing every frame.

## The effects

### 1. Mirror
The left half of the picture reflected onto the right, so one child becomes a symmetrical pair
facing each other, meeting at the centre line. The whole frame is mirrored, room included.
- **Build:** one line of shader in the composite pass, `uv.x = 0.5 - abs(uv.x - 0.5)`, applied
  to the frame before stickers. Variants are nearly free: top/bottom, or a four-way
  kaleidoscope.
- **Question:** was there only a left/right mirror, or several?

### 2. Disco Star
First catalogued from a screenshot as "Disco Dots": the whole picture washed purple-magenta
under a grid of twinkling LED-like dots. The reference video (606034295553604) showed that was
only one of three looks. It cuts straight between them every two bars of its music, about 4.9s,
in a loop: dots, star, lasers.

- **LED wall:** round lights, about 50 across, each showing the colour of the picture under it,
  over a dimmed, tinted view of the room. Star outlines in brighter lights follow the person's
  head and burst outward from it, one after another. The colour walks amber, pink, purple, red
  over the look.
- **Laser star:** a star made of laser beams. A white glowing aura shines from behind it, so its
  edges read clearly, but the middle of the star is see-through because it blocks the light.
  The light is smoky, with a rainbow cast.
- **Laser tunnel:** the room under purple and orange haze, and a set of neon triangles coming
  out from one in the middle, growing, built with the same laser look.

My first build misread the star and the lasers from the 6fps frames. It filled the star with
candy clouds and a rainbow fringe, and scattered triangles, Vs and beams. The user corrected all
three looks. The descriptions above are the corrected versions.
- **Sound:** its own music with voices over it. There's a steady bass and chord, then a chopped
  rhythmic section, then the chord again. It repeats every 2.45s (a bar at about 98bpm), and the
  whole loop is about 24.45s. The one full copy has singing over part of it.
- **Soundtrack, extracted:** `assets/music/disco-loop.wav`.
  - **Loop:** 1,078,045 samples (24.4455s, ten bars of about 98.2bpm). It's cut from 1.5s into
    the clip, where the loop's repeat matches best (0.90 correlation).
  - **Patch:** the sung stretch, 10.5–15.0s, is replaced with the same music two bars later,
    aligned to the sample (216,000 samples on), with 80ms equal-power crossfades. Its bass line
    matches the original there at 0.86–0.99.
  - **Seam:** the loop's head is crossfaded from what followed its end in the clip.
  - **Grid:** the first downbeat is 0.465s in. Looks cut every eight beats of that grid.
  - **Status:** the user cleared it with a copyright checker, so it ships in the repo.
- **Recording:** the original recorded at about 6fps. It was heavy.

**Build** (`Disco.kt`, `FX_DISCO`):

- **Shader:** one frame shader draws the room, the lights and the smoke.
  - **LED wall:** samples each cell's centre and lights up to three expanding star outlines
    around the head.
  - **Laser star:** shades everything outside a signed-distance pentagram (inner corners at
    0.382) with smoky light and a rainbow cast, and leaves the inside clear. At the user's request
    the smoke circles the star in uneven spiral arms, counter-clockwise on the mirrored screen
    (so clockwise in recordings), and is 50% stronger than the first pass. It pours out of one
    side of the star at a time, about a third of the way round, and that side travels round the
    same way about once a look. A faint thin glow keeps the rest of the edge visible.
- **Canvas:** the laser lines are layered strokes (a wide soft glow, a tighter glow, a pale core).
  - **Star:** ten beams along the star's outer edges, from each inner corner out past its tip,
    fading there, at the same angles as the shader's star, so beams and blocked light line up.
    Nothing crosses the middle: five full crossing beams read as a pentagram, and the user
    asked for them to go.
  - **Tunnel:** triangles grow from the screen's middle, thickening as they come.
- **Beats:** while the soundtrack plays, from its grid (below). Each one starts a new LED burst
  or tunnel triangle, and looks cut every two bars. Without it, beats come from the mic, with a
  611ms clock standing in.
- **Cost:** 30fps on the test portrait, frames about 6.5ms, paint about 1.3ms.

### 3. Pop Silhouette
Pop art: the person becomes a flat silhouette filled with an orange-to-yellow gradient, and the
room behind turns flat red, with only a ghost of the room's texture left.
- **Build:** segmentation tier, with no art needed. The mask selects a gradient fill for the
  person and a red wash over the darkened camera for the background, mixed at a soft edge.
- **Questions:** did the colours cycle over time (red/yellow, then blue/pink…)? Did the gradient
  follow the body?

**Reference video 946857547560491** (30s, looked at in frames at 4fps; the effect is described
here, not the person). It's far more than a colour swap. It cuts between looks on the music's
beat, each lasting 0.25–2.5s, in an order that doesn't repeat with the music's loop.

- **Backgrounds:**
  - Mostly a red grunge-paper texture, with a faint diagonal print and big ghosted shapes.
  - For a stretch of about 6s (12–18s), a near-black grunge texture instead. It arrives through
    a particle break-up and leaves through a red torn-paper brush wipe.
- **Person looks** (all from the segmentation mask):
  - **Natural cut-out:** the camera image with a soft dark edge.
  - **Flat yellow silhouette:** with a dark drop shadow offset down and to the left.
  - **Gradient silhouette:** yellow to orange, sometimes with a pale offset rim.
  - **Grain-textured silhouette:** orange-brown, with a grunge or halftone fill.
  - **Ghosted:** darkened into the red (multiply), features faintly visible. Sometimes a patch
    of yellow halftone dots sits on the head.
  - **Translucent yellow:** features show through.
  - **Dark brown silhouette:** with a bright yellow offset rim.
  - **Sticker:** the natural cut-out with a grey paper border offset behind it (mostly on the
    dark background).
- **Overlays and treatments:**
  - **Hand-drawn white rings:** single, double or dashed, drawn on near the head.
  - **White sweeping arcs** and scribbles.
  - **Thin wiggly lines:** yellow-orange or white, tracing along the silhouette's edge.
  - **Motion echoes:** trails of the last few frames, in orange/yellow or dark grey.
  - **Face texture:** a glittery dissolve over the face.
  - **Outlines:** fiery orange glow with flame flecks, or a thin cyan line.
  - **Transitions:** the silhouette breaking into black particles, and red brush-stroke wipes.
- **Music:** a loop of about 9.94s (438,453 samples at 44.1kHz), repeated three times in the
  clip, pulsing about every 0.94s. Onsets in the first loop: 0.02, 0.27, 1.21, 2.10, 3.08, 4.02,
  4.65, 5.28, 6.21, 7.15, 8.08, 9.05, 9.57, 9.80s. A clean-up (the middle value of the three
  aligned repeats, a 50ms seam crossfade) is in the session's scratchpad and on the Portal at
  `files/popart-loop.wav`. It's Meta's music, so it stays out of the repo. About half the
  recording's level didn't repeat (voices or room), so its quality needs listening to.
- **Build notes:**
  - It's a sequence of "looks" switched on beats.
  - The segmentation mask gives the silhouette and edge.
  - A per-look shader covers fill, texture, multiply, offset shadow and rim.
  - Canvas draws the rings, arcs and scribbles, animated as drawn-on strokes.
  - A small history of mask frames gives the motion echoes.
  - Grunge textures would be procedural noise.
  - The music plays through `Mixer`, so it drives the beats and is baked into clips.

**Built** (`PopArt.kt` plus the `FX_POP_ART` shader), all three passes. It runs at 30fps with
frames taking about 5ms, and so far is checked on the test portrait only.

- **Soundtrack:** the user cleared the extracted loop with a copyright checker, so it's shipped
  in `assets/music/popart-loop.wav`. `Filter.music` loops it through `Mixer` while the filter
  is selected and the app is in front. `Soundtrack.startedNs` gives the loop's clock, so cuts
  land on the loop's measured onsets. Without it, cuts follow mic beats, then a steady clock.
- **Director:** each strong beat may move to a new look (weighted, never the same twice).
  After about 10–15 beats on red it breaks up to dark for 6 beats, then brush-wipes back. It
  also rolls extras: an outline, echoes, the dissolve, the halftone cap, arcs and scribbles. Any
  beat may draw a ring.
- **Shader:** all eight looks, both grungy grounds, shadows and rims, the sticker border, motion
  echoes from three 90ms-apart mask textures, the glitter dissolve and halftone cap placed on
  the head, fire and cyan outlines, and both transitions. The mask gets a wide blur and a
  smoothstep, so the low-resolution edges come out clean.
- **Canvas:** rings, sweeping arcs and edge scribbles. They're placed from the silhouette's
  upper outline and head, read from a 0/1 copy of the mask that the compositor hands the
  Painter. Segment-tier filters can now draw an overlay.

After the user's first look:

- **Moving textures:** the grain fill, halftone cap, glitter dissolve and a moving grain over the
  ghost, sheer and gradient looks all slide in a direction the director picks on each strong
  beat (`uDrift`).
- **Squiggles:** loopy white lines drawn right across the screen. The pen's curls are wide and
  close enough together to actually loop. They're drawn on from one side with the tail
  following. Rings and arcs are rarer to make room.
- **Tighter cut-out, for every segment filter:**
  - The segmenter now returns confidence, not categories.
  - The compositor smooths the mask over time and decides once, on clear evidence, whether
    the model's mask comes out inverted.
  - The shaders snap the low-resolution edge to the picture: the mask neighbourhood is weighted
    by camera-colour similarity, then thresholded a little past halfway.
- **Beach, Palace, Moon:** they had never set `usesUnder`, so their scenes weren't painted
  (black since the native build). Fixed along the way.

**Pop Art: done for now (2026-09-14).** The user signed off after these changes, all described
in the README's Portal quirks:

- the segmentation crop around the person
- the 512x288 mask grid
- frame-synced cut-outs at about 28fps on the test portrait
- a note that the multiclass model is too slow (about 830ms a frame on the CPU)

If hair edges ever need more, the next step is running the model through TensorFlow Lite's own
GPU delegate.

### 4. Monster / Cutie (one effect, two moods)
Doodle face paint in two moods. It was first built from two frames. At the user's request it was
redrawn from the full reference video (936589334645536), which showed the following.

- **Monster:**
  - Cream horns with brushy black outlines, tips leaning outward.
  - Thick tapered black brows sloping down to the nose, with a small frown crease between them.
  - A big black pupil with a white glint over each enlarged eye, and a thin line under it.
  - Black X marks on the outer cheeks, with a bold pink zigzag scribble for blush.
  - Closed mouth: a brushy frown line with five white fangs hanging from it.
  - Open mouth: a rounded black cup with fangs top and bottom, and a long pink tongue with a
    crease and a glint drooping past the chin.
- **Cutie:** soft white round ears with pink insides, grainy pink hearts on the cheeks, three
  short black dashes at each eye's outer corner, and enlarged eyes.
- **Both:** the eyes are morphed bigger.
- **Poof:** switching plays a poof sound. A pink cloud of circles swells over the face, opens into
  a ring with the new look already showing through, and breaks into pink specks that drift up and
  fade, about 0.7s in all.
- **Voice:** both are pitch-shifted. Measured from harmonic spacing, the monster is about 170 Hz and
  the cutie about 500 Hz, so about 0.6x and 1.7x of a child's voice. The monster keeps strong
  harmonics up to 2 kHz, so the formants stay put while the pitch moves.
- **What flips it:** in the clip, the switches follow the head turning away and back.

**Build** (`Monster.kt`):

- **Units:** the art is measured in pupil units from the reference, and drawn scaled by the
  face's own pupil distance. The first pass used face units, whose unit is the outer eye corners,
  and came out 1.5x too big.
- **Eyes:** a bulge lens over each eye in both moods, as Hamster's.
- **Strokes:** brows, crease and lip line are filled brush strokes tapering to points.
- **Switch:** a nod or a tap. The new look appears 180ms in, once the cloud covers the face, then
  pops. The trigger was left as the nod; turning away and back, as in the clip, would be a small
  change.
- **Sound:** a synthesized soft puff (swelling filtered noise over a faint low thump). A first try
  with bubbly tones and a chime sounded metallic to the user.
- **Voice: tried and reverted.** A formant-preserving TD-PSOLA shifter was built to match:
  pitch detected on a 16 kHz copy, two-period grains centred on each pulse, and ratios of 0.6 and
  1.7. On a synthetic vowel it held the formants where the granular shifter drags them (880 Hz to
  670 Hz at 0.6x). On the user's real voice it sounded far worse than the original. So the
  original granular `PitchShifter.kt` and its 0.72 and 1.6 ratios are back. A synthetic test isn't
  enough for voice work; any next attempt needs a real recorded voice to compare against first.

### 5. Pixel Hearts
8-bit pixel-art hearts. Small yellow hearts sit on the cheeks, and when the mouth opens a
stream of multicolour pixel hearts (red, pink, blue, yellow) pours out of it and tumbles down.
It works on two children at once.
- **Build:** mesh tier with blendshapes. Heart sprites are drawn as pixel grids, crisp with no
  anti-aliasing. Each track gets a particle emitter at its lower lip, gated on `jawOpen`, with
  gravity, a little spread and a fade. Particles live in frame space, so they keep falling
  after the head moves.
- **Question:** did the hearts come out only while the mouth was open, or was the stream always
  on and just stronger when open?
- **Sound (added at the user's request, 2026-09-15):** when the hearts start pouring out, a quick
  run down a bright little piano plays: E6, D6, C6, A5, G5, 75ms apart, ringing over each other.
  It's synthesized in `Sfx` (`piano`) from a few stretched partials and a hammer tick. It plays
  again every 1.6s while the mouth stays open, and it's baked into clips. The reference video's
  own notes couldn't be picked out from under the voices, so this is a match by ear.

### 6. Hamster
A hamster or mouse face:
- round fuzzy brown ears with pink insides
- a glossy pink 3D-looking nose
- blush cheeks and white whiskers
- noticeably **enlarged eyes**
- a carrot held in front of the mouth by two small pink paws, with green leaves under it

It works on two children at once.
- **Build:** mesh tier.
  - **Eyes:** a patch per eye, a radial magnify like Big Head's, centred on each eye.
  - **Props:** drawn with radial gradients and highlights so they read as soft 3D.
  - **Carrot and paws:** anchored below the mouth, moving with it.
- **Questions:** did the carrot move, as if being eaten or nibbled when the mouth moved? Were the
  cheeks puffed too, as a warp?

**Props rebuilt in real 3D** (`Hamster3D.kt`) from the reference video (589562750311569), where
they look like soft 3D renders:

- **Ears:** half moons at the top corners of the head: tall fuzzy brown cups, cut flat where they meet
  the head, with pink linings turned in toward the face. The fur is fine, soft and fuzzy at its
  edges.
- **Nose:** a glossy pink rounded triangle.
- **Carrot:** chubby, with ridges, and a three-leaf clover at its wide end. The bites cut
  scalloped chunks off its tip in the shader, showing the paler inside.
- **Paws:** each a mitten of three fat, rounded fingers, stacked and bunched, curling over the
  carrot's front, with a pink palm behind and a fuzzy brown back showing at the outer edge. The
  user rejected the first egg shapes. They keep holding the leaves once the carrot is gone.

All of the props are meshes in the View3D pass, sharing one shader with fur speckle, gloss and
ridges. The blush (wide soft pink ovals) and the whiskers (two short ones a side) stay flat,
drawn under the props. The bite mechanics are unchanged. On the test portrait it runs at 30fps,
about 10ms a frame.

### 7. Pink Palace
The room replaced by a dreamy pastel-pink architectural interior: arched windows, a staircase,
bookshelves on a mezzanine, a vase of flowers. The children appear as cut-outs pasted into it,
head and shoulders, with slightly rough blob-shaped edges and a faint pink colour grade.

**Built as Places** (`Places.kt`, the "Places" chip). At the user's request it became a set of
places rather than the one palace, which isn't among them:

- **The places:** Castle, Forest, Waterfall, Circus, Yacht, Beach, North Pole and Moon. They're
  picked from a second row of chips shown over the bottom of the picture while Places is on,
  and the choice is remembered between launches.
- **Pictures:** public-domain and CC0 images from Wikimedia Commons, cropped to the frame
  (1280×720) so each subject sits beside where the person stands, and credited in
  `THIRD-PARTY.md`. The first set mixed photos, paintings and posters. The user kept Castle and
  Forest and asked for scenic real photos for the rest, choosing each from a shortlist of four.
  - **Castle:** Neuschwanstein, a photochrom print from about 1890–1900.
  - **Forest:** a sunlit forest path.
  - **Waterfall:** a waterfall on a mossy Icelandic mountainside.
  - **Circus:** the Chimelong International Circus arena, lit with fountains of sparks.
  - **Yacht:** a sailboat heeling at sunset off a rocky coast.
  - **Beach:** a row of colourful beach huts.
  - **North Pole:** an arctic fox in the snow.
  - **Moon:** Earthrise over the Moon's horizon, from Apollo 8 (NASA).
- **How:** the segmentation tier's person mask pastes the camera over the picture, which is
  drawn into the under layer as a bitmap. Pictures decode on first use, and the last two stay
  loaded.
- **Replaced:** the old drawn Beach, Palace and Moon backdrops are gone, and so is the cartoon
  Skydive filter, which Freefall supersedes.
- **Testing:** `--es place moon` picks a place.

### 8. Lemonade
The face appears inside a glass of pink lemonade, seen through the liquid: tinted pink, softly
blurred and slightly stretched to the glass's shape. The glass has ice cubes, a straw and a
lemon slice on the rim, and floats on a flat coral-to-pink gradient. The room is gone entirely,
and the glass tilts with the head.

The user added details from watching the videos:

- The glass is actually 3D. It tilts, and the things inside move around.
- It stretches the face.
- It moves around the screen as the face moves.
- The ice makes clinking sounds.

From reference video 919589313478329 (30s, frames only; the effect is described here, not the
person):

- **Glass body:** a clear 3D tumbler about half the frame's height. It's slim, with nearly
  straight sides and a slight taper, and tilts about ±20°.
- **Outline:** thick walls with a bright cream outer edge, a fainter inner edge, and a thin
  sheen down each side.
- **Rim:** a rounded lip all the way round, pale, and brighter along the front.
- **Bottom:** heavily rounded corners on a thick clear base. The bottom of the liquid shows as
  a lighter oval above it.
- **Lemonade:** a muted, dusty mauve-pink, filled almost to the rim. The face shows through it
  softened and a little tall.
- **Props:** grey ice cubes with bright tops stand above the liquid at the rim. There's a thin
  plain dark-red straw at the back right, and a big lemon slice hooked on the left rim, behind
  the front of the lip.
- **Background:** an orange-and-pink gradient whose glow drifts around.
- **Build:** mesh tier, no camera background.
  - **Face:** a face patch warped into the glass's interior (an affine plus a slight vertical
    stretch), tinted and blurred in the shader.
  - **Art:** the glass drawn in two Canvas layers, the back rim and ice under the face, the
    front glass highlights and straw over it.
- **Question:** did it bob, bubble or slosh, or react to the mouth, like blowing bubbles through
  the straw?

### 9. Peas in a Pod
A green pea pod standing upright on a lime-green background with twinkling white stars. Inside
are three round peas, each with **the child's face** on it, with pink blush and little
leaf-like arms. One pea has the face with antennae or leaf eyebrows. The pod casts a soft
shadow.
- **Build:** mesh tier, no camera background. It's one face patch drawn three times into three
  circular pea shapes, with pod, peas, arms, stars and shadow drawn in Canvas. Each copy could
  be offset in time for fun, so the peas are a beat apart.
- **Questions:** did the three peas bob independently? Did two children fill different peas?

### 10. Bike Ride
**Built in real 3D** (`BikeRide.kt`, `Park.kt`, `Geo3D.kt`, `RideRenderer.kt`; the "Bike" chip)
from its reference video (3745986732290546). What the video showed:

- **Camera:** it backs down a straight path ahead of the rider, who pedals toward it. The park
  recedes toward a vanishing point at mid-height at about 4 m/s: a lamp post halves in size in
  0.9s. The path never curves, and turning the head doesn't steer.
- **Park:** low-poly, at sunset.
  - **Trees:** faceted three-tier pines with dark undersides, and trees with bendy trunks whose
    branches end in balls of leaves.
  - **Ground:** lime bushes, a grey path with orange edging and a brick walk on each side, and
    dark grass.
  - **Props:** benches and blue bins on the left; lanterns on posts and green recycling bins on
    the right.
  - **Sky:** grey-lilac overhead to peach and pink at the horizon, with a hazy pink city behind
    the trees.
  - **Blur and light:** the ground and the near edges are motion-blurred; the rider isn't. A low
    sun on the right lights the trees' right-hand sides and rims the rider.
- **Rider:** a cartoon kid with a big head.
  - **Helmet:** white and vented, with a dark lower band, and dark straps down the cheeks to a
    buckle under the chin.
  - **Outfit:** a teal long-sleeved jersey, navy trousers, light-blue gloves and yellow shoes, on
    a blue bike.
  - **Face:** it sits under the brim, forehead to chin, with a soft edge at the cheeks.
- **Following the head:** the rider's place on screen follows the face, and their size follows
  the face's size. Leaning into the Portal brings the rider up close, with the handlebars at the
  bottom of the frame; sitting back sends them down the path. Stepping out of frame leaves the
  empty path rolling.
- **Sound:** none. The clip has only voices.

How it's built:

- **Park:** `Park.build` lays out an 80m stretch in two variants, each one mesh with a colour on
  every vertex (`ColorGeo`), so a whole stretch of forest is one draw call. The renderer lays four
  stretches end to end, nearest first, alternating variants, and scrolls them away. The scroll
  wraps at 8km.
- **Ground:** one big quad patterned in the shader in park coordinates:
  - asphalt, the edging and grass
  - bricks that fade to their average colour where they get too small to draw
  - soft tree shadows drifting across
- **Sky:** a full-screen shader with the gradient and two rows of city towers, taller toward the
  middle.
- **Blur:** the park renders into its own framebuffer, then into the composite through an 8-tap
  zoom blur away from the vanishing point, strongest low down and at the edges.
- **Rider:** rebuilt every frame from tubes and ellipsoids in world space:
  - **Bike:** tyres, spokes that turn with the scroll, and cranks turning 1.2 times a second.
  - **Body:** a torso from the saddle to under the chin, with a shoulder yoke.
  - **Limbs:** two-bone arms to the grips and legs to the pedals.
- **Head:**
  - **Helmet:** a shell mesh whose vents, band and dark inside are drawn by its shader. It's
    tipped 14° forward on its brim so its top shows.
  - **Face:** a gently domed window sampling the camera, clipped at the brim. It's drawn 7cm
    nearer along its own line of sight, which leaves it in the same place on screen, so it
    always wins against the collar behind the chin.
  - **Straps:** they draw last, over the face's edges.
- **Placement:**
  - **Distance:** focal length × the face window's half-width ÷ eye distance, corrected for yaw,
    and held between 0.85m and 3.2m. The rider's face comes out the size of the real one.
  - **Sideways:** the face's x places the head. Its height stays within what the body can reach.
  - **Lean:** the bike trails the head on a spring, and the body leans across the gap.
- **Several people:** one rider each, keyed by track and kept through a 350ms tracking blink.
- **Camera:** its own level perspective (55° vertical, 1.17m up), not `View3D`.
- **Cost on the gen 1 Portal:**
  - **Test portrait:** 30fps and about 6–8ms a frame (p50) with one or two riders; 12ms while
    `screenrecord` was running.
  - **Live camera with nobody in view:** 29.5fps and 9ms.
- **Testing on the portrait:** its size never changes, so `--ef rideDist 1.6` holds every rider
  at that distance. A negative value clears it.

Not yet checked with a real person:

- how far away real faces put the rider, since the mapping may need a nudge
- the sideways lean on quick moves
- head roll and yaw on the helmet

### 11. Freefall
**Built in real 3D** (`Freefall.kt`, `FreefallRenderer.kt`; the "Freefall" chip) from its
reference video (897386285564659). What the video showed:

- **Close-up:** a skydiver in a glossy blue suit falls face-on to the camera, arms spread with
  forearms up and legs trailing behind.
  - **Helmet:** quartered blue and white panels, a pale trim round the opening, grey padding,
    white cheek guards with blue riveted plates, and a grey chin strap.
  - **Face:** it fills the opening, bigger than life.
  - **Motion:** the diver follows the head around the frame and turns and tilts with it.
  - **Background:** clouds rushing upward.
- **The fall:** opening the mouth wide sends the diver tumbling away below.
  - **Far view:** within about a second the view tips down onto hazy farmland far below, with
    the diver a small tumbling figure.
  - **White-out:** a cloud deck rises from below and the view goes white.
  - **Back:** the close-up fades in about 3s after the scream, and the next big open mouth does
    it again.
  - **Earlier guess corrected:** this catalogue first guessed a scripted pull-back over the
    clip, which was wrong.
- **Nobody in view:** just the clouds rushing past.
- **Sound:** voices over a steady low rumble. The user asked for synthesized wind and a whoosh.

How it's built:

- **Sky:** one shader over each view ray, drawn at half size and scaled up, since it's all soft.
  - **Cloud wall:** blue sky with a wall of cloud wrapped round the fall line, rushing upward and
    lit from above. The clouds come from a 256² tiling noise texture made at start-up. The wall
    fades from rays looking steeply down, where the wrap would swirl.
  - **Near layer:** at the user's request, a nearer, wispier layer of bigger cloud shapes in front
    of the wall, rushing up about three and a half times as fast on screen.
  - **Below:** the ground photo on a plane 3.2km down, through haze, with a cloud deck in
    between that closes in as the fall goes on.
- **Ground:** a public-domain USDA NAIP aerial photo of New York farmland (2022), 2048² and laid
  over 9km, with mirrored repeat and mipmaps. It's credited in `THIRD-PARTY.md`.
- **Diver:** rebuilt every frame from tubes, ellipsoids and boxes (`ColorGeo`, with colour alpha
  as gloss): the suit, a harness with a chest buckle, the pack, gloves with white cuffs, bent
  legs and boots.
  - **Body tilt:** the user couldn't see the body or feet, so it tips 32° down behind the head,
    pivoting at the neck (`DiverParts.BODY_TILT`). The torso, harness and buckle now show below
    the chin. Tipping it the other way hid everything behind the helmet.
  - **Legs:** splayed, with the shins up, so the boots stick out behind.
- **Wind in the face:** at the user's request, the face shader stretches the cheeks and lips out
  toward the helmet by sampling nearer the middle, and runs ripples back across the cheeks. It
  flaps harder during the fall (`Fall3D.wind`), and the ripples speed up with the cloud clock.
  The cheeks are also puffed: a lens on each magnifies it from its middle, with light across the
  top of each puff and a soft shade beneath.
- **Helmet:** redone at the user's request to match the reference's close-ups.
  - **Shell:** a round ellipsoid, cut open at the face and underneath by its shader.
  - **Paint:** a vivid blue stripe over the crown, bright white bands either side and blue sides,
    with thin seams.
  - **Cheek guards:** white, round the opening from the brow to the jaw, outlined by a groove,
    each with a blue plate of three vent holes. Silver rivets sit at the temples and jaw.
  - **Finish:** a clear coat that reflects the sky and the backdrop's cloud noise, the
    reference's marbled look, with a sharp sun glint and a broad studio streak from the upper
    left. Quilted grey padding lines the inside.
  - **Geometry:** a thin bright rubber trim round the opening, and a ribbed grey chin cup under
    the chin.
  - **Face:** its window sits inside, pulled 2cm nearer so the padding behind it never wins the
    depth test.
- **Placement:** the user asked for the diver to come and go as they lean in and out.
  - **Measured:** on the gen 1 Portal, a face logged at about 80px between the eyes sitting
    normally, 45px sat back, and 150px leaning in.
  - **Mapping:** the diver is 1.2m away at 80px, and distance follows the eye distance's ratio to
    the power 1.6, corrected for yaw. So leaning moves the diver more than it moves the face:
    from 0.6m close up to 3.6m sat back. The user first had it at 0.8m (0.4–2.4m), then asked for
    it all half as far again back.
  - **Smoothing:** the user found the tracking rough and suggested rendering at 20fps. The stats
    said otherwise: 30fps rendering at 6–7ms a frame, but the mesh tracker (needed for `jawOpen`)
    updating 15–19 times a second with 8–17px of jitter, which the depth mapping magnifies. So the
    diver now follows on critically damped springs instead: position at 7 rad/s, depth at 3.2
    rad/s on the log of the distance, turn and tilt at 8 rad/s. The face picture itself still
    follows the live track exactly.
- **The fall:**
  - **Trigger:** `jawOpen` above 0.5, which needs the mesh tier for blendshapes, or a poke.
  - **Motion:** divers drop along a steepening curve, tumbling about the chest. The camera
    pitches to follow the nearest one, and the cloud wall fades as the ground and deck show.
  - **Timing:** white from 1.75s, back to the close-up at 2.35s inside the white, clear by 3s,
    and ready again at 3.3s.
  - **Groups:** everyone falls together.
- **Sound:**
  - **Wind:** `Sfx` synthesizes a seamless 6s loop. It plays as the filter's new `ambience`:
    `MainActivity` loops it through `Mixer` while the filter is selected, so it's baked into
    clips.
  - **Whoosh:** 1.8s, on each fall.
- **Cost on the gen 1 Portal:** 30fps on the test portrait through the whole fall, with frames
  about 5.5ms p50 and 11ms p95.
- **Testing:** `--ef fallDist 0.75` holds divers at a distance. `--es action poke` or
  `--ef jaw 0.8` makes them fall.

Not yet checked with a real person:

- the new distance mapping, with a real person leaning in and out
- how reliably a real scream crosses the jaw threshold, and whether talking sets it off
- the sound on the Portal's speaker

The reference's clouds are photographic; these are procedural.

## Shared building blocks these need

Most of the set reuses a handful of new pieces. Building those first makes each effect small:

1. **Full-frame shader effects:** mirror, tint, dot grid and gradients, as a "frame shader" step
   in `Compositor` that runs before the layers. Unlocks 1, 2 and 3.
2. **Expression switching and cross-fades:** read blendshapes, add hysteresis so the effect
   doesn't flicker, and blend between two looks. Unlocks 4, and helps 5 and 6.
3. **Particles in frame space:** emitters attached to anchors, simulated per frame and drawn in
   Canvas. Unlocks 5, and gives 2 and 9 their stars.
4. **Face patches into shapes:** the existing affine patch, plus a tint, a blur and a
   non-ellipse mask (circle, rounded glass). Unlocks 8 and 9, and improves 10 and 11.
5. **Local magnify warps:** Big Head's patch reused per eye. Unlocks 6.
6. **Image backdrops:** load a bitmap into the under layer. Unlocks 7, and could upgrade Beach,
   Palace and Moon.

## Suggested order

Quick wins that the kids will recognise immediately come first, then the building blocks,
then the big scenes:

1. Mirror, Pop Silhouette, Disco Dots (block 1)
2. Monster / Cutie (block 2)
3. Pixel Hearts (block 3)
4. Hamster (block 5)
5. Peas in a Pod, Lemonade (block 4)
6. Pink Palace (block 6, once there's a backdrop image)
7. Bike Ride, then Freefall
