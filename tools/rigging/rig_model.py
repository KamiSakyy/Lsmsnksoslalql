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

from core import (capsule_weights, extract_glb, load_profile, make_full_resolution,
                  pack_four_weights, read_accessor, read_glb, sha256, smooth_weights,
                  surface_distances, surface_topology, transfer_weights, two_bone_ik, validate_skin)

CLIPS = {'Idle': 4.0, 'Walk_InPlace': 1.2, 'Run_InPlace': 0.8, 'Wave': 3.0, 'Dance': 4.0}
FPS = 30
PREFIX = 'mixamorig:'
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
    rig['rigging_method'] = 'Surface-geodesic anatomical binding + hair/cloth envelopes + diffusion + 4-influence normalization'
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
        for amount in np.unique(amounts):
            if amount:
                group.add(rows[amounts == amount].tolist(), int(amount) / 255, 'REPLACE')
    obj.parent = rig
    modifier = obj.modifiers.new('Skeletal deformation', 'ARMATURE')
    modifier.object = rig
    modifier.use_deform_preserve_volume = False  # glTF uses linear blend skinning too.
    modifier.use_vertex_groups = True
    modifier.use_bone_envelopes = False


def rotation(axis, degrees):
    return Quaternion(Vector(axis), math.radians(degrees))


def create_animations(rig, bones, height):
    scene = bpy.context.scene
    scene.render.fps = FPS
    scene.frame_start = 1
    scene.frame_end = 121
    rig.animation_data_create()
    identity = Quaternion((1, 0, 0, 0))
    rest = {b.name: rig.data.bones[b.name].matrix_local.to_quaternion() for b in bones}
    heads = {b.name: rig.data.bones[b.name].head_local.copy() for b in bones}
    tails = {b.name: rig.data.bones[b.name].tail_local.copy() for b in bones}
    hips_name = PREFIX + 'Hips'
    actions = {}
    for name, duration in CLIPS.items():
        action = bpy.data.actions.new(name)
        action.use_fake_user = True
        rig.animation_data.action = action
        last_frame = int(round(duration * FPS)) + 1
        log(f'Generating {name}: {last_frame} keyframes')
        for frame in range(1, last_frame + 1):
            phase = (frame - 1) / (last_frame - 1)
            t = 2 * math.pi * phase
            deltas = {b.name: identity.copy() for b in bones}
            location = Vector((0, 0, 0))
            sin, cos = math.sin(t), math.cos(t)
            if name == 'Idle':
                location.z = 0.0018 * height * sin
                deltas[hips_name] = rotation((0, 0, 1), 0.8 * sin)
                deltas[PREFIX + 'Spine'] = rotation((1, 0, 0), 0.65 * sin)
                deltas[PREFIX + 'Spine1'] = rotation((1, 0, 0), 1.1 * sin)
                deltas[PREFIX + 'Spine2'] = rotation((1, 0, 0), 1.4 * sin)
                deltas[PREFIX + 'Neck'] = rotation((0, 0, 1), 1.6 * cos)
                deltas[PREFIX + 'Head'] = rotation((0, 0, 1), 2.2 * cos) @ rotation((1, 0, 0), 0.8 * sin)
            elif name in ('Walk_InPlace', 'Run_InPlace', 'Dance'):
                running = name == 'Run_InPlace'
                dancing = name == 'Dance'
                location.z = height * (-0.009 + 0.002 * math.cos(2*t))
                if running:
                    location.z = height * (-0.014 + 0.007 * math.cos(2*t))
                if dancing:
                    location.x = height * 0.014 * sin
                    location.z = height * (-0.018 + 0.004 * math.cos(2*t))
                yaw = (7 if dancing else 4 if running else 2.5) * sin
                deltas[hips_name] = rotation((0, 0, 1), yaw)
                if running:
                    deltas[hips_name] = rotation((1, 0, 0), 6) @ deltas[hips_name]
                deltas[PREFIX + 'Spine'] = rotation((0, 0, 1), -yaw * 0.4)
                deltas[PREFIX + 'Spine1'] = rotation((0, 0, 1), -yaw * 0.6)
                deltas[PREFIX + 'Spine2'] = rotation((0, 0, 1), -yaw * 0.8)
                deltas[PREFIX + 'Head'] = rotation((0, 0, 1), -yaw * 0.4)
                if dancing:
                    deltas[hips_name] = rotation((0, 1, 0), 3 * sin) @ deltas[hips_name]
                for side, offset in [('Left', 0), ('Right', math.pi)]:
                    s = math.sin(t + offset)
                    c = math.cos(t + offset)
                    upper_name, lower_name = PREFIX + side + 'UpLeg', PREFIX + side + 'Leg'
                    hip_rest = heads[upper_name]
                    hip = heads[hips_name] + deltas[hips_name] @ (hip_rest - heads[hips_name]) + location
                    # Align both feet for locomotion despite the source's bent-back right leg.
                    foot = tails[lower_name].copy()
                    foot.y = -0.006 * height
                    foot.y += height * (0.078 if running else 0.038 if not dancing else 0.012) * c
                    foot.z = tails[lower_name].z + height * (0.045 if running else 0.025 if not dancing else 0.009) * max(0, s)
                    upper_length = (tails[upper_name] - heads[upper_name]).length
                    lower_length = (tails[lower_name] - heads[lower_name]).length
                    knee, reachable = two_bone_ik(np.asarray(hip), np.asarray(foot), upper_length,
                                                 lower_length, np.array([0., -1., 0.]))
                    knee, reachable = Vector(knee), Vector(reachable)
                    deltas[upper_name] = (tails[upper_name] - heads[upper_name]).normalized().rotation_difference((knee - hip).normalized())
                    deltas[lower_name] = (tails[lower_name] - heads[lower_name]).normalized().rotation_difference((reachable - knee).normalized())
                    deltas[PREFIX + side + 'Foot'] = rotation((1, 0, 0), -7 * max(0, s) if running else -3 * max(0, s))
                    deltas[PREFIX + side + 'ToeBase'] = identity.copy()
                amplitude = 11 if not running else 22
                if dancing:
                    amplitude = 15
                deltas[PREFIX + 'RightArm'] = rotation((1, 0, 0), amplitude * sin)
                deltas[PREFIX + 'RightForeArm'] = rotation((1, 0, 0), amplitude * sin + (5 if running else 0))
                deltas[PREFIX + 'RightHand'] = deltas[PREFIX + 'RightForeArm'].copy()
                # Preserve the raised, hair-holding arm rather than tearing that contact apart.
                for part in ('Arm', 'ForeArm', 'Hand'):
                    deltas[PREFIX + 'Left' + part] = rotation((1, 0, 0), 1.7 * sin)
            elif name == 'Wave':
                envelope = min(1., phase / 0.20, (1-phase) / 0.20)
                envelope = envelope * envelope * (3 - 2 * envelope)
                upper = PREFIX + 'RightArm'
                lower = PREFIX + 'RightForeArm'
                hand = PREFIX + 'RightHand'
                target_upper = Vector((-0.12, -0.025, -0.03)).normalized()
                target_lower = Vector((-0.015, -0.040, 0.145)).normalized()
                arm_delta = (tails[upper] - heads[upper]).normalized().rotation_difference(target_upper)
                lower_delta = (tails[lower] - heads[lower]).normalized().rotation_difference(target_lower)
                deltas[upper] = identity.slerp(arm_delta, envelope)
                deltas[lower] = identity.slerp(lower_delta, envelope)
                deltas[hand] = rotation((0, 1, 0), 13 * math.sin(6*t) * envelope) @ deltas[lower]
                deltas[PREFIX + 'Head'] = rotation((0, 0, 1), 4 * envelope)
            # Secondary animation is relative to its parent, not independent world space.
            for index, spec in enumerate(bones):
                if spec.category in ('hair', 'coat', 'skirt'):
                    parent_delta = deltas[spec.parent]
                    amplitude = {'Idle': 0.8, 'Walk_InPlace': 2.0, 'Run_InPlace': 3.3,
                                 'Wave': 1.0, 'Dance': 3.0}[name]
                    amplitude *= 1.0 if spec.category == 'hair' else 0.65
                    if '.Held.' in spec.name:
                        amplitude *= 0.20
                    sway = rotation((0, 1, 0), amplitude * math.sin(t + index * 0.3))
                    deltas[spec.name] = parent_delta @ sway
            for spec in bones:
                parent_delta = deltas[spec.parent] if spec.parent else identity
                local = rest[spec.name].inverted() @ parent_delta.inverted() @ deltas[spec.name] @ rest[spec.name]
                pose = rig.pose.bones[spec.name]
                pose.rotation_quaternion = local
                pose.location = (0, 0, 0)
                if spec.name == hips_name:
                    pose.location = rest[spec.name].inverted() @ location
                pose.keyframe_insert(data_path='rotation_quaternion', frame=frame, group=spec.name)
                if spec.name == hips_name:
                    pose.keyframe_insert(data_path='location', frame=frame, group=spec.name)
            scene.frame_set(frame)
        for curve in action.fcurves:
            for key in curve.keyframe_points:
                key.interpolation = 'LINEAR'
        actions[name] = action
    rig.animation_data.action = None
    for pose in rig.pose.bones:
        pose.rotation_quaternion = identity
        pose.location = (0, 0, 0)
    scene.frame_set(0)
    return actions


def select_export(runtime, rig):
    bpy.ops.object.select_all(action='DESELECT')
    runtime.select_set(True)
    rig.select_set(True)
    bpy.context.view_layer.objects.active = rig


def export_models(runtime, rig, output):
    select_export(runtime, rig)
    log('Exporting runtime GLB with five separate animation clips')
    bpy.ops.export_scene.gltf(
        filepath=str(output / 'anime-girl-rigged.glb'), export_format='GLB', use_selection=True,
        export_animations=True, export_animation_mode='ACTIONS', export_anim_single_armature=True,
        export_force_sampling=True, export_frame_step=1, export_frame_range=False,
        export_anim_slide_to_zero=True, export_rest_position_armature=True, export_def_bones=False,
        export_skins=True, export_tangents=True, export_materials='EXPORT', export_cameras=False, export_lights=False,
        export_optimize_animation_size=False)
    log('Exporting FBX with actions (no extra leaf bones)')
    bpy.ops.export_scene.fbx(
        filepath=str(output / 'anime-girl-rigged.fbx'), use_selection=True,
        object_types={'MESH', 'ARMATURE'}, add_leaf_bones=False, bake_anim=True,
        bake_anim_use_nla_strips=False, bake_anim_use_all_actions=True,
        bake_anim_simplify_factor=0.0, axis_forward='-Z', axis_up='Y',
        path_mode='COPY', embed_textures=True)


def pose_quality_checks(runtime, rig, actions, height):
    results = {}
    rest_positions = mesh_vertices(runtime)
    triangles = mesh_triangles(runtime)
    edges = np.concatenate([triangles[:, [0, 1]], triangles[:, [1, 2]], triangles[:, [2, 0]]])
    lengths = np.linalg.norm(rest_positions[edges[:, 0]] - rest_positions[edges[:, 1]], axis=1)
    valid = lengths > height * 1e-4
    edges, lengths = edges[valid], lengths[valid]
    for name, action in actions.items():
        rig.animation_data.action = action
        maximum, stretch = 0., 0.
        for frame in np.linspace(action.frame_range[0], action.frame_range[1], 5):
            bpy.context.scene.frame_set(int(frame))
            depsgraph = bpy.context.evaluated_depsgraph_get()
            evaluated = runtime.evaluated_get(depsgraph)
            mesh = evaluated.to_mesh()
            positions = np.empty(len(mesh.vertices) * 3, dtype=np.float32)
            mesh.vertices.foreach_get('co', positions)
            positions = positions.reshape(-1, 3)
            if not np.all(np.isfinite(positions)):
                raise ValueError(f'Non-finite deformation in {name}')
            extent = positions.max(axis=0) - positions.min(axis=0)
            deformed_lengths = np.linalg.norm(positions[edges[:, 0]] - positions[edges[:, 1]], axis=1)
            stretch = max(stretch, float(np.percentile(deformed_lengths / lengths, 99.5)))
            evaluated.to_mesh_clear()
            maximum = max(maximum, float(extent.max() / height))
            if maximum > 1.6:
                raise ValueError(f'Exploding mesh in {name}: {maximum} model heights')
        log(f'Pose QA {name}: extent={maximum:.3f} H, 99.5-percentile edge stretch={stretch:.3f}')
        if stretch > 12:
            raise ValueError(f'Severe skin stretching in {name}: {stretch}')
        results[name] = {'sampled_frames': 5, 'maximum_extent_in_model_heights': maximum,
                         'edge_stretch_p99_5': stretch}
    rig.animation_data.action = None
    return results


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


def write_readme(output, report):
    run_url = os.environ.get('GITHUB_SERVER_URL', 'https://github.com') + '/' + os.environ.get('GITHUB_REPOSITORY', 'KamiSakyy/Lsmsnksoslalql') + '/actions/runs/' + os.environ.get('GITHUB_RUN_ID', '')
    content = f'''# Девушка Tripo: скелет и анимации

Модель из исходного ZIP обработана автоматически в GitHub Actions с помощью **Blender {bpy.app.version_string}, NumPy и SciPy**, без аккаунта Mixamo и без GPU.

## Результат

- **anime-girl-rigged.glb** — версия для просмотра/игрового движка: {report['runtime']['triangles']:,} треугольников, текстуры, 48 костей, 5 отдельных клипов.
- **anime-girl-full.glb** — исходные {report['source']['vertices']:,} вершин и {report['source']['triangles']:,} треугольников, исходные текстуры и геометрия **без упрощения**, тот же скелет и анимации. В полном артефакте Actions, не дублируется в Git.
- **anime-girl-rigged.blend** — редактируемая сцена Blender. Объект `AnimeGirl_Animated` видим; оригинал `AnimeGirl_Original_HighDetail` тоже имеет веса и Armature, но скрыт, чтобы не рисовать две поверхности одновременно. Снимите скрытие оригинала и скройте облегчённую копию для полной детализации.
- **anime-girl-rigged.fbx** — облегчённая модель со скелетом, текстурами и клипами для Blender/Unity/Unreal.
- **skeleton.png**, **rest-pose.png**, **animation-preview.gif** — настоящий рендер и демонстрация всех движений.
- **rig-report.json**, **khronos-validation.json** — машинные проверки.

[Запуск и полный артефакт GitHub Actions]({run_url}) → раздел **Artifacts** → `anime-girl-rigged`.

## Анимации

| Клип | Длительность | Что делает |
|---|---:|---|
| Idle | 4,0 с | Дыхание, небольшие движения корпуса/головы, вторичные движения волос |
| Walk_InPlace | 1,2 с | Цикл ходьбы на месте; ноги решаются двухзвенным IK |
| Run_InPlace | 0,8 с | Цикл бега на месте |
| Wave | 3,0 с | Подъём свободной правой руки, приветствие, возврат |
| Dance | 4,0 с | Мягкий танцевальный цикл |

Это **процедурные** движения, а не motion capture Mixamo. Первоначальная асимметричная поза является bind/rest pose; поднятая рука у волос в основном сохраняется. Анимации можно заменять/ретаргетировать: основные кости имеют имена `mixamorig:*`, дополнительные `Hair.*`, `Coat.*`, `Skirt.*`. Совпадение основных имён облегчает настройку ретаргетинга, но не делает исходную позу стандартной T-позой.

## Как устроено

Профиль `tools/rigging/model_profile.json` подобран **именно для этой модели** по её геометрии и виду спереди/сбоку. Скрипт масштабирует анатомические ориентиры по фактическим размерам меша, строит иерархию, вычисляет геодезические расстояния по связности поверхности вместе с анатомическими капсульными весами (чтобы близкие, но несвязанные руки и одежда не тянулись друг за другом), отдельно учитывает волосы/подол, сглаживает веса по графу поверхности и оставляет не более четырёх влияний на вершину. Непривязанных вершин: **{report['runtime']['skin']['unweighted_vertices']}**. Веса облегчённой версии переносятся обратно на оригинал.

Это стартовый автоматический **FK-риг**, а не ручной production-риг: нет мимики, лицевых blendshape, отдельных костей пальцев или физической симуляции ткани/волос. Волосы и одежда имеют кости с процедурным движением. Для крупных/экстремальных движений у плеч, локтей, юбки и касания руки с волосами могут понадобиться ручная коррекция весов/позы и корректирующие формы. У AI-меша из одной поверхности нет скрытой анатомии под одеждой; скелет её не создаёт.

## Повторный запуск

Workflow: `.github/workflows/rig-model.yml`, только ветка `arena/1e52fcec-lsmsnksoslalql`. Запускается при изменении скриптов/профиля; также есть `workflow_dispatch`. На Linux с Python 3.11 и системными библиотеками Blender:

```sh
python -m pip install -r tools/rigging/requirements.txt
python tools/rigging/rig_model.py --archive handoff/d247d7c3_46ba_414e_b821_b731d4b67026_ab2fefced0df785fa9f396410d0b5905.zip --output build/rigged --render
```

Исходный ZIP не изменяется, секреты и сервер приложения не используются. Большие `.blend`/FBX/полный GLB хранятся в Actions artifact (90 дней); скачайте их для долговременного хранения. Облегчённый GLB, отчёт и превью сохранены в этой ветке Git.
'''
    (output / 'README_RU.md').write_text(content)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('build/rigged'))
    parser.add_argument('--target-triangles', type=int, default=160000)
    parser.add_argument('--render', action='store_true')
    parser.add_argument('--validate-gltf', action='store_true',
                        help='Require Khronos validation before rendering or saving the final scene')
    args = parser.parse_args()
    if not 10000 <= args.target_triangles <= 500000:
        parser.error('triangle budget must be 10000..500000')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    log(f'Blender {bpy.app.version_string}; source archive SHA-256 {sha256(args.archive)}')
    source = extract_glb(args.archive.resolve(), output.parent / 'source')
    document, binary = read_glb(source)
    primitive = document['meshes'][0]['primitives'][0]
    source_vertices = document['accessors'][primitive['attributes']['POSITION']]['count']
    source_triangles = document['accessors'][primitive['indices']]['count'] // 3
    bones = load_profile(Path(__file__).with_name('model_profile.json'))
    original, runtime, origin, height = import_and_simplify(source, args.target_triangles)
    rig = make_armature(bones, origin, height)
    positions = mesh_vertices(runtime)
    triangles = mesh_triangles(runtime)
    topology = surface_topology(positions / height, triangles)
    log(f'Runtime surface topology: {topology}')
    if topology['boundary_edges'] > 1000:
        raise ValueError('Runtime simplification introduced too many physical mesh cracks')
    colors = vertex_colors(runtime, document, binary)
    log('Solving surface-geodesic distances to avoid hand/coat/hair cross-binding')
    normalized_positions = normalized_gltf(positions, origin, height)
    geodesic = surface_distances(normalized_positions, triangles, bones)
    log('Solving pose-aware body / hair / clothing skin weights')
    weights = capsule_weights(normalized_positions, bones, colors, geodesic)
    weights = smooth_weights(positions / height, triangles, weights)
    indexes, quantized = pack_four_weights(weights)
    # Transfer the actual, quantized skin that the runtime export will use.
    weights[:] = 0
    np.put_along_axis(weights, indexes, quantized.astype(np.float32) / 255, axis=1)
    bind_mesh(runtime, rig, bones, indexes, quantized)
    actions = create_animations(rig, bones, height)
    pose_checks = pose_quality_checks(runtime, rig, actions, height)
    rig.animation_data.action = None
    for pose in rig.pose.bones:
        pose.rotation_quaternion = Quaternion((1, 0, 0, 0))
        pose.location = (0, 0, 0)
    bpy.context.scene.frame_set(0)
    export_models(runtime, rig, output)
    runtime_skin = validate_skin(output / 'anime-girl-rigged.glb', set(CLIPS))
    log(f'Runtime GLB skin validated: {runtime_skin}')
    log('Transferring skin weights to the original high-detail Blender mesh')
    original_indexes, original_quantized = transfer_weights(positions, mesh_vertices(original), weights)
    bind_mesh(original, rig, bones, original_indexes, original_quantized)
    original.hide_set(True)
    original.hide_render = True
    log('Preserving original GLB geometry and textures; appending skeleton and animations')
    raw_positions = read_accessor(document, binary, primitive['attributes']['POSITION'])
    raw_positions += np.asarray(document['nodes'][0].get('translation', [0, 0, 0]))
    raw_blender = np.column_stack([raw_positions[:, 0], -raw_positions[:, 2], raw_positions[:, 1]])
    full_indexes, full_quantized = transfer_weights(positions, raw_blender, weights)
    make_full_resolution(source, output / 'anime-girl-rigged.glb', output / 'anime-girl-full.glb',
                         [b.name for b in bones], full_indexes, full_quantized)
    full_skin = validate_skin(output / 'anime-girl-full.glb', set(CLIPS))
    report = {
        'toolchain': {'blender': bpy.app.version_string, 'method': rig['rigging_method'], 'fps': FPS},
        'source': {'file': source.name, 'archive_sha256': sha256(args.archive), 'glb_sha256': sha256(source),
                   'vertices': source_vertices, 'triangles': source_triangles,
                   'original_skins': len(document.get('skins', [])), 'original_animations': len(document.get('animations', []))},
        'runtime': {'file': 'anime-girl-rigged.glb', 'vertices': len(positions), 'triangles': len(triangles),
                    'skin': runtime_skin, 'topology': topology, 'bytes': (output / 'anime-girl-rigged.glb').stat().st_size},
        'full_resolution': {'file': 'anime-girl-full.glb', 'geometry_and_textures_preserved': True,
                            'skin': full_skin, 'bytes': (output / 'anime-girl-full.glb').stat().st_size},
        'bones': [{'name': b.name, 'parent': b.parent, 'category': b.category} for b in bones],
        'clips': [{'name': name, 'duration_seconds': duration, 'fps': FPS,
                   'procedural': True, 'loop': name != 'Wave'} for name, duration in CLIPS.items()],
        'pose_checks': pose_checks,
        'limitations': ['Profile is fitted to this model, not a universal arbitrary-pose autorigger.',
                        'No facial blendshapes or individually articulated fingers.',
                        'Hair/clothing motion is skeletal/procedural, not physical simulation.',
                        'Extreme poses may need manual weights and corrective shapes.']}
    (output / 'rig-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    if args.validate_gltf:
        log('Checking both exports with the official Khronos validator before rendering')
        subprocess.run(['node', str(Path(__file__).with_name('validate-glb.cjs')),
                        str(output / 'anime-girl-rigged.glb'),
                        str(output / 'anime-girl-full.glb')], check=True)
    camera = add_studio(height, origin)
    if args.render:
        render_previews(rig, bones, actions, camera, output)
    # Keep each clip as an independent, muted NLA track for convenient editing.
    rig.animation_data.action = actions['Idle']
    for name, action in actions.items():
        track = rig.animation_data.nla_tracks.new()
        track.name = name
        track.strips.new(name, 1, action)
        track.mute = True
    bpy.context.scene.frame_set(1)
    select_export(runtime, rig)
    bpy.ops.wm.save_as_mainfile(filepath=str(output / 'anime-girl-rigged.blend'), compress=True)
    write_readme(output, report)
    log('DONE: GLB (runtime + full), FBX, Blender scene, skin checks and preview files')


if __name__ == '__main__':
    main()
    # bpy embeds Blender's native runtime in CPython. Do not tear its worker
    # threads down a second time during CPython finalization. This is reached
    # only after every export, skin check, render and save completed normally;
    # exceptions/crashes in main still fail the job, and Khronos checks follow.
    import sys
    sys.stdout.flush()
    sys.stderr.flush()
    os._exit(0)
