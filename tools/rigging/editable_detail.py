"""Create the corresponding editable high-poly Blender/FBX and actual-file renders.

Run headlessly on Actions. No web server, browser, stand-in mesh or emulator.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import traceback
from pathlib import Path

import bpy
import numpy as np
from mathutils import Matrix, Vector
from PIL import Image
from scipy.spatial import cKDTree

from core import read_accessor, read_sparse_accessor
from motion import BLENDER_TO_GLTF
from quality import SkinEvaluator
from rig_model import add_studio


def reset(scene, mesh, rig):
    for item in (mesh, rig, mesh.data.shape_keys):
        if item is not None and item.animation_data is not None:
            item.animation_data.action = None
            for track in item.animation_data.nla_tracks:
                track.mute = True
    for bone in rig.pose.bones:
        bone.matrix_basis = Matrix.Identity(4)
    if mesh.data.shape_keys:
        for shape in mesh.data.shape_keys.key_blocks:
            shape.value = 0
    scene.frame_set(0)
    bpy.context.view_layer.update()


def select(mesh, rig):
    bpy.ops.object.select_all(action='DESELECT')
    mesh.hide_set(False);mesh.select_set(True);rig.select_set(True)
    bpy.context.view_layer.objects.active = mesh


def render_pose(mesh, evaluator, mapping, name=None, time=0.):
    # The original geometry, skin, samplers AND morph targets from the actual
    # final file are evaluated. Never overlay bones on a static approximation.
    matrices = evaluator.matrices(name, time)
    posed = evaluator.deform(matrices, positions=evaluator.positions_at(name, time))
    positions = posed[mapping] @ BLENDER_TO_GLTF
    mesh.data.vertices.foreach_set('co', positions.astype(np.float32).ravel())
    attrs = evaluator.primitive['attributes']
    normal = read_accessor(evaluator.document, evaluator.binary, attrs['NORMAL']).astype(float)
    amounts = np.zeros(len(evaluator.morphs))
    if name is not None:
        for node, kind, times, values, interpolation in evaluator.channels[name]:
            if kind != 'weights':
                continue
            left = int(np.clip(np.searchsorted(times, time, side='right')-1, 0, len(times)-1));right = min(left+1, len(times)-1)
            f = 0 if left == right else float(np.clip((time-times[left])/(times[right]-times[left]), 0, 1))
            amounts = values[left]*(1-f)+values[right]*f
    for amount, target in zip(amounts, evaluator.primitive.get('targets', [])):
        if amount and 'NORMAL' in target:
            ids, delta = read_sparse_accessor(evaluator.document, evaluator.binary, target['NORMAL'])
            normal[ids] += amount*delta
    transformed = np.zeros_like(normal)
    for start in range(0, len(normal), 65536):
        stop = min(start+65536, len(normal))
        for slot in range(4):
            rotation = matrices[evaluator.joints[start:stop, slot], :3, :3]
            transformed[start:stop] += np.einsum('nij,nj->ni', rotation, normal[start:stop])*evaluator.weights[start:stop, slot, None]
    transformed /= np.maximum(np.linalg.norm(transformed, axis=1, keepdims=True), 1e-20)
    transformed = transformed[mapping] @ BLENDER_TO_GLTF
    mesh.data.normals_split_custom_set_from_vertices(transformed.tolist())
    mesh.data.update()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    evaluator = SkinEvaluator(args.model)
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(args.model.resolve()), merge_vertices=False)
    meshes = [o for o in bpy.context.scene.objects if o.type == 'MESH']
    rigs = [o for o in bpy.context.scene.objects if o.type == 'ARMATURE']
    if len(meshes) != 1 or len(rigs) != 1:
        raise ValueError('Expected the real high-poly mesh and its one armature')
    mesh, rig = meshes[0], rigs[0]
    mesh.name = 'AnimeGirl_Original_HighDetail'
    rig.name = 'AnimeGirl_Precision_Rig_100'
    # All 100 skin joints, including all 30 phalanges, must survive import.
    missing = set(evaluator.joint_names)-set(rig.data.bones.keys())
    if missing:
        raise ValueError(f'Editable model lost rig joints: {missing}')
    expected = evaluator.document['meshes'][0]['extras']['targetNames']
    actual = mesh.data.shape_keys.key_blocks.keys() if mesh.data.shape_keys else []
    if set(expected)-set(actual):
        raise ValueError(f'Editable model lost facial keys: {set(expected)-set(actual)}')
    scene = bpy.context.scene
    reset(scene, mesh, rig)
    rig.show_in_front = True
    rig['facial_controls'] = 'Face.* weighted bones and 40 independently editable expression shape keys'
    rig['finger_controls'] = 'Both hands: Thumb, Index, Middle, Ring, Pinky; three fitted phalanges per digit'
    # Face control meshes are visible as bones in pose mode, not empty names.
    for bone in rig.data.bones:
        category = 'face' if bone.name.startswith('Face.') else 'finger' if 'Hand' in bone.name and any(x in bone.name for x in ('Thumb', 'Index', 'Middle', 'Ring', 'Pinky')) else 'body'
        bone['category'] = category
        bone.color.palette = {'face': 'THEME03', 'finger': 'THEME02', 'body': 'THEME04'}[category]
    select(mesh, rig)
    print('Exporting actual high-poly FBX with 100 joints and 40 facial shapes', flush=True)
    bpy.ops.export_scene.fbx(filepath=str((args.output/'anime-girl-rigged.fbx').resolve()),
        use_selection=True, object_types={'MESH', 'ARMATURE'}, add_leaf_bones=False,
        bake_anim=True, bake_anim_use_nla_strips=False, bake_anim_use_all_actions=True,
        bake_anim_simplify_factor=0., axis_forward='-Z', axis_up='Y', path_mode='COPY', embed_textures=True)
    reset(scene, mesh, rig)
    select(mesh, rig)
    bpy.ops.wm.save_as_mainfile(filepath=str((args.output/'anime-girl-rigged.blend').resolve()), compress=True)
    print('Editable source saved; making offline close-up renders of the real final file', flush=True)
    # Render a full-resolution copy with actual-file evaluated coordinates. The
    # editable rig is already saved, with actual shape keys/groups/animations.
    copy_mesh = mesh.copy();copy_mesh.data = mesh.data.copy()
    bpy.context.collection.objects.link(copy_mesh)
    copy_mesh.name = 'Actual_GLTF_Quality_Surface'
    copy_mesh.shape_key_clear()
    copy_mesh.modifiers.clear();copy_mesh.parent = None;copy_mesh.matrix_world = Matrix.Identity(4)
    coords = np.empty(len(mesh.data.vertices)*3, dtype=np.float32);mesh.data.vertices.foreach_get('co', coords)
    points = np.c_[coords.reshape(-1, 3), np.ones(len(mesh.data.vertices))] @ np.asarray(mesh.matrix_world).T
    world = points[:, :3] @ BLENDER_TO_GLTF.T
    rest = evaluator.deform(evaluator.matrices())
    distance, mapping = cKDTree(rest).query(world, workers=2)
    if distance.max() > evaluator.height*.001:
        raise ValueError(f'Editable import no longer represents actual full mesh: {distance.max()}')
    mesh.hide_render = True;mesh.hide_set(True)
    rig.hide_render = True
    origin = np.array([0., 0., 0.]);height = evaluator.height
    camera = add_studio(height, origin)
    scene.cycles.samples = 12
    scene.cycles.seed = 42
    scene.render.resolution_x = 512;scene.render.resolution_y = 512
    scene.render.image_settings.file_format = 'PNG'
    folder = args.output/'quality';folder.mkdir(exist_ok=True)
    views = [
        ('face-neutral', None, 0., [-.013, .888, .077], [-.013, .888, .45], .17),
        ('face-blink', 'Blink', 1., [-.013, .888, .077], [-.013, .888, .45], .17),
        ('face-smile', 'Smile', 1.5, [-.013, .888, .077], [-.013, .888, .45], .17),
        ('hand-right-neutral', None, 0., [-.158, .48, .003], [-.39, .48, .08], .09),
        ('hand-right-curl', 'Finger_Curl', 1.5, [-.158, .48, .003], [-.39, .48, .08], .09),
        ('hand-left-neutral', None, 0., [.14, .825, .080], [.34, .825, .19], .10),
        ('hand-left-curl', 'Finger_Curl', 1.5, [.14, .825, .080], [.34, .825, .19], .10),
    ]
    camera.data.type = 'ORTHO'
    for filename, clip, when, target, eye, scale in views:
        render_pose(copy_mesh, evaluator, mapping, clip, when)
        target = Vector((np.array(target)*height) @ BLENDER_TO_GLTF)
        camera.location = Vector((np.array(eye)*height) @ BLENDER_TO_GLTF)
        camera.rotation_euler = (target-camera.location).to_track_quat('-Z', 'Y').to_euler()
        camera.data.ortho_scale = scale*height
        scene.render.filepath = str((folder/(filename+'.png')).resolve())
        print('Rendering real source resolution:', filename, flush=True)
        bpy.ops.render.render(write_still=True)
    for label, files in [('facial-animation', ['face-neutral', 'face-blink', 'face-neutral', 'face-smile', 'face-neutral']),
                         ('finger-animation', ['hand-right-neutral', 'hand-right-curl', 'hand-right-neutral'])]:
        images = [Image.open(folder/(f+'.png')).convert('RGB').resize((384, 384)) for f in files]
        images[0].save(folder/(label+'.gif'), save_all=True, append_images=images[1:], duration=550, loop=0, optimize=True)
    print('DONE: actual GLB preserved, matching editable FBX/Blender and real facial/finger proof renders', flush=True)


if __name__ == '__main__':
    try:
        main();code = 0
    except Exception:
        traceback.print_exc();code = 1
    sys.stdout.flush();sys.stderr.flush();os._exit(code)
