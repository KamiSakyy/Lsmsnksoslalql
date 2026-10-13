"""Компактный VITS: энкодер текста, предсказатель длительности, поток, HiFi-GAN декодер.

Одноголосая модель; размер чекпоинта/ONNX регулируется конфигом
(hidden_channels, upsample_initial_channel) и укладывается в 100 МБ.
"""
from __future__ import annotations

import math
from typing import Optional, Tuple

import torch
import torch.nn as nn
import torch.nn.functional as F


# ---------------------------------------------------------------------------
# базовые блоки
# ---------------------------------------------------------------------------

class LayerNorm(nn.Module):
    def __init__(self, channels: int, eps: float = 1e-5):
        super().__init__()
        self.gamma = nn.Parameter(torch.ones(1, channels, 1))
        self.beta = nn.Parameter(torch.zeros(1, channels, 1))
        self.eps = eps

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        mean = x.mean(1, keepdim=True)
        var = x.pow(2).mean(1, keepdim=True)
        return (x - mean) * torch.rsqrt(var + self.eps) * self.gamma + self.beta


class ConvWN(nn.Module):
    """Conv1d + weight norm + слабая инициализация."""

    def __init__(self, in_ch: int, out_ch: int, kernel: int, dilation: int = 1,
                 groups: int = 1, zero_init: bool = False):
        super().__init__()
        padding = (kernel * dilation - dilation) // 2
        conv = nn.Conv1d(in_ch, out_ch, kernel, dilation=dilation,
                         padding=padding, groups=groups)
        nn.init.xavier_uniform_(conv.weight)
        if zero_init:
            nn.init.zeros_(conv.bias)
        self.conv = nn.utils.parametrizations.weight_norm(conv)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.conv(x)


class SinePositionalEncoding(nn.Module):
    def __init__(self, channels: int, max_len: int = 4096):
        super().__init__()
        pe = torch.zeros(max_len, channels)
        position = torch.arange(0, max_len, dtype=torch.float).unsqueeze(1)
        div = torch.exp(torch.arange(0, channels, 2).float() * (-math.log(10000.0) / channels))
        pe[:, 0::2] = torch.sin(position * div)
        pe[:, 1::2] = torch.cos(position * div)
        self.register_buffer("pe", pe.unsqueeze(0).transpose(1, 2), persistent=False)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return x + self.pe[:, :, : x.size(2)]


# ---------------------------------------------------------------------------
# энкодер текста
# ---------------------------------------------------------------------------

class TextEncoder(nn.Module):
    """Свёрточный энкодер текста (стиль Glow-TTS): динамические длины дружат с ONNX."""

    def __init__(self, n_vocab: int, hidden: int, n_layers: int = 6, n_heads: int = 4,
                 ff_mult: int = 4, dropout: float = 0.1):
        super().__init__()
        self.emb = nn.Embedding(n_vocab, hidden)
        nn.init.normal_(self.emb.weight, 0.0, hidden ** -0.5)
        self.pre = ConvWN(hidden, hidden, 1)
        self.wn = WaveNet(hidden, kernel=5, n_layers=n_layers)
        self.post = ConvWN(hidden, hidden, 1)
        self.proj = nn.Conv1d(hidden, hidden * 2, 1)
        self.dropout = dropout
        self.hidden = hidden

    def forward(self, x: torch.Tensor, x_mask: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor]:
        """
        x: [B, T] id фонем, x_mask: [B, 1, T] (1 = валидно)
        -> m_p, logs_p: [B, C, T]
        """
        h = self.emb(x).transpose(1, 2) * math.sqrt(self.hidden)   # [B, C, T]
        h = self.pre(h * x_mask)
        h = self.wn(h, x_mask)
        h = self.post(h * x_mask)
        stats = self.proj(F.dropout(h, self.dropout, self.training) * x_mask)
        m_p, logs_p = torch.split(stats, self.hidden, dim=1)
        return m_p * x_mask, logs_p * x_mask


class DurationPredictor(nn.Module):
    def __init__(self, channels: int, filter_size: int = 256, kernel: int = 5,
                 dropout: float = 0.5):
        super().__init__()
        self.conv1 = ConvWN(channels, filter_size, kernel)
        self.norm1 = LayerNorm(filter_size)
        self.conv2 = ConvWN(filter_size, filter_size, kernel)
        self.norm2 = LayerNorm(filter_size)
        self.proj = nn.Linear(filter_size, 1)
        self.dropout = dropout

    def forward(self, x: torch.Tensor, x_mask: torch.Tensor) -> torch.Tensor:
        """x: [B, C, T] -> logw: [B, 1, T]"""
        x = self.conv1(x * x_mask)
        x = F.leaky_relu(x, 0.1)
        x = self.norm1(x)
        x = F.dropout(x, self.dropout, self.training)
        x = self.conv2(x * x_mask)
        x = F.leaky_relu(x, 0.1)
        x = self.norm2(x)
        x = F.dropout(x, self.dropout, self.training)
        x = self.proj(x.transpose(1, 2)).transpose(1, 2)
        return x * x_mask


# ---------------------------------------------------------------------------
# постериор-энкодер и поток
# ---------------------------------------------------------------------------

class WaveNet(nn.Module):
    """Дилатированные свёртки с гейтом (используется в постериоре и потоке)."""

    def __init__(self, channels: int, kernel: int = 5, n_layers: int = 6):
        super().__init__()
        self.n_layers = n_layers
        self.in_layers = nn.ModuleList()
        self.res_skip = nn.ModuleList()
        for i in range(n_layers):
            dilation = 2 ** i
            self.in_layers.append(ConvWN(channels, 2 * channels, kernel, dilation=dilation))
            self.res_skip.append(nn.Conv1d(channels, 2 * channels, 1))

    def forward(self, x: torch.Tensor, x_mask: torch.Tensor,
                g: Optional[torch.Tensor] = None) -> torch.Tensor:
        output = torch.zeros_like(x)
        for i in range(self.n_layers):
            x_in = self.in_layers[i](x * x_mask)
            tanh, gate = torch.split(x_in, x.size(1), dim=1)
            acts = torch.tanh(tanh) * torch.sigmoid(gate)
            res_skip = self.res_skip[i](acts)
            res, skip = torch.split(res_skip, x.size(1), dim=1)
            if i < self.n_layers - 1:
                x = (x + res) * x_mask
            output = output + skip
        return output * x_mask


class PosteriorEncoder(nn.Module):
    def __init__(self, in_channels: int, hidden: int, out_channels: int,
                 n_layers: int = 8, kernel: int = 5):
        super().__init__()
        self.pre = ConvWN(in_channels, hidden, 1)
        self.wn = WaveNet(hidden, kernel, n_layers)
        self.proj = nn.Conv1d(hidden, out_channels * 2, 1)

    def forward(self, x: torch.Tensor, x_mask: torch.Tensor,
                g: Optional[torch.Tensor] = None) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        x = self.pre(x * x_mask)
        x = self.wn(x, x_mask, g)
        stats = self.proj(x)
        m, logs = torch.split(stats, stats.size(1) // 2, dim=1)
        z = (m + torch.randn_like(m) * torch.exp(logs)) * x_mask
        return z, m, logs


class ResidualCouplingLayer(nn.Module):
    def __init__(self, channels: int, kernel: int = 5, n_layers: int = 4,
                 mean_only: bool = True):
        super().__init__()
        self.half = channels // 2
        self.pre = nn.Conv1d(self.half, channels, 1)
        self.wn = WaveNet(channels, kernel, n_layers)
        self.post = nn.Conv1d(channels, self.half, 1)
        nn.init.zeros_(self.post.weight)
        nn.init.zeros_(self.post.bias)
        self.mean_only = mean_only

    def forward(self, x: torch.Tensor, x_mask: torch.Tensor, reverse: bool = False,
                g: Optional[torch.Tensor] = None) -> torch.Tensor:
        x0, x1 = torch.split(x, [self.half, x.size(1) - self.half], dim=1)
        h = self.pre(x0) * x_mask
        h = self.wn(h, x_mask, g)
        stats = self.post(h) * x_mask
        if not self.mean_only:
            m, logs = torch.split(stats, self.half, dim=1)
        else:
            m, logs = stats, None
        if not reverse:
            x1 = (m + x1) * x_mask if self.mean_only else (m + x1 * torch.exp(logs)) * x_mask
        else:
            x1 = (x1 - m) * x_mask if self.mean_only else (x1 - m) * torch.exp(-logs) * x_mask
        return torch.cat([x0, x1], dim=1)


class ResidualCouplingBlock(nn.Module):
    def __init__(self, channels: int, n_flows: int = 4, kernel: int = 5, n_layers: int = 4):
        super().__init__()
        self.flows = nn.ModuleList()
        for _ in range(n_flows):
            self.flows.append(ResidualCouplingLayer(channels, kernel, n_layers, mean_only=True))
            self.flows.append(Flip())

    def forward(self, x: torch.Tensor, x_mask: torch.Tensor, reverse: bool = False,
                g: Optional[torch.Tensor] = None) -> torch.Tensor:
        if not reverse:
            for flow in self.flows:
                x = flow(x, x_mask, g=g)
        else:
            for flow in reversed(self.flows):
                x = flow(x, x_mask, reverse=True, g=g)
        return x


class Flip(nn.Module):
    def forward(self, x: torch.Tensor, x_mask: Optional[torch.Tensor] = None,
                reverse: bool = False, g: Optional[torch.Tensor] = None) -> torch.Tensor:
        return torch.flip(x, [1])


# ---------------------------------------------------------------------------
# декодер (HiFi-GAN)
# ---------------------------------------------------------------------------

class ResBlock(nn.Module):
    def __init__(self, channels: int, kernel: int = 3, dilations: Tuple[int, ...] = (1, 3, 5)):
        super().__init__()
        self.convs1 = nn.ModuleList()
        self.convs2 = nn.ModuleList()
        for d in dilations:
            self.convs1.append(nn.Conv1d(channels, channels, kernel, dilation=d,
                                         padding=(kernel * d - d) // 2))
            self.convs2.append(nn.Conv1d(channels, channels, kernel, dilation=1,
                                         padding=(kernel - 1) // 2))
            nn.init.xavier_uniform_(self.convs1[-1].weight)
            nn.init.xavier_uniform_(self.convs2[-1].weight)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        for c1, c2 in zip(self.convs1, self.convs2):
            xt = F.leaky_relu(x, 0.1)
            xt = c1(xt)
            xt = F.leaky_relu(xt, 0.1)
            xt = c2(xt)
            x = xt + x
        return x


class Generator(nn.Module):
    def __init__(self, in_channels: int, upsample_initial: int = 384,
                 upsample_rates: Tuple[int, ...] = (8, 8, 2, 2),
                 upsample_kernels: Tuple[int, ...] = (16, 16, 4, 4),
                 resblock_kernels: Tuple[int, ...] = (3, 7, 11)):
        super().__init__()
        self.conv_pre = nn.Conv1d(in_channels, upsample_initial, 7, padding=3)
        self.ups = nn.ModuleList()
        self.resblocks = nn.ModuleList()
        ch = upsample_initial
        for r, k in zip(upsample_rates, upsample_kernels):
            self.ups.append(nn.ConvTranspose1d(ch, ch // 2, k, stride=r,
                                               padding=(k - r) // 2))
            ch //= 2
            for rk in resblock_kernels:
                self.resblocks.append(ResBlock(ch, rk))
        self.num_kernels = len(resblock_kernels)
        self.conv_post = nn.Conv1d(ch, 1, 7, padding=3)
        nn.init.zeros_(self.conv_post.weight)
        nn.init.zeros_(self.conv_post.bias)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        x = self.conv_pre(x)
        for i, up in enumerate(self.ups):
            x = F.leaky_relu(x, 0.1)
            x = up(x)
            xs = None
            for j in range(self.num_kernels):
                rb = self.resblocks[i * self.num_kernels + j](x)
                xs = rb if xs is None else xs + rb
            x = xs / self.num_kernels
        x = F.leaky_relu(x, 0.1)
        x = self.conv_post(x)
        return torch.tanh(x)


# ---------------------------------------------------------------------------
# дискриминатор
# ---------------------------------------------------------------------------

class DiscriminatorP(nn.Module):
    def __init__(self, period: int):
        super().__init__()
        self.period = period
        chs = [1, 32, 128, 512, 1024, 1024]
        self.convs = nn.ModuleList()
        for i in range(len(chs) - 1):
            self.convs.append(
                nn.Conv2d(chs[i], chs[i + 1], (5, 1), stride=(3, 1),
                          padding=(2, 0))
            )
        self.conv_post = nn.Conv2d(chs[-1], 1, (3, 1), padding=(1, 0))

    def forward(self, x: torch.Tensor):
        fmap = []
        b, c, t = x.shape
        if t % self.period != 0:
            pad = self.period - (t % self.period)
            x = F.pad(x, (0, pad), mode="reflect")
            t += pad
        x = x.view(b, c, t // self.period, self.period)
        for conv in self.convs:
            x = F.leaky_relu(conv(x), 0.1)
            fmap.append(x)
        x = self.conv_post(x)
        fmap.append(x)
        return x, fmap


class MultiPeriodDiscriminator(nn.Module):
    def __init__(self, periods: Tuple[int, ...] = (2, 3, 5, 7, 11)):
        super().__init__()
        self.discriminators = nn.ModuleList([DiscriminatorP(p) for p in periods])

    def forward(self, y: torch.Tensor, y_hat: torch.Tensor):
        out_real, out_fake, fmap_real, fmap_fake = [], [], [], []
        for d in self.discriminators:
            r, fr = d(y)
            f, ff = d(y_hat)
            out_real.append(r)
            out_fake.append(f)
            fmap_real.append(fr)
            fmap_fake.append(ff)
        return out_real, out_fake, fmap_real, fmap_fake


# ---------------------------------------------------------------------------
# полная модель
# ---------------------------------------------------------------------------

class MilaVITS(nn.Module):
    def __init__(
        self,
        n_vocab: int,
        hidden: int = 192,
        text_layers: int = 4,
        flow_layers: int = 4,
        flow_n_flows: int = 4,
        posterior_layers: int = 8,
        upsample_initial: int = 384,
        segment_size: int = 32,
        spec_channels: int = 513,
    ):
        super().__init__()
        self.n_vocab = n_vocab
        self.hidden = hidden
        self.segment_size = segment_size
        self.text_enc = TextEncoder(n_vocab, hidden, n_layers=text_layers)
        self.dp = DurationPredictor(hidden)
        self.posterior = PosteriorEncoder(spec_channels, hidden, hidden, n_layers=posterior_layers)
        self.flow = ResidualCouplingBlock(hidden, n_flows=flow_n_flows, n_layers=flow_layers)
        self.dec = Generator(hidden, upsample_initial=upsample_initial)

    # -- обучение --
    def forward(self, x, x_lengths, spec, spec_lengths, y=None):
        x_mask = sequence_mask(x_lengths, x.size(1)).unsqueeze(1).float()
        spec_mask = sequence_mask(spec_lengths, spec.size(2)).unsqueeze(1).float()
        m_p, logs_p = self.text_enc(x, x_mask)
        z, m_q, logs_q = self.posterior(spec, spec_mask)
        z_p = self.flow(z, spec_mask)
        logw = self.dp(m_p.detach(), x_mask)
        return {
            "z": z, "z_p": z_p, "m_p": m_p, "logs_p": logs_p,
            "m_q": m_q, "logs_q": logs_q, "x_mask": x_mask,
            "spec_mask": spec_mask, "logw": logw,
        }

    def decode(self, z: torch.Tensor) -> torch.Tensor:
        return self.dec(z)

    # -- инференс (экспорт в ONNX) --
    def infer(
        self,
        x: torch.Tensor,
        x_lengths: torch.Tensor,
        noise_scale: torch.Tensor,
        length_scale: torch.Tensor,
        noise_w: torch.Tensor,
    ) -> torch.Tensor:
        x_mask = sequence_mask(x_lengths, x.size(1)).unsqueeze(1).float()
        m_p, logs_p = self.text_enc(x, x_mask)
        logw = self.dp(m_p, x_mask)
        logw = logw + noise_w * torch.randn_like(logw) * 0.1
        w = torch.exp(logw) * x_mask * length_scale
        w_ceil = torch.ceil(w)
        y_lengths = torch.clamp_min(torch.sum(w_ceil, [1, 2]), 1).long()
        y_mask = sequence_mask(y_lengths, None).unsqueeze(1).float()
        attn_mask = torch.unsqueeze(x_mask, 2) * torch.unsqueeze(y_mask, -1)
        attn = generate_path(w_ceil, attn_mask)          # [B, 1, T_y, T_x]
        m_p = torch.matmul(attn.squeeze(1), m_p.transpose(1, 2)).transpose(1, 2)
        logs_p = torch.matmul(attn.squeeze(1), logs_p.transpose(1, 2)).transpose(1, 2)
        z_p = m_p + torch.randn_like(m_p) * torch.exp(logs_p) * noise_scale
        z = self.flow(z_p, y_mask, reverse=True)
        o = self.dec(z * y_mask)
        return o


# ---------------------------------------------------------------------------
# утилиты
# ---------------------------------------------------------------------------

def sequence_mask(lengths: torch.Tensor, max_length: Optional[torch.Tensor] = None) -> torch.Tensor:
    """[B, max_length]: True/1 там, где позиция < длины. Дружит с ONNX-экспортом."""
    if max_length is None:
        max_length = lengths.max()
    if not torch.is_tensor(max_length):
        max_length = torch.tensor(max_length, dtype=lengths.dtype, device=lengths.device)
    ids = torch.arange(0, max_length, device=lengths.device, dtype=lengths.dtype)
    return ids.unsqueeze(0) < lengths.unsqueeze(1)


def generate_path(duration: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
    """Монотонный алигнмент из длительностей. duration: [B,1,T_x], mask: [B,1,T_y,T_x].

    Размер T_y берётся из тензора (cumsum по ones), а не из shape —
    это позволяет экспортировать в ONNX с динамическими длинами.
    """
    cum = torch.cumsum(duration, dim=-1)          # [B, 1, T_x]
    cum_excl = cum - duration
    ones = torch.ones_like(mask[:, :, :, :1])     # [B, 1, T_y, 1]
    grid_y = torch.cumsum(ones, dim=2)            # 1..T_y
    path = ((cum_excl.unsqueeze(2) < grid_y) & (grid_y <= cum.unsqueeze(2))).float()
    return path * mask


def maximum_path(neg_cent: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
    """Monotonic Alignment Search (из оригинального VITS).

    neg_cent, mask: [B, T_y, T_x] (T_y — фреймы спектра, T_x — фонемы).
    """
    import numpy as np

    value_np = neg_cent.detach().float().cpu().numpy()
    mask_np = mask.detach().float().cpu().numpy()
    path = torch.zeros_like(neg_cent)
    path_np = path.numpy()
    b = value_np.shape[0]
    for bi in range(b):
        value = value_np[bi]
        m = mask_np[bi]
        t_y, t_x = value.shape
        y_valid = int(m.any(axis=1).sum())
        x_valid = int(m.any(axis=0).sum())
        if y_valid == 0 or x_valid == 0:
            continue
        v = np.full((y_valid, x_valid), -1e9, dtype=np.float32)
        for i in range(y_valid):
            # как в оригинальном MAS: на фрейме i допустимы токены
            # [max(0, x_valid + i - y_valid), min(x_valid, i + 1)) —
            # это гарантирует каждому токену хотя бы один фрейм
            lo = max(0, x_valid + i - y_valid)
            hi = min(x_valid, i + 1)
            if lo >= hi:
                continue
            if i == 0:
                v[0, lo] = value[0, lo]
                continue
            for j in range(lo, hi):
                stay = v[i - 1, j]
                move = v[i - 1, j - 1] if j > 0 else -1e9
                v[i, j] = value[i, j] + (stay if stay > move else move)
        # backtrack
        j = x_valid - 1
        for i in range(y_valid - 1, -1, -1):
            path_np[bi, i, j] = 1.0
            if i > 0 and j > 0 and v[i - 1, j - 1] > v[i - 1, j]:
                j -= 1
    return torch.from_numpy(path_np).to(neg_cent.device)


def kl_loss(z_p, logs_q, m_p, logs_p, z_mask) -> torch.Tensor:
    kl = logs_p - logs_q - 0.5
    kl = kl + 0.5 * ((z_p - m_p) ** 2) * torch.exp(-2.0 * logs_p)
    return torch.sum(kl * z_mask) / torch.clamp(z_mask.sum(), min=1.0)


def slice_segments(z: torch.Tensor, ids: torch.Tensor, segment_size: int) -> torch.Tensor:
    """Режет [B, C, T] на сегменты фиксированной длины (для GAN-обучения)."""
    ret = []
    for i, start in enumerate(ids):
        ret.append(z[i: i + 1, :, start: start + segment_size])
    return torch.cat(ret, dim=0)


def rand_slice_segments(z: torch.Tensor, segment_size: int):
    b, c, t = z.shape
    ids = torch.clamp(
        (torch.rand(b, device=z.device) * (t - segment_size + 1)).long(), min=0
    )
    return slice_segments(z, ids, segment_size), ids
