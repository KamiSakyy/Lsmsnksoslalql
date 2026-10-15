# -*- coding: utf-8 -*-
"""
Движок скачивания: HLS (AniLibria/AniLiberty CDN) -> MP4 через ffmpeg, на сервере.

Ключевые моменты:
    * Скачивание идёт на сервер (не на устройство пользователя).
    * 1-я попытка — remux без перекодирования (-c copy): быстро, качество 1:1.
      Если контейнер/кодеки не лезут в mp4 (редкие серии) — включается транскод
      (x264 veryfast + AAC), результат всегда пригоден для Telegram.
    * Прогресс берётся из ffmpeg (-progress pipe:1), длительность — из плейлиста
      m3u8 (сумма EXTINF) или из ffprobe, поэтому прогресс-бар честный.
    * Отмена — убиваем процесс ffmpeg и подчищаем .part.
    * Кэш файлов на диске + LRU-чистка, чтобы хостинг не забивался.
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import os
import re
import shutil
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Awaitable, Callable, Dict, Optional

import aiohttp

import config

log = logging.getLogger("download")

_EXTINF_RE = re.compile(r"#EXTINF:\s*([0-9.]+)")
_VARIANT_RE = re.compile(r"^#EXT-X-STREAM-INF", re.IGNORECASE)


class DownloadError(Exception):
    """Ошибка скачивания."""


class DownloadCancelled(Exception):
    """Задача отменена пользователем."""


class FileTooBig(DownloadError):
    """Файл больше лимита Telegram (2000 МБ)."""


@dataclass
class MediaInfo:
    path: str
    size: int = 0
    duration: float = 0.0
    width: int = 0
    height: int = 0
    video_codec: str = ""
    audio_codec: str = ""
    thumb_path: Optional[str] = None
    mode: str = "copy"
    from_cache: bool = False

    @property
    def size_mb(self) -> float:
        return self.size / (1024 * 1024)


@dataclass
class ProgressState:
    """Живое состояние задачи (для прогресс-бара в чате)."""

    stage: str = "queue"          # queue | download | upload | done | error
    percent: float = 0.0
    position: float = 0.0         # секунды готового видео
    duration: float = 0.0
    speed: str = ""
    downloaded: int = 0
    uploaded: int = 0
    total_upload: int = 0
    extra: str = ""
    started_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)

    def touch(self, **kwargs) -> "ProgressState":
        for key, value in kwargs.items():
            setattr(self, key, value)
        self.updated_at = time.time()
        return self


ProgressCb = Optional[Callable[[ProgressState], Awaitable[None]]]


# ---------------------------------------------------------------------------
# Вспомогательные вещи
# ---------------------------------------------------------------------------
def human_size(num: float) -> str:
    step = 1024.0
    for unit in ("Б", "КБ", "МБ", "ГБ", "ТБ"):
        if abs(num) < step or unit == "ТБ":
            return f"{num:.0f} {unit}" if unit in ("Б", "КБ") else f"{num:.2f} {unit}"
        num /= step
    return f"{num:.2f} ТБ"


def human_time(seconds: float) -> str:
    seconds = int(max(0, seconds))
    hours, remainder = divmod(seconds, 3600)
    minutes, secs = divmod(remainder, 60)
    if hours:
        return f"{hours}ч {minutes:02d}м {secs:02d}с"
    if minutes:
        return f"{minutes}м {secs:02d}с"
    return f"{secs}с"


def cache_key(release_id: int | str, episode: int, quality: int) -> str:
    raw = f"{release_id}:{episode}:{quality}"
    return hashlib.sha1(raw.encode("utf-8")).hexdigest()[:20]


def episode_paths(release_id: int | str, episode: int, quality: int) -> Dict[str, Path]:
    folder = Path(config.DOWNLOAD_DIR) / str(release_id)
    folder.mkdir(parents=True, exist_ok=True)
    stem = f"ep{int(episode):03d}_{int(quality)}p_{cache_key(release_id, episode, quality)}"
    return {
        "dir": folder,
        "video": folder / f"{stem}.mp4",
        "part": folder / f"{stem}.part.mp4",
        "thumb": folder / f"{stem}.jpg",
    }


def directory_size(path: Path) -> int:
    total = 0
    for root, _dirs, files in os.walk(path):
        for name in files:
            try:
                total += os.path.getsize(os.path.join(root, name))
            except OSError:
                pass
    return total


def cleanup_cache(max_bytes: Optional[int] = None) -> int:
    """LRU-чистка кэша: удаляет самые старые файлы, пока размер > лимита."""
    max_bytes = max_bytes if max_bytes is not None else config.MAX_CACHE_BYTES
    root = Path(config.DOWNLOAD_DIR)
    if not root.exists():
        return 0
    entries: list[tuple[float, int, Path]] = []
    for path in root.rglob("*"):
        if path.is_file():
            try:
                stat = path.stat()
            except OSError:
                continue
            entries.append((stat.st_atime, stat.st_size, path))
    total = sum(item[1] for item in entries)
    if total <= max_bytes:
        return 0
    removed = 0
    for _atime, size, path in sorted(entries, key=lambda item: item[0]):
        if total <= max_bytes:
            break
        try:
            path.unlink()
            total -= size
            removed += size
        except OSError:
            continue
    if removed:
        log.info("Кэш очищен на %s", human_size(removed))
    return removed


# ---------------------------------------------------------------------------
# ffmpeg
# ---------------------------------------------------------------------------
def ffmpeg_bin() -> str:
    return config.FFMPEG


def _ffmpeg_headers() -> str:
    return (
        f"Referer: {config.DOWNLOAD_REFERER}\r\n"
        f"Origin: {config.ANILIBRIA_SITE}\r\n"
    )


def _base_cmd(url: str) -> list[str]:
    cmd = [
        ffmpeg_bin(), "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
        "-user_agent", config.DOWNLOAD_USER_AGENT,
        "-headers", _ffmpeg_headers(),
        "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5",
        "-rw_timeout", str(config.FFMPEG_RW_TIMEOUT_US),
        "-protocol_whitelist", "file,http,https,tcp,tls,crypto,httpproxy",
        "-i", url,
    ]
    if config.FFMPEG_THREADS:
        cmd += ["-threads", str(config.FFMPEG_THREADS)]
    return cmd


def build_modes(url: str, out_path: str) -> list[tuple[str, list[str]]]:
    """Цепочка стратегий: от самого быстрого (copy) к самому надёжному (транскод)."""
    wrap = [
        "-sn", "-dn",
        "-movflags", "+faststart",
        "-f", "mp4",
        "-progress", "pipe:1", "-nostats",
        out_path,
    ]
    return [
        # 1. Быстрый remux: видео и звук как есть
        ("copy", _base_cmd(url) + [
            "-map", "0:v:0?", "-map", "0:a:0?",
            "-c", "copy", "-bsf:a", "aac_adtstoasc", "-max_muxing_queue_size", "4096",
        ] + wrap),
        # 2. Видео как есть, звук в AAC (mp4 не всегда понимает AC-3/E-AC-3/MP2 из HLS)
        ("audio-aac", _base_cmd(url) + [
            "-map", "0:v:0?", "-map", "0:a:0?",
            "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-ac", "2",
            "-max_muxing_queue_size", "4096",
        ] + wrap),
        # 3. Полный транскод (HEVC/AV1/экзотика) — медленно, но всегда играбельно
        ("encode", _base_cmd(url) + [
            "-map", "0:v:0?", "-map", "0:a:0?",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "192k", "-ac", "2",
        ] + wrap),
        # 4. Совсем резерв: без faststart (если хостинг режет перезапись файла)
        ("encode-flat", _base_cmd(url) + [
            "-map", "0:v:0?", "-map", "0:a:0?",
            "-c:v", "libx264", "-preset", "ultrafast", "-crf", "25",
            "-c:a", "aac", "-b:a", "160k",
            "-f", "mp4", "-progress", "pipe:1", "-nostats", out_path,
        ]),
    ]


async def hls_duration(url: str) -> float:
    """Длительность из m3u8 (сумма EXTINF). Работает и для master-плейлиста."""
    try:
        timeout = aiohttp.ClientTimeout(total=20, connect=10)
        headers = {
            "User-Agent": config.DOWNLOAD_USER_AGENT,
            "Referer": config.DOWNLOAD_REFERER,
            "Accept": "*/*",
        }
        async with aiohttp.ClientSession(timeout=timeout, headers=headers) as session:
            text = await _fetch_playlist(session, url)
            if text is None:
                return 0.0
            if "#EXT-X-STREAM-INF" in text:  # master -> берём первый вариант
                variant = _first_variant(text, url)
                if variant:
                    text = await _fetch_playlist(session, variant) or text
            total = sum(float(m.group(1)) for m in _EXTINF_RE.finditer(text))
            return float(total)
    except Exception as exc:  # noqa: BLE001
        log.debug("Не удалось посчитать длительность HLS: %s", exc)
        return 0.0


async def _fetch_playlist(session: aiohttp.ClientSession, url: str) -> Optional[str]:
    async with session.get(url, proxy=config.ANILIBRIA_PROXY) as resp:
        if resp.status >= 400:
            return None
        return await resp.text()


def _first_variant(text: str, base_url: str) -> Optional[str]:
    lines = [line.strip() for line in text.splitlines()]
    for index, line in enumerate(lines):
        if _VARIANT_RE.match(line):
            for candidate in lines[index + 1:]:
                if candidate and not candidate.startswith("#"):
                    if candidate.startswith("http"):
                        return candidate
                    from urllib.parse import urljoin

                    return urljoin(base_url, candidate)
    return None


async def run_ffmpeg(
    url: str,
    out_path: Path,
    on_progress: ProgressCb = None,
    cancel_event: Optional[asyncio.Event] = None,
    expected_duration: float = 0.0,
) -> str:
    """Прогоняет ffmpeg по цепочке стратегий, возвращает имя удачного режима."""
    last_error = "неизвестная ошибка"
    for mode, cmd in build_modes(url, str(out_path)):
        if cancel_event and cancel_event.is_set():
            raise DownloadCancelled()
        if mode != "copy":
            log.info("Режим %s: предыдущий не подошёл (%s)", mode, last_error)
        state = ProgressState(stage="download", duration=expected_duration)
        code, last_error = await _spawn(cmd, state, on_progress, cancel_event, expected_duration)
        if code == 0 and out_path.exists() and out_path.stat().st_size > 0:
            return mode
        if cancel_event and cancel_event.is_set():
            raise DownloadCancelled()
        try:
            if out_path.exists():
                out_path.unlink()
        except OSError:
            pass
    raise DownloadError(f"ffmpeg не смог собрать mp4: {last_error}")


async def _spawn(
    cmd: list[str],
    state: ProgressState,
    on_progress: ProgressCb,
    cancel_event: Optional[asyncio.Event],
    expected_duration: float,
) -> tuple[int, str]:
    log.debug("ffmpeg: %s", " ".join(cmd))
    process = await asyncio.create_subprocess_exec(
        *cmd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    tail: list[str] = []

    async def read_stderr() -> None:
        assert process.stderr is not None
        while True:
            line = await process.stderr.readline()
            if not line:
                break
            text = line.decode("utf-8", "replace").strip()
            if text:
                tail.append(text)
                del tail[:-12]

    stderr_task = asyncio.create_task(read_stderr())
    last_push = 0.0
    last_milestone = -1
    try:
        assert process.stdout is not None
        while True:
            if cancel_event and cancel_event.is_set() and process.returncode is None:
                _kill(process)
                await _reap(process)
                raise DownloadCancelled()
            try:
                line = await asyncio.wait_for(process.stdout.readline(), timeout=1.0)
            except asyncio.TimeoutError:
                if process.returncode is not None:
                    break
                continue
            if not line:
                break
            key, _, value = line.decode("utf-8", "replace").strip().partition("=")
            if key == "out_time_us" or key == "out_time_ms":
                try:
                    seconds = int(value) / 1_000_000
                except ValueError:
                    continue
                state.position = seconds
                if expected_duration:
                    state.percent = min(99.9, seconds / expected_duration * 100)
            elif key == "out_time":
                try:
                    hours, minutes, secs = value.split(":")
                    seconds = int(hours) * 3600 + int(minutes) * 60 + float(secs)
                except ValueError:
                    continue
                state.position = seconds
                if expected_duration:
                    state.percent = min(99.9, seconds / expected_duration * 100)
            elif key == "speed":
                state.speed = value
            elif key == "total_size":
                try:
                    state.downloaded = int(value)
                except ValueError:
                    pass
            now = time.time()
            milestone = int(state.percent // 10) * 10
            if on_progress and (now - last_push >= config.PROGRESS_INTERVAL or milestone > last_milestone):
                last_push, last_milestone = now, milestone
                await on_progress(state.touch(stage="download"))
        code = await process.wait()
        await stderr_task
        summary = "; ".join(tail[-4:]).strip()
        if not summary:
            summary = ("ffmpeg упал без сообщений (код %s) — вероятно, сборка ffmpeg несовместима с этим окружением"
                       % code) if code else "без вывода"
        return code or 0, summary
    except asyncio.CancelledError:
        _kill(process)
        await _reap(process)
        stderr_task.cancel()
        raise
    finally:
        if process.returncode is None:
            _kill(process)
            await _reap(process)
        if not stderr_task.done():
            stderr_task.cancel()


async def _reap(process: asyncio.subprocess.Process, timeout: float = 10.0) -> None:
    """Дожидается завершения процесса после kill(), чтобы не терять транспорт."""
    if process.returncode is not None:
        return
    try:
        await asyncio.wait_for(process.wait(), timeout)
    except Exception:  # noqa: BLE001 - CancelledError (BaseException) пробрасывается дальше
        pass


def _kill(process: asyncio.subprocess.Process) -> None:
    try:
        process.kill()
    except (ProcessLookupError, OSError):
        pass


# ---------------------------------------------------------------------------
# Метаданные / превью
# ---------------------------------------------------------------------------
def probe(path: str | Path) -> Dict[str, float | int | str]:
    """Метаданные файла: ffprobe, а если его нет — парсим вывод ffmpeg -i."""
    path = str(path)
    if shutil.which(config.FFPROBE):
        cmd = [
            config.FFPROBE, "-v", "error", "-print_format", "json",
            "-show_format", "-show_streams", path,
        ]
        try:
            import json
            import subprocess

            done = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
            data = json.loads(done.stdout or "{}")
            streams = data.get("streams") or []
            video = next((s for s in streams if s.get("codec_type") == "video"), {})
            audio = next((s for s in streams if s.get("codec_type") == "audio"), {})
            fmt = data.get("format") or {}
            return {
                "duration": float(fmt.get("duration") or video.get("duration") or 0),
                "size": int(float(fmt.get("size") or 0)),
                "width": int(video.get("width") or 0),
                "height": int(video.get("height") or 0),
                "video_codec": str(video.get("codec_name") or ""),
                "audio_codec": str(audio.get("codec_name") or ""),
                "has_video": bool(video),
                "has_audio": bool(audio),
            }
        except Exception as exc:  # noqa: BLE001
            log.debug("ffprobe не сработал (%s), пробую ffmpeg", exc)

    # резервный путь: ffmpeg -i и разбор строк Duration/Stream
    result: Dict[str, float | int | str] = {"duration": 0.0, "size": 0, "width": 0, "height": 0,
                                           "video_codec": "", "audio_codec": "", "has_video": False,
                                           "has_audio": False}
    try:
        import subprocess

        proc = subprocess.run(
            [ffmpeg_bin(), "-hide_banner", "-i", path],
            capture_output=True, text=True, timeout=120,
        ).stderr
        match = re.search(r"Duration:\s*(\d+):(\d+):([0-9.]+)", proc)
        if match:
            result["duration"] = int(match.group(1)) * 3600 + int(match.group(2)) * 60 + float(match.group(3))
        match = re.search(r"Video:\s*([a-z0-9_]+).*?(\d{2,5})x(\d{2,5})", proc)
        if match:
            result["video_codec"] = match.group(1)
            result["width"] = int(match.group(2))
            result["height"] = int(match.group(3))
            result["has_video"] = True
        match = re.search(r"Audio:\s*([a-z0-9_]+)", proc)
        if match:
            result["audio_codec"] = match.group(1)
            result["has_audio"] = True
        try:
            result["size"] = os.path.getsize(path)
        except OSError:
            result["size"] = 0
    except Exception as exc:  # noqa: BLE001
        log.warning("Не удалось определить метаданные %s: %s", path, exc)
    return result


async def make_thumbnail(video_path: Path, thumb_path: Path, duration: float = 0.0) -> Optional[str]:
    """Кадр-превью (≤200 КБ, как требует Telegram)."""
    positions = []
    if duration > 60:
        positions = [duration * 0.25, duration * 0.1, 60.0]
    else:
        positions = [max(duration * 0.25, 1.0), 1.0]
    for scale in (config.THUMBNAIL_WIDTH, 240):
        for position in positions:
            if await _grab_frame(video_path, thumb_path, position, scale):
                try:
                    if thumb_path.stat().st_size <= config.THUMBNAIL_MAX_BYTES:
                        return str(thumb_path)
                except OSError:
                    continue
    return None


async def _grab_frame(video_path: Path, thumb_path: Path, position: float, width: int) -> bool:
    cmd = [
        ffmpeg_bin(), "-hide_banner", "-loglevel", "error", "-y",
        "-ss", f"{max(position, 0):.2f}", "-i", str(video_path),
        "-frames:v", "1", "-vf", f"scale={width}:-2", "-q:v", "4",
        str(thumb_path),
    ]
    try:
        process = await asyncio.create_subprocess_exec(
            *cmd, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL
        )
        code = await asyncio.wait_for(process.wait(), timeout=90)
        return code == 0 and thumb_path.exists() and thumb_path.stat().st_size > 0
    except Exception:  # noqa: BLE001
        return False


# ---------------------------------------------------------------------------
# Главная функция скачивания
# ---------------------------------------------------------------------------
async def download_episode(
    release_id: int | str,
    episode: int,
    quality: int,
    hls_url: str,
    on_progress: ProgressCb = None,
    cancel_event: Optional[asyncio.Event] = None,
) -> MediaInfo:
    """Качает серию в mp4 (или отдаёт готовый файл из кэша)."""
    paths = episode_paths(release_id, episode, quality)
    video_path: Path = paths["video"]
    thumb_path: Path = paths["thumb"]
    part_path: Path = paths["part"]

    if video_path.exists() and video_path.stat().st_size > 0:
        meta = await asyncio.to_thread(probe, video_path)
        if meta.get("has_video") and meta.get("duration"):
            size = int(meta.get("size") or video_path.stat().st_size)
            if size > config.MAX_FILE_BYTES:
                raise FileTooBig(f"{human_size(size)} > {config.MAX_FILE_MB} МБ")
            if not thumb_path.exists():
                await make_thumbnail(video_path, thumb_path, float(meta.get("duration") or 0))
            return MediaInfo(
                path=str(video_path), size=size, duration=float(meta.get("duration") or 0),
                width=int(meta.get("width") or 0), height=int(meta.get("height") or 0),
                video_codec=str(meta.get("video_codec") or ""), audio_codec=str(meta.get("audio_codec") or ""),
                thumb_path=str(thumb_path) if thumb_path.exists() else None,
                mode="cache", from_cache=True,
            )
        try:
            video_path.unlink()  # битый файл — качаем заново
        except OSError:
            pass

    duration = await hls_duration(hls_url)
    try:
        mode = await run_ffmpeg(hls_url, part_path, on_progress, cancel_event, duration)
    except DownloadCancelled:
        for path in (part_path,):
            try:
                path.unlink(missing_ok=True)
            except OSError:
                pass
        raise

    if not part_path.exists() or part_path.stat().st_size == 0:
        raise DownloadError("ffmpeg не создал файл (поток недоступен или заблокирован по региону)")

    size = part_path.stat().st_size
    if size > config.MAX_FILE_BYTES:
        try:
            part_path.unlink()
        except OSError:
            pass
        raise FileTooBig(f"{human_size(size)} > {config.MAX_FILE_MB} МБ — нужен меньший битрейт/качество")

    meta = await asyncio.to_thread(probe, part_path)
    if not meta.get("has_video"):
        try:
            part_path.unlink()
        except OSError:
            pass
        raise DownloadError("в файле нет видеопотока")

    os.replace(part_path, video_path)
    final_size = video_path.stat().st_size
    duration_value = float(meta.get("duration") or duration or 0)
    thumb = await make_thumbnail(video_path, thumb_path, duration_value)
    if on_progress:
        await on_progress(ProgressState(stage="download", percent=100.0, downloaded=final_size,
                                        duration=duration_value, position=duration_value, extra="готово"))
    return MediaInfo(
        path=str(video_path), size=final_size, duration=duration_value,
        width=int(meta.get("width") or 0), height=int(meta.get("height") or 0),
        video_codec=str(meta.get("video_codec") or ""), audio_codec=str(meta.get("audio_codec") or ""),
        thumb_path=thumb, mode=mode, from_cache=False,
    )


__all__ = [
    "download_episode", "make_thumbnail", "probe", "hls_duration", "cleanup_cache",
    "directory_size", "episode_paths", "cache_key", "human_size", "human_time",
    "MediaInfo", "ProgressState", "DownloadError", "DownloadCancelled", "FileTooBig",
]
