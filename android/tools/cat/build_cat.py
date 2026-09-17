"""
Builds Cat Hat's kitten (assets/cat/) from FainoDS's "Kitten" on Sketchfab (CC BY 4.0).

The model is textured but not rigged, and it is made of separate pieces (head, ears, body and
tail, four legs, small face parts) that overlap where they meet. This script gives it a skeleton,
skins it with smooth distance weights so the overlapping pieces bend together without opening
seams, poses it lying down, and writes the posed mesh, the bones and the textures for the app.

Posing uses the same skinning the app does: every bone turns about its pivot in the model's
axes, children inherit their parents' turns, and each vertex is a weighted blend of up to four
bones. So the preview renders here are what the Portal will draw.

    ./tools/cat/fetch-kitten.sh                      # once: the source model, not committed
    BLENDER=~/Development/portal-tools/blender-4.2.23-linux-x64/blender
    $BLENDER -b -P tools/cat/build_cat.py -- export app/src/main/assets/cat
    $BLENDER -b -P tools/cat/build_cat.py -- render /tmp/cat      # preview renders

Axes: Blender's model is z up with the kitten facing +y. The app's are x right, y down and z away
from the camera, with the kitten facing the camera, so (x, y, z) goes to (-x, -z, -y).
"""
import bpy
import math
import os
import struct
import sys

import numpy as np
from mathutils import Matrix, Quaternion, Vector

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "kitten.glb")

# ---------------------------------------------------------------- skeleton
# name, parent, pivot (the joint it turns about), end (for distance weights and aiming)
L, R = 1, -1


def mirror(p, side):
    return (p[0] * side, p[1], p[2])


BONES = [
    ("pelvis", None, (0, -3.0, 5.4), (0, -1.0, 5.6)),
    ("spine", "pelvis", (0, -1.0, 5.6), (0, 1.2, 5.8)),
    ("chest", "spine", (0, 1.2, 5.8), (0, 2.4, 6.6)),
    ("neck", "chest", (0, 2.4, 6.4), (0, 3.4, 7.2)),
    ("head", "neck", (0, 3.4, 7.2), (0, 5.4, 7.4)),
    ("tail0", "pelvis", (0, -4.3, 6.9), (0, -5.0, 7.8)),
    ("tail1", "tail0", (0, -5.0, 7.8), (0, -5.5, 8.8)),
    ("tail2", "tail1", (0, -5.5, 8.8), (0, -5.6, 9.7)),
    ("tail3", "tail2", (0, -5.6, 9.7), (0, -5.1, 10.4)),
    ("tail4", "tail3", (0, -5.1, 10.4), (0, -4.6, 10.8)),
]
for side, s in (("L", L), ("R", R)):
    BONES += [
        ("ear" + side, "head", mirror((1.55, 4.25, 8.8), s), mirror((2.3, 4.4, 10.2), s)),
        ("shoulder" + side, "chest", mirror((1.5, 1.4, 5.2), s), mirror((1.8, 1.4, 3.2), s)),
        ("elbow" + side, "shoulder" + side, mirror((1.8, 1.4, 3.2), s), mirror((1.8, 1.5, 1.1), s)),
        ("paw" + side, "elbow" + side, mirror((1.8, 1.5, 1.1), s), mirror((1.7, 2.3, 0.1), s)),
        ("hip" + side, "pelvis", mirror((1.5, -3.4, 5.0), s), mirror((1.8, -3.2, 3.0), s)),
        ("knee" + side, "hip" + side, mirror((1.8, -3.2, 3.0), s), mirror((1.8, -4.2, 1.4), s)),
        ("foot" + side, "knee" + side, mirror((1.8, -4.2, 1.4), s), mirror((1.7, -3.5, 0.1), s)),
    ]
NAMES = [b[0] for b in BONES]
INDEX = {n: i for i, n in enumerate(NAMES)}

# ---------------------------------------------------------------- the lying-down pose
# Turns in degrees about model axes (x toward the kitten's left, y forward, z up), each in its
# parent's already-turned frame.
POSE = {
    # Draped over the dome of the head, arched: the rump drops down its back and the chest down
    # its front, so the kitten hugs the head instead of standing on it.
    "pelvis": ((1, 0, 0), 18),
    "spine": ((1, 0, 0), -36),
    "chest": ((1, 0, 0), -12),
    # The head comes up and looks ahead, at whoever is in front of the Portal.
    "neck": ((1, 0, 0), 44),
    "head": ((1, 0, 0), -8),
    # Front legs reach forward and down over the forehead, paws resting on it.
    "shoulderL": ((1, -0.15, 0), 46),
    "shoulderR": ((1, 0.15, 0), 46),
    "elbowL": ((1, 0, 0), -5),
    "elbowR": ((1, 0, 0), -5),
    "pawL": ((1, 0, 0), -38),
    "pawR": ((1, 0, 0), -38),
    # Settled down onto the head.
    "_shift": (0, 0.3, -0.9),
}

# CatHat.kt's head for a typical adult, in kitten units: centre and radii. The app seats the
# kitten's (0, -1.7, 0.4) on it, which is (0, -0.4, 1.7) here; the head's centre is 0.1 face
# units behind and 1.22 below that, and one face unit is 5.3 kitten units.
HEAD_CENTRE = (0.0, -0.93, -4.74)
HEAD_RADII = (4.87, 5.53, 6.9)


def on_head(x, y, z, out=1.0):
    """The head's surface in the direction (x, y, z) from its centre, pushed out by [out]."""
    rx, ry, rz = HEAD_RADII
    k = out / math.sqrt(x * x + y * y + z * z)
    return (HEAD_CENTRE[0] + x * k * rx, HEAD_CENTRE[1] + y * k * ry, HEAD_CENTRE[2] + z * k * rz)


# The hind legs hold on round the back of the head: knee, foot and toes aimed at points just off
# its surface, down its back corners. x flips for the other side.
HIND_PATH = [(0.45, -0.62, 0.64, 1.06), (0.62, -0.55, 0.45, 1.05), (0.66, -0.35, 0.4, 1.02)]

# The tail is aimed rather than turned by hand: straight out of the rump, then drooping over the
# back of the head toward the kitten's right. Offsets from the tail's root, where each tail bone
# should end up pointing.
TAIL_PATH = [(-0.2, -1.06, 0.17), (-0.7, -1.96, -0.33), (-1.3, -2.56, -1.03), (-1.9, -2.86, -1.73), (-2.4, -2.96, -2.33)]


def aim(pose, names, points):
    """Turns each bone in a chain so its end points at the next point, in order."""
    for name, target in zip(names, points):
        world, _ = bone_matrices(pose)
        i = INDEX[name]
        b = BONES[i]
        rest = (Vector(b[3]) - Vector(b[2])).normalized()
        here = (world[i] @ Vector((0, 0, 0, 1))).to_3d()
        parent_rot = world[INDEX[b[1]]].to_3x3() if b[1] else Matrix.Identity(3)
        # The bone's current turn is replaced, so measure against its parent's frame.
        want = parent_rot.inverted() @ (Vector(target) - here).normalized()
        q = rest.rotation_difference(want)
        axis, angle = q.to_axis_angle()
        pose[name] = (tuple(axis), math.degrees(angle))
    return pose


# ---------------------------------------------------------------- loading
def load():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    if not os.path.exists(SRC):
        sys.exit("missing " + SRC + ": run tools/cat/fetch-kitten.sh")
    bpy.ops.import_scene.gltf(filepath=SRC)
    meshes = [o for o in bpy.context.scene.objects if o.type == "MESH"]
    return meshes


def triangles(obj):
    """Unique (position, normal, tangent, uv) corners and their triangles, in model space."""
    me = obj.data
    m = obj.matrix_world
    nm = m.to_3x3().inverted().transposed()
    has_tan = True
    try:
        me.calc_tangents()
    except RuntimeError:
        has_tan = False
    # Only after calc_tangents, which reallocates the corner data.
    uv = me.uv_layers.active.data
    me.calc_loop_triangles()
    key_of = {}
    pos, nrm, tan, uvs, src = [], [], [], [], []
    tris = []
    for t in me.loop_triangles:
        tri = []
        for li in t.loops:
            loop = me.loops[li]
            n = loop.normal
            u = uv[li].uv
            k = (loop.vertex_index, round(n.x, 2), round(n.y, 2), round(n.z, 2), round(u.x, 4), round(u.y, 4))
            idx = key_of.get(k)
            if idx is None:
                idx = len(pos)
                key_of[k] = idx
                p = m @ me.vertices[loop.vertex_index].co
                wn = (nm @ n).normalized()
                pos.append(p[:])
                nrm.append(wn[:])
                if has_tan:
                    wt = (m.to_3x3() @ loop.tangent).normalized()
                    tan.append((wt.x, wt.y, wt.z, loop.bitangent_sign))
                else:
                    tan.append((1.0, 0.0, 0.0, 1.0))
                uvs.append((u.x, 1.0 - u.y))
                src.append(loop.vertex_index)
            tri.append(idx)
        tris.append(tri)
    pos, uvs = np.array(pos), np.array(uvs)
    return pos, np.array(nrm), np.array(tan), uvs, np.array(tris), np.array(src)


def islands(obj):
    """Connected piece id for every vertex of obj."""
    me = obj.data
    parent = list(range(len(me.vertices)))

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    for e in me.edges:
        a, b = find(e.vertices[0]), find(e.vertices[1])
        if a != b:
            parent[a] = b
    return np.array([find(i) for i in range(len(me.vertices))])


def region_of(c):
    """Which part of the kitten a piece is, from where its middle is: the lids take their
    colours from the head's."""
    x, y, z = c
    side = "L" if x > 0 else "R"
    if z > 8.6 and abs(x) > 1.2 and y > 3.0:
        return "ear" + side
    if y > 3.9 or (y > 2.0 and z > 7.0):
        return "head"
    if z < 4.3 and abs(x) > 0.5:
        return ("front" if y > -0.5 else "hind") + side
    return "body"


def seg_dist(p, a, b):
    ab = b - a
    t = np.clip(((p - a) @ ab) / max(ab @ ab, 1e-9), 0.0, 1.0)
    return np.linalg.norm(p - (a + t[:, None] * ab), axis=1)


def reach(name, p):
    """Where a bone may pull at all, by position alone, so pieces that overlap get the same
    weights and bend together without opening a seam."""
    x, y, z = p[:, 0], p[:, 1], p[:, 2]
    side = 1.0 if name.endswith("L") else -1.0
    if name.startswith(("shoulder", "elbow", "paw")):
        return (x * side > 0.25) & (y > -1.2) & (z < 6.9)
    if name.startswith(("hip", "knee", "foot")):
        return (x * side > 0.25) & (y < -1.4) & (z < 6.9)
    if name.startswith("ear"):
        return (x * side > 0.5) & (z > 8.2)
    if name == "tail0":
        return (y < -4.0) & (z > 6.0) & (np.abs(x) < 1.2)
    if name.startswith("tail"):
        return (y < -4.4) & (z > 6.4) & (np.abs(x) < 1.2)
    if name == "head":
        return y > 2.2
    if name == "neck":
        return y > 0.6
    return np.ones(len(p), dtype=bool)


def weights(pos):
    """Up to four bones per vertex, from distance to each bone's segment."""
    d = np.stack([seg_dist(pos, np.array(b[2], float), np.array(b[3], float)) for b in BONES], axis=1)
    w = 1.0 / np.power(d + 0.35, 6)
    for i, b in enumerate(BONES):
        w[~reach(b[0], pos), i] = 0
    order = np.argsort(-w, axis=1)[:, :4]
    top = np.take_along_axis(w, order, axis=1)
    top = top / top.sum(axis=1, keepdims=True)
    top[top < 0.02] = 0
    top = top / top.sum(axis=1, keepdims=True)
    return order.astype(np.int32), top.astype(np.float32)


# ---------------------------------------------------------------- skinning, shared with the app
def quat(axis, deg):
    return Quaternion(Vector(axis).normalized(), math.radians(deg))


def bone_matrices(pose):
    """Model-space skinning matrix per bone for a {name: (axis, deg)} pose."""
    world = [None] * len(BONES)
    skin = [None] * len(BONES)
    for i, (name, parent, pivot, _) in enumerate(BONES):
        q = pose.get(name)
        rot = quat(*q).to_matrix().to_4x4() if q else Matrix.Identity(4)
        pv = Vector(pivot)
        if parent is None:
            local = Matrix.Translation(pv + Vector(pose.get("_shift", (0, 0, 0)))) @ rot
            world[i] = local
        else:
            pp = Vector(BONES[INDEX[parent]][2])
            world[i] = world[INDEX[parent]] @ Matrix.Translation(pv - pp) @ rot
        skin[i] = world[i] @ Matrix.Translation(-pv)
    return world, skin


def skin(pos, nrm, tan, joints, wts, pose):
    world, mats = bone_matrices(pose)
    M = np.array([np.array(m) for m in mats])  # (bones, 4, 4)
    blend = np.einsum("vk,vkij->vij", wts, M[joints])
    p = np.einsum("vij,vj->vi", blend[:, :3, :3], pos) + blend[:, :3, 3]
    n = np.einsum("vij,vj->vi", blend[:, :3, :3], nrm)
    n /= np.linalg.norm(n, axis=1, keepdims=True) + 1e-9
    t3 = np.einsum("vij,vj->vi", blend[:, :3, :3], tan[:, :3])
    t3 /= np.linalg.norm(t3, axis=1, keepdims=True) + 1e-9
    return p, n, np.concatenate([t3, tan[:, 3:]], axis=1), world


# ---------------------------------------------------------------- build
def build():
    meshes = load()
    eye_obj = next(o for o in meshes if len(o.data.vertices) < 700)
    parts = []
    for o in meshes:
        pos, nrm, tan, uv, tris, src = triangles(o)
        isl = islands(o)[src]
        kind = 1 if o is eye_obj else 0
        region = np.empty(len(pos), dtype=object)
        for piece in set(isl):
            sel = np.where(isl == piece)[0]
            region[sel] = "head" if kind == 1 else region_of(pos[sel].mean(axis=0))
        parts.append(dict(kind=kind, pos=pos, nrm=nrm, tan=tan, uv=uv, tris=tris, region=region))
    # Eyes after the fur, so the fur's depth is there first.
    parts.sort(key=lambda p: p["kind"])
    pos = np.concatenate([p["pos"] for p in parts])
    nrm = np.concatenate([p["nrm"] for p in parts])
    tan = np.concatenate([p["tan"] for p in parts])
    uv = np.concatenate([p["uv"] for p in parts])
    region = np.concatenate([p["region"] for p in parts])
    tris, ranges, base = [], [], 0
    for p in parts:
        start = sum(len(t) for t in tris) * 3
        tris.append(p["tris"] + base)
        ranges.append((p["kind"], start, len(p["tris"]) * 3))
        base += len(p["pos"])
    tris = np.concatenate(tris)
    joints, wts = weights(pos)
    eyes = eye_centres(parts[-1]["pos"])
    return dict(eye_vertex=len(pos) - len(parts[-1]["pos"]), pos=pos, nrm=nrm, tan=tan, uv=uv, tris=tris, ranges=ranges, joints=joints, wts=wts, region=region, eyes=eyes)


def eye_centres(eye_pos):
    out = []
    for s in (1, -1):
        sel = eye_pos[eye_pos[:, 0] * s > 0]
        lo, hi = sel.min(axis=0), sel.max(axis=0)
        out.append(((lo + hi) / 2, (hi - lo) / 2))
    return out


def full_pose():
    pose = dict(POSE)
    for side, sx in (("L", 1), ("R", -1)):
        points = [on_head(x * sx, y, z, out) for x, y, z, out in HIND_PATH]
        aim(pose, ["hip" + side, "knee" + side, "foot" + side], points)
    world, _ = bone_matrices(pose)
    root = (world[INDEX["tail0"]] @ Vector((0, 0, 0, 1))).to_3d()
    return aim(pose, ["tail0", "tail1", "tail2", "tail3", "tail4"], [tuple(root + Vector(o)) for o in TAIL_PATH])


def posed(model, pose):
    p, n, t, world = skin(model["pos"], model["nrm"], model["tan"], model["joints"], model["wts"], pose)
    pivots = []
    for i, b in enumerate(BONES):
        pivots.append(np.array((world[i] @ Vector((0, 0, 0, 1)))[:3]))
    eyes = []
    head = world[INDEX["head"]]
    hp = Vector(BONES[INDEX["head"]][2])
    for c, r in model["eyes"]:
        pc = head @ (Vector(c) - hp).to_4d()
        eyes.append((np.array(pc[:3]), r, np.array(head.to_3x3())))
    return p, n, t, pivots, eyes


# ---------------------------------------------------------------- export
def base_color_image():
    nodes = bpy.data.materials["body"].node_tree
    for link in nodes.links:
        if link.to_socket.name == "Base Color":
            return link.from_node.image


def to_app(v):
    return (-v[0], -v[2], -v[1])


APP = np.array([[-1, 0, 0], [0, 0, -1], [0, -1, 0]], dtype=float)


def lid_uvs(model, centre, radii):
    """Fur texture coordinates for a lid: the brow above the eye, and just over its top edge."""
    eye_start = model["eye_vertex"]
    fur = np.where((model["region"] == "head") & (np.arange(len(model["pos"])) < eye_start))[0]
    out = []
    for up, fwd in ((2.2, -0.2), (1.25, 0.35)):
        target = centre + np.array((0.0, radii[1] * fwd, radii[2] * up))
        i = fur[np.argmin(np.linalg.norm(model["pos"][fur] - target, axis=1))]
        out.append(model["uv"][i])
    return out


def export(model, outdir):
    os.makedirs(outdir, exist_ok=True)
    p, n, t, pivots, eyes = posed(model, full_pose())
    app = p @ APP.T
    lo, hi = app.min(axis=0), app.max(axis=0)
    print("posed extent (app axes)", lo.round(2), hi.round(2))
    with open(os.path.join(outdir, "kitten.bin"), "wb") as f:
        f.write(b"CAT1")
        f.write(struct.pack("<iiii", len(p), len(model["tris"]) * 3, len(BONES), len(model["ranges"])))
        for i, b in enumerate(BONES):
            parent = -1 if b[1] is None else INDEX[b[1]]
            name = b[0].encode()
            f.write(struct.pack("<i3f", parent, *to_app(pivots[i])))
            f.write(struct.pack("<B", len(name)) + name)
        for kind, start, count in model["ranges"]:
            f.write(struct.pack("<iii", kind, start, count))
        # The eye lids are made in the app, from each eye's centre, its radii across, up and
        # through, the head's resting turn, and two colours of fur from around the eye.
        for (c, r, rot), (rc, rr) in zip(eyes, model["eyes"]):
            f.write(struct.pack("<3f3f", *to_app(c), r[0], r[2], r[1]))
            f.write(struct.pack("<9f", *(APP @ rot @ APP.T).T.ravel()))
            for uv in lid_uvs(model, rc, rr):
                f.write(struct.pack("<2f", *uv))
        verts = np.zeros((len(p), 20), dtype=np.float32)
        verts[:, 0:3] = app
        verts[:, 3:6] = n @ APP.T
        # The flip to the app's axes mirrors, so the bitangent's sign flips with it.
        verts[:, 6:9] = t[:, :3] @ APP.T
        verts[:, 9] = -t[:, 3]
        verts[:, 10:12] = model["uv"]
        verts[:, 12:16] = model["joints"]
        verts[:, 16:20] = model["wts"]
        f.write(verts.tobytes())
        # A mirror also turns every triangle inside out; the app doesn't cull, but keep it right.
        f.write(model["tris"][:, [0, 2, 1]].astype(np.uint16).tobytes())
    print("wrote", len(p), "vertices", len(model["tris"]), "triangles", len(BONES), "bones")
    body = bpy.data.materials["body"].node_tree
    normal = next(l.from_node.image for l in body.links if l.to_node.type == "NORMAL_MAP")
    settings = bpy.context.scene.render.image_settings
    settings.file_format = "JPEG"
    settings.quality = 90
    settings.color_management = "OVERRIDE"
    settings.view_settings.view_transform = "Standard"
    for image, name in ((base_color_image(), "fur"), (normal, "fur-normal")):
        # Raw, so the normal map's numbers aren't run through a colour transform.
        image.colorspace_settings.name = "Non-Color"
        image.save_render(os.path.join(outdir, name + ".jpg"))


# ---------------------------------------------------------------- preview
def render(model, out, pose=None, views=None):
    pose = full_pose() if pose is None else pose
    p, n, t, pivots, eyes = posed(model, pose)
    for o in list(bpy.context.scene.objects):
        bpy.data.objects.remove(o)
    src_mats = [m for m in bpy.data.materials]
    me = bpy.data.meshes.new("cat")
    me.from_pydata([tuple(v) for v in p], [], [tuple(tri) for tri in model["tris"]])
    uvl = me.uv_layers.new()
    for poly in me.polygons:
        for li in poly.loop_indices:
            vi = me.loops[li].vertex_index
            uvl.data[li].uv = (model["uv"][vi][0], 1 - model["uv"][vi][1])
    kinds = np.zeros(len(model["tris"]), dtype=int)
    for kind, start, count in model["ranges"]:
        kinds[start // 3:(start + count) // 3] = kind
    for name, rough in (("fur", 0.8), ("eye", 0.1)):
        mat = bpy.data.materials.new(name)
        mat.use_nodes = True
        bsdf = mat.node_tree.nodes["Principled BSDF"]
        tex = mat.node_tree.nodes.new("ShaderNodeTexImage")
        tex.image = base_color_image()
        mat.node_tree.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
        bsdf.inputs["Roughness"].default_value = rough
        me.materials.append(mat)
    for poly in me.polygons:
        poly.material_index = int(kinds[poly.index])
        poly.use_smooth = True
    me.normals_split_custom_set_from_vertices([tuple(v) for v in n])
    obj = bpy.data.objects.new("cat", me)
    sc = bpy.context.scene
    sc.collection.objects.link(obj)
    if "--head" in sys.argv:
        bpy.ops.mesh.primitive_uv_sphere_add(radius=1, location=HEAD_CENTRE, segments=48, ring_count=24)
        h = bpy.context.object
        h.scale = HEAD_RADII
        bpy.ops.object.shade_smooth()
    sc.render.engine = "CYCLES"
    sc.cycles.samples = 24
    sc.render.resolution_x = 720
    sc.render.resolution_y = 720
    w = bpy.data.worlds.new("w")
    sc.world = w
    w.use_nodes = True
    w.node_tree.nodes["Background"].inputs[0].default_value = (0.85, 0.85, 0.9, 1)
    sun = bpy.data.objects.new("sun", bpy.data.lights.new("sun", "SUN"))
    sun.data.energy = 3
    sun.rotation_euler = (math.radians(40), 0, math.radians(200))
    sc.collection.objects.link(sun)
    cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam"))
    sc.collection.objects.link(cam)
    sc.camera = cam
    centre = Vector((0, -0.5, 2))
    for name, d in (views or [("front", (0, 1, 0.15)), ("q", (0.55, 1, 0.2)), ("side", (1, 0.05, 0.1)), ("back", (-0.5, -1, 0.3))]):
        d = Vector(d).normalized()
        cam.location = centre + d * 26
        cam.rotation_euler = (centre - cam.location).to_track_quat("-Z", "Y").to_euler()
        sc.render.filepath = out + "_" + name + ".png"
        bpy.ops.render.render(write_still=True)


if __name__ == "__main__":
    args = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    model = build()
    if args and args[0] == "export":
        export(model, args[1])
    elif args and args[0] == "render":
        render(model, args[1], None if "--rest" not in args else {})
