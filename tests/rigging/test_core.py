import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools' / 'rigging'))
from core import (Bone, capsule_weights, extract_glb, load_profile, make_full_resolution,
                  node_matrix, pack_four_weights, read_accessor, read_glb, smooth_weights,
                  surface_distances, surface_topology, two_bone_ik, validate_skin, world_matrices, write_glb)

PROFILE = Path(__file__).resolve().parents[2] / 'tools' / 'rigging' / 'model_profile.json'


class CoreTests(unittest.TestCase):
    def test_profile_hierarchy_and_count(self):
        bones = load_profile(PROFILE)
        self.assertEqual(len(bones), 48)
        self.assertEqual(bones[0].category, 'root')
        self.assertIn('mixamorig:LeftHand', {b.name for b in bones})
        self.assertEqual(sum(b.category == 'hair' for b in bones), 15)

    def test_bind_weights_are_finite_and_normalized(self):
        bones = load_profile(PROFILE)
        positions = np.array([[0, .9, .035], [-.15, .46, .05], [.046, .07, .03],
                              [0, .55, .04], [.052, .67, -.09], [0, 0, 0]])
        weights = capsule_weights(positions, bones, None)
        np.testing.assert_allclose(weights.sum(axis=1), 1, atol=1e-6)
        self.assertTrue(np.all(np.isfinite(weights)))
        self.assertTrue(np.all(weights[:, 0] == 0))
        self.assertEqual(bones[weights[0].argmax()].name, 'mixamorig:Head')
        self.assertTrue(bones[weights[4].argmax()].name.startswith('Hair.'))

    def test_four_influences_and_exact_quantized_normalization(self):
        rng = np.random.default_rng(17)
        weights = rng.random((1000, 48)).astype(np.float32)
        indexes, quantized = pack_four_weights(weights)
        self.assertEqual(indexes.shape, (1000, 4))
        self.assertEqual(quantized.dtype, np.uint8)
        self.assertTrue(np.all(quantized.sum(axis=1) == 255))
        self.assertTrue(np.all(indexes < 48))

    def test_unweighted_vertices_are_rejected(self):
        with self.assertRaises(ValueError):
            pack_four_weights(np.zeros((3, 6)))

    def test_surface_smoothing_is_normalized(self):
        vertices = np.array([[0, 0, 0], [1, 0, 0], [0, 1, 0], [3, 3, 3]], dtype=float)
        triangles = np.array([[0, 1, 2]])
        weights = np.eye(4, dtype=np.float32)
        result = smooth_weights(vertices, triangles, weights)
        np.testing.assert_allclose(result.sum(axis=1), 1, atol=1e-6)
        self.assertGreater(result[0, 1], 0)
        self.assertEqual(result[3, 3], 1)

    def test_ik_preserves_both_bone_lengths(self):
        hip = np.array([0., 0., 1.])
        target = np.array([0., .1, .3])
        knee, ankle = two_bone_ik(hip, target, .45, .4, np.array([0., -1., 0.]))
        self.assertAlmostEqual(np.linalg.norm(knee-hip), .45, places=6)
        self.assertAlmostEqual(np.linalg.norm(ankle-knee), .4, places=6)
        np.testing.assert_allclose(ankle, target)

    def test_ik_clamps_unreachable_and_degenerate_targets(self):
        for target in (np.array([0., 0., -8.]), np.zeros(3)):
            knee, ankle = two_bone_ik(np.zeros(3), target, .3, .4, np.array([0., 0., -1.]))
            self.assertTrue(np.all(np.isfinite(knee)))
            self.assertAlmostEqual(np.linalg.norm(knee), .3, places=6)
            self.assertAlmostEqual(np.linalg.norm(ankle-knee), .4, places=6)

    def test_zip_traversal_and_absolute_paths_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('../girl.glb', '/tmp/girl.glb', '..\\girl.glb', 'C:\\girl.glb'):
                with zipfile.ZipFile(root / 'bad.zip', 'w') as archive:
                    archive.writestr(name, b'not executed')
                with self.assertRaises(ValueError):
                    extract_glb(root / 'bad.zip', root / 'out')

    def test_extracts_one_glb_without_other_archive_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with zipfile.ZipFile(root / 'model.zip', 'w') as archive:
                archive.writestr('nested/girl.glb', b'glTF')
                archive.writestr('run.sh', b'never execute this')
            output = extract_glb(root / 'model.zip', root / 'out')
            self.assertEqual(output.name, 'girl.glb')
            self.assertEqual(output.read_bytes(), b'glTF')
            self.assertFalse((root / 'out' / 'run.sh').exists())

    def test_glb_roundtrip_and_normalized_accessor(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'model.glb'
            document = {'asset': {'version': '2.0'},
                        'bufferViews': [{'buffer': 0, 'byteLength': 4}],
                        'accessors': [{'bufferView': 0, 'componentType': 5121,
                                       'count': 1, 'type': 'VEC4', 'normalized': True}]}
            write_glb(path, document, bytes([128, 127, 0, 0]))
            read, binary = read_glb(path)
            weights = read_accessor(read, binary, 0)
            self.assertAlmostEqual(float(weights.sum()), 1, places=6)

    def test_invalid_glb_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'not.glb'
            path.write_bytes(b'<html>Not a model</html>')
            with self.assertRaises(ValueError):
                read_glb(path)

    def test_topology_detects_cracks_and_ignores_uv_splits(self):
        vertices = np.array([[0, 0, 0], [1, 0, 0], [0, 1, 0], [0, 0, 1]], dtype=float)
        faces = np.array([[0, 1, 2], [0, 3, 1], [1, 3, 2], [0, 2, 3]])
        self.assertEqual(surface_topology(vertices, faces)['boundary_edges'], 0)
        self.assertEqual(surface_topology(vertices, faces[:3])['boundary_edges'], 3)
        split_vertices = np.vstack([vertices, vertices[0]])
        split_faces = faces.copy()
        split_faces[0, 0] = 4
        self.assertEqual(surface_topology(split_vertices, split_faces)['boundary_edges'], 0)

    def test_geodesic_distances_do_not_jump_between_nearby_surfaces(self):
        vertices = np.array([[0, 0, 0], [.05, 0, 0], [0, .05, 0],
                             [0, 0, .003], [.05, 0, .003], [0, .05, .003]])
        triangles = np.array([[0, 1, 2], [3, 4, 5]])
        bone = Bone('Arm', None, np.zeros(3), np.array([.02, 0, 0]), .01, 'body')
        distances = surface_distances(vertices, triangles, [bone])
        self.assertTrue(np.all(np.isfinite(distances[:3])))
        self.assertTrue(np.all(np.isinf(distances[3:])))

    def test_full_resolution_preserves_geometry_and_canonical_empty_slots(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            positions = np.array([[0, 0, 0], [1, 0, 0], [0, 1, 0]], dtype='<f4')
            source = {'asset': {'version': '2.0'}, 'scene': 0, 'scenes': [{'nodes': [0]}],
                      'nodes': [{'mesh': 0, 'translation': [.3, .1, .2]}],
                      'meshes': [{'primitives': [{'attributes': {'POSITION': 0}}]}],
                      'bufferViews': [{'buffer': 0, 'byteLength': 36, 'byteOffset': 0, 'target': 34962}],
                      'accessors': [{'bufferView': 0, 'componentType': 5126, 'count': 3,
                                     'type': 'VEC3', 'min': [0, 0, 0], 'max': [1, 1, 0]}]}
            write_glb(root / 'source.glb', source, positions.tobytes())
            matrices = np.tile(np.eye(4, dtype='<f4').ravel(), (2, 1))
            times = np.array([0, 1], dtype='<f4')
            rotations = np.array([[0, 0, 0, 1], [0, 0, 0, 1]], dtype='<f4')
            binary = matrices.tobytes() + times.tobytes() + rotations.tobytes() + positions.tobytes()
            runtime = {'asset': {'version': '2.0'}, 'scene': 0, 'scenes': [{'nodes': [0, 1, 2]}],
                       'nodes': [{'mesh': 0, 'skin': 0}, {'name': 'Root'}, {'name': 'Other'}],
                       'meshes': [{'primitives': [{'attributes': {'POSITION': 3}}]}],
                       'skins': [{'joints': [1, 2], 'inverseBindMatrices': 0}],
                       'bufferViews': [{'buffer': 0, 'byteOffset': 0, 'byteLength': 128},
                                       {'buffer': 0, 'byteOffset': 128, 'byteLength': 8},
                                       {'buffer': 0, 'byteOffset': 136, 'byteLength': 32},
                                       {'buffer': 0, 'byteOffset': 168, 'byteLength': 36}],
                       'accessors': [{'bufferView': 0, 'componentType': 5126, 'count': 2, 'type': 'MAT4'},
                                     {'bufferView': 1, 'componentType': 5126, 'count': 2, 'type': 'SCALAR'},
                                     {'bufferView': 2, 'componentType': 5126, 'count': 2, 'type': 'VEC4'},
                                     {'bufferView': 3, 'componentType': 5126, 'count': 3, 'type': 'VEC3'}],
                       'animations': [{'name': 'Idle',
                                       'channels': [{'sampler': 0, 'target': {'node': 1, 'path': 'rotation'}}],
                                       'samplers': [{'input': 1, 'output': 2, 'interpolation': 'LINEAR'}]}]}
            write_glb(root / 'runtime.glb', runtime, binary)
            indexes = np.tile(np.array([0, 1, 1, 1], dtype=np.uint16), (3, 1))
            weights = np.tile(np.array([255, 0, 0, 0], dtype=np.uint8), (3, 1))
            make_full_resolution(root / 'source.glb', root / 'runtime.glb', root / 'full.glb',
                                 ['Root', 'Other'], indexes, weights)
            full, full_binary = read_glb(root / 'full.glb')
            np.testing.assert_array_equal(read_accessor(full, full_binary, 0), positions)
            attrs = full['meshes'][0]['primitives'][0]['attributes']
            self.assertTrue(np.all(read_accessor(full, full_binary, attrs['JOINTS_0']) == 0))
            self.assertEqual(len(full['accessors']), 6)  # No redundant runtime geometry accessor.
            self.assertEqual(validate_skin(root / 'full.glb', {'Idle'})['unweighted_vertices'], 0)

    def test_node_hierarchy_transforms(self):
        doc = {'nodes': [{'translation': [2, 0, 0], 'children': [1]},
                         {'translation': [0, 3, 0]}]}
        matrix = world_matrices(doc)[1]
        np.testing.assert_allclose(matrix[:3, 3], [2, 3, 0])
        np.testing.assert_allclose(node_matrix({'scale': [2, 3, 4]})[:3, :3], np.diag([2, 3, 4]))


if __name__ == '__main__':
    unittest.main()
