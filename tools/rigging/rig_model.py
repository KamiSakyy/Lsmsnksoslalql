"""Rig the supplied Tripo girl, animate, export and render on a CPU Actions runner.

Run with ordinary Python using the pinned bpy wheel (no GUI or GPU required).
The height-normalized skeleton profile was fitted to this source's asymmetric pose.
"""
from __future__ import annotations

import argparse
import io
import json
import math
import os
import subprocess
import time
from pathlib import Path

import bpy
import numpy as np
from bpy_extras.object_utils import world_to_camera_view
from mathutils import Matrix, Quaternion, Vector
from PIL import Image, ImageDraw, ImageFont

from core import (extract_glb, load_profile, make_full_resolution, pack_skin_weights,
                  read_accessor, read_glb, repair_runtime_tangents, sha256,
                  surface_topology, validate_skin)
from binding import solve_bbw, localize_fields, transfer_surface_weights
from fit import fit_bones
from motion import CLIPS, FPS, PREFIX, BLENDER_TO_GLTF, clip_deltas
from quality import SkinEvaluator, assert_geometry_preserved

START = time.monotonic()


def log(message):
    print(f'[{time.monotonic() - START:7.1f}s] {message}', flush=True)


def activate(obj):
    bpy.ops.object.select_all(action='DESELECT')
    obj.hide_set(False)
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj


def mesh_vertices(obj):
    result = np.empty(len(obj.data.vertices) * 3, dtype=np.float32)
    obj.data.vertices.foreach_get('co', result)
    return result.reshape(-1, 3)


def mesh_triangles(obj):
    obj.data.calc_loop_triangles()
    result = np.empty(len(obj.data.loop_triangles) * 3, dtype=np.int32)
    obj.data.loop_triangles.foreach_get('vertices', result)
    return result.reshape(-1, 3)


def normalized_gltf(vertices, origin, height):
    p = (vertices - origin) / height
    return np.column_stack([p[:, 0], p[:, 2], -p[:, 1]])


def point_in_blender(point, origin, height):
    return Vector(origin + np.array([point[0], -point[2], point[1]]) * height)


def weld_runtime_surface(obj, height):
    """Weld positional duplicates, NOT merely equal normals, before decimation.

    Tripo stores differing normals at coincident vertices. The glTF importer's
    normal-aware merge leaves seams disconnected; decimating those independently
    creates cracks. UVs remain per-corner, so welding does not erase UV islands.
    The untouched source object is not modified.
    """
    mesh = obj.data
    vertices = mesh_vertices(obj)
    _, first, inverse = np.unique(np.rint(vertices / height * 1e7).astype(np.int64),
                                  axis=0, return_index=True, return_inverse=True)
    corner_vertices = np.empty(len(mesh.loops), dtype=np.int32)
    mesh.loops.foreach_get('vertex_index', corner_vertices)
    starts = np.empty(len(mesh.polygons), dtype=np.int32)
    counts = np.empty(len(mesh.polygons), dtype=np.int32)
    materials = np.empty(len(mesh.polygons), dtype=np.int32)
    mesh.polygons.foreach_get('loop_start', starts)
    mesh.polygons.foreach_get('loop_total', counts)
    mesh.polygons.foreach_get('material_index', materials)
    replacement = bpy.data.meshes.new('AnimeGirl_Welded_Runtime_Surface')
    replacement.vertices.add(len(first))
    replacement.vertices.foreach_set('co', vertices[first].ravel())
    replacement.loops.add(len(mesh.loops))
    replacement.loops.foreach_set('vertex_index', inverse[corner_vertices].astype(np.int32))
    replacement.polygons.add(len(mesh.polygons))
    replacement.polygons.foreach_set('loop_start', starts)
    replacement.polygons.foreach_set('loop_total', counts)
    replacement.polygons.foreach_set('material_index', materials)
    replacement.polygons.foreach_set('use_smooth', np.ones(len(mesh.polygons), dtype=bool))
    for layer in mesh.uv_layers:
        uv = np.empty(len(mesh.loops) * 2, dtype=np.float32)
        layer.data.foreach_get('uv', uv)
        replacement.uv_layers.new(name=layer.name).data.foreach_set('uv', uv)
    for material in mesh.materials:
        replacement.materials.append(material)
    replacement.update(calc_edges=True)
    obj.data = replacement
    bpy.data.meshes.remove(mesh)
    log(f'Welded runtime topology: {len(vertices):,} → {len(first):,} vertices; UV corners preserved')


def import_and_simplify(source, triangle_budget):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(source), merge_vertices=True)
    meshes = [o for o in bpy.context.scene.objects if o.type == 'MESH']
    if len(meshes) != 1:
        raise ValueError(f'Expected this model\'s single mesh, got {len(meshes)}')
    original = meshes[0]
    activate(original)
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    original.name = 'AnimeGirl_Original_HighDetail'
    original.data.name = 'Tripo_Original_Geometry'
    vertices = mesh_vertices(original)
    minimum, maximum = vertices.min(axis=0), vertices.max(axis=0)
    height = float(maximum[2] - minimum[2])
    origin = np.array([(minimum[0] + maximum[0]) / 2,
                       (minimum[1] + maximum[1]) / 2, minimum[2]])
    if height <= 0:
        raise ValueError('Invalid model height')
    runtime = original.copy()
    runtime.data = original.data.copy()
    bpy.context.collection.objects.link(runtime)
    runtime.name = 'AnimeGirl_Animated'
    activate(runtime)
    weld_runtime_surface(runtime, height)
    faces_before = len(runtime.data.polygons)
    if faces_before > triangle_budget:
        modifier = runtime.modifiers.new('Texture-preserving runtime simplification', 'DECIMATE')
        modifier.decimate_type = 'COLLAPSE'
        modifier.ratio = min(1.0, triangle_budget / faces_before)
        modifier.use_collapse_triangulate = True
        bpy.ops.object.modifier_apply(modifier=modifier.name)
    bpy.ops.object.shade_smooth()
    original.hide_render = True
    original.hide_set(True)
    log(f'Mesh: {len(original.data.vertices):,} source vertices; '
        f'{len(runtime.data.vertices):,} runtime vertices / {len(runtime.data.polygons):,} triangles')
    return original, runtime, origin, height


def vertex_colors(obj, document, binary):
    material = document['materials'][0]
    index = material['pbrMetallicRoughness']['baseColorTexture']['index']
    source = document['textures'][index]['source']
    view = document['bufferViews'][document['images'][source]['bufferView']]
    raw = binary[view.get('byteOffset', 0):view.get('byteOffset', 0) + view['byteLength']]
    image = np.asarray(Image.open(io.BytesIO(raw)).convert('RGB'), dtype=np.float32) / 255
    layer = obj.data.uv_layers.active
    if layer is None:
        raise ValueError('The textured source has lost its UV layer')
    uv = np.empty(len(obj.data.loops) * 2, dtype=np.float32)
    layer.data.foreach_get('uv', uv)
    uv = uv.reshape(-1, 2)
    loop_vertices = np.empty(len(obj.data.loops), dtype=np.int32)
    obj.data.loops.foreach_get('vertex_index', loop_vertices)
    unique, first = np.unique(loop_vertices, return_index=True)
    colors = np.ones((len(obj.data.vertices), 3), dtype=np.float32)
    x = np.clip((uv[first, 0] * image.shape[1]).astype(int), 0, image.shape[1] - 1)
    y = np.clip(((1 - uv[first, 1]) * image.shape[0]).astype(int), 0, image.shape[0] - 1)
    colors[unique] = image[y, x]
    return colors


def make_armature(bones, origin, height):
    data = bpy.data.armatures.new('AnimeGirl_48_Bones')
    rig = bpy.data.objects.new('AnimeGirl_Rig', data)
    bpy.context.collection.objects.link(rig)
    activate(rig)
    bpy.ops.object.mode_set(mode='EDIT')
    for spec in bones:
        bone = data.edit_bones.new(spec.name)
        bone.head = point_in_blender(spec.head, origin, height)
        bone.tail = point_in_blender(spec.tail, origin, height)
        if spec.parent:
            bone.parent = data.edit_bones[spec.parent]
        bone.use_connect = False
        bone.use_deform = spec.category != 'root'
        bone.head_radius = spec.radius * height
        bone.tail_radius = spec.radius * height * 0.7
        bone.envelope_distance = spec.radius * height
        axis = (bone.tail - bone.head).normalized()
        bone.align_roll(Vector((0, -1, 0)) if abs(axis.y) < 0.9 else Vector((0, 0, 1)))
    bpy.ops.object.mode_set(mode='OBJECT')
    rig.show_in_front = True
    data.display_type = 'OCTAHEDRAL'
    for spec in bones:
        data.bones[spec.name]['category'] = spec.category
        data.bones[spec.name].color.palette = {'body': 'THEME04', 'root': 'THEME04',
                                              'hair': 'THEME03', 'coat': 'THEME02',
                                              'skirt': 'THEME02'}[spec.category]
        rig.pose.bones[spec.name].rotation_mode = 'QUATERNION'
    rig['rigging_method'] = 'Source-fitted 3D joints + libigl bounded biharmonic weights + compact arm/strand envelopes + barycentric surface transfer + 16-bit weights'
    rig['source_pose'] = 'Original asymmetric pose preserved as the bind/rest pose'
    rig['limitations'] = 'FK body rig; no facial blendshapes or individual finger articulation; procedural secondary motion'
    return rig


def bind_mesh(obj, rig, bones, indexes, quantized):
    if len(indexes) != len(obj.data.vertices):
        raise ValueError('Skin/vertex count mismatch')
    for index, spec in enumerate(bones):
        if spec.category == 'root':
            continue
        group = obj.vertex_groups.new(name=spec.name)
        rows, slot = np.where(indexes == index)
        amounts = quantized[rows, slot]
        if not len(amounts):
            continue
        order = np.argsort(amounts)
        sorted_rows, sorted_amounts = rows[order], amounts[order]
        boundaries = np.r_[0, np.flatnonzero(np.diff(sorted_amounts.astype(np.int64)))+1, len(order)]
        divisor = np.iinfo(quantized.dtype).max
        for start, stop in zip(boundaries[:-1], boundaries[1:]):
            amount = int(sorted_amounts[start])
            if amount:
                group.add(sorted_rows[start:stop].tolist(), amount / divisor, 'REPLACE')
    obj.parent = rig
    modifier = obj.modifiers.new('Skeletal deformation', 'ARMATURE')
    modifier.object = rig
    modifier.use_deform_preserve_volume = False  # glTF uses linear blend skinning too.
    modifier.use_vertex_groups = True
    modifier.use_bone_envelopes = False


def create_animations(rig, bones, height):
    scene = bpy.context.scene
    scene.render.fps = FPS
    scene.frame_start, scene.frame_end = 1, 121
    rig.animation_data_create()
    rest = {b.name: BLENDER_TO_GLTF @ np.asarray(rig.data.bones[b.name].matrix_local.to_3x3()) for b in bones}
    actions = {}
    for name, duration in CLIPS.items():
        action = bpy.data.actions.new(name)
        action.use_fake_user = True
        rig.animation_data.action = action
        last = int(round(duration*FPS))+1
        log(f'Generating {name}: {last} keyframes on the fitted skeleton')
        for frame in range(1, last+1):
            deltas, location = clip_deltas(bones, name, (frame-1)/(last-1))
            for bone in bones:
                parent = deltas[bone.parent] if bone.parent else np.eye(3)
                local = rest[bone.name].T @ parent.T @ deltas[bone.name] @ rest[bone.name]
                pose = rig.pose.bones[bone.name]
                pose.rotation_quaternion = Matrix(local.tolist()).to_quaternion()
                pose.location = (0, 0, 0)
                if bone.name == PREFIX+'Hips':
                    pose.location = Vector((rest[bone.name].T @ (location*height)).tolist())
                pose.keyframe_insert(data_path='rotation_quaternion', frame=frame, group=bone.name)
                if bone.name == PREFIX+'Hips':
                    pose.keyframe_insert(data_path='location', frame=frame, group=bone.name)
            scene.frame_set(frame)
        for curve in action.fcurves:
            for key in curve.keyframe_points:
                key.interpolation = 'LINEAR'
        actions[name] = action
    reset_rest_pose(rig)
    return actions


def reset_rest_pose(rig):
    rig.animation_data.action = None
    for pose in rig.pose.bones:
        pose.rotation_quaternion = Quaternion((1, 0, 0, 0))
        pose.location = (0, 0, 0)
    bpy.context.scene.frame_set(0)


def select_export(runtime, rig):
    bpy.ops.object.select_all(action='DESELECT')
    runtime.select_set(True)
    rig.select_set(True)
    bpy.context.view_layer.objects.active = rig


def export_models(runtime, rig, output):
    select_export(runtime, rig)
    log('Exporting the INTERNAL solver cage and sampled animation carriers; not the delivered model')
    bpy.ops.export_scene.gltf(
        filepath=str(output / 'solver-cage.glb'), export_format='GLB', use_selection=True,
        export_animations=True, export_animation_mode='ACTIONS', export_anim_single_armature=True,
        export_force_sampling=True, export_frame_step=1, export_frame_range=False,
        export_anim_slide_to_zero=True, export_rest_position_armature=True, export_def_bones=False,
        export_skins=True, export_tangents=True, export_materials='EXPORT', export_cameras=False, export_lights=False,
        export_optimize_animation_size=False)


def export_high_fbx(original, rig, output):
    original.hide_set(False)
    select_export(original, rig)
    log('Exporting FULL-resolution FBX with sampled clips, not an optimized replacement')
    bpy.ops.export_scene.fbx(
        filepath=str(output / 'anime-girl-rigged.fbx'), use_selection=True,
        object_types={'MESH', 'ARMATURE'}, add_leaf_bones=False, bake_anim=True,
        bake_anim_use_nla_strips=False, bake_anim_use_all_actions=True,
        bake_anim_simplify_factor=0.0, axis_forward='-Z', axis_up='Y',
        path_mode='COPY', embed_textures=True)


def add_studio(height, origin):
    scene = bpy.context.scene
    scene.render.engine = 'CYCLES'
    scene.cycles.device = 'CPU'
    scene.cycles.samples = 24
    scene.cycles.use_denoising = True
    scene.render.threads_mode = 'FIXED'
    scene.render.threads = 2
    scene.render.resolution_x, scene.render.resolution_y = 600, 900
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = 'PNG'
    scene.render.film_transparent = False
    scene.view_settings.view_transform = 'AgX'
    world = bpy.data.worlds.new('Soft studio')
    world.use_nodes = True
    world.node_tree.nodes['Background'].inputs[0].default_value = (0.70, 0.73, 0.80, 1)
    world.node_tree.nodes['Background'].inputs[1].default_value = 0.6
    scene.world = world
    camera_data = bpy.data.cameras.new('Character portrait')
    camera = bpy.data.objects.new('Camera', camera_data)
    scene.collection.objects.link(camera)
    target = Vector(origin) + Vector((0, 0, height * 0.52))
    camera.location = Vector(origin) + Vector((height * 0.13, -height * 2.8, height * 0.60))
    camera.rotation_euler = (target - camera.location).to_track_quat('-Z', 'Y').to_euler()
    camera_data.type = 'ORTHO'
    camera_data.ortho_scale = height * 1.12
    scene.camera = camera
    for name, location, energy, size in [
            ('Key', (-1.0, -1.3, 1.7), 150, 1.5),
            ('Fill', (1.0, -0.5, 1.2), 70, 1.2),
            ('Rim', (0.2, 0.8, 1.5), 120, 1.0)]:
        data = bpy.data.lights.new(name, 'AREA')
        data.energy = energy * height * height
        data.shape = 'DISK'
        data.size = size * height
        light = bpy.data.objects.new(name, data)
        scene.collection.objects.link(light)
        light.location = Vector(origin) + Vector(location) * height
        light.rotation_euler = (target - light.location).to_track_quat('-Z', 'Y').to_euler()
    bpy.ops.mesh.primitive_plane_add(size=height * 200, location=(origin[0], origin[1], origin[2] - 0.002 * height))
    floor = bpy.context.object
    floor.name = 'Studio floor'
    material = bpy.data.materials.new('Matte neutral floor')
    material.use_nodes = True
    material.node_tree.nodes['Principled BSDF'].inputs['Base Color'].default_value = (0.60, 0.64, 0.72, 1)
    material.node_tree.nodes['Principled BSDF'].inputs['Roughness'].default_value = 0.8
    floor.data.materials.append(material)
    return camera


def label_image(image, label):
    draw = ImageDraw.Draw(image)
    try:
        font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', max(13, image.width // 28))
    except OSError:
        font = ImageFont.load_default()
    draw.rounded_rectangle((12, 12, image.width - 12, 49), radius=9, fill=(18, 26, 43))
    draw.text((23, 21), label, font=font, fill=(235, 245, 255))
    return image


def render_previews(rig, bones, actions, camera, output):
    scene = bpy.context.scene
    rig.animation_data.action = None
    for pose in rig.pose.bones:
        pose.rotation_quaternion = Quaternion((1, 0, 0, 0))
        pose.location = (0, 0, 0)
    scene.frame_set(0)
    scene.render.filepath = str(output / 'rest-pose.png')
    log('Rendering the actual rigged model and skeleton preview on CPU')
    bpy.ops.render.render(write_still=True)
    image = Image.open(output / 'rest-pose.png').convert('RGB')
    draw = ImageDraw.Draw(image)
    colors = {'body': (59, 190, 255), 'root': (59, 190, 255),
              'hair': (244, 121, 224), 'coat': (255, 187, 62), 'skirt': (255, 187, 62)}
    for spec in bones:
        if spec.category == 'root':
            continue
        bone = rig.data.bones[spec.name]
        points = [world_to_camera_view(scene, camera, rig.matrix_world @ point)
                  for point in (bone.head_local, bone.tail_local)]
        a, b = [(int(p.x * image.width), int((1-p.y) * image.height)) for p in points]
        draw.line([a, b], fill=colors[spec.category], width=3)
        draw.ellipse((a[0]-3, a[1]-3, a[0]+3, a[1]+3), fill=colors[spec.category])
    label_image(image, '48 bones | body + hair + coat + skirt').save(output / 'skeleton.png')
    scene.cycles.samples = 8
    scene.render.resolution_x, scene.render.resolution_y = 384, 576
    frames = []
    frame_directory = output / 'preview-frames'
    frame_directory.mkdir(exist_ok=True)
    # Show every clip, not merely a static render with a skeleton drawn over it.
    for name, action in actions.items():
        rig.animation_data.action = action
        for index, frame in enumerate(np.linspace(action.frame_range[0], action.frame_range[1], 8, endpoint=False)):
            scene.frame_set(int(round(frame)))
            file = frame_directory / f'{name}-{index:02}.png'
            scene.render.filepath = str(file)
            log(f'Preview {name} {index + 1}/8')
            bpy.ops.render.render(write_still=True)
            frames.append(label_image(Image.open(file).convert('RGB'), name))
    frames[0].save(output / 'animation-preview.gif', save_all=True, append_images=frames[1:],
                   duration=140, loop=0, disposal=2, optimize=False)
    rig.animation_data.action = actions['Idle']
    scene.frame_set(1)
    scene.cycles.samples = 24
    scene.render.resolution_x, scene.render.resolution_y = 600, 900


def render_quality_proof(original, runtime, rig, actions, report, height, origin, output):
    """Render the actual high-poly original and its actual skin, not the cage."""
    camera = add_studio(height, origin)
    scene = bpy.context.scene
    scene.cycles.samples = 16
    scene.cycles.seed = 42
    runtime.hide_render = True
    runtime.hide_set(True)
    original.hide_render = False
    original.hide_set(False)
    folder = output / 'quality'
    folder.mkdir(exist_ok=True)
    reset_rest_pose(rig)
    modifier = next(m for m in original.modifiers if m.type == 'ARMATURE')
    modifier.show_render = False
    scene.render.filepath = str(folder / 'source-rest.png')
    log('Quality render: untouched high-poly source at rest')
    bpy.ops.render.render(write_still=True)
    modifier.show_render = True
    scene.render.filepath = str(folder / 'rigged-rest.png')
    log('Quality render: the same high-poly geometry with its real skin at rest')
    bpy.ops.render.render(write_still=True)
    time = report['pose_checks']['Wave'].get('worst_time', 1.5)
    rig.animation_data.action = actions['Wave']
    scene.frame_set(int(round(time*FPS))+1)
    scene.render.filepath = str(folder / 'wave-check.png')
    log(f'Quality render: full high-poly Wave at the audited worst time {time:.3f}s')
    bpy.ops.render.render(write_still=True)
    before = np.asarray(Image.open(folder/'source-rest.png').convert('RGB'), dtype=np.float32)
    after = np.asarray(Image.open(folder/'rigged-rest.png').convert('RGB'), dtype=np.float32)
    report['render_checks'] = {'full_resolution_mesh': True, 'source_and_bound_rest_mean_pixel_difference': float(np.abs(before-after).mean()/255),
                              'wave_time_seconds': time, 'source_triangles': report['source']['triangles']}
    reset_rest_pose(rig)
    return camera


def write_readme(output, report):
    run_url = f"{os.environ.get('GITHUB_SERVER_URL', 'https://github.com')}/{os.environ.get('GITHUB_REPOSITORY', 'KamiSakyy/Lsmsnksoslalql')}/actions/runs/{os.environ.get('GITHUB_RUN_ID', '')}"
    primary = report['primary']
    checks = report['pose_checks']
    rows = '\n'.join(f"| {name} | {item['sampled_frames']} | {item['edge_stretch_p99_5']:.3f} | {item['edge_stretch_p99_9']:.3f} |" for name, item in checks.items())
    content = f'''# Полноразмерная девушка Tripo — исправленная привязка

## Основной файл — НЕ упрощённая модель

**anime-girl-rigged.glb** — {primary['bytes']:,} байт ({primary['bytes']/1e6:.2f} МБ),
исходные **{report['source']['vertices']:,} вершин и {report['source']['triangles']:,} треугольников**.
Исходные POSITION, NORMAL, UV, индексы и все текстуры сохранены **побайтно**;
проверка исходного бинарного буфера и структуры выполняется до публикации.
Добавлены только скелет, привязка и анимации. GLB хранится непосредственно в этой ветке Git,
а не только во временном артефакте. Никакой сайт или эмулятор не нужен.

Прежняя копия на 18 МБ заменена полноразмерной. `solver-cage.glb` — только внутренняя
расчётная поверхность; она не выдаётся за основную модель и не сохраняется в handoff.

## Что исправлено

- Суставы ног и рук подогнаны по **сечениям исходной 3D-геометрии**. Учитывается реальная
  нога, отведённая назад, и глубина свободной руки: её кости больше не стоят в юбке.
- Привязка вычисляется **libigl / bounded biharmonic weights**, с плавным ограничением
  дальних влияний рук и передних прядей; нет привязки по случайному цвету текстуры.
- Перенос на полный меш — **барицентрическая интерполяция ближайшего треугольника**,
  вместо смешивания трёх вершин, которые могут принадлежать разным поверхностям.
- Веса имеют **16-битную точность**, а не 8-битную; не более четырёх влияний на вершину
  для совместимости с обычными glTF-движками. Непривязанных вершин: **{primary['skin']['unweighted_vertices']}**.
- Проверяются деформации именно **экспортированного полноразмерного GLB**,
  {next(iter(checks.values()))['sampled_frames']} точка времени для каждого из пяти клипов.

## Проверка растяжения

Отношение длины ребра в движении к длине в исходной позе. Учитываются физические рёбра,
не повторения UV-швов; порог очень маленьких рёбер — 0,0001 высоты модели.
Это количественная проверка деформации, а не обещание отсутствия всех визуальных дефектов.

| Клип | Проверенных времён | Растяжение, 99,5-й процентиль | 99,9-й процентиль |
|---|---:|---:|---:|
{rows}

Результаты, максимальные значения и компрессия: `rig-report.json`.
Официальная проверка формата — `khronos-validation.json`; она **не заменяет** проверку качества привязки.

## Кости, анимации и редактирование

48 костей тела, волос и одежды. Клип `Idle` (4 с), `Walk_InPlace` (1,2 с),
`Run_InPlace` (0,8 с), `Wave` (3 с), `Dance` (4 с). Это процедурные движения, не motion capture Mixamo.

`anime-girl-rigged.blend` и `anime-girl-rigged.fbx` в [пакете Actions]({run_url}) →
**Artifacts / anime-girl-rigged** содержат высокодетализированную модель, не замену на 160 000 треугольников.
В Blender видим и выбран объект `AnimeGirl_Original_HighDetail`; служебная поверхность скрыта.
Артефакт хранится 90 дней; основной GLB в Git не имеет этого срока хранения.

`quality/source-rest.png`, `quality/rigged-rest.png`, `quality/wave-check.png` — офлайн-рендеры
самого высокодетализированного меша, не эмулятор и не изображение костей поверх неподвижной модели.

Риг модель-специфичный, FK; без мимики, отдельных костей пальцев, скрытой анатомии под одеждой
и физической симуляции ткани. Очень сильные изгибы и контакт руки с волосами могут требовать
ручных корректирующих форм. Автоматическая проверка не делает его ручным production-ригом.

Исходный ZIP не изменён. Сервер приложения и его конфигурация не используются.
Workflow `.github/workflows/rig-model.yml` работает только в `arena/1e52fcec-lsmsnksoslalql`.
'''
    (output/'README_RU.md').write_text(content)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('build/rigged'))
    parser.add_argument('--target-triangles', type=int, default=160000,
                        help='INTERNAL solver cage budget; the delivered mesh is never simplified')
    parser.add_argument('--render', action='store_true')
    parser.add_argument('--quality-renders', action='store_true')
    parser.add_argument('--validate-gltf', action='store_true')
    args = parser.parse_args()
    if not 10000 <= args.target_triangles <= 500000:
        parser.error('internal cage budget must be 10000..500000')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    log(f'Blender {bpy.app.version_string}; archive SHA-256 {sha256(args.archive)}')
    source = extract_glb(args.archive.resolve(), output.parent/'source')
    if sha256(source) != 'c185de86a14426dd0ff7627c7f3f5d1409b48e4c7fa5ac674193b124f65f3eec':
        raise ValueError('This fitting profile is specific to the supplied source model; source hash differs')
    document, binary = read_glb(source)
    primitive = document['meshes'][0]['primitives'][0]
    source_vertices = document['accessors'][primitive['attributes']['POSITION']]['count']
    source_triangles = document['accessors'][primitive['indices']]['count']//3
    source_positions = read_accessor(document, binary, primitive['attributes']['POSITION']).astype(np.float64)
    source_height = float(np.ptp(source_positions[:, 1]))
    source_origin = (source_positions.max(axis=0)+source_positions.min(axis=0))/2
    source_origin[1] = source_positions[:, 1].min()
    bones, fitting = fit_bones((source_positions-source_origin)/source_height,
                               load_profile(Path(__file__).with_name('model_profile.json')))
    (output/'joint-fitting.json').write_text(json.dumps(fitting, indent=2)+'\n')
    log('Joint depths fitted to the actual source: free hand, raised arm, backward leg and both ankles')
    original, runtime, origin, height = import_and_simplify(source, args.target_triangles)
    rig = make_armature(bones, origin, height)
    positions = mesh_vertices(runtime)
    triangles = mesh_triangles(runtime)
    topology = surface_topology(positions/height, triangles)
    if topology['boundary_edges'] > 1000:
        raise ValueError('Internal cage introduced excessive cracks')
    normalized = normalized_gltf(positions, origin, height)
    weights, solver_report = solve_bbw(normalized, triangles, bones, log=log)
    weights = localize_fields(normalized, weights, bones).astype(np.float64)**1.5
    weights /= weights.sum(axis=1, keepdims=True)
    solver_report['locality_power'] = 1.5
    solver_report['tail_suppression'] = 'compact moving-arm/front-strand envelopes and smooth torso partition'
    indexes, quantized = pack_skin_weights(weights)
    weights = np.zeros_like(weights, dtype=np.float32)
    np.put_along_axis(weights, indexes, quantized.astype(np.float32)/65535, axis=1)
    bind_mesh(runtime, rig, bones, indexes, quantized)
    actions = create_animations(rig, bones, height)
    reset_rest_pose(rig)
    export_models(runtime, rig, output)
    repairs = repair_runtime_tangents(output/'solver-cage.glb')
    validate_skin(output/'solver-cage.glb', set(CLIPS))
    log('Transferring skin to full-resolution Blender geometry using closest faces')
    original_indexes, original_quantized, original_transfer = transfer_surface_weights(
        positions, triangles, mesh_vertices(original), weights)
    bind_mesh(original, rig, bones, original_indexes, original_quantized)
    original.hide_set(False)
    original.hide_render = False
    runtime.hide_set(True)
    runtime.hide_render = True
    log('Transferring skin to ALL original GLB vertices; geometry/UV/textures remain untouched')
    raw_positions = source_positions + np.asarray(document['nodes'][0].get('translation', [0, 0, 0]))
    raw_blender = np.column_stack([raw_positions[:, 0], -raw_positions[:, 2], raw_positions[:, 1]])
    full_indexes, full_quantized, full_transfer = transfer_surface_weights(
        positions, triangles, raw_blender, weights)
    primary_path = output/'anime-girl-rigged.glb'
    make_full_resolution(source, output/'solver-cage.glb', primary_path,
                         [b.name for b in bones], full_indexes, full_quantized)
    preservation = assert_geometry_preserved(source, primary_path)
    full_skin = validate_skin(primary_path, set(CLIPS))
    report = {
        'toolchain': {'blender': bpy.app.version_string, 'libigl': '2.6.3',
                      'method': rig['rigging_method'], 'fps': FPS},
        'source': {'file': source.name, 'archive_sha256': sha256(args.archive), 'glb_sha256': sha256(source),
                   'vertices': source_vertices, 'triangles': source_triangles,
                   'bytes': source.stat().st_size, 'original_skins': 0, 'original_animations': 0},
        'primary': {'file': primary_path.name, 'resolution': 'original, NOT decimated',
                    'vertices': source_vertices, 'triangles': source_triangles,
                    'bytes': primary_path.stat().st_size, 'sha256': sha256(primary_path), 'skin': full_skin},
        'preservation': preservation, 'joint_fitting': fitting, 'solver': solver_report,
        'solver_cage': {'internal_only': True, 'vertices': len(positions), 'triangles': len(triangles),
                        'topology': topology, 'tangent_repairs': repairs},
        'surface_transfer': {'blender_original': original_transfer, 'glb_original': full_transfer},
        'bones': [{'name': b.name, 'parent': b.parent, 'category': b.category} for b in bones],
        'clips': [{'name': name, 'duration_seconds': duration, 'fps': FPS,
                   'procedural': True, 'loop': name != 'Wave'} for name, duration in CLIPS.items()],
        'pose_checks': {},
        'limitations': ['Model-specific FK rig; not a universal arbitrary-pose autorigger.',
                        'No facial shapes or individually articulated fingers.',
                        'Skeletal secondary motion, not cloth/hair physics.',
                        'Strong contact poses may still need manual corrective shapes.']}
    report_path = output/'rig-report.json'
    report_path.write_text(json.dumps(report, indent=2)+'\n')
    log('Auditing REAL EXPORTED FULL-resolution skin at 21 times per clip')
    evaluator = SkinEvaluator(primary_path)
    report['pose_checks'] = evaluator.audit(frames=21)
    rest_error = float(np.max(np.abs(evaluator.deform(evaluator.matrices()) - raw_positions)))
    report['preservation']['maximum_rest_position_error_world_units'] = rest_error
    report_path.write_text(json.dumps(report, indent=2)+'\n')
    if rest_error > height*2e-5:
        raise ValueError(f'Rest skin changes original vertex positions: {rest_error}')
    for name, qa in report['pose_checks'].items():
        log(f'FULL QA {name}: p99.5={qa["edge_stretch_p99_5"]:.3f}, p99.9={qa["edge_stretch_p99_9"]:.3f}, max={qa["edge_stretch_max"]:.3f}, compression={qa["edge_compression_p00_5"]:.3f}')
        if (qa['edge_stretch_p99_5'] > 2.2 or qa['edge_stretch_p99_9'] > 3.6
                or qa['edge_compression_p00_5'] < .35
                or qa['maximum_extent_in_model_heights'] > 1.5):
            raise ValueError(f'Full-resolution deformation quality gate failed in {name}: {qa}')
    if not source.stat().st_size <= primary_path.stat().st_size < 100*1024*1024:
        raise ValueError('Unexpected full-resolution size or GitHub file limit exceeded')
    if args.validate_gltf:
        subprocess.run(['node', str(Path(__file__).with_name('validate-glb.cjs')),
                        str(primary_path)], check=True)
    # Only now produce the editable/full-resolution ancillary assets.
    del evaluator
    reset_rest_pose(rig)
    export_high_fbx(original, rig, output)
    camera = None
    if args.quality_renders:
        camera = render_quality_proof(original, runtime, rig, actions, report, height, origin, output)
    if args.render:
        camera = camera or add_studio(height, origin)
        render_previews(rig, bones, actions, camera, output)
    reset_rest_pose(rig)
    for name, action in actions.items():
        track = rig.animation_data.nla_tracks.new()
        track.name = name
        track.strips.new(name, 1, action)
        track.mute = True
    activate(original)
    rig.select_set(True)
    bpy.ops.wm.save_as_mainfile(filepath=str(output/'anime-girl-rigged.blend'), compress=True)
    report_path.write_text(json.dumps(report, indent=2)+'\n')
    write_readme(output, report)
    log(f'DONE: original-detail GLB {primary_path.stat().st_size:,} bytes; original buffers verified; high-poly FBX/Blender; deformation gates passed')


if __name__ == '__main__':
    import sys
    import traceback
    try:
        main()
        exit_code = 0
    except Exception:
        traceback.print_exc()
        exit_code = 1
    sys.stdout.flush()
    sys.stderr.flush()
    os._exit(exit_code)
