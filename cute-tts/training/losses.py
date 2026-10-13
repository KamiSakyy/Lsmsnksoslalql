"""Лоссы VITS: KL, длительность, mel-реконструкция, adversarial + feature matching."""
from __future__ import annotations

import math
from typing import Dict, Tuple

import torch
import torch.nn.functional as F

from .vits import maximum_path


def _mel_filterbank(n_fft: int, n_mels: int, sample_rate: int, device, dtype) -> torch.Tensor:
    def hz_to_mel(hz):
        return 2595.0 * torch.log10(1.0 + hz / 700.0)

    def mel_to_hz(mel):
        return 700.0 * (10.0 ** (mel / 2595.0) - 1.0)

    f_max = sample_rate / 2
    m_min, m_max = hz_to_mel(torch.tensor(0.0)), hz_to_mel(torch.tensor(f_max))
    m_pts = torch.linspace(m_min, m_max, n_mels + 2)
    f_pts = mel_to_hz(m_pts)
    bins = torch.floor((n_fft + 1) * f_pts / sample_rate).long()
    fb = torch.zeros(n_mels, n_fft // 2 + 1)
    for i in range(n_mels):
        l, c, r = bins[i], bins[i + 1], bins[i + 2]
        if c > l:
            fb[i, l:c] = (torch.arange(l, c) - l) / max(c - l, 1)
        if r > c:
            fb[i, c:r] = (r - torch.arange(c, r)) / max(r - c, 1)
    return fb.to(device=device, dtype=dtype)


class MelSpectrogram(torch.nn.Module):
    def __init__(self, sample_rate: int = 22050, n_fft: int = 1024,
                 hop: int = 256, win: int = 1024, n_mels: int = 80):
        super().__init__()
        self.hop, self.win, self.n_fft = hop, win, n_fft
        window = torch.hann_window(win)
        self.register_buffer("window", window)
        fb = _mel_filterbank(n_fft, n_mels, sample_rate, torch.device("cpu"), torch.float32)
        self.register_buffer("mel_basis", fb)

    def forward(self, y: torch.Tensor) -> torch.Tensor:
        """y: [B, 1, T] -> log-mel [B, n_mels, T//hop]"""
        pad = int((self.n_fft - self.hop) / 2)
        y = F.pad(y, (pad, pad), mode="reflect")
        spec = torch.stft(y.squeeze(1), self.n_fft, self.hop, self.win, self.window,
                          return_complex=True)
        mag = spec.abs().clamp_min(1e-5)
        mel = torch.matmul(self.mel_basis, mag)
        return torch.log(mel.clamp_min(1e-5))


def compute_alignment(z, z_p, m_p, logs_p, x_mask, y_mask) -> torch.Tensor:
    """MAS-алигнмент. Возвращает attn [B, 1, T_spec, T_text]."""
    s_p_sq_r = torch.exp(-2.0 * logs_p)
    neg_cent1 = torch.sum(-0.5 * math.log(2 * math.pi) - logs_p, [1], keepdim=True)
    neg_cent2 = torch.matmul(-0.5 * (z ** 2).transpose(1, 2), s_p_sq_r)
    neg_cent3 = torch.matmul(z_p.transpose(1, 2), (m_p * s_p_sq_r))
    neg_cent4 = torch.sum(-0.5 * (m_p ** 2) * s_p_sq_r, [1], keepdim=True)
    neg_cent = neg_cent1 + neg_cent2 + neg_cent3 + neg_cent4
    attn_mask = torch.unsqueeze(x_mask, 2) * torch.unsqueeze(y_mask, -1)
    attn = maximum_path(neg_cent, attn_mask.squeeze(1)).unsqueeze(1).detach()
    return attn


def expand_prior(attn: torch.Tensor, m_p: torch.Tensor, logs_p: torch.Tensor):
    """Раскладывает prior по фреймам согласно алигнменту."""
    m = torch.matmul(attn.squeeze(1), m_p.transpose(1, 2)).transpose(1, 2)
    logs = torch.matmul(attn.squeeze(1), logs_p.transpose(1, 2)).transpose(1, 2)
    return m, logs


def kl_loss(z_p, logs_q, m_p, logs_p, z_mask) -> torch.Tensor:
    kl = logs_p - logs_q - 0.5
    kl = kl + 0.5 * ((z_p - m_p) ** 2) * torch.exp(-2.0 * logs_p)
    return torch.sum(kl * z_mask) / torch.clamp(z_mask.sum(), min=1.0)


def duration_loss(logw: torch.Tensor, attn: torch.Tensor, x_mask: torch.Tensor) -> torch.Tensor:
    w = attn.sum(2)                                   # [B, 1, T_text]
    logw_ = torch.log(w + 1e-6) * x_mask
    return torch.sum((logw - logw_) ** 2 * x_mask) / torch.clamp(x_mask.sum(), min=1.0)


def feature_matching_loss(fmap_real, fmap_fake) -> torch.Tensor:
    loss = 0.0
    for dr, df in zip(fmap_real, fmap_fake):
        for r, f in zip(dr, df):
            loss = loss + (r.detach() - f).abs().mean()
    return loss


def discriminator_loss(real_logits, fake_logits) -> Tuple[torch.Tensor, torch.Tensor]:
    loss_real = 0.0
    loss_fake = 0.0
    for dr, df in zip(real_logits, fake_logits):
        loss_real = loss_real + torch.mean((1.0 - dr) ** 2)
        loss_fake = loss_fake + torch.mean(df ** 2)
    return loss_real, loss_fake


def generator_adv_loss(fake_logits) -> torch.Tensor:
    loss = 0.0
    for df in fake_logits:
        loss = loss + torch.mean((1.0 - df) ** 2)
    return loss
