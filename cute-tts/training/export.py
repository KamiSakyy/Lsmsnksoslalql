"""Экспорт обученного чекпоинта в ONNX + JSON (формат Piper-совместимый)."""
from __future__ import annotations

import argparse
import json
import os
import sys

import torch

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from training.vits import MilaVITS  # noqa: E402

MAX_MB = 100.0


class InferWrapper(torch.nn.Module):
    def __init__(self, model: MilaVITS):
        super().__init__()
        self.model = model

    def forward(self, x, x_lengths, noise_scale, length_scale, noise_w):
        return self.model.infer(x, x_lengths, noise_scale, length_scale, noise_w)


def main():
    ap = argparse.ArgumentParser(description="Экспорт чекпоинта MilaTTS в ONNX")
    ap.add_argument("--checkpoint", required=True)
    ap.add_argument("--out", default="models/mila.onnx")
    ap.add_argument("--opset", type=int, default=17)
    ap.add_argument("--int8", action="store_true", help="квантизация int8 (сильное сжатие)")
    args = ap.parse_args()

    ck = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
    cfg = ck["config"]
    phoneme_id_map = ck["phoneme_id_map"]
    n_vocab = max(phoneme_id_map.values()) + 1

    mcfg = cfg.get("model", {})
    model = MilaVITS(
        n_vocab=n_vocab,
        hidden=mcfg.get("hidden", 192),
        text_layers=mcfg.get("text_layers", 4),
        flow_layers=mcfg.get("flow_layers", 4),
        flow_n_flows=mcfg.get("flow_n_flows", 4),
        posterior_layers=mcfg.get("posterior_layers", 8),
        upsample_initial=mcfg.get("upsample_initial", 384),
        segment_size=mcfg.get("segment_size", 32),
    )
    model.load_state_dict(ck["model"])
    model.eval()

    wrapper = InferWrapper(model)
    x = torch.randint(0, n_vocab, (1, 40), dtype=torch.long)
    x_lengths = torch.tensor([40], dtype=torch.long)
    noise_scale = torch.tensor(0.667, dtype=torch.float32)
    length_scale = torch.tensor(1.0, dtype=torch.float32)
    noise_w = torch.tensor(0.0, dtype=torch.float32)

    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with torch.no_grad():
        export_kwargs = dict(
            input_names=["x", "x_lengths", "noise_scale", "length_scale", "noise_w"],
            output_names=["y"],
            dynamic_axes={
                "x": {1: "phonemes"},
                "x_lengths": {0: "batch"},
                "y": {2: "samples"},
            },
            opset_version=args.opset,
        )
        try:
            # легаси-экспортер (как у Piper) — корректнее с динамическими длинами
            torch.onnx.export(
                wrapper,
                (x, x_lengths, noise_scale, length_scale, noise_w),
                args.out,
                dynamo=False,
                **export_kwargs,
            )
        except TypeError:
            torch.onnx.export(
                wrapper,
                (x, x_lengths, noise_scale, length_scale, noise_w),
                args.out,
                **export_kwargs,
            )

    out_json = args.out + ".json"
    audio_cfg = cfg.get("audio", {})
    payload = {
        "audio": {
            "sample_rate": int(audio_cfg.get("sample_rate", 22050)),
            "hop_length": 256,
            "win_length": 1024,
            "n_fft": 1024,
            "num_mels": 80,
        },
        "text": {
            "language": cfg.get("text", {}).get("language", "ru"),
            "phoneme_type": cfg.get("text", {}).get("phoneme_type", "espeak"),
            "phoneme_id_map": phoneme_id_map,
        },
        "inference": {
            "noise_scale": 0.667,
            "length_scale": 1.0,
            "noise_w": 0.0,
        },
        "model": {
            "type": "mila-vits",
            "hidden": mcfg.get("hidden", 192),
            "step": ck.get("step"),
        },
    }
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)

    size_mb = os.path.getsize(args.out) / (1024 * 1024)
    print(f"{args.out}: {size_mb:.1f} МБ, конфиг: {out_json}")

    if args.int8 or size_mb > MAX_MB:
        try:
            from onnxruntime.quantization import QuantType, quantize_dynamic

            qpath = args.out.replace(".onnx", ".int8.onnx")
            quantize_dynamic(args.out, qpath, weight_type=QuantType.QInt8)
            q_mb = os.path.getsize(qpath) / (1024 * 1024)
            print(f"квантизация int8: {qpath} ({q_mb:.1f} МБ)")
            if size_mb > MAX_MB and q_mb <= MAX_MB:
                os.replace(qpath, args.out)
                print("используем int8-версию как основную (вписались в лимит)")
        except Exception as e:
            print(f"предупреждение: не удалось сделать int8: {e}")

    final_mb = os.path.getsize(args.out) / (1024 * 1024)
    if final_mb > MAX_MB:
        raise SystemExit(f"модель {final_mb:.1f} МБ превышает лимит {MAX_MB} МБ")
    print(f"итог: {final_mb:.1f} МБ ✓ (лимит {MAX_MB:.0f} МБ)")


if __name__ == "__main__":
    main()
