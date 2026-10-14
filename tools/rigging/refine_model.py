"""Add source-fitted finger phalanges, weighted face bones and sparse expressions.

The previously verified high-resolution body rig is the immutable input. This
pipeline does NOT reduce geometry, replace textures, or pretend names alone are
articulation. It verifies actual vertex motion of every digit and face target.
"""
from __future__ import annotations

import argparse
import copy
import json
import math
import os
import subprocess
import time
from pathlib import Path

import numpy as np
from scipy.spatial.transform import Rotation

from core import (Bone, extract_glb, node_matrix, pack_skin_weights, read_accessor,
                  read_glb, sha256, smoothstep, validate_skin, world_matrices, write_glb)
from binding import solve_bbw
from detail import (DIGITS, facial_bones, fit_face, fit_fingers, gaussian, in_bounds,
                    morph_fields, source_data, surface_graph)
from quality import SkinEvaluator, assert_geometry_preserved

START = time.monotonic()
BODY_BASE_SHA = '713e6ab6d67ad668d86b37e4832405498a3987e3f6b704570997a54544c3dfd7'
FPS = 30


def log(text):
    print(f'[{time.monotonic()-START:7.1f}s] {text}', flush=True)


class Assembler:
    def __init__(self, document, binary):
        self.document = document
        self.binary = bytearray(binary)

    def view(self, array, target=None):
        self.binary.extend(b'\0'*(-len(self.binary)%4))
        raw = array.tobytes()
        view = {'buffer': 0, 'byteOffset': len(self.binary), 'byteLength': len(raw)}
        if target:
            view['target'] = target
        self.binary.extend(raw)
        index = len(self.document['bufferViews'])
        self.document['bufferViews'].append(view)
        return index

    def accessor(self, array, kind, component=5126, bounds=False):
        index = len(self.document['accessors'])
        entry = {'bufferView': self.view(array), 'componentType': component,
                 'count': len(array), 'type': kind}
        if bounds:
            entry.update(min=np.min(array, axis=0).ravel().tolist(),
                         max=np.max(array, axis=0).ravel().tolist())
        self.document['accessors'].append(entry)
        return index

    def sparse(self, ids, values, count, bounds=False):
        order = np.argsort(ids)
        ids, values = np.asarray(ids[order], dtype='<u4'), np.asarray(values[order], dtype='<f4')
        if len(np.unique(ids)) != len(ids):
            raise ValueError('Duplicate sparse morph indices')
        entry = {'componentType': 5126, 'count': count, 'type': 'VEC3',
                 'sparse': {'count': len(ids), 'indices': {'bufferView': self.view(ids), 'componentType': 5125},
                            'values': {'bufferView': self.view(values)}}}
        if bounds:
            entry['min'] = np.minimum(values.min(0), 0).tolist()
            entry['max'] = np.maximum(values.max(0), 0).tolist()
        index = len(self.document['accessors'])
        self.document['accessors'].append(entry)
        return index

    def replace_attribute(self, index, array):
        accessor = self.document['accessors'][index]
        view = self.document['bufferViews'][accessor['bufferView']]
        offset = view.get('byteOffset', 0)+accessor.get('byteOffset', 0)
        raw = array.tobytes()
        if len(raw) != view['byteLength']:
            raise ValueError('Replacing an attribute must not change its byte length')
        self.binary[offset:offset+len(raw)] = raw


def bone_world(spec, height, origin, mesh_transform, curl_axis):
    direction = spec.tail-spec.head
    direction /= np.linalg.norm(direction)
    x_axis = np.asarray(curl_axis, dtype=float).copy()
    x_axis -= direction*(x_axis@direction)
    if np.linalg.norm(x_axis) < 1e-8:
        x_axis = np.array([1., 0, 0]); x_axis -= direction*(x_axis@direction)
    x_axis /= np.linalg.norm(x_axis)
    z_axis = np.cross(x_axis, direction)
    matrix = np.eye(4)
    matrix[:3, :3] = np.column_stack([x_axis, direction, z_axis])
    matrix[:3, 3] = (mesh_transform @ np.r_[origin+height*spec.head, 1])[:3]
    return matrix


def add_bones(assembler, bones, height, origin, profile):
    doc = assembler.document
    world = world_matrices(doc)
    by_name = {node.get('name'): i for i, node in enumerate(doc['nodes'])}
    skin = doc['skins'][0]
    mesh_node = next(node for node in doc['nodes'] if node.get('skin') == 0)
    mesh_transform = node_matrix(mesh_node)
    old_bind = read_accessor(doc, assembler.binary, skin['inverseBindMatrices']).reshape(-1, 4, 4)
    new_bind, joint_indices = [], {}
    for spec in bones:
        axis = profile['hands']['Left' if 'LeftHand' in spec.name else 'Right']['curl_axis'] if spec.category == 'finger' else [1, 0, 0]
        rest = bone_world(spec, height, origin, mesh_transform, axis)
        parent = by_name[spec.parent]
        local = np.linalg.inv(world[parent]) @ rest
        quaternion = Rotation.from_matrix(local[:3, :3]).as_quat()
        index = len(doc['nodes'])
        doc['nodes'].append({'name': spec.name, 'translation': local[:3, 3].tolist(),
            'rotation': quaternion.tolist(), 'extras': {'category': spec.category,
            'fitted_source_head': spec.head.tolist(), 'fitted_source_tail': spec.tail.tolist(),
            'functional_skin_joint': True}})
        doc['nodes'][parent].setdefault('children', []).append(index)
        by_name[spec.name] = index
        world.append(rest)
        joint_indices[spec.name] = len(skin['joints'])
        skin['joints'].append(index)
        new_bind.append((np.linalg.inv(rest)@mesh_transform).T)
    combined = np.concatenate([old_bind, np.asarray(new_bind)]).astype('<f4')
    skin['inverseBindMatrices'] = assembler.accessor(combined.reshape(-1, 16), 'MAT4')
    return by_name, joint_indices


def edit_finger_weights(vertices, faces, colors, profile, side, bones, old_joints, old_weights,
                        names, new_indices, output_joints, output_weights, total_joints):
    hand_index = names.index('mixamorig:'+side+'Hand')
    forearm = names.index('mixamorig:'+side+'ForeArm')
    mass = (old_weights*((old_joints == hand_index) | (old_joints == forearm))).sum(1)
    selected = in_bounds(vertices, profile['hands'][side]['bounds']) & (mass > .15)
    ids, unique, triangles, remap, graph = surface_graph(vertices, faces, selected)
    fitted, measurements = fit_fingers(vertices, colors, profile, side, selected)
    palm = np.array(profile['hands'][side]['palm'])
    helper = [Bone('Root', None, palm+[0, -.01, 0], palm+[0, -.005, 0], .005, 'root'),
              Bone('Palm', 'Root', palm+[0, .009, 0], palm+[0, -.002, 0], .009, 'body')] + fitted
    field, solve = solve_bbw(unique, triangles, helper, log=log)
    field = np.asarray(field, dtype=float)**1.5
    field /= np.maximum(field.sum(1, keepdims=True), 1e-20)
    field = field[remap]
    combined = np.zeros((len(ids), total_joints), dtype=np.float64)
    np.put_along_axis(combined, old_joints[ids], old_weights[ids], axis=1)
    amount = combined[:, hand_index].copy()
    # Distal actual skin must not retain a distant coat/forearm field. Preserve
    # the original transition at the wrist and on the held hair strand.
    skin = colors[ids, 0].astype(int) > colors[ids, 2].astype(int)+3
    takeover = smoothstep(.30, .75, 1-field[:, 1])*skin
    combined *= (1-takeover[:, None])
    amount = amount*(1-takeover)+takeover
    combined[:, hand_index] = amount*field[:, 1]
    for column, spec in enumerate(fitted, 2):
        combined[:, new_indices[spec.name]] = amount*field[:, column]
    new_j, new_w = pack_skin_weights(combined)
    output_joints[ids] = new_j
    output_weights[ids] = new_w
    # A named joint with no real influences is not an articulated finger.
    weighted = {b.name: int(((new_j == new_indices[b.name]) & (new_w > 0)).sum()) for b in fitted}
    if min(weighted.values()) < 15:
        raise ValueError(f'{side} phalanx lacks actual skin influence: {weighted}')
    return {'fitting': measurements, 'solver': solve, 'weighted_vertices_per_phalanx': weighted,
            'source_hand_vertices': len(ids), 'surface_components': int(connected_components_count(graph))}, ids


def connected_components_count(graph):
    from scipy.sparse.csgraph import connected_components
    return connected_components(graph, directed=False)[0]


def edit_face_weights(vertices, face_ids, landmarks, bones, new_indices, head_index,
                      total_joints, output_joints, output_weights):
    p = vertices[face_ids]
    fields = np.zeros((len(p), total_joints))
    fields[:, head_index] = .55
    mouth = np.asarray(landmarks['mouth'])
    for bone in bones:
        if bone.name == 'Face.Jaw':
            gate = (1-smoothstep(mouth[1]-.006, mouth[1]+.008, p[:, 1]))
            gate *= (1-smoothstep(.030, .050, np.abs(p[:, 0]-mouth[0])))
            fields[:, new_indices[bone.name]] = gate*.80
        else:
            radius = bone.radius
            radii = [radius*1.15, radius*.75, radius*2.5]
            fields[:, new_indices[bone.name]] = gaussian(p, bone.head, radii)*.60
    fields /= fields.sum(1, keepdims=True)
    output_joints[face_ids], output_weights[face_ids] = pack_skin_weights(fields)
    weighted = {b.name: int(((output_joints[face_ids] == new_indices[b.name]) &
                            (output_weights[face_ids] > 0)).sum()) for b in bones}
    if min(weighted.values()) < 15:
        raise ValueError(f'Facial bones are not genuinely weighted: {weighted}')
    return weighted


def morph_normals(positions, normals, faces, ids, delta):
    # Work on a one-ring neighbourhood, not dense copies of forty entire meshes.
    lookup = np.zeros(len(positions), dtype=bool);lookup[ids] = True
    subset = faces[lookup[faces].any(axis=1)]
    region = np.unique(subset)
    mapping = np.full(len(positions), -1, dtype=np.int64);mapping[region] = np.arange(len(region))
    triangles = mapping[subset]
    local = positions[region].copy(); modified = local.copy();modified[mapping[ids]] += delta
    def normal(v):
        cross = np.cross(v[triangles[:, 1]]-v[triangles[:, 0]], v[triangles[:, 2]]-v[triangles[:, 0]])
        result = np.zeros_like(v)
        for slot in range(3):
            np.add.at(result, triangles[:, slot], cross)
        result /= np.maximum(np.linalg.norm(result, axis=1, keepdims=True), 1e-20)
        return result
    difference = normal(modified)-normal(local)
    corrected = normals[region]+difference
    corrected /= np.maximum(np.linalg.norm(corrected, axis=1, keepdims=True), 1e-20)
    difference = corrected-normals[region]
    active = np.linalg.norm(difference, axis=1) > 8e-5
    return region[active], difference[active].astype('<f4')


def animation_channel(assembler, animation, times, values, node, path, kind='VEC4'):
    input_index = assembler.accessor(np.asarray(times, dtype='<f4').reshape(-1, 1), 'SCALAR', bounds=True)
    output_index = assembler.accessor(np.asarray(values, dtype='<f4').reshape(-1, 1 if path == 'weights' else 4),
                                       'SCALAR' if path == 'weights' else kind)
    sampler = len(animation.setdefault('samplers', []))
    animation['samplers'].append({'input': input_index, 'output': output_index, 'interpolation': 'LINEAR'})
    animation.setdefault('channels', []).append({'sampler': sampler, 'target': {'node': node, 'path': path}})


def add_detail_animations(assembler, fingers, by_name, morph_names):
    doc = assembler.document
    mesh_node = next(i for i, node in enumerate(doc['nodes']) if node.get('skin') == 0)
    defaults = {b.name: Rotation.from_quat(doc['nodes'][by_name[b.name]]['rotation']) for b in fingers}
    clips = []
    for name, duration in [('Finger_Curl', 3.), ('Finger_Spread', 3.), ('Finger_Isolation', 10.)]:
        animation = {'name': name}
        times = np.linspace(0, duration, int(duration*FPS)+1)
        for bone in fingers:
            side = 'Left' if 'LeftHand' in bone.name else 'Right'
            digit = next(d for d in DIGITS if d in bone.name)
            phalanx = int(bone.name[-1])-1
            amounts = np.sin(np.pi*times/duration)**2
            axis = np.array([1., 0, 0]); maximum = [18., 25., 18.][phalanx]
            if name == 'Finger_Spread':
                axis = np.array([0., 0, 1.]); maximum = ([-12., -7., 0., 7., 12.][DIGITS.index(digit)] if phalanx == 0 else 0.)
            elif name == 'Finger_Isolation':
                digit_index = DIGITS.index(digit)+(5 if side == 'Left' else 0)
                phase = np.clip((times-digit_index)/.8, 0, 1)
                amounts = np.where((times >= digit_index) & (times <= digit_index+.8), np.sin(np.pi*phase)**2, 0)
            deltas = Rotation.from_rotvec(np.radians(amounts*maximum)[:, None]*axis)
            quaternions = (defaults[bone.name]*deltas).as_quat()
            animation_channel(assembler, animation, times, quaternions, by_name[bone.name], 'rotation')
        doc['animations'].append(animation);clips.append(name)
    for name, duration in [('Blink', 2.), ('Smile', 3.), ('Visemes', 5.), ('Facial_Expressions', 6.)]:
        animation = {'name': name}; times = np.linspace(0, duration, int(duration*FPS)+1)
        amounts = np.zeros((len(times), len(morph_names)))
        def activate(label, values):
            amounts[:, morph_names.index(label)] = values
        envelope = np.sin(np.pi*times/duration)**2
        if name == 'Blink':
            for side in ('Left', 'Right'):
                activate('eyeBlink'+side, envelope)
        elif name == 'Smile':
            for side in ('Left', 'Right'):
                activate('mouthSmile'+side, envelope*.8);activate('cheekSquint'+side, envelope*.5)
            activate('browInnerUp', envelope*.3)
        elif name == 'Visemes':
            for i, label in enumerate(('A', 'I', 'U', 'E', 'O')):
                phase = np.clip((times-i)/.8, 0, 1)
                values = np.where((times >= i)&(times <= i+.8), np.sin(np.pi*phase)**2*.8, 0)
                activate('viseme_'+label, values)
        else:
            for i, (label, maximum) in enumerate([('eyeBlinkLeft', 1.), ('eyeBlinkRight', 1.),
                    ('browInnerUp', .8), ('mouthFrownLeft', .8), ('mouthFrownRight', .8), ('mouthPucker', .8)]):
                phase = np.clip((times-i)/.8, 0, 1)
                values = np.where((times >= i)&(times <= i+.8), np.sin(np.pi*phase)**2*maximum, 0)
                activate(label, values)
        animation_channel(assembler, animation, times, amounts, mesh_node, 'weights')
        doc['animations'].append(animation);clips.append(name)
    return clips


def local_motion_checks(path, fingers, finger_regions, face_ids, morph_names):
    evaluator = SkinEvaluator(path)
    rest = evaluator.deform(evaluator.matrices())
    report = {'digits': {}, 'facial_targets': {}, 'local_hand_deformation': {}}
    for side in ('Right', 'Left'):
        ids = finger_regions[side]
        joints = {name: i for i, name in enumerate(evaluator.joint_names)}
        for digit in DIGITS:
            selected = sum((evaluator.weights[ids]*(evaluator.joints[ids] == joints[f'mixamorig:{side}Hand{digit}{i}'])).sum(1) for i in (1, 2, 3)) > .35
            if selected.sum() < 20:
                raise ValueError(f'{side}{digit} lacks independent vertices')
            time = (DIGITS.index(digit)+(5 if side == 'Left' else 0))+.4
            posed = evaluator.posed('Finger_Isolation', time)
            movement = np.linalg.norm(posed[ids[selected]]-rest[ids[selected]], axis=1)
            if movement.max() < evaluator.height*.0005:
                raise ValueError(f'Named {side}{digit} does not actually move')
            report['digits'][side+digit] = {'weighted_test_vertices': int(selected.sum()),
                'maximum_independent_displacement': float(movement.max()), 'time_seconds': time}
    for side in ('Right', 'Left'):
        region = finger_regions[side]
        mask = np.zeros(len(rest), dtype=bool);mask[region] = True
        subset = evaluator.triangles[mask[evaluator.triangles].all(1)]
        edges = np.unique(np.sort(np.concatenate([subset[:, [0, 1]], subset[:, [1, 2]], subset[:, [2, 0]]]), axis=1), axis=0)
        lengths = np.linalg.norm(rest[edges[:, 0]]-rest[edges[:, 1]], axis=1)
        valid = lengths > evaluator.height*.0001
        edges, lengths = edges[valid], lengths[valid]
        item = {'tested_physical_edges': len(edges), 'stretch_p99_5': 0., 'stretch_p99_9': 0., 'maximum': 0.}
        for time in np.linspace(0, 3, 13):
            posed = evaluator.posed('Finger_Curl', float(time))
            ratio = np.linalg.norm(posed[edges[:, 0]]-posed[edges[:, 1]], axis=1)/lengths
            item['stretch_p99_5'] = max(item['stretch_p99_5'], float(np.percentile(ratio, 99.5)))
            item['stretch_p99_9'] = max(item['stretch_p99_9'], float(np.percentile(ratio, 99.9)))
            item['maximum'] = max(item['maximum'], float(ratio.max()))
        report['local_hand_deformation'][side] = item
        log(f'LOCAL hand QA {side}: {item}')
        if item['stretch_p99_5'] > 3. or item['stretch_p99_9'] > 5.:
            raise ValueError(f'Local {side} hand deformation needs correction: {item}')
    for name, (ids, delta) in zip(morph_names, evaluator.morphs):
        if len(ids) < 20 or not np.all(np.isfinite(delta)):
            raise ValueError(f'Invalid actual facial target {name}')
        report['facial_targets'][name] = {'affected_vertices': len(ids), 'maximum_displacement': float(np.linalg.norm(delta, axis=1).max())}
    # Confirm the exported weights channel really changes the original geometry.
    for name, when in [('Blink', 1.), ('Smile', 1.5), ('Visemes', .4)]:
        movement = np.linalg.norm(evaluator.posed(name, when)[face_ids]-rest[face_ids], axis=1)
        if movement.max() < evaluator.height*.001:
            raise ValueError(f'{name} is just a name, not a functioning facial animation')
        report[name] = {'maximum_actual_vertex_motion': float(movement.max())}
    return report


def write_readme(output, report):
    run_url = f"https://github.com/{os.environ.get('GITHUB_REPOSITORY', 'KamiSakyy/Lsmsnksoslalql')}/actions/runs/{os.environ.get('GITHUB_RUN_ID', '')}"
    shape_names = ', '.join(report['facial_targets'])
    qa = '\n'.join(f"| {name} | {item['edge_stretch_p99_5']:.3f} | {item['edge_stretch_p99_9']:.3f} |" for name, item in report['pose_checks'].items())
    text = f"""# Девушка — полный меш, пальцы и мимика

**anime-girl-rigged.glb**: {report['primary']['bytes']:,} байт; исходные
**1 159 795 вершин / 1 981 742 треугольника / текстуры 4096 × 4096**.
Исходные POSITION, NORMAL, UV, индексы и изображения сохранены побайтно.
Нет упрощения, эмулятора, сайта или заменяющего модель изображения.

## Теперь есть отдельные пальцы и мимика

- **100 настоящих skin joints**: исходные 48 + **30 костей фаланг** + **22 кости лица**.
- Обе руки: Thumb, Index, Middle, Ring, Pinky — по три независимые фаланги.
  Стандартные имена `mixamorig:LeftHandIndex1..3`, `mixamorig:RightHandThumb1..3` и т. п.
- Кости `Face.*`: челюсть, глаза/веки, брови, углы рта, верхняя/нижняя губа, щёки, нос.
  Они реально взвешены на вершины, не просто присутствуют в списке.
- **40 отдельных facial morph targets** с реальными POSITION/NORMAL-дельтами:
  моргание/прищур/широкие глаза слева и справа, брови, улыбка, нахмуривание, губы,
  челюсть, щёки и формы A/I/U/E/O. Изменение геометрии хранится в самом GLB.
- Веса: четыре влияния, 16-битная точность, ноль непривязанных вершин.

## Точная подгонка

Пальцы подогнаны к **трёхмерной исходной согнутой позе**, не к шаблонной прямой ладони.
Для каждой кисти отдельно решена поверхность BBW на высокодетализированной геометрии;
не переносится чужая юбка/волосы на пальцы. Переход в запястье сохранён.
Лицо выделяется геодезикой от носа, затем глаза/губы измеряются по реальной геометрии и UV.
Убраны дальние влияния позвоночника/волос на кожу лица; мимика локализована, не двигает туловище.

Sparse morph accessors экономят место без удаления вершин/текстур или снижения точности.
Нейтральные значения всех форм — ноль; исходная внешность сохраняется.

## 12 клипов в самом файле

Исходные: Idle, Walk_InPlace, Run_InPlace, Wave, Dance.
Дополнительные: **Finger_Curl, Finger_Spread, Finger_Isolation, Blink, Smile, Visemes,
Facial_Expressions**. Finger_Isolation последовательно двигает все десять пальцев отдельно.
В приложении можно использовать клипы либо управлять каждой костью и морф-формой вручную.

В .blend: выберите `AnimeGirl_Original_HighDetail` → Object Data → Shape Keys;
для пальцев/костей лица — `AnimeGirl_Precision_Rig_100` → Pose Mode.
Все имена и исходные action-клипы сохраняются. Полноразмерные .blend/.fbx —
[пакет Actions]({run_url}) → Artifacts / anime-girl-rigged (90 дней).
Основной GLB хранится непосредственно в этой Git-ветке, без срока артефакта.

## Проверки реального движения

В `rig-report.json`: количество взвешенных вершин **каждой фаланги и каждой кости лица**;
измеренное независимое перемещение всех десяти пальцев; реальные дельты всех 40 форм;
проверка изменения вершин при воспроизведении экспортированных Blink/Smile/Visemes.
По **21 времени всех 12 клипов** проверяется LBS + морфинг самого итогового полного файла.

| Клип | Максимальный 99,5-й процентиль растяжения | 99,9-й |
|---|---:|---:|
{qa}

`khronos-validation.json`: официальная проверка GLB. Она не подменяет проверку деформаций.
`quality/facial-animation.gif` и `quality/finger-animation.gif` — офлайн-рендеры
полного итогового GLB с его настоящими весами/морфами, не интерактивный просмотрщик.

## Управления мимикой

{shape_names}

## Честные границы исходника

Это автоматический модель-специфичный риг, не обещание ручной студийной точности любой позы.
В исходном Tripo меше рот закрыт: JawOpen и viseme-формы реально двигают губы/подбородок,
но не реконструируют скрытые зубы, язык и ротовую полость. Глаза остаются исходной
текстурированной поверхностью; веки/формы дают моргание, не отдельные сферические eyeballs.
Некоторые соприкасающиеся/слитые участки пальцев и захват волос ограничены исходной топологией;
крайние контактные позы могут требовать ручных корректирующих форм. Движения процедурные,
не motion capture. Физика ткани не имитируется. Исходный ZIP не изменён.
"""
    (output/'README_RU.md').write_text(text)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', type=Path, required=True)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('build/rigged'))
    parser.add_argument('--validate-gltf', action='store_true')
    args = parser.parse_args();args.output.mkdir(parents=True, exist_ok=True)
    if sha256(args.base) != BODY_BASE_SHA:
        raise ValueError('Expected the immutable, previously verified full-resolution body rig')
    profile = json.loads(Path(__file__).with_name('detail_profile.json').read_text())
    source = extract_glb(args.archive, args.output.parent/'source')
    if sha256(source) != profile['source_sha256']:
        raise ValueError('Detailed source-specific profile does not match the actual model')
    source_doc, source_bin = read_glb(source)
    v, faces, normals, colors, height, origin = source_data(source_doc, source_bin)
    doc, raw = read_glb(args.base)
    assembler = Assembler(doc, raw);primitive = doc['meshes'][0]['primitives'][0];attrs = primitive['attributes']
    old_j = read_accessor(doc, raw, attrs['JOINTS_0']);old_w = read_accessor(doc, raw, attrs['WEIGHTS_0'])
    names = [doc['nodes'][x]['name'] for x in doc['skins'][0]['joints']]
    face_ids, landmarks = fit_face(v, faces, normals, colors, profile)
    log(f'Face fitted to {len(face_ids):,} original vertices; measured eye/lip landmarks {landmarks}')
    finger_bones, hand_fits = [], {}
    for side in ('Right', 'Left'):
        hi = names.index('mixamorig:'+side+'Hand');fi = names.index('mixamorig:'+side+'ForeArm')
        mass = (old_w*((old_j == hi)|(old_j == fi))).sum(1)
        selected = in_bounds(v, profile['hands'][side]['bounds']) & (mass > .15)
        fitted, measurements = fit_fingers(v, colors, profile, side, selected)
        finger_bones.extend(fitted);hand_fits[side] = measurements
    face_bones = facial_bones(landmarks, profile)
    by_name, new_indices = add_bones(assembler, finger_bones+face_bones, height, origin, profile)
    joint_count = len(doc['skins'][0]['joints'])
    new_j = old_j.astype(np.uint16).copy()
    # Recover exact stored integer weights, do not introduce an 8-bit round trip.
    new_w = np.rint(old_w*65535).astype(np.uint16)
    hand_reports, finger_regions = {}, {}
    for side in ('Right', 'Left'):
        log(f'Fine hand-only surface BBW solve for {side}, 15 individual phalanges')
        hand_reports[side], finger_regions[side] = edit_finger_weights(v, faces, colors, profile, side,
            finger_bones, old_j, old_w, names, new_indices, new_j, new_w, joint_count)
    log('Correcting face skin to head/jaw/lips/lids/brows instead of distant hair/spine fields')
    face_weighted = edit_face_weights(v, face_ids, landmarks, face_bones, new_indices,
                                      names.index('mixamorig:Head'), joint_count, new_j, new_w)
    assembler.replace_attribute(attrs['JOINTS_0'], new_j.astype(np.uint8))
    assembler.replace_attribute(attrs['WEIGHTS_0'], new_w.astype('<u2'))
    fields = morph_fields(v, face_ids, landmarks)
    targets = []
    shape_report = {}
    for name, (ids, delta) in fields.items():
        log(f'Building actual sparse expression {name}: {len(ids):,} vertices')
        target = {'POSITION': assembler.sparse(ids, delta*height, len(v), bounds=True)}
        ni, nd = morph_normals(v, normals, faces, ids, delta)
        if len(ni):
            target['NORMAL'] = assembler.sparse(ni, nd, len(v))
        targets.append(target)
        shape_report[name] = {'position_vertices': len(ids), 'normal_vertices': len(ni)}
    primitive['targets'] = targets
    mesh = doc['meshes'][0];mesh['weights'] = [0.]*len(targets)
    mesh.setdefault('extras', {})['targetNames'] = list(fields)
    doc['nodes'][0]['weights'] = [0.]*len(targets)
    added_clips = add_detail_animations(assembler, finger_bones, by_name, list(fields))
    doc['asset']['generator'] = 'Source-preserved Blender/libigl body; source-fitted articulated fingers and geodesic facial rig with sparse expression geometry'
    doc.setdefault('extras', {})['precision_rig'] = {'version': 2, 'finger_bones': 30,
        'face_bones': len(face_bones), 'facial_targets': len(targets), 'body_source_sha256': BODY_BASE_SHA,
        'shape_names': list(fields)}
    path = args.output/'anime-girl-rigged.glb'
    write_glb(path, doc, assembler.binary)
    preservation = assert_geometry_preserved(source, path, allow_morph_targets=True)
    if path.stat().st_size >= 100*1024*1024:
        raise ValueError('Actual full-resolution asset exceeds ordinary GitHub file size limit')
    skin = validate_skin(path, {a['name'] for a in doc['animations']})
    report = {'source': {'archive_sha256': sha256(args.archive), 'glb_sha256': sha256(source),
        'vertices': len(v), 'triangles': len(faces), 'bytes': source.stat().st_size},
        'primary': {'file': path.name, 'bytes': path.stat().st_size, 'sha256': sha256(path),
        'vertices': len(v), 'triangles': len(faces), 'skin': skin}, 'preservation': preservation,
        'fingers': hand_reports, 'facial_landmarks': landmarks, 'face_bones': face_weighted,
        'facial_targets': shape_report, 'new_clips': added_clips,
        'bones': [{'name': b.name, 'parent': b.parent, 'category': b.category,
                   'head': b.head.tolist(), 'tail': b.tail.tolist()} for b in finger_bones+face_bones],
        'actual_motion_checks': {}, 'pose_checks': {}}
    report_path = args.output/'rig-report.json';report_path.write_text(json.dumps(report, indent=2)+'\n')
    report['actual_motion_checks'] = local_motion_checks(path, finger_bones, finger_regions, face_ids, list(fields))
    log('Checking all 100 real joints, ten independent moving fingers, and exported facial sampler geometry')
    evaluator = SkinEvaluator(path)
    neutral = evaluator.deform(evaluator.matrices())
    raw_positions = read_accessor(source_doc, source_bin, 0).astype(float)+source_doc['nodes'][0].get('translation', [0, 0, 0])
    rest_error = float(np.max(np.abs(neutral-raw_positions)))
    if rest_error > height*2e-5:
        raise ValueError(f'Neutral changes the original geometry: {rest_error}')
    report['preservation']['maximum_rest_position_error'] = rest_error
    log('Auditing actual exported body, finger and facial animation geometry at 21 times per clip')
    report['pose_checks'] = evaluator.audit(frames=21)
    for name, checks in report['pose_checks'].items():
        log(f'QA {name}: p99.5={checks["edge_stretch_p99_5"]:.3f}, p99.9={checks["edge_stretch_p99_9"]:.3f}, compression={checks["edge_compression_p00_5"]:.3f}')
        if (checks['edge_stretch_p99_5'] > 2.2 or checks['edge_stretch_p99_9'] > 4.
                or checks['maximum_extent_in_model_heights'] > 1.5):
            report_path.write_text(json.dumps(report, indent=2)+'\n')
            raise ValueError(f'Precision deformation gate failed in {name}')
    report_path.write_text(json.dumps(report, indent=2)+'\n')
    if args.validate_gltf:
        subprocess.run(['node', str(Path(__file__).with_name('validate-glb.cjs')), str(path)], check=True)
    (args.output/'joint-fitting.json').write_text(json.dumps({'hands': hand_fits, 'face': landmarks}, indent=2)+'\n')
    write_readme(args.output, report)
    log(f'DONE: original-detail {path.stat().st_size:,} byte GLB; {joint_count} real skin joints; 30 finger bones; {len(face_bones)} weighted face bones; {len(targets)} actual expressions')


if __name__ == '__main__':
    main()
