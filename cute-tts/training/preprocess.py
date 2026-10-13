"""Подготовка датасета из mp3/wav: тишина, громкость, ресемпл, разбиение, metadata.csv.

Использование:
  python -m training.preprocess --input data/voice_samples --out data/processed \
      [--transcripts data/transcripts] [--auto-transcribe]
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from training.dataset import SAMPLE_RATE  # noqa: E402

AUDIO_EXTS = (".mp3", ".wav", ".m4a", ".ogg", ".flac", ".webm", ".aac")


def read_audio(path: str) -> tuple[np.ndarray, int]:
    """Читает аудио (в т.ч. mp3) через soundfile, при неудаче — через ffmpeg."""
    try:
        import soundfile as sf

        audio, sr = sf.read(path, dtype="float32", always_2d=False)
        if audio.ndim > 1:
            audio = audio.mean(axis=1)
        return audio, sr
    except Exception:
        pass
    try:
        raw = subprocess.run(
            ["ffmpeg", "-v", "error", "-i", path, "-f", "f32le", "-ac", "1", "-ar", "44100", "-"],
            capture_output=True, check=True,
        ).stdout
        return np.frombuffer(raw, dtype=np.float32).copy(), 44100
    except Exception as e:
        raise RuntimeError(f"не удалось прочитать {path}: {e}")


def resample(audio: np.ndarray, sr: int, target_sr: int = SAMPLE_RATE) -> np.ndarray:
    if sr == target_sr:
        return audio
    duration = len(audio) / sr
    n_out = int(duration * target_sr)
    x_old = np.linspace(0.0, 1.0, num=len(audio), endpoint=False)
    x_new = np.linspace(0.0, 1.0, num=n_out, endpoint=False)
    return np.interp(x_new, x_old, audio).astype(np.float32)


def trim_silence(audio: np.ndarray, thresh_db: float = -42.0, pad_ms: int = 60) -> np.ndarray:
    if audio.size == 0:
        return audio
    frame = 512
    n = len(audio) // frame
    if n == 0:
        return audio
    frames = audio[: n * frame].reshape(n, frame)
    energy = (frames ** 2).mean(axis=1)
    ref = energy.max() if energy.max() > 0 else 1.0
    db = 10 * np.log10(np.maximum(energy, 1e-12) / ref)
    keep = db > thresh_db
    if not keep.any():
        return audio
    first = int(np.argmax(keep))
    last = len(keep) - int(np.argmax(keep[::-1]))
    pad = int(pad_ms / 1000 * SAMPLE_RATE)
    return audio[max(0, first * frame - pad): min(len(audio), last * frame + pad)]


def normalize_rms(audio: np.ndarray, target_db: float = -20.0) -> np.ndarray:
    rms = float(np.sqrt(np.mean(audio ** 2))) if audio.size else 0.0
    if rms < 1e-8:
        return audio
    gain = 10 ** (target_db / 20) / rms
    out = audio * gain
    peak = float(np.abs(out).max() or 1.0)
    if peak > 0.98:
        out = out * (0.98 / peak)
    return out.astype(np.float32)


def split_long(audio: np.ndarray, max_sec: float = 12.0, min_sec: float = 1.0):
    """Режет длинные записи на куски по паузам (тишине)."""
    max_len = int(max_sec * SAMPLE_RATE)
    if len(audio) <= max_len:
        return [audio]
    chunks = []
    start = 0
    while start < len(audio):
        end = min(start + max_len, len(audio))
        if end < len(audio):
            win = audio[max(start, end - SAMPLE_RATE): end]
            energy = (win ** 2)
            if energy.any():
                thresh = energy.max() * 0.001
                quiet = np.where(energy < thresh)[0]
                if quiet.size:
                    cut = max(start, end - SAMPLE_RATE) + int(quiet[-1])
                    if cut - start >= min_sec * SAMPLE_RATE:
                        end = cut
        chunks.append(audio[start:end])
        start = end
    return [c for c in chunks if len(c) >= min_sec * SAMPLE_RATE]


def load_transcripts(folder: str) -> dict:
    """Папка с .txt: имя_файла.txt → текст (имя без расширения совпадает с аудио)."""
    out = {}
    if not folder or not os.path.isdir(folder):
        return out
    for name in os.listdir(folder):
        if name.lower().endswith(".txt"):
            with open(os.path.join(folder, name), "r", encoding="utf-8") as f:
                out[os.path.splitext(name)[0]] = f.read().strip()
    return out


def auto_transcribe(paths: list[str], language: str = "ru") -> dict:
    """Транскрипция faster-whisper (нужен интернет для загрузки модели — делается один раз)."""
    from faster_whisper import WhisperModel

    model = WhisperModel("medium", device="auto", compute_type="int8")
    out = {}
    for p in paths:
        segments, _ = model.transcribe(p, language=language)
        out[os.path.splitext(os.path.basename(p))[0]] = " ".join(
            s.text.strip() for s in segments
        ).strip()
    return out


def main():
    ap = argparse.ArgumentParser(description="Подготовка датасета из mp3/wav")
    ap.add_argument("--input", required=True, help="папка с mp3/wav")
    ap.add_argument("--out", default="data/processed")
    ap.add_argument("--transcripts", default=None, help="папка с .txt-транскриптами")
    ap.add_argument("--metadata", default=None, help="готовый metadata.csv (filename|text)")
    ap.add_argument("--auto-transcribe", action="store_true",
                    help="расшифровать речь faster-whisper (нужен интернет один раз)")
    ap.add_argument("--max-sec", type=float, default=12.0)
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    files = sorted(
        os.path.join(args.input, f)
        for f in os.listdir(args.input)
        if f.lower().endswith(AUDIO_EXTS)
    )
    if not files:
        raise SystemExit(f"в {args.input} нет аудиофайлов ({', '.join(AUDIO_EXTS)})")

    texts: dict[str, str] = {}
    if args.metadata:
        with open(args.metadata, "r", encoding="utf-8") as f:
            for line in f:
                if "|" in line:
                    k, v = line.split("|", 1)
                    texts[os.path.splitext(k.strip())[0]] = v.strip()
    texts.update(load_transcripts(args.transcripts or ""))
    if args.auto_transcribe:
        print("расшифровка аудио (faster-whisper)…")
        texts.update(auto_transcribe(files))

    import soundfile as sf

    meta_lines = []
    n_done = 0
    for path in files:
        base = os.path.splitext(os.path.basename(path))[0]
        text = texts.get(base, "").strip()
        if not text:
            print(f"пропуск {path}: нет транскрипта (положи {base}.txt в --transcripts "
                  f"или строку в metadata.csv)")
            continue
        audio, sr = read_audio(path)
        audio = resample(audio, sr)
        audio = trim_silence(audio)
        if len(audio) < int(0.5 * SAMPLE_RATE):
            print(f"пропуск {path}: слишком коротко после обрезки тишины")
            continue
        chunks = split_long(audio, max_sec=args.max_sec)
        for i, chunk in enumerate(chunks):
            chunk = normalize_rms(chunk)
            name = f"{base}_{i:03d}.wav" if len(chunks) > 1 else f"{base}.wav"
            sf.write(os.path.join(args.out, name), chunk, SAMPLE_RATE)
            meta_lines.append(f"{name}|{text}")
            n_done += 1

    with open(os.path.join(args.out, "metadata.csv"), "w", encoding="utf-8") as f:
        f.write("\n".join(meta_lines) + "\n")
    total = sum(
        1 for _ in open(os.path.join(args.out, "metadata.csv"), encoding="utf-8")
    )
    print(f"готово: {total} фраз → {args.out}/metadata.csv")
    if total < 20:
        print("совет: для хорошего качества нужно 20-50+ минут речи (≈200+ фраз)")


if __name__ == "__main__":
    main()
