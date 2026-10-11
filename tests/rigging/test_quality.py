import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools' / 'rigging'))
from core import (Bone, extract_glb, load_profile, make_full_resolution, pack_skin_weights,
                  read_accessor, read_glb, write_glb)
from binding import localize_fields, solve_bbw, transfer_surface_weights
from fit import fit_bones
from motion import CLIPS, clip_deltas, rest_matrices
from quality import SkinEvaluator, quaternion_slerp, assert_geometry_preserved


def fixture(root):
    positions = np.array([[0, 0, 0], [1, 0, 0], [0, 1, 0]], dtype='<f4')
    indices = np.array([0, 1, 2], dtype='<u2')
    source = {'asset': {'version': '2.0'}, 'scene': 0, 'scenes': [{'nodes': [0]}],
              'nodes': [{'mesh': 0, 'translation': [.3, .1, .2]}],
              'meshes': [{'primitives': [{'attributes': {'POSITION': 0}, 'indices': 1, 'material': 0}]}],
              'materials': [{'doubleSided': True}],
              'bufferViews': [{'buffer': 0, 'byteOffset': 0, 'byteLength': 36},
                              {'buffer': 0, 'byteOffset': 36, 'byteLength': 6}],
              'accessors': [{'bufferView': 0, 'componentType': 5126, 'count': 3, 'type': 'VEC3',
                             'min': [0, 0, 0], 'max': [1, 1, 0]},
                            {'bufferView': 1, 'componentType': 5123, 'count': 3, 'type': 'SCALAR'}]}
    write_glb(root/'source.glb', source, positions.tobytes()+indices.tobytes()+b'original texture bytes')
    matrices = np.tile(np.eye(4, dtype='<f4').ravel(), (2, 1))
    times = np.array([0, 1], dtype='<f4')
    rotations = np.array([[0, 0, 0, 1], [0, 0, np.sqrt(.5), np.sqrt(.5)]], dtype='<f4')
    runtime = {'asset': {'version': '2.0'}, 'scene': 0, 'scenes': [{'nodes': [0, 1, 2]}],
               'nodes': [{'mesh': 0, 'skin': 0}, {'name': 'Root'}, {'name': 'Other'}],
               'meshes': [{'primitives': [{'attributes': {'POSITION': 0}}]}],
               'skins': [{'joints': [1, 2], 'inverseBindMatrices': 1}],
               'bufferViews': [{'buffer': 0, 'byteOffset': 0, 'byteLength': 36},
                               {'buffer': 0, 'byteOffset': 36, 'byteLength': 128},
                               {'buffer': 0, 'byteOffset': 164, 'byteLength': 8},
                               {'buffer': 0, 'byteOffset': 172, 'byteLength': 32}],
               'accessors': [{'bufferView': 0, 'componentType': 5126, 'count': 3, 'type': 'VEC3'},
                             {'bufferView': 1, 'componentType': 5126, 'count': 2, 'type': 'MAT4'},
                             {'bufferView': 2, 'componentType': 5126, 'count': 2, 'type': 'SCALAR'},
                             {'bufferView': 3, 'componentType': 5126, 'count': 2, 'type': 'VEC4'}],
               'animations': [{'name': 'Idle', 'channels': [{'sampler': 0, 'target': {'node': 1, 'path': 'rotation'}}],
                               'samplers': [{'input': 2, 'output': 3, 'interpolation': 'LINEAR'}]}]}
    write_glb(root/'cage.glb', runtime, positions.tobytes()+matrices.tobytes()+times.tobytes()+rotations.tobytes())
    make_full_resolution(root/'source.glb', root/'cage.glb', root/'full.glb', ['Root', 'Other'],
                         np.zeros((3, 4), dtype=np.uint16),
                         np.tile([65535, 0, 0, 0], (3, 1)).astype(np.uint16))
    return positions


class QualityTests(unittest.TestCase):
    def test_u16_quantization_exact_sum_and_canonical_empty_slots(self):
        rng = np.random.default_rng(13)
        weights = rng.random((2000, 48))
        weights[0] = 0
        weights[0, 3] = 1
        indexes, quantized = pack_skin_weights(weights)
        self.assertEqual(quantized.dtype, np.uint16)
        self.assertTrue(np.all(quantized.sum(axis=1) == 65535))
        self.assertTrue(np.all(indexes[quantized == 0] == 0))
        chosen = np.take_along_axis(weights, indexes, axis=1)
        chosen[quantized == 0] = 0
        chosen /= chosen.sum(axis=1, keepdims=True)
        np.testing.assert_allclose(chosen, quantized/65535, atol=1/65535)

    def test_invalid_u16_weight_fields_rejected(self):
        for weights in (np.zeros((2, 48)), np.full((2, 48), np.nan),
                        np.full((2, 48), -1.), np.ones((2, 2))):
            with self.assertRaises(ValueError):
                pack_skin_weights(weights)

    def test_full_glb_has_normalized_u16_weights_and_original_buffer(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture(root)
            doc, binary = read_glb(root/'full.glb')
            attrs = doc['meshes'][0]['primitives'][0]['attributes']
            accessor = doc['accessors'][attrs['WEIGHTS_0']]
            self.assertEqual(accessor['componentType'], 5123)
            self.assertTrue(accessor['normalized'])
            np.testing.assert_allclose(read_accessor(doc, binary, attrs['WEIGHTS_0']).sum(axis=1), 1)
            proof = assert_geometry_preserved(root/'source.glb', root/'full.glb')
            self.assertTrue(proof['geometry_and_textures_byte_identical'])

    def test_preservation_rejects_even_one_changed_original_byte(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture(root)
            doc, binary = read_glb(root/'full.glb')
            changed = bytearray(binary)
            changed[0] ^= 1
            write_glb(root/'full.glb', doc, changed)
            with self.assertRaises(ValueError):
                assert_geometry_preserved(root/'source.glb', root/'full.glb')

    def test_slerp_does_not_modify_keyframe_buffers(self):
        a = np.array([0., 0., 0., 1.])
        b = np.array([0., 0., np.sqrt(.5), np.sqrt(.5)])
        original_a, original_b = a.copy(), b.copy()
        q = quaternion_slerp(a, b, .5)
        np.testing.assert_array_equal(a, original_a)
        np.testing.assert_array_equal(b, original_b)
        np.testing.assert_allclose(q, [0, 0, np.sin(np.pi/8), np.cos(np.pi/8)], atol=1e-7)

    def test_evaluator_uses_inverse_bind_and_interpolated_rotation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            positions = fixture(root)
            evaluator = SkinEvaluator(root/'full.glb')
            translated = positions + [.3, .1, .2]
            np.testing.assert_allclose(evaluator.deform(evaluator.matrices()), translated, atol=2e-7)
            c = np.sqrt(.5)
            rotation = np.array([[c, -c, 0], [c, c, 0], [0, 0, 1.]])
            np.testing.assert_allclose(evaluator.deform(evaluator.matrices('Idle', .5)),
                                       translated @ rotation.T, atol=2e-7)
            self.assertAlmostEqual(evaluator.audit(frames=5)['Idle']['edge_stretch_max'], 1., places=6)

    def test_nearest_face_transfer_never_mixes_close_disconnected_sheets(self):
        vertices = np.array([[0, 0, 0], [1, 0, 0], [0, 1, 0],
                             [0, 0, .003], [1, 0, .003], [0, 1, .003]])
        triangles = np.array([[0, 1, 2], [3, 4, 5]])
        weights = np.zeros((6, 4)); weights[:3, 0] = 1; weights[3:, 1] = 1
        indexes, quantized, report = transfer_surface_weights(vertices, triangles,
            np.array([[.3, .3, 0], [.3, .3, .003]]), weights)
        self.assertEqual(int(indexes[0, 0]), 0)
        self.assertEqual(int(indexes[1, 0]), 1)
        self.assertTrue(np.all(quantized[:, 0] == 65535))
        self.assertLess(report['distance_max'], 1e-10)

    def test_transfer_is_barycentric_not_nearest_vertex(self):
        vertices = np.array([[0, 0, 0], [1, 0, 0], [0, 1, 0]])
        indexes, quantized, _ = transfer_surface_weights(vertices, np.array([[0, 1, 2]]),
            np.array([[1/3, 1/3, 0]]), np.eye(4)[:3])
        self.assertEqual(set(indexes[0, :3]), {0, 1, 2})
        np.testing.assert_array_equal(quantized[0, :3], [21845, 21845, 21845])

    def test_native_bbw_is_normalized_with_root_unused(self):
        vertices = np.array([[x, y, 0.] for y in range(3) for x in range(3)])
        triangles = np.array([[a, a+1, a+3] for a in [0, 1, 3, 4]] +
                             [[a+1, a+4, a+3] for a in [0, 1, 3, 4]])
        bones = [Bone('Root', None, np.array([0., 0., 0.]), np.array([0., 0., .1]), .1, 'root'),
                 Bone('Lower', 'Root', np.array([0., 0., 0.]), np.array([2., 0., 0.]), .1, 'body'),
                 Bone('Upper', 'Root', np.array([0., 2., 0.]), np.array([2., 2., 0.]), .1, 'body'),
                 Bone('Right', 'Root', np.array([2., 0., 0.]), np.array([2., 2., 0.]), .1, 'body')]
        result, report = solve_bbw(vertices, triangles, bones, log=lambda _: None)
        self.assertTrue(np.all(np.isfinite(result)))
        self.assertTrue(np.all(result >= 0))
        self.assertTrue(np.all(result[:, 0] == 0))
        np.testing.assert_allclose(result.sum(axis=1), 1., atol=1e-6)
        self.assertEqual(report['surface_components'], 1)

    def test_distant_hand_tail_is_removed_but_actual_hand_is_kept(self):
        bones = load_profile(ROOT/'tools/rigging/model_profile.json')
        hand = next(i for i, b in enumerate(bones) if b.name == 'mixamorig:RightHand')
        vertices = np.array([[0, .52, .1], (bones[hand].head+bones[hand].tail)/2])
        before = vertices.copy()
        field = np.zeros((2, len(bones))); field[:, hand] = 1
        result = localize_fields(vertices, field, bones)
        np.testing.assert_array_equal(vertices, before)
        np.testing.assert_allclose(result.sum(axis=1), 1., atol=1e-6)
        self.assertEqual(result[0, hand], 0)
        self.assertEqual(result[1, hand], 1)
        self.assertTrue(np.all(result[:, 0] == 0))


class SourceFittingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        source = extract_glb(ROOT/'handoff/d247d7c3_46ba_414e_b821_b731d4b67026_ab2fefced0df785fa9f396410d0b5905.zip',
                             Path(cls.temp.name))
        doc, binary = read_glb(source)
        vertices = read_accessor(doc, binary, doc['meshes'][0]['primitives'][0]['attributes']['POSITION']).astype(float)
        origin = (vertices.min(axis=0)+vertices.max(axis=0))/2
        origin[1] = vertices[:, 1].min()
        cls.vertices = (vertices-origin)/np.ptp(vertices[:, 1])
        cls.original = load_profile(ROOT/'tools/rigging/model_profile.json')
        cls.bones, cls.report = fit_bones(cls.vertices, cls.original)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_fit_corrects_the_actual_backward_leg_not_the_other_one(self):
        by = {b.name: b for b in self.bones}
        self.assertLess(by['mixamorig:LeftFoot'].head[2], -.05)
        self.assertGreater(by['mixamorig:RightFoot'].head[2], 0)
        self.assertEqual(self.report['leg_depth_order'], 'Left/+X is back; Right/-X is front')

    def test_free_hand_is_not_fitted_to_the_front_of_the_skirt(self):
        by = {b.name: b for b in self.bones}
        self.assertLess(abs(by['mixamorig:RightHand'].head[2]), .025)
        np.testing.assert_array_equal(by['mixamorig:RightForeArm'].tail, by['mixamorig:RightHand'].head)

    def test_fitting_leaves_source_vertices_and_input_profile_untouched(self):
        profile_before = np.array([np.r_[b.head, b.tail] for b in self.original])
        vertex_before = self.vertices.copy()
        fitted, _ = fit_bones(self.vertices, self.original)
        np.testing.assert_array_equal(self.vertices, vertex_before)
        np.testing.assert_array_equal(np.array([np.r_[b.head, b.tail] for b in self.original]), profile_before)
        self.assertEqual([b.name for b in fitted], [b.name for b in self.original])

    def test_fitted_chain_endpoints_and_rest_matrices_are_valid(self):
        by = {b.name: b for b in self.bones}
        for side in ('Left', 'Right'):
            prefix = 'mixamorig:'+side
            for parent, child in (('UpLeg', 'Leg'), ('Leg', 'Foot'), ('Foot', 'ToeBase'),
                                  ('Arm', 'ForeArm'), ('ForeArm', 'Hand')):
                np.testing.assert_array_equal(by[prefix+parent].tail, by[prefix+child].head)
        matrices, _ = rest_matrices(self.bones, np.zeros(3), 1)
        for matrix in matrices.values():
            self.assertAlmostEqual(np.linalg.det(matrix[:3, :3]), 1., places=8)

    def test_shared_motion_keeps_all_five_full_amplitude_clips_looping(self):
        self.assertEqual(set(CLIPS), {'Idle', 'Walk_InPlace', 'Run_InPlace', 'Wave', 'Dance'})
        for name in CLIPS:
            first, loc0 = clip_deltas(self.bones, name, 0)
            last, loc1 = clip_deltas(self.bones, name, 1)
            np.testing.assert_allclose(loc0, loc1, atol=1e-8)
            for bone in self.bones:
                np.testing.assert_allclose(first[bone.name], last[bone.name], atol=1e-8)
        middle, _ = clip_deltas(self.bones, 'Wave', .5)
        self.assertGreater(np.linalg.norm(middle['mixamorig:RightArm']-np.eye(3)), .5)


if __name__ == '__main__':
    unittest.main()
