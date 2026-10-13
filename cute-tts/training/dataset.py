"""Датасет для обучения: wav + текст → спектры и id фонем."""
from __future__ import annotations

import os
from typing import Dict, List

import numpy as np
import torch
import torch.nn.functional as F
from torch.utils.data import Dataset

import sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from cute_tts.phonemize import text_to_ids  # noqa: E402

SAMPLE_RATE = 22050
HOP = 256
WIN = 1024
N_FFT = 1024
N_MELS = 80


def load_wav(path: str, sr: int = SAMPLE_RATE) -> np.ndarray:
    import soundfile as sf

    audio, file_sr = sf.read(path, dtype="float32", always_2d=False)
    if audio.ndim > 1:
        audio = audio.mean(axis=1)
    if file_sr != sr:
        # линейная интерполяция (для препроцессинга есть более точный ресемпл)
        duration = len(audio) / file_sr
        n_out = int(duration * sr)
        x_old = np.linspace(0.0, 1.0, num=len(audio), endpoint=False)
        x_new = np.linspace(0.0, 1.0, num=n_out, endpoint=False)
        audio = np.interp(x_new, x_old, audio).astype(np.float32)
    return audio


def stft_linear(audio: np.ndarray) -> np.ndarray:
    """Линейный спектр [n_fft//2+1, T] (как в оригинальном VITS)."""
    x = torch.from_numpy(audio).float().unsqueeze(0)
    x = F.pad(x, (int((N_FFT - HOP) / 2),) * 2, mode="reflect")
    window = torch.hann_window(WIN)
    spec = torch.stft(x, N_FFT, HOP, WIN, window, return_complex=True)
    mag = spec.abs().clamp_min(1e-5)
    return torch.log(mag).squeeze(0).numpy()


class TTSDataset(Dataset):
    """metadata.csv: строки вида `имя_файла|текст` (файл относительно data_dir)."""

    def __init__(self, data_dir: str, metadata_file: str = "metadata.csv",
                 phoneme_id_map: Dict[str, int] = None,
                 phoneme_type: str = "espeak", language: str = "ru",
                 min_sec: float = 0.5, max_sec: float = 15.0):
        self.data_dir = data_dir
        self.phoneme_id_map = phoneme_id_map
        self.phoneme_type = phoneme_type
        self.language = language
        self.items: List[Dict] = []
        meta_path = os.path.join(data_dir, metadata_file)
        with open(meta_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or "|" not in line:
                    continue
                name, text = line.split("|", 1)
                name, text = name.strip(), text.strip()
                if not name or not text:
                    continue
                wav_path = os.path.join(data_dir, name)
                if not os.path.isfile(wav_path):
                    continue
                self.items.append({"wav": wav_path, "text": text})
        if not self.items:
            raise RuntimeError(f"Пустой датасет: {meta_path}")

    def __len__(self) -> int:
        return len(self.items)

    def __getitem__(self, idx: int) -> Dict:
        item = self.items[idx]
        audio = load_wav(item["wav"])
        spec = stft_linear(audio)                      # [513, T]
        ids_chains = text_to_ids(
            item["text"], self.phoneme_id_map, self.phoneme_type, self.language
        )
        ids = ids_chains[0] if ids_chains else [1, 2]
        return {
            "text": torch.tensor(ids, dtype=torch.long),
            "spec": torch.from_numpy(spec).float(),
            "wav": torch.from_numpy(audio).float().unsqueeze(0),
        }


def collate_batch(batch: List[Dict]):
    text_lengths = torch.tensor([b["text"].shape[0] for b in batch], dtype=torch.long)
    spec_lengths = torch.tensor([b["spec"].shape[1] for b in batch], dtype=torch.long)
    wav_lengths = torch.tensor([b["wav"].shape[1] for b in batch], dtype=torch.long)

    max_text = int(text_lengths.max())
    max_spec = int(spec_lengths.max())
    max_wav = int(wav_lengths.max())

    texts = torch.zeros(len(batch), max_text, dtype=torch.long)
    specs = torch.zeros(len(batch), batch[0]["spec"].shape[0], max_spec)
    wavs = torch.zeros(len(batch), 1, max_wav)

    for i, b in enumerate(batch):
        texts[i, : b["text"].shape[0]] = b["text"]
        specs[i, :, : b["spec"].shape[1]] = b["spec"]
        wavs[i, :, : b["wav"].shape[1]] = b["wav"]

    return texts, text_lengths, specs, spec_lengths, wavs, wav_lengths
