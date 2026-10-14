"""Audit actual exported glTF skin deformations, not merely container validity.

This evaluates the exported animation samplers and linear blend skin matrices on
CPU. No browser, viewer, or stand-in geometry is involved.
"""
from __future__ import annotations

from pathlib import Path

import numpy as np

from core import read_accessor, read_sparse_accessor, read_glb, world_matrices


def quaternion_slerp(a, b, factor):
    a = np.array(a, dtype=np.float64, copy=True)
    b = np.array(b, dtype=np.float64, copy=True)
    a /= np.linalg.norm(a)
    b /= np.linalg.norm(b)
    dot = float(np.dot(a, b))
    if dot < 0:
        b = -b
        dot = -dot
    if dot > 0.9995:
        result = a + factor * (b - a)
        return result / np.linalg.norm(result)
    angle = np.arccos(np.clip(dot, -1, 1))
    return (np.sin((1 - factor) * angle) * a + np.sin(factor * angle) * b) / np.sin(angle)


class SkinEvaluator:
    def __init__(self, path: Path):
        self.document, self.binary = read_glb(path)
        doc, raw = self.document, self.binary
        if len(doc.get('skins', [])) != 1:
            raise ValueError('Quality audit expects one skin')
        self.skin = doc['skins'][0]
        skin_nodes = [(i, n) for i, n in enumerate(doc['nodes']) if n.get('skin') == 0]
        if len(skin_nodes) != 1:
            raise ValueError('Quality audit expects one skinned mesh')
        self.node_index, node = skin_nodes[0]
        primitives = doc['meshes'][node['mesh']]['primitives']
        if len(primitives) != 1 or primitives[0].get('mode', 4) != 4:
            raise ValueError('Quality audit expects this model\'s triangle primitive')
        self.primitive = primitives[0]
        attrs = self.primitive['attributes']
        self.positions = read_accessor(doc, raw, attrs['POSITION']).astype(np.float64)
        self.triangles = read_accessor(doc, raw, self.primitive['indices']).ravel().reshape(-1, 3)
        self.morphs = []
        for target in self.primitive.get('targets', []):
            index = target['POSITION']
            accessor = doc['accessors'][index]
            if 'sparse' in accessor and 'bufferView' not in accessor:
                self.morphs.append(read_sparse_accessor(doc, raw, index))
            else:
                data = read_accessor(doc, raw, index)
                ids = np.flatnonzero(np.linalg.norm(data, axis=1)>0)
                self.morphs.append((ids, data[ids]))
        self.default_morph_weights = node.get('weights', doc['meshes'][node['mesh']].get('weights', [0.]*len(self.morphs)))
        self.joints = read_accessor(doc, raw, attrs['JOINTS_0'])
        self.weights = read_accessor(doc, raw, attrs['WEIGHTS_0'])
        self.inverse_bind = read_accessor(doc, raw, self.skin['inverseBindMatrices']).reshape(-1, 4, 4).transpose(0, 2, 1)
        self.channels = {}
        self.durations = {}
        for animation in doc.get('animations', []):
            channels = []
            duration = 0.
            for channel in animation['channels']:
                sampler = animation['samplers'][channel['sampler']]
                interpolation = sampler.get('interpolation', 'LINEAR')
                if interpolation not in ('LINEAR', 'STEP'):
                    raise ValueError(f'Unsupported audit interpolation: {interpolation}')
                times = read_accessor(doc, raw, sampler['input']).ravel()
                values = read_accessor(doc, raw, sampler['output'])
                target = channel['target']
                if target['path'] == 'weights':
                    values = values.reshape(len(times), len(self.morphs))
                channels.append((target['node'], target['path'], times, values, interpolation))
                duration = max(duration, float(times[-1]))
            self.channels[animation['name']] = channels
            self.durations[animation['name']] = duration
        self.height = float(np.ptp(self.positions[:, 1]))
        self.joint_names = [doc['nodes'][i].get('name', str(i)) for i in self.skin['joints']]

    def matrices(self, name: str | None = None, time: float = 0.) -> np.ndarray:
        doc = dict(self.document)
        doc['nodes'] = [dict(node) for node in self.document['nodes']]
        if name is not None:
            for node, kind, times, values, interpolation in self.channels[name]:
                if kind == 'weights':
                    continue
                left = int(np.clip(np.searchsorted(times, time, side='right') - 1, 0, len(times) - 1))
                right = min(left + 1, len(times) - 1)
                fraction = 0. if left == right else float(np.clip((time-times[left]) / (times[right]-times[left]), 0, 1))
                if interpolation == 'STEP' or left == right:
                    value = values[left]
                elif kind == 'rotation':
                    value = quaternion_slerp(values[left], values[right], fraction)
                else:
                    value = values[left] * (1-fraction) + values[right] * fraction
                doc['nodes'][node].pop('matrix', None)
                doc['nodes'][node][kind] = value.tolist()
        world = world_matrices(doc)
        return np.asarray([world[index] for index in self.skin['joints']]) @ self.inverse_bind

    def deform(self, matrices: np.ndarray, indexes=None, weights=None, chunk=65536, positions=None) -> np.ndarray:
        indexes = self.joints if indexes is None else indexes
        weights = self.weights if weights is None else weights
        positions = self.positions if positions is None else positions
        result = np.empty_like(positions)
        for start in range(0, len(result), chunk):
            stop = min(start + chunk, len(result))
            p = positions[start:stop]
            deformed = np.zeros_like(p)
            for slot in range(indexes.shape[1]):
                transforms = matrices[indexes[start:stop, slot]]
                moved = np.einsum('nij,nj->ni', transforms[:, :3, :3], p) + transforms[:, :3, 3]
                deformed += moved * weights[start:stop, slot, None]
            result[start:stop] = deformed
        return result

    def positions_at(self, name=None, time=0.):
        amounts = np.array(self.default_morph_weights, dtype=float)
        if name is not None:
            for node, kind, times, values, interpolation in self.channels[name]:
                if kind != 'weights' or node != self.node_index:
                    continue
                left = int(np.clip(np.searchsorted(times, time, side='right')-1, 0, len(times)-1))
                right = min(left+1, len(times)-1)
                fraction = 0 if left == right else float(np.clip((time-times[left])/(times[right]-times[left]), 0, 1))
                amounts = values[left] if interpolation == 'STEP' else values[left]*(1-fraction)+values[right]*fraction
        if not np.any(amounts):
            return self.positions
        result = self.positions.copy()
        for amount, (ids, delta) in zip(amounts, self.morphs):
            if amount:
                result[ids] += amount*delta
        return result

    def posed(self, name, time):
        return self.deform(self.matrices(name, time), positions=self.positions_at(name, time))

    def audit(self, frames=21, indexes=None, weights=None, worst_edges=0) -> dict:
        indexes = self.joints if indexes is None else indexes
        weights = self.weights if weights is None else weights
        rest = self.deform(self.matrices(), indexes, weights)
        edges = np.concatenate([self.triangles[:, [0, 1]], self.triangles[:, [1, 2]], self.triangles[:, [2, 0]]])
        # Count each physical edge only once and remove UV duplicate edges.
        unique, remap = np.unique(np.rint(rest/self.height*1e7).astype(np.int64), axis=0, return_inverse=True)
        _, first = np.unique(remap, return_index=True)
        physical_edges = np.unique(np.sort(remap[edges], axis=1), axis=0)
        edges = first[physical_edges]
        lengths = np.linalg.norm(rest[edges[:, 0]] - rest[edges[:, 1]], axis=1)
        valid = lengths > self.height * 1e-4
        edges, lengths = edges[valid], lengths[valid]
        results = {}
        for name, duration in self.durations.items():
            summary = {'sampled_frames': frames, 'edge_stretch_p99_5': 0.,
                       'edge_stretch_p99_9': 0., 'edge_stretch_max': 0.,
                       'edge_compression_p00_5': 1., 'maximum_extent_in_model_heights': 0.}
            worst = None
            for time in np.linspace(0, duration, frames):
                posed = self.deform(self.matrices(name, float(time)), indexes, weights, positions=self.positions_at(name, float(time)))
                if not np.all(np.isfinite(posed)):
                    raise ValueError(f'Non-finite exported deformation: {name}')
                ratio = np.linalg.norm(posed[edges[:, 0]] - posed[edges[:, 1]], axis=1) / lengths
                p005, p995, p999 = np.percentile(ratio, [.5, 99.5, 99.9])
                if p995 > summary['edge_stretch_p99_5']:
                    summary['worst_time'] = float(time)
                    worst = (float(time), ratio.copy()) if worst_edges else None
                summary['edge_stretch_p99_5'] = max(summary['edge_stretch_p99_5'], float(p995))
                summary['edge_stretch_p99_9'] = max(summary['edge_stretch_p99_9'], float(p999))
                summary['edge_stretch_max'] = max(summary['edge_stretch_max'], float(ratio.max()))
                summary['edge_compression_p00_5'] = min(summary['edge_compression_p00_5'], float(p005))
                summary['maximum_extent_in_model_heights'] = max(summary['maximum_extent_in_model_heights'], float(np.ptp(posed, axis=0).max()/self.height))
            if worst is not None:
                time, ratio = worst
                rows = np.argsort(ratio)[-worst_edges:][::-1]
                summary['worst_time'] = time
                summary['worst_edges'] = [{'ratio': float(ratio[i]),
                    'positions': rest[edges[i]].tolist(),
                    'joint_names': [[self.joint_names[j] for j in indexes[v]] for v in edges[i]],
                    'weights': weights[edges[i]].tolist()} for i in rows]
            results[name] = summary
        return results


def assert_geometry_preserved(source_path: Path, output_path: Path, allow_morph_targets=False) -> dict:
    """Require exact original attribute definitions, binary buffers and textures."""
    source, original = read_glb(source_path)
    output, current = read_glb(output_path)
    if current[:len(original)] != original:
        raise ValueError('Original geometry or texture buffer bytes were modified')
    for key in ('bufferViews', 'accessors'):
        count = len(source.get(key, []))
        if output.get(key, [])[:count] != source.get(key, []):
            raise ValueError(f'Original {key} layout was modified')
    for key in ('images', 'textures', 'materials'):
        if output.get(key, []) != source.get(key, []):
            raise ValueError(f'Original {key} were modified')
    if len(source['meshes']) != len(output['meshes']):
        raise ValueError('Original mesh count changed')
    for original_mesh, output_mesh in zip(source['meshes'], output['meshes']):
        if len(original_mesh['primitives']) != len(output_mesh['primitives']):
            raise ValueError('Original primitive count changed')
        for a, b in zip(original_mesh['primitives'], output_mesh['primitives']):
            for key, value in a['attributes'].items():
                if b['attributes'].get(key) != value:
                    raise ValueError(f'Original {key} attribute was replaced')
            for key in ('indices', 'material', 'mode') + (() if allow_morph_targets else ('targets',)):
                if a.get(key) != b.get(key):
                    raise ValueError(f'Original primitive {key} was replaced')
    import hashlib
    return {'geometry_and_textures_byte_identical': True,
            'original_binary_bytes': len(original),
            'original_binary_sha256': hashlib.sha256(original).hexdigest()}
