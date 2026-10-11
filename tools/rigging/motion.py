"""Procedural motions expressed in glTF world coordinates, reusable for audits."""
from __future__ import annotations

import math

import numpy as np
from scipy.spatial.transform import Rotation

from core import two_bone_ik

CLIPS = {'Idle': 4.0, 'Walk_InPlace': 1.2, 'Run_InPlace': 0.8, 'Wave': 3.0, 'Dance': 4.0}
FPS = 30
PREFIX = 'mixamorig:'
# Blender world axes -> glTF world axes. Local bone axes are not rebased.
BLENDER_TO_GLTF = np.array([[1., 0., 0.], [0., 0., 1.], [0., -1., 0.]])


def rotation(axis_blender, degrees):
    axis = BLENDER_TO_GLTF @ np.asarray(axis_blender, dtype=float)
    axis /= np.linalg.norm(axis)
    return Rotation.from_rotvec(axis * math.radians(degrees)).as_matrix()


def rotation_difference(before, after):
    before = np.asarray(before, dtype=float) / np.linalg.norm(before)
    after = np.asarray(after, dtype=float) / np.linalg.norm(after)
    dot = float(np.clip(before @ after, -1, 1))
    axis = np.cross(before, after)
    if np.linalg.norm(axis) < 1e-9:
        if dot > 0:
            return np.eye(3)
        basis = np.eye(3)[np.argmin(np.abs(before))]
        axis = np.cross(before, basis)
    axis /= np.linalg.norm(axis)
    return Rotation.from_rotvec(axis * math.acos(dot)).as_matrix()


def rest_matrices(bones, origin=None, height=1.):
    origin = np.zeros(3) if origin is None else np.asarray(origin)
    world = {}
    for bone in bones:
        direction = bone.tail - bone.head
        direction /= np.linalg.norm(direction)
        target = np.array([0., 0., 1.]) if abs(direction[2]) < .9 else np.array([0., 1., 0.])
        z_axis = target - direction * (target @ direction)
        z_axis /= np.linalg.norm(z_axis)
        x_axis = np.cross(direction, z_axis)
        matrix = np.eye(4)
        matrix[:3, :3] = np.column_stack([x_axis, direction, z_axis])
        matrix[:3, 3] = origin + bone.head * height
        world[bone.name] = matrix
    local = {b.name: np.linalg.inv(world[b.parent]) @ world[b.name] if b.parent else world[b.name].copy() for b in bones}
    return world, local


def clip_deltas(bones, name, phase):
    identity = np.eye(3)
    deltas = {b.name: identity.copy() for b in bones}
    heads = {b.name: b.head for b in bones}
    tails = {b.name: b.tail for b in bones}
    hips_name = PREFIX + 'Hips'
    t = 2 * math.pi * phase
    sin, cos = math.sin(t), math.cos(t)
    location = np.zeros(3)
    if name == 'Idle':
        location[1] = .0018 * sin
        deltas[hips_name] = rotation((0, 0, 1), .8 * sin)
        for part, amount in [('Spine', .65), ('Spine1', 1.1), ('Spine2', 1.4)]:
            deltas[PREFIX + part] = rotation((1, 0, 0), amount * sin)
        deltas[PREFIX + 'Neck'] = rotation((0, 0, 1), 1.6 * cos)
        deltas[PREFIX + 'Head'] = rotation((0, 0, 1), 2.2 * cos) @ rotation((1, 0, 0), .8 * sin)
    elif name in ('Walk_InPlace', 'Run_InPlace', 'Dance'):
        running, dancing = name == 'Run_InPlace', name == 'Dance'
        location[1] = -.009 + .002 * math.cos(2*t)
        if running:
            location[1] = -.014 + .007 * math.cos(2*t)
        if dancing:
            location[0] = .014 * sin
            location[1] = -.018 + .004 * math.cos(2*t)
        yaw = (7 if dancing else 4 if running else 2.5) * sin
        deltas[hips_name] = rotation((0, 0, 1), yaw)
        if running:
            deltas[hips_name] = rotation((1, 0, 0), 6) @ deltas[hips_name]
        for part, factor in [('Spine', .4), ('Spine1', .6), ('Spine2', .8), ('Head', .4)]:
            deltas[PREFIX + part] = rotation((0, 0, 1), -yaw * factor)
        if dancing:
            deltas[hips_name] = rotation((0, 1, 0), 3 * sin) @ deltas[hips_name]
        for side, offset in [('Left', 0), ('Right', math.pi)]:
            s, c = math.sin(t+offset), math.cos(t+offset)
            upper, lower = PREFIX + side + 'UpLeg', PREFIX + side + 'Leg'
            hip = heads[hips_name] + deltas[hips_name] @ (heads[upper]-heads[hips_name]) + location
            foot = tails[lower].copy()
            foot[2] = .006 - (.078 if running else .038 if not dancing else .012) * c
            foot[1] += (.045 if running else .025 if not dancing else .009) * max(0, s)
            upper_length = np.linalg.norm(tails[upper]-heads[upper])
            lower_length = np.linalg.norm(tails[lower]-heads[lower])
            knee, reachable = two_bone_ik(hip, foot, upper_length, lower_length, np.array([0., 0., 1.]))
            deltas[upper] = rotation_difference(tails[upper]-heads[upper], knee-hip)
            deltas[lower] = rotation_difference(tails[lower]-heads[lower], reachable-knee)
            deltas[PREFIX + side + 'Foot'] = rotation((1, 0, 0), -7*max(0, s) if running else -3*max(0, s))
        amount = 15 if dancing else 22 if running else 11
        deltas[PREFIX + 'RightArm'] = rotation((1, 0, 0), amount * sin)
        deltas[PREFIX + 'RightForeArm'] = rotation((1, 0, 0), amount * sin + (5 if running else 0))
        deltas[PREFIX + 'RightHand'] = deltas[PREFIX + 'RightForeArm'].copy()
        for part in ('Arm', 'ForeArm', 'Hand'):
            deltas[PREFIX + 'Left' + part] = rotation((1, 0, 0), 1.7 * sin)
    elif name == 'Wave':
        envelope = min(1., phase/.20, (1-phase)/.20)
        envelope = envelope * envelope * (3-2*envelope)
        upper, lower, hand = (PREFIX + 'Right' + part for part in ('Arm', 'ForeArm', 'Hand'))
        target_upper = BLENDER_TO_GLTF @ np.array([-.12, -.025, -.03])
        target_lower = BLENDER_TO_GLTF @ np.array([-.015, -.040, .145])
        for part, target in [(upper, target_upper), (lower, target_lower)]:
            delta = rotation_difference(tails[part]-heads[part], target)
            deltas[part] = Rotation.from_rotvec(Rotation.from_matrix(delta).as_rotvec()*envelope).as_matrix()
        deltas[hand] = rotation((0, 1, 0), 13*math.sin(6*t)*envelope) @ deltas[lower]
        deltas[PREFIX + 'Head'] = rotation((0, 0, 1), 4*envelope)
    else:
        raise ValueError(f'Unknown clip: {name}')
    for index, bone in enumerate(bones):
        if bone.category in ('hair', 'coat', 'skirt'):
            amount = {'Idle': .8, 'Walk_InPlace': 2., 'Run_InPlace': 3.3, 'Wave': 1., 'Dance': 3.}[name]
            amount *= 1. if bone.category == 'hair' else .65
            if '.Held.' in bone.name:
                amount *= .20
            deltas[bone.name] = deltas[bone.parent] @ rotation((0, 1, 0), amount * math.sin(t+index*.3))
    return deltas, location
