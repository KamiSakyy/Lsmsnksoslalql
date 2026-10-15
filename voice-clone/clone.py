#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Клонирование голоса по образцу "мой голос.mp3".

Модели (самые тяжёлые из работающих на CPU-раннерах GitHub Actions):
  1. xtts       — Coqui XTTS-v2 (~2 GB, мультиязычная zero-shot)
  2. chatterbox — Resemble AI Chatterbox Multilingual 0.5B (23 языка)

Запуск:
  python clone.py --model xtts
  python clone.py --model chatterbox
  python clone.py --model all

Результаты: handoff/voice-clone-<model>.mp3  (+ .wav в voice-clone/_work)
"""
import argparse
import gc
import os
import shutil
import subprocess
import sys
import traceback

import numpy as np
import soundfile as sf

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REF_MP3 = os.path.join(ROOT, "мой голос.mp3")
VOICE_DIR = os.path.join(ROOT, "voice-clone")
REF_WAV = os.path.join(VOICE_DIR, "reference.wav")
WORK_DIR = os.path.join(VOICE_DIR, "_work")
OUT_DIR = os.path.join(ROOT, "handoff")

# Текст пользователя, разбитый на языковые сегменты (ru/en — кросс-лингвальное клонирование)
SEGMENTS = [
    ("Привет, мяу мяу, смотрите аниме в моем боте.", "ru"),
    ("Hello, I love you.", "en"),
]
GAP_SEC = 0.45  # пауза между сегментами


def ffmpeg_exe() -> str:
    """ffmpeg из PATH или статичный бинарник imageio-ffmpeg (на новых раннерах ffmpeg нет)."""
    exe = shutil.which("ffmpeg")
    if exe:
        return exe
    import imageio_ffmpeg
    return imageio_ffmpeg.get_ffmpeg_exe()


def prepare_reference() -> None:
    """mp3 -> моно wav 22050 Hz (стандартный вход для обеих моделей)."""
    os.makedirs(WORK_DIR, exist_ok=True)
    subprocess.run(
        [ffmpeg_exe(), "-y", "-v", "error", "-i", REF_MP3,
         "-ar", "22050", "-ac", "1", "-c:a", "pcm_s16le", REF_WAV],
        check=True,
    )
    info = sf.info(REF_WAV)
    print(f"[ref] {info.duration:.1f}s, {info.samplerate}Hz, mono -> {REF_WAV}", flush=True)


def join_chunks(chunks, sr: int) -> np.ndarray:
    gap = np.zeros(int(sr * GAP_SEC), dtype=np.float32)
    parts = []
    for i, c in enumerate(chunks):
        if i:
            parts.append(gap)
        parts.append(np.asarray(c, dtype=np.float32))
    return np.concatenate(parts) if parts else np.zeros(1, dtype=np.float32)


def export_mp3(audio: np.ndarray, sr: int, name: str) -> str:
    os.makedirs(OUT_DIR, exist_ok=True)
    wav_path = os.path.join(WORK_DIR, name.replace(".mp3", ".wav"))
    mp3_path = os.path.join(OUT_DIR, name)
    sf.write(wav_path, audio, sr)
    subprocess.run(
        [ffmpeg_exe(), "-y", "-v", "error", "-i", wav_path,
         "-codec:a", "libmp3lame", "-qscale:a", "2", mp3_path],
        check=True,
    )
    print(f"[ok] сохранено: {mp3_path} ({len(audio)/sr:.1f}s)", flush=True)
    return mp3_path


def run_xtts() -> str:
    os.environ.setdefault("COQUI_TOS_AGREED", "1")
    from TTS.api import TTS

    print("[xtts] загружаю XTTS-v2 (~2 GB при первом запуске)...", flush=True)
    tts = TTS("tts_models/multilingual/multi-dataset/xtts_v2").to("cpu")
    sr = int(tts.synthesizer.output_sample_rate)
    chunks = []
    for text, lang in SEGMENTS:
        print(f"[xtts] генерирую ({lang}): {text!r}", flush=True)
        wav = tts.tts(text=text, speaker_wav=REF_WAV, language=lang)
        chunks.append(np.array(wav, dtype=np.float32))
    audio = join_chunks(chunks, sr)
    path = export_mp3(audio, sr, "voice-clone-xtts-v2.mp3")
    del tts
    gc.collect()
    return path


def run_chatterbox() -> str:
    import torch  # noqa: F401
    from chatterbox.mtl_tts import ChatterboxMultilingualTTS

    print("[chatterbox] загружаю Chatterbox Multilingual 0.5B...", flush=True)
    model = ChatterboxMultilingualTTS.from_pretrained(device="cpu")
    sr = int(getattr(model, "sr", 24000))
    chunks = []
    for text, lang in SEGMENTS:
        print(f"[chatterbox] генерирую ({lang}): {text!r}", flush=True)
        t = model.generate(
            text,
            language_id=lang,
            audio_prompt_path=REF_WAV,
            exaggeration=0.5,
            cfg_weight=0.5,
        )
        chunks.append(t.squeeze(0).cpu().numpy())
    audio = join_chunks(chunks, sr)
    path = export_mp3(audio, sr, "voice-clone-chatterbox.mp3")
    del model
    gc.collect()
    return path


MODELS = {"xtts": run_xtts, "chatterbox": run_chatterbox}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", choices=list(MODELS) + ["all"], default="all")
    args = ap.parse_args()

    prepare_reference()
    targets = list(MODELS) if args.model == "all" else [args.model]

    results = {}
    for name in targets:
        try:
            results[name] = MODELS[name]()
        except Exception:
            traceback.print_exc()
            results[name] = None

    print("\n===== ИТОГ =====", flush=True)
    for name, path in results.items():
        print(f"{name}: {'OK -> ' + path if path else 'FAIL'}", flush=True)
    return 0 if any(results.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
