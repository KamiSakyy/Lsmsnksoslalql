"""Оффлайн-движок синтеза: ONNX-модель + фонемизация. Интернет не нужен."""
from __future__ import annotations

import json
import os
from typing import List, Optional

import numpy as np

from .normalize import normalize_text
from .phonemize import split_sentences, text_to_ids

DEFAULT_MODEL_NAMES = ("mila.onnx", "mila_voice.onnx", "voice.onnx", "irina.onnx", "model.onnx")


def find_model(explicit: Optional[str] = None) -> str:
    if explicit:
        if not os.path.isfile(explicit):
            raise FileNotFoundError(f"модель не найдена: {explicit}")
        return explicit
    env = os.environ.get("MILA_MODEL")
    candidates = []
    if env:
        candidates.append(env)
    here = os.path.dirname(os.path.abspath(__file__))
    roots = [
        os.getcwd(),
        os.path.join(here, "..", "models"),
        os.path.join(here, ".."),
        os.path.expanduser("~/.mila-tts"),
    ]
    # для PyInstaller-бинарника: models/ рядом с исполняемым файлом
    import sys

    exe_dir = os.path.dirname(os.path.abspath(sys.executable))
    roots += [exe_dir, os.path.join(exe_dir, "models")]
    if getattr(sys, "frozen", False):
        roots.insert(0, os.path.join(getattr(sys, "_MEIPASS", exe_dir), "models"))
    for root in roots:
        for name in DEFAULT_MODEL_NAMES:
            candidates.append(os.path.join(root, name))
            candidates.append(os.path.join(root, "models", name))
    for c in candidates:
        c = os.path.abspath(c)
        if os.path.isfile(c):
            return c
    raise FileNotFoundError(
        "Не найдена модель (.onnx). Положите её в models/ или укажите --model. "
        "Как получить модель: см. README.md (обучение на своём голосе)."
    )


class MilaTTS:
    def __init__(self, model_path: Optional[str] = None, providers: Optional[List[str]] = None):
        import onnxruntime as ort

        self.model_path = find_model(model_path)
        cfg_path = self.model_path + ".json"
        if not os.path.isfile(cfg_path):
            raise FileNotFoundError(f"не найден конфиг модели: {cfg_path}")
        with open(cfg_path, "r", encoding="utf-8") as f:
            self.config = json.load(f)

        opts = ort.SessionOptions()
        opts.intra_op_num_threads = os.cpu_count() or 2
        opts.inter_op_num_threads = 1
        self.session = ort.InferenceSession(
            self.model_path, sess_options=opts,
            providers=providers or ["CPUExecutionProvider"],
        )
        self.input_names = {i.name for i in self.session.get_inputs()}
        outputs = self.session.get_outputs()
        self.output_name = outputs[0].name
        self.sample_rate = int(self.config.get("audio", {}).get("sample_rate", 22050))
        text_cfg = self.config.get("text", {})
        self.phoneme_id_map = {
            k: int(v) for k, v in text_cfg.get("phoneme_id_map", {}).items()
        }
        self.phoneme_type = text_cfg.get("phoneme_type", "espeak")
        self.language = text_cfg.get("language", "ru")
        inf = self.config.get("inference", {})
        self.noise_scale = float(inf.get("noise_scale", 0.667))
        self.length_scale = float(inf.get("length_scale", 1.0))
        self.noise_w = float(inf.get("noise_w", 0.0))

    def _run_chunk(self, ids: List[int], noise_scale: float,
                   length_scale: float, noise_w: float) -> np.ndarray:
        x = np.array([ids], dtype=np.int64)
        x_lengths = np.array([len(ids)], dtype=np.int64)
        feeds = {
            "x": x,
            "x_lengths": x_lengths,
            "noise_scale": np.array(noise_scale, dtype=np.float32),
            "length_scale": np.array(length_scale, dtype=np.float32),
            "noise_w": np.array(noise_w, dtype=np.float32),
        }
        feeds = {k: v for k, v in feeds.items() if k in self.input_names}
        y = self.session.run([self.output_name], feeds)[0]
        return y.reshape(-1).astype(np.float32)

    def synthesize(
        self,
        text: str,
        length_scale: Optional[float] = None,
        noise_scale: Optional[float] = None,
        noise_w: Optional[float] = None,
    ) -> np.ndarray:
        """Текст → аудио (float32, mono, sample_rate Гц)."""
        text = normalize_text(text)
        if not text:
            return np.zeros(1, dtype=np.float32)
        length_scale = self.length_scale if length_scale is None else length_scale
        noise_scale = self.noise_scale if noise_scale is None else noise_scale
        noise_w = self.noise_w if noise_w is None else noise_w

        id_chains = text_to_ids(
            text, self.phoneme_id_map, self.phoneme_type, self.language
        )
        pieces = []
        pause = np.zeros(int(self.sample_rate * 0.18), dtype=np.float32)
        for i, ids in enumerate(id_chains):
            audio = self._run_chunk(ids, noise_scale, length_scale, noise_w)
            if audio.size:
                pieces.append(audio)
            if i < len(id_chains) - 1:
                pieces.append(pause)
        if not pieces:
            return np.zeros(1, dtype=np.float32)
        out = np.concatenate(pieces)
        # мягкая нормализация громкости
        peak = float(np.abs(out).max() or 0.0)
        if peak > 0:
            out = out * (0.92 / peak)
        return out

    def synthesize_to_file(self, text: str, path: str, **kwargs) -> str:
        import soundfile as sf

        audio = self.synthesize(text, **kwargs)
        sf.write(path, audio, self.sample_rate)
        return path
