import numpy as np
import torch

from cute_tts.phonemize import build_phoneme_id_map
from training.losses import compute_alignment, expand_prior
from training.vits import MilaVITS, MultiPeriodDiscriminator, generate_path, sequence_mask


def _tiny_model():
    pm = build_phoneme_id_map("espeak")
    return MilaVITS(
        n_vocab=max(pm.values()) + 1,
        hidden=64, text_layers=1, flow_layers=1, flow_n_flows=2,
        posterior_layers=2, upsample_initial=64,
    )


def test_sequence_mask():
    m = sequence_mask(torch.tensor([2, 4]), 4)
    assert m.shape == (2, 4)
    assert m[0].tolist() == [True, True, False, False]


def test_generate_path():
    dur = torch.tensor([[[2.0, 3.0]]])
    x_mask = torch.ones(1, 1, 2)
    y_mask = torch.ones(1, 1, 5)
    attn_mask = torch.unsqueeze(x_mask, 2) * torch.unsqueeze(y_mask, -1)
    path = generate_path(dur, attn_mask)
    assert path.shape == (1, 1, 5, 2)
    assert float(path.sum()) == 5.0


def test_forward_and_infer():
    torch.manual_seed(0)
    model = _tiny_model()
    x = torch.randint(3, 40, (2, 12))
    x_len = torch.tensor([12, 9])
    spec = torch.randn(2, 513, 40)
    spec_len = torch.tensor([40, 31])
    out = model(x, x_len, spec, spec_len)
    attn = compute_alignment(
        out["z"], out["z_p"], out["m_p"], out["logs_p"], out["x_mask"], out["spec_mask"]
    )
    assert attn.shape == (2, 1, 40, 12)
    # каждый валидный токен получил хотя бы один фрейм
    for bi, tl in enumerate(x_len.tolist()):
        durs = attn.sum(2)[bi, 0, :tl]
        assert (durs >= 1).all()
    m, logs = expand_prior(attn, out["m_p"], out["logs_p"])
    assert m.shape == out["z"].shape

    model.eval()
    with torch.no_grad():
        y = model.infer(
            x[:1, :10], torch.tensor([10]),
            torch.tensor(0.5), torch.tensor(1.0), torch.tensor(0.0),
        )
    assert y.shape[0] == 1 and y.shape[1] == 1
    assert y.shape[2] > 0 and y.shape[2] % 256 == 0


def test_mpd_shapes():
    mpd = MultiPeriodDiscriminator()
    y = torch.randn(2, 1, 4096)
    y_hat = torch.randn(2, 1, 4096)
    real, fake, fr, ff = mpd(y, y_hat)
    assert len(real) == 5 and len(fake) == 5
