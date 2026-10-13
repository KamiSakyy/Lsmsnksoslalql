"""Обучение MilaVITS на своём голосе (wav + транскрипты из metadata.csv)."""
from __future__ import annotations

import argparse
import json
import os
import sys
import time

import torch
import torch.nn.functional as F
from torch.utils.data import DataLoader

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from cute_tts.phonemize import build_phoneme_id_map  # noqa: E402
from training.dataset import TTSDataset, collate_batch  # noqa: E402
from training.losses import (  # noqa: E402
    MelSpectrogram,
    compute_alignment,
    discriminator_loss,
    duration_loss,
    expand_prior,
    feature_matching_loss,
    generator_adv_loss,
    kl_loss,
)
from training.vits import MilaVITS, MultiPeriodDiscriminator, rand_slice_segments  # noqa: E402


def load_config(path: str) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()
    if path.endswith(".json"):
        return json.loads(text)
    import yaml  # pyyaml

    return yaml.safe_load(text)


def main():
    ap = argparse.ArgumentParser(description="Обучение голоса MilaTTS")
    ap.add_argument("--config", default="configs/mila.yaml")
    ap.add_argument("--data-dir", default=None, help="папка с wav + metadata.csv")
    ap.add_argument("--out", default="models/run", help="куда писать чекпоинты")
    ap.add_argument("--steps", type=int, default=None)
    ap.add_argument("--batch-size", type=int, default=None)
    ap.add_argument("--resume", default=None)
    ap.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    ap.add_argument("--smoke", action="store_true", help="быстрый тест пайплайна (50 шагов)")
    args = ap.parse_args()

    cfg = load_config(args.config)
    if args.data_dir:
        cfg.setdefault("data", {})["dir"] = args.data_dir
    if args.steps is not None:
        cfg.setdefault("train", {})["steps"] = args.steps
    if args.batch_size is not None:
        cfg.setdefault("train", {})["batch_size"] = args.batch_size
    if args.smoke:
        cfg["train"]["steps"] = 50
        cfg["train"]["save_every"] = 50
        cfg["train"]["log_every"] = 5

    device = torch.device(args.device)
    torch.manual_seed(cfg.get("train", {}).get("seed", 42))

    phoneme_type = cfg.get("text", {}).get("phoneme_type", "espeak")
    language = cfg.get("text", {}).get("language", "ru")
    phoneme_id_map = build_phoneme_id_map(phoneme_type)
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
    ).to(device)
    mpd = MultiPeriodDiscriminator().to(device)

    tcfg = cfg.get("train", {})
    opt_g = torch.optim.AdamW(
        model.parameters(), lr=float(tcfg.get("lr", 2e-4)),
        betas=tuple(tcfg.get("betas", [0.8, 0.99])), weight_decay=1e-4,
    )
    opt_d = torch.optim.AdamW(
        mpd.parameters(), lr=float(tcfg.get("lr", 2e-4)),
        betas=tuple(tcfg.get("betas", [0.8, 0.99])), weight_decay=1e-4,
    )

    start_step = 0
    if args.resume and os.path.isfile(args.resume):
        ck = torch.load(args.resume, map_location=device)
        model.load_state_dict(ck["model"])
        mpd.load_state_dict(ck["disc"])
        opt_g.load_state_dict(ck["opt_g"])
        opt_d.load_state_dict(ck["opt_d"])
        start_step = ck.get("step", 0)
        print(f"продолжаем с шага {start_step}")

    ds = TTSDataset(
        cfg["data"]["dir"],
        phoneme_id_map=phoneme_id_map,
        phoneme_type=phoneme_type,
        language=language,
    )
    print(f"датасет: {len(ds)} фраз")
    loader = DataLoader(
        ds, batch_size=tcfg.get("batch_size", 4), shuffle=True,
        collate_fn=collate_batch, drop_last=len(ds) > tcfg.get("batch_size", 4),
        num_workers=0,
    )

    mel_fn = MelSpectrogram().to(device)
    steps = int(tcfg.get("steps", 2000))
    log_every = int(tcfg.get("log_every", 20))
    save_every = int(tcfg.get("save_every", 200))
    grad_clip = float(tcfg.get("grad_clip", 1.0))
    w_mel = float(tcfg.get("mel_weight", 45.0))
    w_adv = float(tcfg.get("adv_weight", 1.0))
    w_fm = float(tcfg.get("fm_weight", 2.0))
    w_dur = float(tcfg.get("dur_weight", 1.0))
    w_kl = float(tcfg.get("kl_weight", 1.0))

    os.makedirs(args.out, exist_ok=True)
    model.train()
    mpd.train()
    step = start_step
    t0 = time.time()
    data_iter = iter(loader)

    while step < steps:
        try:
            batch = next(data_iter)
        except StopIteration:
            data_iter = iter(loader)
            batch = next(data_iter)
        texts, text_lengths, specs, spec_lengths, wavs, wav_lengths = [
            b.to(device) for b in batch
        ]

        out = model(texts, text_lengths, specs, spec_lengths)
        x_mask, y_mask = out["x_mask"], out["spec_mask"]
        attn = compute_alignment(out["z"], out["z_p"], out["m_p"], out["logs_p"], x_mask, y_mask)
        m_p_exp, logs_p_exp = expand_prior(attn, out["m_p"], out["logs_p"])

        loss_kl = kl_loss(out["z_p"], out["logs_q"], m_p_exp, logs_p_exp, y_mask)
        loss_dur = duration_loss(out["logw"], attn, x_mask)

        z_slice, ids = rand_slice_segments(out["z"], model.segment_size)
        y_slice = torch.zeros(
            z_slice.size(0), 1, z_slice.size(2) * 256, device=device
        )
        for i, start in enumerate(ids):
            seg = wavs[i: i + 1, :, start * 256: start * 256 + y_slice.size(2)]
            y_slice[i: i + 1, :, : seg.size(2)] = seg

        y_hat = model.decode(z_slice)
        mel_hat = mel_fn(y_hat)
        mel_ref = mel_fn(y_slice)
        t_min = min(mel_hat.size(2), mel_ref.size(2))
        loss_mel = F.l1_loss(mel_hat[:, :, :t_min], mel_ref[:, :, :t_min]) * w_mel

        # --- дискриминатор ---
        opt_d.zero_grad(set_to_none=True)
        real_logits, fake_logits, _, _ = mpd(y_slice, y_hat.detach())
        loss_d_real, loss_d_fake = discriminator_loss(real_logits, fake_logits)
        loss_d = loss_d_real + loss_d_fake
        loss_d.backward()
        torch.nn.utils.clip_grad_norm_(mpd.parameters(), grad_clip)
        opt_d.step()

        # --- генератор ---
        opt_g.zero_grad(set_to_none=True)
        real_logits, fake_logits, fmap_real, fmap_fake = mpd(y_slice, y_hat)
        loss_adv = generator_adv_loss(fake_logits) * w_adv
        loss_fm = feature_matching_loss(fmap_real, fmap_fake) * w_fm
        loss_g = loss_mel + loss_adv + loss_fm + w_dur * loss_dur + w_kl * loss_kl
        loss_g.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), grad_clip)
        opt_g.step()

        step += 1
        if step % log_every == 0 or step == 1:
            speed = step / max(time.time() - t0, 1e-5)
            print(
                f"шаг {step}/{steps} | mel {loss_mel.item():.3f} dur {loss_dur.item():.3f} "
                f"kl {loss_kl.item():.3f} adv {loss_adv.item():.3f} fm {loss_fm.item():.3f} "
                f"d {loss_d.item():.3f} | {speed:.2f} шаг/с",
                flush=True,
            )
        if step % save_every == 0 or step == steps:
            ckpt = {
                "model": model.state_dict(),
                "disc": mpd.state_dict(),
                "opt_g": opt_g.state_dict(),
                "opt_d": opt_d.state_dict(),
                "step": step,
                "config": cfg,
                "phoneme_id_map": phoneme_id_map,
            }
            torch.save(ckpt, os.path.join(args.out, "latest.pt"))
            if step == steps:
                torch.save(ckpt, os.path.join(args.out, "final.pt"))
            print(f"сохранили чекпоинт (шаг {step})", flush=True)

    print("готово ✓")


if __name__ == "__main__":
    main()
