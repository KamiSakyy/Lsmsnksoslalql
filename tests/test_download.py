# -*- coding: utf-8 -*-
"""Проверка загрузки после возврата к проверенной схеме.

Сценарии:
  1) обычная загрузка HLS (fMP4, как у CDN AniLibria): mp4 + превью, каталог сегментов вычищен;
  2) CDN отвечает 429 (лимит) — загрузка доводится до конца, а прогресс в чате обновляется
     каждые пару секунд (сообщение не выглядит «зависшим на 0%»);
  3) обрыв на середине: скачанные сегменты остаются на диске, повторный запуск качает только
     недостающие (докачка), а не сначала.

Запуск:  python tests/test_download.py
"""

from __future__ import annotations

import asyncio
import functools
import http.server
import os
import pathlib
import shutil
import subprocess
import sys
import threading
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
TMP = pathlib.Path("/tmp/test_download")
shutil.rmtree(TMP, ignore_errors=True)
TMP.mkdir(parents=True, exist_ok=True)

try:
    import imageio_ffmpeg
    FFMPEG = imageio_ffmpeg.get_ffmpeg_exe()
except Exception:  # noqa: BLE001
    FFMPEG = shutil.which("ffmpeg") or "ffmpeg"

os.environ.update({
    "DATA_DIR": str(TMP), "SESSION_DIR": str(TMP / "sessions"),
    "DOWNLOAD_DIR": str(TMP / "cache"), "DB_PATH": str(TMP / "db.sqlite3"),
    "FFMPEG": FFMPEG, "PROGRESS_INTERVAL": "0.3",
    "HLS_CONNECTIONS": "8", "HLS_START_CONNECTIONS": "3", "HLS_RAMP_AFTER": "3",
    "HLS_JITTER": "0.05", "HLS_SEGMENT_RETRIES": "4", "MAX_CACHE_GB": "1",
    "HLS_MAX_RATE_HITS": "40", "HLS_LONG_PAUSE": "1",
})
sys.path.insert(0, str(ROOT))

import config                       # noqa: E402
import dl_engine                    # noqa: E402

config.ensure_dirs()
checks = 0


def ok(name: str) -> None:
    global checks
    checks += 1
    print(f"{checks}) {name}: OK")


# --------------------------- тестовый HLS-поток -----------------------------
HLS_ROOT = TMP / "hls"
(HLS_ROOT / "ep").mkdir(parents=True, exist_ok=True)
subprocess.run([FFMPEG, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                "-t", "48", "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-f", "hls", "-hls_time", "8", "-hls_playlist_type", "vod",
                "-hls_segment_type", "fmp4", "-hls_fmp4_init_filename", "init.mp4",
                "-hls_segment_filename", str(HLS_ROOT / "ep" / "seg%03d.m4s"),
                str(HLS_ROOT / "ep" / "index.m3u8")], check=True)
SEGMENTS = sorted((HLS_ROOT / "ep").glob("seg*.m4s"))
print(f"Тестовый поток: 48 сек, {len(SEGMENTS)} сегментов\n")

STATE = {"active": 0, "max_active": 0, "served": 0, "limit_left": 0, "lock": threading.Lock()}


class Handler(http.server.SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):  # noqa: D102
        pass

    def do_GET(self):  # noqa: N802
        if self.path.endswith(".m3u8"):
            return super().do_GET()
        with STATE["lock"]:
            STATE["active"] += 1
            STATE["max_active"] = max(STATE["max_active"], STATE["active"])
            give_limit = STATE["limit_left"] > 0
            if give_limit:
                STATE["limit_left"] -= 1
        try:
            if give_limit:
                body = b"rate limited"
                self.send_response(429)
                self.send_header("Retry-After", "2")
                self.send_header("Content-Type", "text/plain")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            time.sleep(0.02)
            with STATE["lock"]:
                STATE["served"] += 1
            return super().do_GET()
        finally:
            with STATE["lock"]:
                STATE["active"] -= 1


class QuietServer(http.server.ThreadingHTTPServer):
    def handle_error(self, *args):  # noqa: D102
        pass


httpd = QuietServer(("127.0.0.1", 8814), functools.partial(Handler, directory=str(HLS_ROOT)))
threading.Thread(target=httpd.serve_forever, daemon=True).start()
BASE = "http://127.0.0.1:8814/ep/index.m3u8"

TOTAL_BYTES = sum(p.stat().st_size for p in SEGMENTS)


async def scenario_download() -> None:
    """1) Обычная загрузка: mp4 собирается, превью есть, сегменты убраны."""
    ticks: list = []

    async def on_progress(state):
        ticks.append((time.time(), round(state.percent, 1), state.extra))

    t0 = time.time()
    info = await dl_engine.download_episode(100, 1, 480, BASE, on_progress=on_progress)
    elapsed = time.time() - t0
    assert info.mode == "copy", info.mode
    assert pathlib.Path(info.path).exists() and info.size > TOTAL_BYTES * 0.5, info.size
    assert info.duration >= 45, info.duration
    assert info.thumb_path and pathlib.Path(info.thumb_path).exists(), info.thumb_path
    assert info.width and info.height, (info.width, info.height)
    segments_dir = pathlib.Path(info.path).with_suffix(".segments")
    assert not segments_dir.exists(), f"каталог сегментов не вычищен: {segments_dir}"
    gaps = [b[0] - a[0] for a, b in zip(ticks, ticks[1:])]
    print(f"   {info.size_mb:.2f} МБ за {elapsed:.1f} с, обновлений прогресса {len(ticks)}, "
          f"максимальный разрыв {max(gaps or [0]):.1f} с")
    ok(f"Обычная загрузка: режим copy (без перекодирования), превью {info.width}x{info.height}, "
       f"сегменты убраны")


async def scenario_ratelimit() -> None:
    """2) CDN отвечает 429 — доходим до конца, а прогресс в чате не залипает."""
    shutil.rmtree(config.DOWNLOAD_DIR, ignore_errors=True)
    config.ensure_dirs()
    dl_engine._GLOBAL_LIMITER = None
    STATE["served"] = 0
    STATE["limit_left"] = 5                      # первые пять запросов к сегментам — 429
    ticks: list = []

    async def on_progress(state):
        ticks.append((time.time(), round(state.percent, 1), state.extra or ""))

    t0 = time.time()
    info = await dl_engine.download_episode(100, 2, 480, BASE, on_progress=on_progress)
    elapsed = time.time() - t0
    gaps = [b[0] - a[0] for a, b in zip(ticks, ticks[1:])]
    pauses = [t for t in ticks if "пауза" in t[2]]
    print(f"   {info.size_mb:.2f} МБ за {elapsed:.1f} с, обновлений {len(ticks)}, "
          f"максимальный разрыв {max(gaps or [0]):.1f} с, сообщений про паузу {len(pauses)}")
    assert info.mode == "copy" and info.size > TOTAL_BYTES * 0.5
    assert len(ticks) >= 4, ticks
    assert max(gaps or [0]) <= 6.5, f"прогресс залипал {max(gaps):.1f} с"
    assert pauses, "пользователь должен видеть, что бот ждёт CDN"
    ok(f"Лимит CDN (429 + Retry-After): загрузка завершилась за {elapsed:.1f} с, прогресс "
       f"обновлялся всё время («{pauses[0][2][:45]}…»)")


async def scenario_resume() -> None:
    """3) Обрыв на середине: готовые сегменты переиспользуются (докачка, не сначала)."""
    shutil.rmtree(config.DOWNLOAD_DIR, ignore_errors=True)
    config.ensure_dirs()
    dl_engine._GLOBAL_LIMITER = None
    STATE["served"] = 0
    STATE["limit_left"] = 0

    cancel = asyncio.Event()
    calls = {"n": 0}

    async def on_progress(state):
        calls["n"] += 1
        if calls["n"] >= 3:
            cancel.set()                      # отменяем на середине

    try:
        await dl_engine.download_episode(100, 3, 480, BASE, on_progress=on_progress, cancel_event=cancel)
        cancelled = False
    except dl_engine.DownloadCancelled:
        cancelled = True
    assert cancelled, "отмена должна прервать загрузку"

    paths = dl_engine.episode_paths(100, 3, 480)
    seg_dir = paths["dir"] / (paths["video"].stem + ".segments")
    kept = [p for p in seg_dir.glob("seg*.m4s") if p.stat().st_size > 0] if seg_dir.exists() else []
    assert kept, "скачанные сегменты должны остаться на диске для докачки"

    served_before = STATE["served"]
    dl_engine._GLOBAL_LIMITER = None
    STATE["limit_left"] = 0
    info = await dl_engine.download_episode(100, 3, 480, BASE)
    fetched_again = STATE["served"] - served_before
    assert info.mode == "copy" and info.size > TOTAL_BYTES * 0.5
    assert fetched_again < len(SEGMENTS), (fetched_again, len(SEGMENTS))
    ok(f"Обрыв на середине: {len(kept)} сегмент(ов) сохранены, при повторе скачано только "
       f"{fetched_again} из {len(SEGMENTS)} — докачка работает")


async def main() -> None:
    await scenario_download()
    await scenario_ratelimit()
    await scenario_resume()
    print("\n✅ ЗАГРУЗКА РАБОТАЕТ: собирается mp4, лимиты CDN переживаем, прогресс видно, "
          "докачка с места")


asyncio.run(main())
httpd.shutdown()
