"""Bounded biharmonic skinning and face-based transfer using the real libigl."""
from __future__ import annotations

import time

import igl
import numpy as np
from scipy.spatial import cKDTree
from scipy.sparse.csgraph import connected_components

from core import pack_skin_weights


def solve_bbw(vertices, triangles, bones, locality_power=1., log=print):
    started = time.monotonic()
    vertices = np.asarray(vertices, dtype=np.float64)
    triangles = np.asarray(triangles, dtype=np.int64)
    _, first, remap = np.unique(np.rint(vertices*1e7).astype(np.int64), axis=0,
                                return_index=True, return_inverse=True)
    v = vertices[first]
    f = remap[triangles]
    areas = np.linalg.norm(np.cross(v[f[:, 1]]-v[f[:, 0]], v[f[:, 2]]-v[f[:, 0]]), axis=1)
    f = f[areas > 1e-14]
    if not len(f):
        raise ValueError('No nondegenerate solver triangles')
    deforming = [i for i, b in enumerate(bones) if b.category != 'root']
    tree = cKDTree(v)
    votes = {}
    for column, index in enumerate(deforming):
        bone = bones[index]
        # A thigh handle must not be placed on the outside of the skirt.
        lower = .50 if bone.name.endswith('UpLeg') else .20
        points = bone.head + np.linspace(lower, .80, 7)[:, None] * (bone.tail-bone.head)
        _, anchors = tree.query(points)
        for vertex in np.unique(anchors):
            votes.setdefault(int(vertex), np.zeros(len(deforming)))[column] += 1
    graph = igl.adjacency_matrix(f)
    if graph.shape[0] != len(v):
        from scipy.sparse import coo_matrix
        coo = graph.tocoo()
        graph = coo_matrix((coo.data, (coo.row, coo.col)), shape=(len(v), len(v))).tocsr()
    components, labels = connected_components(graph, directed=False)
    anchored = {int(labels[index]) for index in votes}
    for component in range(components):
        if component in anchored:
            continue
        members = np.flatnonzero(labels == component)
        center = v[members].mean(axis=0)
        distance = [np.linalg.norm(center - (bones[i].head+bones[i].tail)/2) for i in deforming]
        votes[int(members[0])] = np.eye(len(deforming))[int(np.argmin(distance))]
    boundary = np.array(sorted(votes), dtype=np.int64)
    bc = np.asarray([votes[int(i)] for i in boundary], dtype=np.float64)
    # Shared surface anchors get shared weights, never conflicting adjacent pins.
    bc /= bc.sum(axis=1, keepdims=True)
    log(f'libigl BBW: {len(v):,} vertices, {len(boundary)} handles, {components} surface components')
    warm_start = igl.harmonic(v, f, boundary, bc, 2)
    if not np.all(np.isfinite(warm_start)):
        raise ValueError('Non-finite biharmonic warm start')
    warm_start = np.clip(warm_start, 0, 1)
    warm_start /= np.maximum(warm_start.sum(axis=1, keepdims=True), 1e-20)
    field = igl.bbw(v, f, boundary, bc, W0=warm_start, max_iter=30, verbosity=0)
    if not np.all(np.isfinite(field)) or field.min() < -.02 or field.max() > 1.02:
        raise ValueError('BBW did not produce a numerically bounded solution')
    raw_range = [float(field.min()), float(field.max())]
    field = np.clip(field, 0, 1) ** locality_power
    sums = field.sum(axis=1, keepdims=True)
    if np.any(sums <= 1e-20):
        raise ValueError('BBW produced an unweighted vertex')
    field /= sums
    result = np.zeros((len(v), len(bones)), dtype=np.float32)
    result[:, deforming] = field
    log(f'BBW solved in {time.monotonic()-started:.1f}s; raw bounds {raw_range}')
    return result[remap], {'method': 'libigl bounded biharmonic weights',
        'solver_vertices': len(v), 'solver_triangles': len(f),
        'surface_components': components, 'boundary_vertices': len(boundary),
        'raw_numerical_bounds': raw_range, 'locality_power': locality_power,
        'max_active_set_iterations': 30, 'elapsed_seconds': time.monotonic()-started}


def transfer_surface_weights(vertices, triangles, target, weights, chunk=65536):
    """Interpolate one nearest TRIANGLE, not three unrelated nearby vertices.

    The source and destination geometry are never changed. This avoids mixing
    neighboring, disconnected hand/clothing surfaces during high-poly transfer.
    """
    vertices = np.asarray(vertices, dtype=np.float64)
    triangles = np.asarray(triangles, dtype=np.int64)
    target = np.asarray(target, dtype=np.float64)
    weights = np.asarray(weights, dtype=np.float32)
    area = np.linalg.norm(np.cross(vertices[triangles[:, 1]]-vertices[triangles[:, 0]],
                                  vertices[triangles[:, 2]]-vertices[triangles[:, 0]]), axis=1)
    triangles = triangles[area > 1e-18]
    if not len(triangles):
        raise ValueError('Cannot transfer from a degenerate surface')
    tree = igl.AABB()
    tree.init(vertices, triangles)
    indexes = np.empty((len(target), 4), dtype=np.uint16)
    quantized = np.empty((len(target), 4), dtype=np.uint16)
    distance = np.empty(len(target), dtype=np.float64)
    for start in range(0, len(target), chunk):
        stop = min(start+chunk, len(target))
        squared, faces, closest = tree.squared_distance(vertices, triangles, target[start:stop])
        if np.any(faces < 0):
            raise ValueError('Nearest-surface transfer could not find a face')
        corners = triangles[faces]
        barycentric = igl.barycentric_coordinates(closest, vertices[corners[:, 0]],
                                                   vertices[corners[:, 1]], vertices[corners[:, 2]])
        if not np.all(np.isfinite(barycentric)):
            raise ValueError('Invalid barycentric coordinates')
        barycentric = np.clip(barycentric, 0, 1)
        barycentric /= np.maximum(barycentric.sum(axis=1, keepdims=True), 1e-20)
        blended = np.einsum('ni,nij->nj', barycentric, weights[corners])
        indexes[start:stop], quantized[start:stop] = pack_skin_weights(blended)
        distance[start:stop] = np.sqrt(np.maximum(squared, 0))
    return indexes, quantized, {'method': 'nearest-face barycentric interpolation',
        'target_vertices': len(target), 'distance_median': float(np.median(distance)),
        'distance_p99': float(np.percentile(distance, 99)), 'distance_max': float(distance.max()),
        'weight_precision_bits': 16, 'max_influences': 4}


def localize_fields(vertices, field, bones):
    """Suppress far-away limb/strand influences before four-slot sparsification.

    BBW is smooth but can retain small global tails. Truncating a distant moving
    arm in/out of the fourth slot can tear tiny high-poly edges on a jacket.
    Compact anatomical envelopes remove that tail continuously; removed mass is
    distributed to a smooth torso partition, never left unnormalized.
    """
    from core import segment_distances, smoothstep
    vertices = np.asarray(vertices, dtype=np.float64)
    result = np.asarray(field, dtype=np.float64).copy()
    distances = segment_distances(vertices, bones)
    torso = [i for i, b in enumerate(bones) if b.name in (
        'mixamorig:Hips', 'mixamorig:Spine', 'mixamorig:Spine1',
        'mixamorig:Spine2', 'mixamorig:Neck', 'mixamorig:Head')]
    if not torso:
        raise ValueError('Expected torso hierarchy for field localization')
    lost = np.zeros(len(vertices))
    for i, bone in enumerate(bones):
        moving_arm = bone.category == 'body' and bone.name.endswith(('Arm', 'ForeArm', 'Hand'))
        front_strand = bone.name.startswith('Hair.Front.')
        if not moving_arm and not front_strand:
            continue
        inner, outer = ((bone.radius*1.25, bone.radius*2.5) if moving_arm
                        else (bone.radius*2., bone.radius*5.))
        gate = 1-smoothstep(inner, outer, distances[:, i])
        if front_strand:
            gate *= smoothstep(.710, .755, vertices[:, 1])
        lost += result[:, i]*(1-gate)
        result[:, i] *= gate
    # A height-based torso partition remains smooth even where the original
    # torso field is almost zero (hair tips and close-contact mesh islands).
    centers = np.array([(bones[i].head[1]+bones[i].tail[1])/2 for i in torso])
    logits = -((vertices[:, 1, None]-centers[None, :])/.055)**2
    logits -= logits.max(axis=1, keepdims=True)
    recipient = np.exp(logits)
    recipient /= np.maximum(recipient.sum(axis=1, keepdims=True), 1e-20)
    result[:, torso] += recipient*lost[:, None]
    result /= np.maximum(result.sum(axis=1, keepdims=True), 1e-20)
    return result.astype(np.float32)
