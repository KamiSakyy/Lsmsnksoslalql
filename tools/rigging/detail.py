"""Source-specific finger/face fitting and genuine localized facial morphs.

No stand-in mesh: every field is fitted/evaluated on the original high-poly
vertices. Source texture coordinates are glTF coordinates (image origin at top
left); they are not flipped as Blender UV coordinates would be.
"""
from __future__ import annotations

import io
import json
from pathlib import Path

import numpy as np
from PIL import Image
from scipy.sparse import coo_matrix
from scipy.sparse.csgraph import connected_components, dijkstra
from scipy.spatial import cKDTree

from core import Bone, read_accessor, smoothstep

DIGITS = ('Thumb', 'Index', 'Middle', 'Ring', 'Pinky')


def source_data(document, binary):
    primitive = document['meshes'][0]['primitives'][0]
    attrs = primitive['attributes']
    positions = read_accessor(document, binary, attrs['POSITION']).astype(np.float64)
    height = float(np.ptp(positions[:, 1]))
    origin = (positions.min(0)+positions.max(0))/2
    origin[1] = positions[:, 1].min()
    normalized = (positions-origin)/height
    normals = read_accessor(document, binary, attrs['NORMAL']).astype(np.float64)
    uv = read_accessor(document, binary, attrs['TEXCOORD_0'])
    faces = read_accessor(document, binary, primitive['indices']).ravel().reshape(-1, 3)
    material = document['materials'][primitive.get('material', 0)]
    texture = document['textures'][material['pbrMetallicRoughness']['baseColorTexture']['index']]
    image = document['images'][texture['source']]
    view = document['bufferViews'][image['bufferView']]
    offset = view.get('byteOffset', 0)
    pixels = np.array(Image.open(io.BytesIO(binary[offset:offset+view['byteLength']])).convert('RGB'))
    colors = pixels[np.clip((uv[:, 1]*pixels.shape[0]).astype(int), 0, pixels.shape[0]-1),
                    np.clip((uv[:, 0]*pixels.shape[1]).astype(int), 0, pixels.shape[1]-1)]
    return normalized, faces, normals, colors, height, origin


def in_bounds(vertices, bounds):
    mask = np.ones(len(vertices), dtype=bool)
    for axis in range(3):
        mask &= (vertices[:, axis] > bounds[2*axis]) & (vertices[:, axis] < bounds[2*axis+1])
    return mask


def surface_graph(vertices, faces, selected):
    """Weld only the internal graph, never alter actual UV/normal seam vertices."""
    triangles = faces[selected[faces].all(axis=1)]
    ids = np.unique(triangles)
    positions = vertices[ids]
    _, first, remap = np.unique(np.rint(positions*1e7).astype(np.int64), axis=0,
                                return_index=True, return_inverse=True)
    mapping = np.full(len(vertices), -1, dtype=np.int64)
    mapping[ids] = remap
    triangles = mapping[triangles]
    unique = positions[first]
    edges = np.unique(np.sort(np.concatenate([triangles[:, [0, 1]], triangles[:, [1, 2]],
                                             triangles[:, [2, 0]]]), axis=1), axis=0)
    length = np.linalg.norm(unique[edges[:, 0]]-unique[edges[:, 1]], axis=1)
    graph = coo_matrix((np.r_[length, length], (np.r_[edges[:, 0], edges[:, 1]],
                        np.r_[edges[:, 1], edges[:, 0]])), shape=(len(unique), len(unique))).tocsr()
    return ids, unique, triangles, remap, graph


def fit_fingers(vertices, colors, profile, side, candidate_mask):
    skin = (colors[:, 0].astype(int) > colors[:, 2].astype(int)+3)
    cloud = vertices[candidate_mask & skin]
    if len(cloud) < 1000:
        raise ValueError(f'Insufficient actual {side} hand geometry')
    tree = cKDTree(cloud)
    result, report = [], {}
    for digit in DIGITS:
        template = np.array(profile['fingers'][side][digit], dtype=np.float64)
        fitted = template.copy()
        for joint, target in enumerate(template):
            tangent = (template[min(joint+1, 3)]-template[max(joint-1, 0)])
            tangent /= np.linalg.norm(tangent)
            candidates = tree.query_ball_point(target, .006 if joint < 3 else .0045)
            points = cloud[candidates]
            delta = points-target
            points = points[np.abs(delta@tangent) < .0016]
            if len(points) >= 12:
                # Opposing surface samples estimate the centre, not a snap to
                # the visible skin. The strict window avoids another finger.
                low, high = np.quantile(points, [.10, .90], axis=0)
                displacement = (low+high)/2-target
                displacement -= tangent*(displacement@tangent)
                magnitude = np.linalg.norm(displacement)
                if magnitude > .0028:
                    displacement *= .0028/magnitude
                fitted[joint] += displacement
            else:
                distance, closest = tree.query(target)
                if distance > .0045:
                    fitted[joint] = cloud[closest]
        distances = tree.query(fitted)[0]
        if distances.max() > .0055:
            raise ValueError(f'{side}{digit} joint is outside the measured hand: {distances}')
        for phalanx in range(3):
            name = f'mixamorig:{side}Hand{digit}{phalanx+1}'
            parent = f'mixamorig:{side}Hand' if phalanx == 0 else f'mixamorig:{side}Hand{digit}{phalanx}'
            if np.linalg.norm(fitted[phalanx+1]-fitted[phalanx]) < .001:
                raise ValueError(f'Degenerate fitted phalanx: {name}')
            result.append(Bone(name, parent, fitted[phalanx], fitted[phalanx+1], .003, 'finger'))
        report[digit] = {'joints': fitted.tolist(), 'nearest_source_surface_distance': distances.tolist(),
                         'template_adjustment': np.linalg.norm(fitted-template, axis=1).tolist()}
    return result, report


def fit_face(vertices, faces, normals, colors, profile):
    selected = in_bounds(vertices, profile['face']['bounds'])
    ids, unique, triangles, remap, graph = surface_graph(vertices, faces, selected)
    # Geodesic distance stops front-facing bangs/back hair being mistaken for
    # forehead/eyelids simply because they overlap in a frontal photograph.
    _, seed = cKDTree(unique).query([-.0127, .8757, .0971])
    distance = dijkstra(graph, directed=False, indices=int(seed))[remap]
    euclidean = np.linalg.norm(vertices[ids]-unique[seed], axis=1)
    mask = ((distance < .087) & (distance/np.maximum(euclidean, .004) < 1.6)
            & (normals[ids, 2] > .15) & (vertices[ids, 2] > .055))
    facial_ids = ids[mask]
    p = vertices[facial_ids]
    rgb = colors[facial_ids].astype(float)
    landmarks = {}
    for side, xlo, xhi in [('Left', .003, .037), ('Right', -.056, -.022)]:
        eye = ((p[:, 0] > xlo) & (p[:, 0] < xhi) & (p[:, 1] > .886) & (p[:, 1] < .904)
               & (rgb[:, 2] > rgb[:, 0]*1.08) & (rgb[:, 2] > rgb[:, 1]*1.04))
        points = p[eye]
        if len(points) < 60:
            raise ValueError(f'Could not fit {side} iris from actual geometry and UV texture')
        lo, hi = np.quantile(points, [.05, .95], axis=0)
        center = (lo+hi)/2
        landmarks[side] = {'eye': center.tolist(), 'eye_bounds_p05': lo.tolist(),
                           'eye_bounds_p95': hi.tolist(), 'feature_samples': len(points)}
    lip = ((p[:, 0] > -.030) & (p[:, 0] < .012) & (p[:, 1] > .8545) & (p[:, 1] < .862)
           & (rgb.mean(1) < 165) & (rgb[:, 0] > rgb[:, 2]+3))
    points = p[lip]
    if len(points) < 30:
        raise ValueError('Could not fit actual mouth line')
    lo, hi = np.quantile(points, [.05, .95], axis=0)
    mouth = (lo+hi)/2
    landmarks['mouth'] = mouth.tolist()
    landmarks['mouth_bounds_p05'] = lo.tolist()
    landmarks['mouth_bounds_p95'] = hi.tolist()
    landmarks['face_vertex_count'] = len(facial_ids)
    landmarks['method'] = 'nose-seeded surface geodesics + actual UV eye/lip features + 3D geometry'
    return facial_ids, landmarks


def gaussian(points, center, radii):
    q = (points-np.asarray(center))/np.asarray(radii)
    distance = np.sum(q*q, axis=1)
    return np.exp(-distance*2) * (1-smoothstep(2., 4., distance))


def facial_bones(landmarks, profile):
    result = []
    def add(name, point, radius, parent='mixamorig:Head'):
        point = np.asarray(point, dtype=float)
        result.append(Bone('Face.'+name, parent, point, point+[0, .005, 0], radius, 'face'))
    mouth = np.asarray(landmarks['mouth'])
    add('Jaw', profile['face']['jaw_hinge'], .03)
    for side, sign in [('Left', 1), ('Right', -1)]:
        eye = np.asarray(landmarks[side]['eye'])
        add('Eye.'+side, eye+[0, 0, -.002], .007)
        add('LidUpper.'+side, eye+[0, .005, 0], .007)
        add('LidLower.'+side, eye+[0, -.005, 0], .007)
        for part, xoffset in [('Inner', -sign*.010), ('Middle', 0), ('Outer', sign*.010)]:
            add('Brow'+part+'.'+side, eye+[xoffset, .015, -.002], .008)
        add('MouthCorner.'+side, mouth+[sign*.014, 0, -.002], .007)
        add('LipUpper.'+side, mouth+[sign*.006, .002, .001], .006)
        add('LipLower.'+side, mouth+[sign*.006, -.002, .001], .006, 'Face.Jaw')
        add('Cheek.'+side, mouth+[sign*.020, .012, -.008], .012)
    add('Nose', mouth+[0, .016, .012], .006)
    return result


def morph_fields(vertices, face_ids, landmarks):
    """Actual independent displacement fields; neutral stays byte-identical."""
    p = vertices[face_ids]
    mouth = np.asarray(landmarks['mouth'])
    output = {}
    def store(name, delta):
        delta = np.asarray(delta, dtype=np.float32)
        active = np.linalg.norm(delta, axis=1) > 2e-7
        if active.sum() < 20:
            raise ValueError(f'Empty facial expression: {name}')
        output[name] = (face_ids[active], delta[active])
    def vector_field(weight, vector):
        return weight[:, None]*np.asarray(vector)
    for side, sign in [('Left', 1), ('Right', -1)]:
        eye = np.asarray(landmarks[side]['eye'])
        # A compact, smooth contraction of the actual textured eye surface.
        # No pretend independent eyeball or hidden mouth anatomy is asserted.
        gx = 1-smoothstep(.012, .020, np.abs(p[:, 0]-eye[0]))
        gy = 1-smoothstep(.012, .024, np.abs(p[:, 1]-eye[1]))
        eye_gate = gx*gy*(1-smoothstep(.009, .019, np.abs(p[:, 2]-eye[2])))
        blink = np.zeros_like(p)
        closed_lid = eye[1]-.0008+.0012*(1-np.clip((p[:, 0]-eye[0])/.014, -1.5, 1.5)**2)
        blink[:, 1] = (closed_lid-p[:, 1])*.95*eye_gate
        store('eyeBlink'+side, blink)
        store('eyeSquint'+side, blink*.45)
        store('eyeWide'+side, -blink*.28)
        outer_brow = gaussian(p, eye+[sign*.009, .016, -.003], [.014, .010, .016])
        inner_brow = gaussian(p, eye+[-sign*.010, .016, -.003], [.012, .010, .016])
        store('browOuterUp'+side, vector_field(outer_brow, [0, .0045, 0]))
        store('browDown'+side, vector_field(inner_brow, [0, -.0035, .0004]))
        corner = mouth+[sign*.015, 0, -.002]
        gate = gaussian(p, corner, [.013, .011, .018])
        store('mouthSmile'+side, vector_field(gate, [sign*.004, .004, 0]))
        store('mouthFrown'+side, vector_field(gate, [-sign*.001, -.0038, 0]))
        store('mouthStretch'+side, vector_field(gate, [sign*.005, 0, 0]))
        upper = gaussian(p, mouth+[sign*.007, .003, .001], [.013, .008, .020])
        lower = gaussian(p, mouth+[sign*.007, -.003, .001], [.013, .009, .020])
        store('mouthUpperUp'+side, vector_field(upper, [0, .0028, .0002]))
        store('mouthLowerDown'+side, vector_field(lower, [0, -.0033, .0002]))
        press = gaussian(p, mouth+[sign*.008, 0, 0], [.014, .008, .020])
        delta = np.zeros_like(p); delta[:, 1] = (mouth[1]-p[:, 1])*.20*press
        store('mouthPress'+side, delta)
        cheek = gaussian(p, mouth+[sign*.022, .015, -.009], [.017, .013, .021])
        store('cheekSquint'+side, vector_field(cheek, [0, .0025, .001]))
        nose = gaussian(p, mouth+[sign*.006, .015, .007], [.010, .013, .018])
        store('noseSneer'+side, vector_field(nose, [sign*.0008, .0027, .001]))
    eyes = [np.asarray(landmarks[s]['eye']) for s in ('Left', 'Right')]
    inner = sum(gaussian(p, eye+[(-1 if s=='Left' else 1)*.011, .016, -.002],
                         [.013, .011, .019]) for s, eye in zip(('Left', 'Right'), eyes))
    store('browInnerUp', vector_field(np.clip(inner, 0, 1), [0, .004, 0]))
    lip = gaussian(p, mouth, [.025, .017, .025])
    jaw = (1-smoothstep(mouth[1]-.005, mouth[1]+.010, p[:, 1]))
    jaw *= 1-smoothstep(.037, .055, np.abs(p[:, 0]-mouth[0]))
    jaw *= smoothstep(.836, .847, p[:, 1])
    opened = vector_field(jaw, [0, -.008, -.001])
    store('jawOpen', opened)
    store('jawLeft', vector_field(jaw, [-.003, 0, 0]))
    store('jawRight', vector_field(jaw, [.003, 0, 0]))
    store('jawForward', vector_field(jaw, [0, 0, .003]))
    pucker = np.zeros_like(p);pucker[:, 0] = -(p[:, 0]-mouth[0])*.19*lip;pucker[:, 2] = .003*lip
    store('mouthPucker', pucker)
    funnel = pucker*.6+opened*.4
    store('mouthFunnel', funnel)
    store('mouthClose', -opened*.4)
    cheek = sum(gaussian(p, mouth+[sign*.024, .014, -.009], [.019, .018, .025]) for sign in (-1, 1))
    store('cheekPuff', vector_field(np.clip(cheek, 0, 1), [0, 0, .003]))
    stretch = np.zeros_like(p);stretch[:, 0] = (p[:, 0]-mouth[0])*.14*lip
    store('viseme_A', opened*.70)
    store('viseme_I', stretch+opened*.12)
    store('viseme_U', pucker*.9+opened*.12)
    store('viseme_E', stretch*.7+opened*.33)
    store('viseme_O', funnel*.9)
    return output
