import json
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools/rigging'))
from core import (extract_glb, read_accessor, read_glb, read_sparse_accessor, write_glb)
from detail import (DIGITS, facial_bones, fit_face, fit_fingers, in_bounds, morph_fields,
                    source_data)
from quality import quaternion_slerp
from refine_model import Assembler, bone_world


class SparseAccessorTests(unittest.TestCase):
    def test_sparse_morph_returns_zero_for_untouched_vertices(self):
        doc = {'asset': {'version': '2.0'}, 'bufferViews': [], 'accessors': []}
        assembler = Assembler(doc, b'')
        ids = np.array([7, 2, 9]);delta = np.array([[0, .1, 0], [.2, 0, 0], [0, 0, .3]], dtype='<f4')
        index = assembler.sparse(ids, delta, 12, bounds=True)
        actual = read_accessor(doc, assembler.binary, index)
        np.testing.assert_allclose(actual[ids], delta)
        np.testing.assert_array_equal(actual[[0, 1, 3, 4, 5, 6, 8, 10, 11]], 0)
        indexes, values = read_sparse_accessor(doc, assembler.binary, index)
        np.testing.assert_array_equal(indexes, [2, 7, 9])
        self.assertEqual(doc['accessors'][index]['count'], 12)
        self.assertNotIn('bufferView', doc['accessors'][index])

    def test_sparse_roundtrip_keeps_precise_values_and_local_size(self):
        with tempfile.TemporaryDirectory() as directory:
            doc = {'asset': {'version': '2.0'}, 'bufferViews': [], 'accessors': []}
            assembler = Assembler(doc, b'')
            index = assembler.sparse(np.array([120000, 900001]), np.array([[1e-4, 0, 0], [0, -2e-4, 0]]), 1159795)
            path = Path(directory)/'sparse.glb';write_glb(path, doc, assembler.binary)
            read, binary = read_glb(path)
            ids, values = read_sparse_accessor(read, binary, index)
            np.testing.assert_array_equal(ids, [120000, 900001])
            np.testing.assert_allclose(values, [[1e-4, 0, 0], [0, -2e-4, 0]])
            self.assertLess(path.stat().st_size, 1024)

    def test_duplicate_sparse_indices_rejected(self):
        assembler = Assembler({'bufferViews': [], 'accessors': []}, b'')
        with self.assertRaises(ValueError):
            assembler.sparse(np.array([1, 1]), np.zeros((2, 3)), 3)

    def test_normalized_signed_minimum_is_minus_one(self):
        doc = {'bufferViews': [{'buffer': 0, 'byteLength': 2}],
               'accessors': [{'bufferView': 0, 'componentType': 5120, 'count': 2,
                              'type': 'SCALAR', 'normalized': True}]}
        np.testing.assert_array_equal(read_accessor(doc, bytes([128, 127]), 0), [[-1], [1]])


class DetailedSourceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        p = extract_glb(ROOT/'handoff/d247d7c3_46ba_414e_b821_b731d4b67026_ab2fefced0df785fa9f396410d0b5905.zip', Path(cls.temp.name))
        doc, raw = read_glb(p)
        cls.vertices, cls.faces, cls.normals, cls.colors, cls.height, cls.origin = source_data(doc, raw)
        cls.profile = json.loads((ROOT/'tools/rigging/detail_profile.json').read_text())
        cls.face_ids, cls.landmarks = fit_face(cls.vertices, cls.faces, cls.normals, cls.colors, cls.profile)
        cls.shapes = morph_fields(cls.vertices, cls.face_ids, cls.landmarks)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_exact_original_high_detail_is_used(self):
        self.assertEqual(len(self.vertices), 1159795)
        self.assertEqual(len(self.faces), 1981742)

    def test_real_face_landmarks_not_placeholder_coordinates(self):
        self.assertGreater(self.landmarks['Left']['feature_samples'], 60)
        self.assertGreater(self.landmarks['Right']['feature_samples'], 60)
        self.assertGreater(self.landmarks['Left']['eye'][0], 0)
        self.assertLess(self.landmarks['Right']['eye'][0], 0)
        self.assertGreater(len(self.face_ids), 10000)
        self.assertLess(len(self.face_ids), 40000)

    def test_all_forty_actual_local_facial_fields_nonzero(self):
        self.assertEqual(len(self.shapes), 40)
        for name, (ids, delta) in self.shapes.items():
            self.assertGreater(len(ids), 20, name)
            self.assertTrue(np.all(np.isfinite(delta)), name)
            self.assertGreater(np.linalg.norm(delta, axis=1).max(), .0004, name)
            self.assertLess(np.linalg.norm(delta, axis=1).max(), .030, name)
            self.assertTrue(np.all(np.isin(ids, self.face_ids)), name)
            self.assertGreater(self.vertices[ids, 1].min(), .835, name)

    def test_blink_left_and_right_are_independent(self):
        left = self.shapes['eyeBlinkLeft'][0];right = self.shapes['eyeBlinkRight'][0]
        self.assertLess(len(np.intersect1d(left, right)), .1*min(len(left), len(right)))
        self.assertGreater(np.median(self.vertices[left, 0]), -.005)
        self.assertLess(np.median(self.vertices[right, 0]), -.020)

    def test_fitting_and_shapes_never_modify_original_positions(self):
        before = self.vertices.copy()
        morph_fields(self.vertices, self.face_ids, self.landmarks)
        np.testing.assert_array_equal(self.vertices, before)

    def test_face_bones_are_real_named_controls_with_valid_parent_order(self):
        bones = facial_bones(self.landmarks, self.profile)
        self.assertEqual(len(bones), 22)
        seen = {'mixamorig:Head'}
        for bone in bones:
            self.assertIn(bone.parent, seen)
            self.assertGreater(np.linalg.norm(bone.tail-bone.head), .001)
            seen.add(bone.name)
        self.assertIn('Face.Jaw', seen)
        self.assertIn('Face.LidUpper.Left', seen)
        self.assertIn('Face.LipLower.Right', seen)

    def test_both_hands_have_all_fifteen_fitted_phalanges(self):
        for side in ('Right', 'Left'):
            selected = in_bounds(self.vertices, self.profile['hands'][side]['bounds'])
            bones, report = fit_fingers(self.vertices, self.colors, self.profile, side, selected)
            self.assertEqual(len(bones), 15)
            self.assertEqual(set(report), set(DIGITS))
            for digit in DIGITS:
                chain = [b for b in bones if digit in b.name]
                self.assertEqual(len(chain), 3)
                for a, b in zip(chain, chain[1:]):
                    np.testing.assert_array_equal(a.tail, b.head)
                    self.assertEqual(b.parent, a.name)
                self.assertLess(max(report[digit]['nearest_source_surface_distance']), .0056)

    def test_joint_world_basis_is_right_handed_and_uses_actual_source_origin(self):
        selected = in_bounds(self.vertices, self.profile['hands']['Right']['bounds'])
        bones, _ = fit_fingers(self.vertices, self.colors, self.profile, 'Right', selected)
        transform = np.eye(4);transform[:3, 3] = [.01, 0, .02]
        matrix = bone_world(bones[0], self.height, self.origin, transform, [0, 0, 1])
        self.assertAlmostEqual(np.linalg.det(matrix[:3, :3]), 1., places=8)
        np.testing.assert_allclose(matrix[:3, 3], self.origin+self.height*bones[0].head+[.01, 0, .02])


if __name__ == '__main__':
    unittest.main()
