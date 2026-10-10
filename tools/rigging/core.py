"""CPU-only rigging utilities, independent of Blender and testable with unittest."""
from __future__ import annotations

import copy
import hashlib
import json
import stat
import struct
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

import numpy as np
from scipy.sparse import coo_matrix, diags
from scipy.spatial import cKDTree


@dataclass
class Bone:
    name: str
    parent: str | None
    head: np.ndarray
    tail: np.ndarray
    radius: float
    category: str


def load_profile(path: Path) -> list[Bone]:
    data = json.loads(path.read_text())
    bones = [Bone(b['name'], b.get('parent'), np.asarray(b['head'], dtype=float),
                  np.asarray(b['tail'], dtype=float), b['radius'], b['category'])
             for b in data['bones']]
    seen = set()
    for bone in bones:
        if bone.name in seen or (bone.parent is not None and bone.parent not in seen):
            raise ValueError(f'Invalid or non-topological bone hierarchy: {bone.name}')
        if np.linalg.norm(bone.tail - bone.head) < 1e-6:
            raise ValueError(f'Zero-length bone: {bone.name}')
        seen.add(bone.name)
    return bones


def sha256(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def extract_glb(archive: Path, destination: Path) -> Path:
    """Extract only one GLB; never execute or blindly unpack archive members."""
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        entries = z.infolist()
        for entry in entries:
            name = PurePosixPath(entry.filename.replace('\\', '/'))
            if (name.is_absolute() or '..' in name.parts or ':' in entry.filename
                    or stat.S_ISLNK(entry.external_attr >> 16)):
                raise ValueError(f'Unsafe ZIP entry: {entry.filename}')
        models = [e for e in entries if not e.is_dir() and e.filename.lower().endswith('.glb')]
        if len(models) != 1:
            raise ValueError('Expected exactly one GLB model in the ZIP.')
        model = models[0]
        if model.file_size > 512 * 1024 * 1024:
            raise ValueError('Source GLB exceeds the 512 MiB extraction budget.')
        output = destination / Path(model.filename).name
        with z.open(model) as source, output.open('wb') as target:
            import shutil
            shutil.copyfileobj(source, target)
    return output


def read_glb(path: Path) -> tuple[dict, bytes]:
    raw = path.read_bytes()
    if len(raw) < 20:
        raise ValueError('Truncated GLB')
    magic, version, length = struct.unpack_from('<4sII', raw)
    if magic != b'glTF' or version != 2 or length != len(raw):
        raise ValueError('Invalid GLB 2.0 header')
    offset, document, binary = 12, None, b''
    while offset < length:
        size, kind = struct.unpack_from('<I4s', raw, offset)
        offset += 8
        chunk = raw[offset:offset + size]
        if len(chunk) != size:
            raise ValueError('Truncated GLB chunk')
        if kind == b'JSON':
            document = json.loads(chunk)
        elif kind == b'BIN\0':
            binary = chunk
        offset += size
    if document is None:
        raise ValueError('Missing GLB JSON chunk')
    return document, binary


def write_glb(path: Path, document: dict, binary: bytes | bytearray) -> None:
    binary = bytes(binary)
    binary += b'\0' * (-len(binary) % 4)
    document['buffers'] = [{'byteLength': len(binary)}]
    text = json.dumps(document, separators=(',', ':'), ensure_ascii=False).encode()
    text += b' ' * (-len(text) % 4)
    raw = (struct.pack('<4sII', b'glTF', 2, 12 + 8 + len(text) + 8 + len(binary))
           + struct.pack('<I4s', len(text), b'JSON') + text
           + struct.pack('<I4s', len(binary), b'BIN\0') + binary)
    path.write_bytes(raw)


def read_accessor(document: dict, binary: bytes, index: int) -> np.ndarray:
    accessor = document['accessors'][index]
    if 'sparse' in accessor:
        raise ValueError('Sparse accessors are not supported by this inspection helper')
    view = document['bufferViews'][accessor['bufferView']]
    dtype = np.dtype({5120: 'i1', 5121: 'u1', 5122: '<i2', 5123: '<u2',
                      5125: '<u4', 5126: '<f4'}[accessor['componentType']])
    width = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4, 'MAT4': 16}[accessor['type']]
    stride = view.get('byteStride', width * dtype.itemsize)
    result = np.ndarray((accessor['count'], width), dtype=dtype, buffer=binary,
                        offset=view.get('byteOffset', 0) + accessor.get('byteOffset', 0),
                        strides=(stride, dtype.itemsize)).copy()
    if accessor.get('normalized'):
        result = result.astype(np.float32) / np.iinfo(dtype).max
    return result


def smoothstep(start: float, end: float, x: np.ndarray) -> np.ndarray:
    t = np.clip((x - start) / (end - start), 0, 1)
    return t * t * (3 - 2 * t)


def segment_distances(vertices: np.ndarray, bones: list[Bone]) -> np.ndarray:
    distances = np.empty((len(vertices), len(bones)), dtype=np.float32)
    for index, bone in enumerate(bones):
        delta = bone.tail - bone.head
        t = np.clip((vertices - bone.head) @ delta / np.dot(delta, delta), 0, 1)
        nearest = bone.head + t[:, None] * delta
        distances[:, index] = np.linalg.norm(vertices - nearest, axis=1)
    return distances


def capsule_weights(vertices: np.ndarray, bones: list[Bone], colors: np.ndarray | None) -> np.ndarray:
    """Anatomical capsule binding with separate hair, coat and skirt envelopes.

    Coordinates are height-normalized glTF coordinates (Y up, +Z forward).
    This profile is tuned to the supplied, already posed Tripo character.
    """
    distances = segment_distances(vertices, bones)
    radii = np.asarray([b.radius for b in bones], dtype=np.float32)
    scores = (radii[None, :] / (distances + radii[None, :] * 0.22)) ** 6
    x, height, depth = vertices.T
    absolute_x = np.abs(x)
    groups = {name: np.asarray([i for i, b in enumerate(bones) if b.category == name])
              for name in ('body', 'hair', 'coat', 'skirt')}

    def normalized(category: str) -> np.ndarray:
        indexes = groups[category]
        result = np.zeros_like(scores)
        values = scores[:, indexes]
        values /= np.maximum(values.sum(axis=1, keepdims=True), 1e-30)
        result[:, indexes] = values
        return result

    body = normalized('body')
    hair = normalized('hair')
    coat = normalized('coat')
    skirt = normalized('skirt')
    body_distance = distances[:, groups['body']].min(axis=1)
    hair_distance = distances[:, groups['hair']].min(axis=1)
    coat_distance = distances[:, groups['coat']].min(axis=1)
    blonde = np.ones(len(vertices), dtype=np.float32)
    navy = np.ones(len(vertices), dtype=np.float32)
    if colors is not None:
        red, green, blue = colors.T
        blonde = 0.65 + 0.35 * smoothstep(-0.015, 0.06, red - blue)
        navy = (1 - smoothstep(0.28, 0.57, colors.max(axis=1))) * smoothstep(-0.05, 0.04, blue - red)
    back_hair = (smoothstep(0.51, 0.57, height) * smoothstep(-0.030, -0.060, depth)
                 * smoothstep(0.145, 0.105, absolute_x))
    front_hair = (smoothstep(0.74, 0.80, height) * smoothstep(0.030, 0.049, absolute_x)
                  * smoothstep(0.105, 0.080, absolute_x) * smoothstep(0.026, 0.053, depth))
    held_hair = (smoothstep(0.795, 0.825, height) * smoothstep(0.073, 0.11, x)
                 * smoothstep(0.04, 0.065, depth))
    affinity = body_distance / np.maximum(body_distance + hair_distance, 1e-8)
    hair_amount = (np.maximum.reduce([back_hair, front_hair, held_hair])
                   * smoothstep(0.36, 0.66, affinity) * blonde)
    coat_region = (smoothstep(0.34, 0.39, height) * smoothstep(0.63, 0.56, height)
                   * np.maximum(smoothstep(0.070, 0.105, absolute_x),
                                smoothstep(-0.020, -0.045, depth)))
    coat_affinity = body_distance / np.maximum(body_distance + coat_distance, 1e-8)
    coat_amount = coat_region * smoothstep(0.28, 0.64, coat_affinity) * (1 - hair_amount)
    skirt_amount = (smoothstep(0.425, 0.45, height) * smoothstep(0.61, 0.565, height)
                    * smoothstep(0.12, 0.10, absolute_x) * smoothstep(-0.005, 0.02, depth)
                    * navy * (1 - hair_amount) * (1 - coat_amount))
    body_amount = 1 - hair_amount - coat_amount - skirt_amount
    weights = (body * body_amount[:, None] + hair * hair_amount[:, None]
               + coat * coat_amount[:, None] + skirt * skirt_amount[:, None])
    weights /= np.maximum(weights.sum(axis=1, keepdims=True), 1e-30)
    return weights


def smooth_weights(vertices: np.ndarray, triangles: np.ndarray, weights: np.ndarray,
                   iterations: int = 4) -> np.ndarray:
    """Surface graph diffusion; connect identical UV-seam vertices as well."""
    edges = np.concatenate([triangles[:, [0, 1]], triangles[:, [1, 2]], triangles[:, [2, 0]]])
    coincident = cKDTree(vertices).query_pairs(1e-7, output_type='ndarray')
    if len(coincident):
        edges = np.concatenate([edges, coincident])
    rows = np.concatenate([edges[:, 0], edges[:, 1]])
    columns = np.concatenate([edges[:, 1], edges[:, 0]])
    graph = coo_matrix((np.ones(len(rows), dtype=np.float32), (rows, columns)),
                      shape=(len(vertices), len(vertices))).tocsr()
    degree = np.asarray(graph.sum(axis=1)).ravel()
    graph = diags(1 / np.maximum(degree, 1)) @ graph
    original = weights.copy()
    for _ in range(iterations):
        weights = 0.80 * original + 0.20 * (graph @ weights)
    weights /= np.maximum(weights.sum(axis=1, keepdims=True), 1e-30)
    return weights


def pack_four_weights(weights: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Largest-remainder quantization: four influences, exact sum of 255."""
    indexes = np.argsort(weights, axis=1)[:, -4:][:, ::-1]
    chosen = np.take_along_axis(weights, indexes, axis=1)
    if np.any(chosen.sum(axis=1) <= 0) or not np.all(np.isfinite(chosen)):
        raise ValueError('Unweighted or non-finite vertices')
    chosen /= chosen.sum(axis=1, keepdims=True)
    scaled = chosen * 255
    quantized = np.floor(scaled).astype(np.int16)
    remainder = 255 - quantized.sum(axis=1)
    order = np.argsort(scaled - quantized, axis=1)[:, ::-1]
    for rank in range(4):
        rows = np.flatnonzero(remainder > rank)
        quantized[rows, order[rows, rank]] += 1
    if not np.all(quantized.sum(axis=1) == 255):
        raise ValueError('Weight quantization failed')
    return indexes.astype(np.uint16), quantized.astype(np.uint8)


def transfer_weights(source: np.ndarray, target: np.ndarray, weights: np.ndarray,
                     chunk: int = 16384) -> tuple[np.ndarray, np.ndarray]:
    """Transfer the solved low-poly skin back to the unmodified source mesh."""
    tree = cKDTree(source)
    indexes = np.empty((len(target), 4), dtype=np.uint16)
    quantized = np.empty((len(target), 4), dtype=np.uint8)
    for start in range(0, len(target), chunk):
        stop = min(start + chunk, len(target))
        distance, neighbors = tree.query(target[start:stop], k=3, workers=2)
        blend = 1 / np.maximum(distance, 1e-7) ** 2
        blend /= blend.sum(axis=1, keepdims=True)
        combined = np.sum(weights[neighbors] * blend[:, :, None], axis=1)
        indexes[start:stop], quantized[start:stop] = pack_four_weights(combined)
    return indexes, quantized


def two_bone_ik(hip: np.ndarray, ankle: np.ndarray, upper: float, lower: float,
                pole: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Analytic two-bone IK, stable even for unreachable/degenerate targets."""
    delta = np.asarray(ankle, dtype=float) - hip
    distance = np.linalg.norm(delta)
    direction = delta / distance if distance > 1e-9 else np.array([0., 0., -1.])
    distance = np.clip(distance, abs(upper - lower) + 1e-6, upper + lower - 1e-6)
    reachable = hip + direction * distance
    bend = pole - direction * np.dot(pole, direction)
    if np.linalg.norm(bend) < 1e-8:
        fallback = np.array([1., 0., 0.])
        if abs(np.dot(fallback, direction)) > 0.9:
            fallback = np.array([0., 1., 0.])
        bend = fallback - direction * np.dot(fallback, direction)
    bend /= np.linalg.norm(bend)
    along = (upper * upper - lower * lower + distance * distance) / (2 * distance)
    altitude = np.sqrt(max(upper * upper - along * along, 0))
    knee = hip + along * direction + altitude * bend
    return knee, reachable


def node_matrix(node: dict) -> np.ndarray:
    if 'matrix' in node:
        return np.asarray(node['matrix']).reshape(4, 4).T
    translation = np.asarray(node.get('translation', [0, 0, 0]))
    scale = np.asarray(node.get('scale', [1, 1, 1]))
    x, y, z, w = node.get('rotation', [0, 0, 0, 1])
    rotation = np.array([[1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)],
                         [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)],
                         [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]])
    matrix = np.eye(4)
    matrix[:3, :3] = rotation @ np.diag(scale)
    matrix[:3, 3] = translation
    return matrix


def world_matrices(document: dict) -> list[np.ndarray]:
    nodes = document['nodes']
    parents = {child: parent for parent, node in enumerate(nodes) for child in node.get('children', [])}
    memo = {}
    def world(index):
        if index not in memo:
            local = node_matrix(nodes[index])
            memo[index] = world(parents[index]) @ local if index in parents else local
        return memo[index]
    return [world(i) for i in range(len(nodes))]


def validate_skin(path: Path, required_animations: set[str]) -> dict:
    document, binary = read_glb(path)
    if len(document.get('skins', [])) != 1:
        raise ValueError('Expected exactly one skin')
    animations = {a['name'] for a in document.get('animations', [])}
    if animations != required_animations:
        raise ValueError(f'Animation mismatch: {animations}')
    skin = document['skins'][0]
    world = world_matrices(document)
    bind = read_accessor(document, binary, skin['inverseBindMatrices']).reshape(-1, 4, 4).transpose(0, 2, 1)
    skin_nodes = [(i, n) for i, n in enumerate(document['nodes']) if n.get('skin') == 0]
    if len(skin_nodes) != 1:
        raise ValueError('Expected one skinned mesh node')
    node_index, mesh_node = skin_nodes[0]
    error = max(float(np.abs(np.linalg.inv(world[node_index]) @ world[joint] @ matrix - np.eye(4)).max())
                for joint, matrix in zip(skin['joints'], bind))
    if error > 2e-4:
        raise ValueError(f'Invalid rest/inverse-bind transforms: {error}')
    vertices, unweighted, max_influences = 0, 0, 0
    for primitive in document['meshes'][mesh_node['mesh']]['primitives']:
        attrs = primitive['attributes']
        joints = read_accessor(document, binary, attrs['JOINTS_0'])
        weights = read_accessor(document, binary, attrs['WEIGHTS_0'])
        if joints.max() >= len(skin['joints']):
            raise ValueError('Joint index out of range')
        if not np.all(np.isfinite(weights)) or np.max(np.abs(weights.sum(axis=1) - 1)) > 2e-5:
            raise ValueError('Invalid or unnormalized skin weights')
        vertices += len(weights)
        unweighted += int(np.sum(weights.sum(axis=1) == 0))
        max_influences = max(max_influences, int((weights > 0).sum(axis=1).max()))
    for animation in document['animations']:
        for sampler in animation['samplers']:
            times = read_accessor(document, binary, sampler['input']).ravel()
            if len(times) < 2 or np.any(np.diff(times) <= 0):
                raise ValueError(f'Invalid keyframe times: {animation["name"]}')
    return {'joints': len(skin['joints']), 'vertices': vertices, 'unweighted_vertices': unweighted,
            'max_influences': max_influences, 'rest_bind_error': error, 'animations': sorted(animations)}


def make_full_resolution(source_path: Path, runtime_path: Path, output_path: Path,
                         bone_names: list[str], joint_indexes: np.ndarray,
                         quantized_weights: np.ndarray) -> None:
    """Preserve the original GLB geometry/textures and append skin + animations.

    Only skin attributes and additional nodes are added to the original model.
    Original POSITION, NORMAL, UV, index and image buffer views are not rewritten.
    """
    document, original_binary = read_glb(source_path)
    runtime, runtime_binary = read_glb(runtime_path)
    if len(document['nodes']) != 1 or len(document['meshes']) != 1:
        raise ValueError('Full-resolution assembly expects this source model\'s one-mesh layout')
    binary = bytearray(original_binary)
    binary += b'\0' * (-len(binary) % 4)
    runtime_offset = len(binary)
    binary.extend(runtime_binary)
    view_offset = len(document['bufferViews'])
    accessor_offset = len(document['accessors'])
    for view in runtime['bufferViews']:
        copied = copy.deepcopy(view)
        copied['byteOffset'] = copied.get('byteOffset', 0) + runtime_offset
        document['bufferViews'].append(copied)
    for accessor in runtime['accessors']:
        copied = copy.deepcopy(accessor)
        copied['bufferView'] += view_offset
        document['accessors'].append(copied)
    mapping = {}
    for index, node in enumerate(runtime['nodes']):
        if 'mesh' not in node:
            mapping[index] = len(document['nodes'])
            document['nodes'].append(copy.deepcopy(node))
    for old, new in mapping.items():
        node = document['nodes'][new]
        if 'children' in node:
            node['children'] = [mapping[i] for i in node['children'] if i in mapping]
    root_nodes = runtime['scenes'][runtime.get('scene', 0)]['nodes']
    document['scenes'][document.get('scene', 0)]['nodes'].extend(mapping[i] for i in root_nodes if i in mapping)
    skin = copy.deepcopy(runtime['skins'][0])
    skin['joints'] = [mapping[i] for i in skin['joints']]
    if 'skeleton' in skin:
        skin['skeleton'] = mapping[skin['skeleton']]
    skin['inverseBindMatrices'] += accessor_offset
    document['skins'] = [skin]
    document['nodes'][0]['skin'] = 0
    mesh_transform = node_matrix(document['nodes'][0])
    bind = read_accessor(document, binary, skin['inverseBindMatrices']).reshape(-1, 4, 4).transpose(0, 2, 1)
    corrected = bind @ mesh_transform
    accessor = document['accessors'][skin['inverseBindMatrices']]
    view = document['bufferViews'][accessor['bufferView']]
    offset = view.get('byteOffset', 0) + accessor.get('byteOffset', 0)
    raw = corrected.transpose(0, 2, 1).astype('<f4').tobytes()
    binary[offset:offset + len(raw)] = raw
    runtime_names = {runtime['nodes'][node]['name']: i for i, node in enumerate(runtime['skins'][0]['joints'])}
    remap = np.asarray([runtime_names[name] for name in bone_names], dtype=np.uint8)
    full_joints = remap[joint_indexes]

    def append_attribute(array: np.ndarray, component: int, normalized: bool) -> int:
        binary.extend(b'\0' * (-len(binary) % 4))
        offset = len(binary)
        raw = array.tobytes()
        binary.extend(raw)
        view = len(document['bufferViews'])
        document['bufferViews'].append({'buffer': 0, 'byteOffset': offset, 'byteLength': len(raw), 'target': 34962})
        accessor = len(document['accessors'])
        value = {'bufferView': view, 'componentType': component, 'count': len(array), 'type': 'VEC4'}
        if normalized:
            value['normalized'] = True
        document['accessors'].append(value)
        return accessor

    attrs = document['meshes'][0]['primitives'][0]['attributes']
    attrs['JOINTS_0'] = append_attribute(full_joints, 5121, False)
    attrs['WEIGHTS_0'] = append_attribute(quantized_weights, 5121, True)
    document['animations'] = []
    for original in runtime['animations']:
        animation = copy.deepcopy(original)
        for sampler in animation['samplers']:
            sampler['input'] += accessor_offset
            sampler['output'] += accessor_offset
        for channel in animation['channels']:
            channel['target']['node'] = mapping[channel['target']['node']]
        document['animations'].append(animation)
    document['asset']['generator'] = 'Blender 4.2 LTS + SciPy; original Tripo mesh preserved'
    write_glb(output_path, document, binary)
