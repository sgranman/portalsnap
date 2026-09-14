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
| 3 | Pop Silhouette | 946857547560491 | segment | mask + shader | small |
| 4 | Monster / Cutie | 936589334645536 | mesh + blendshapes | Canvas doodles, switched by expression | small–medium |
| 5 | Pixel Hearts | 483283294836303 | mesh + blendshapes | Canvas sprites + particles from an open mouth | medium |
| 6 | Hamster | 589562750311569 | mesh | eye-enlarge warp patch + soft "3D" props | medium |
| 7 | Pink Palace | (screenshot 103721) | segment | backdrop image + person cut-out | small, plus art |
| 8 | Lemonade | (screenshot 103801) | mesh | face patch warped into a glass, tinted and blurred | medium |
| 9 | Peas in a Pod | 1434519407443964 | mesh | the face cloned into three peas | medium |
| 10 | Bike Ride | 3745986732290546 | fast / mesh | moving scene + rider body + face in helmet | medium–large |
| 11 | Freefall | 897386285564659 | fast | photoreal-style skydiver, with a camera that pulls away | large |

### Status (2026-09-14)

Effects 1–6 are built (`PhotoBooth.kt`, the `FX_*` shaders in `Gl.kt`, `MicHub.kt`). They're
the first six chips: Mirror, Pop Art, Disco, Monster, Hearts, Hamster.

Tested on the gen 1 Portal with a real person:

- **Monster / Cutie:** a nod flips between them, and the voice pitch follows. A nod is a head
  pitch swing of more than 10° that comes back within 1.1s.
- **Disco and Pop Art:** both react to music in the room. With no beats they fall back to idle
  timing: Disco bursts every 1.1s, and Pop Art changes look every 2s.
- **Recording:** works with a mic-reactive filter on.

**Hamster** is so far checked only on the test portrait. The eyes use a new `bulge` lens patch
(2.2x at the centre, easing to no change at the rim). The props are drawn with gradients. The
two open questions above were answered by guessing, so check both against the videos:

- **Nibbling:** yes. The carrot rides the lower lip and wiggles while the mouth is open. At the
  user's request it also gets eaten: each open-then-close takes a scalloped bite and drops
  crumbs, three bites finish it, and a fresh one pops in 1.3s later.
- **Cheek puff:** no warp yet. A pair of gentle `bulge` patches on the cheeks would add one.

**Peas in a Pod** (`PeasInAPod`, the "Peas" chip) is also checked only on the test portrait.
It's a full scene like Skydive: the camera is hidden, and each pea is a circular patch of the
head box. It uses the fast tier rather than mesh, since only the head box is needed and faces
then follow at 30fps. It renders at 30fps with a paint time of about 4ms. Its two open
questions were also guessed:

- **Bobbing:** yes, each pea bobs and sways on its own phase. There's no time offset, which
  would need a history of camera frames.
- **Two children:** the peas alternate top, middle, bottom between them. With three, each gets
  one pea.

Additions the user asked for:

- **Rocking:** the pod rocks ±6° like a cradle on a 1.8s swing, about a pivot below the frame.
  The face patches follow the rotation, and the shadow slides under the base.
- **Creak:** a wooden creak plays at each end of the swing, only while someone is in view.
  It's synthesized in `Sfx.kt`.

Every app sound, effects and music alike, plays through `Mixer.kt`. The mixer feeds the speaker
and also adds the same samples to recordings after the voice effect, matched to the mic's
capture times. So clips carry the sounds cleanly and at their own pitch, instead of the faint
copy the mic hears in the room. Beat detection hears the mix directly too.
- **Arms:** bendy arms with mitten hands. On each pea one arm waves and the other swings.

## The effects

### 1. Mirror
The left half of the picture reflected onto the right, so one child becomes a symmetrical pair
facing each other, meeting at the centre line. The whole frame is mirrored, room included.
- **Build:** one line of shader in the composite pass, `uv.x = 0.5 - abs(uv.x - 0.5)`, applied
  to the frame before stickers. Variants are nearly free: top/bottom, or a four-way
  kaleidoscope.
- **Question:** was there only a left/right mirror, or several?

### 2. Disco Dots
The whole picture washed purple-magenta, with a grid of round LED-like dots over everything.
The dots vary in brightness as if twinkling, and the person shows through the dots.
- **Build:** a full-frame shader. Tint toward magenta, then screen-blend a dot grid whose
  per-cell brightness comes from a hash of the cell and time.
- **Questions:** did the dots pulse to music or to voices, as an audio-reactive effect? Did they
  avoid the person (segmentation) or cover everything? The frame suggests everything, dimmer
  over the face.

### 3. Pop Silhouette
Pop art: the person becomes a flat silhouette filled with an orange-to-yellow gradient, and the
room behind turns flat red, with only a ghost of the room's texture left.
- **Build:** segmentation tier, with no art needed. The mask selects a gradient fill for the
  person and a red wash over the darkened camera for the background, mixed at a soft edge.
- **Questions:** did the colours cycle over time (red/yellow, then blue/pink…)? Did the gradient
  follow the body?

### 4. Monster / Cutie (one effect, two moods)
Hand-drawn doodle face paint that changes with expression. Both frames come from the same
video, 1s and 4s in.
- **Monster:** cream horns on the forehead, thick angry eyebrows over the eyes, black X marks
  with pink scribbled blush on the cheeks, and a huge black fanged mouth with a pink tongue
  drawn over an open mouth.
- **Cutie:** round white bear ears with pink insides, little lash strokes at the eye corners,
  and pink heart blush on the cheeks.
- **Build:** mesh tier with blendshapes, drawn in Canvas in a doodle style (black outlines,
  flat fills), much like the existing filters. The switch is expression-driven. Probable
  triggers are `jawOpen` plus `browDownLeft/Right` or `mouthFrownLeft/Right` for Monster, and
  a smile for Cutie. It should cross-fade between the moods, not snap.
- **Question:** which expression flipped it: an open mouth, a frown, or both?

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

### 7. Pink Palace
The room replaced by a dreamy pastel-pink architectural interior: arched windows, a staircase,
bookshelves on a mezzanine, a vase of flowers. The children appear as cut-outs pasted into it,
head and shoulders, with slightly rough blob-shaped edges and a faint pink colour grade.
- **Build:** segmentation tier, the same mechanism as Beach, Palace and Moon. Its look is
  photographic, though, so it needs a backdrop *image* rather than vector art: one the family
  picks or makes, or an openly licensed render.
- **Questions:** were there other rooms or scenes in the same effect? Did the backdrop move?

### 8. Lemonade
The face appears inside a glass of pink lemonade, seen through the liquid: tinted pink, softly
blurred and slightly stretched to the glass's shape. The glass has ice cubes, a straw and a
lemon slice on the rim, and floats on a flat coral-to-pink gradient. The room is gone entirely,
and the glass tilts with the head.
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
The child on a bike ride through a stylised low-poly forest at sunset: cone and ball trees, a
road rushing toward the camera with motion blur, and a pink-orange sky. Their face sits in a
cartoon rider's head wearing a white bike helmet with a chin strap, on a teal-jersey body
leaning forward. The rider leans and steers with the head.
- **Build:** the Skydive pattern at a larger scale.
  - **Scene:** a scrolling vanishing-point scene in Canvas, with trees spawning at the horizon
    and growing as they pass, plus the road.
  - **Rider:** a body under the face patch and a helmet over it.
  - **Steering:** the head's yaw and x position.
- **Question:** did turning the head actually steer, with the road curving and trees passing on
  one side?

### 11. Freefall
A near-photoreal skydiver in a blue-and-white suit and striped helmet, arms spread, with the
child's face in the helmet opening, against a sky of real-looking clouds. Over the ~30 seconds
the camera pulls back: by 22s the diver is a small figure tumbling below, with the ground
visible through the clouds.
- **Build:** the largest of the set. The existing Skydive filter is a cartoon version of the
  idea. Getting this look means rendered art for the diver, in a few poses, plus a cloud
  backdrop, and a scripted camera move over the clip's duration.
- **Question:** did the pull-away start when recording started, or loop on its own?

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
