import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools' / 'rigging'))
from core import (capsule_weights, extract_glb, load_profile, node_matrix, pack_four_weights,
                  read_accessor, read_glb, smooth_weights, two_bone_ik, world_matrices, write_glb)

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

    def test_node_hierarchy_transforms(self):
        doc = {'nodes': [{'translation': [2, 0, 0], 'children': [1]},
                         {'translation': [0, 3, 0]}]}
        matrix = world_matrices(doc)[1]
        np.testing.assert_allclose(matrix[:3, 3], [2, 3, 0])
        np.testing.assert_allclose(node_matrix({'scale': [2, 3, 4]})[:3, :3], np.diag([2, 3, 4]))


if __name__ == '__main__':
    unittest.main()
