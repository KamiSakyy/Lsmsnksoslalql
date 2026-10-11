"""Geometry-fitted joints for the supplied asymmetric Tripo character.

Do not infer depth from a frontal picture: in this source the +X leg is bent
backwards and the free -X hand is behind, not in front of, the skirt.
"""
from __future__ import annotations

import copy

import numpy as np

from core import Bone


def fit_bones(vertices: np.ndarray, bones: list[Bone]) -> tuple[list[Bone], dict]:
    """Fit visible limb cross sections; preserve hidden anatomical assumptions.

    Input is height-normalized, source-local glTF coordinates. The selection
    windows are specific to this character's pose, not a universal autorigger.
    Original vertices are NEVER changed.
    """
    vertices = np.asarray(vertices, dtype=np.float64)
    result = copy.deepcopy(bones)
    by_name = {b.name: b for b in result}
    measurements = {}

    def section(label, y, xlo, xhi, zlo=-np.inf, zhi=np.inf):
        mask = ((np.abs(vertices[:, 1] - y) < .004)
                & (vertices[:, 0] > xlo) & (vertices[:, 0] < xhi)
                & (vertices[:, 2] > zlo) & (vertices[:, 2] < zhi))
        points = vertices[mask]
        if len(points) < 30:
            raise ValueError(f'Insufficient source geometry to fit {label}: {len(points)} points')
        low, high = np.quantile(points, [.05, .95], axis=0)
        center = (low + high) / 2
        center[1] = y
        measurements[label] = {'points': len(points), 'center': center.tolist(),
                               'bounds_p05': low.tolist(), 'bounds_p95': high.tolist()}
        return center

    for side, sign in (('Left', 1), ('Right', -1)):
        xlo, xhi = (.012, .085) if sign > 0 else (-.085, -.012)
        knee = section(side + 'Knee', .29, xlo, xhi)
        ankle = section(side + 'Ankle', .076, xlo, xhi)
        hip = by_name['mixamorig:' + side + 'UpLeg'].head.copy()
        hip[0] = knee[0]
        upper = by_name['mixamorig:' + side + 'UpLeg']
        lower = by_name['mixamorig:' + side + 'Leg']
        foot = by_name['mixamorig:' + side + 'Foot']
        toe = by_name['mixamorig:' + side + 'ToeBase']
        upper.head, upper.tail = hip, knee
        lower.head, lower.tail = knee.copy(), ankle
        foot.head = ankle.copy()
        toe.head[0] = ankle[0]
        toe.tail[0] = ankle[0]
        foot.tail[0] = ankle[0]
        if side == 'Left':
            # This is the actually raised/backward foot, not the opposite foot.
            foot.tail = np.array([ankle[0], .029, -.040])
            toe.head = foot.tail.copy()
            toe.tail = np.array([ankle[0], .022, -.014])
        else:
            foot.tail = np.array([ankle[0], .021, .085])
            toe.head = foot.tail.copy()
            toe.tail = np.array([ankle[0], .017, .122])

    elbow = section('RightElbow', .651, -.155, -.095, -.045, .045)
    wrist = section('RightWrist', .512, -.18, -.13, -.055, .045)
    finger = section('RightHandEnd', .448, -.18, -.14, -.045, .045)
    by_name['mixamorig:RightArm'].tail = elbow
    by_name['mixamorig:RightForeArm'].head = elbow.copy()
    by_name['mixamorig:RightForeArm'].tail = wrist
    by_name['mixamorig:RightHand'].head = wrist.copy()
    by_name['mixamorig:RightHand'].tail = finger

    left_elbow = section('LeftElbow', .683, .076, .132, .005, .10)
    left_wrist = section('LeftWrist', .790, .128, .19, .03, .12)
    by_name['mixamorig:LeftArm'].tail = left_elbow
    by_name['mixamorig:LeftForeArm'].head = left_elbow.copy()
    by_name['mixamorig:LeftForeArm'].tail = left_wrist
    by_name['mixamorig:LeftHand'].head = left_wrist.copy()
    by_name['mixamorig:LeftHand'].tail = section('LeftHandEnd', .841, .12, .18, .04, .12)
    for bone in result:
        if np.linalg.norm(bone.tail - bone.head) < 1e-5:
            raise ValueError(f'Degenerate fitted bone: {bone.name}')
    return result, {'method': 'source-mesh cross-section fitting in three dimensions',
                    'leg_depth_order': ('Left/+X is back; Right/-X is front'
                        if measurements['LeftAnkle']['center'][2] < measurements['RightAnkle']['center'][2]
                        else 'Right/-X is back; Left/+X is front'),
                    'measurements': measurements,
                    'bones': [{'name': b.name, 'head': b.head.tolist(), 'tail': b.tail.tolist()} for b in result]}
