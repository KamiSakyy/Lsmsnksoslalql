# -*- coding: utf-8 -*-
"""
Движок скачивания: HLS (AniLibria/AniLiberty CDN) -> MP4 через ffmpeg, на сервере.

Ключевые моменты:
    * Скачивание идёт на сервер (не на устройство пользователя).
    * ГЛАВНЫЙ УСКОРИТЕЛЬ: сегменты HLS качаются ПАРАЛЛЕЛЬНО в N соединений
      (по умолчанию 16, см. HLS_CONNECTIONS). Одна связь к CDN AniLibria даёт
      ~200–500 КБ/с, поэтому старая схема «ffmpeg сам тянет поток» растягивала
      серию на десятки минут. Параллельная загрузка сегментов + локальный remux
      отдают файл за секунды-минуты.
    * Дальше — remux без перекодирования (-c copy): качество 1:1, без нагрузки на CPU.
      Если контейнер/кодеки не лезут в mp4 (редкие серии) — включается транскод
      (x264 veryfast + AAC), результат всегда пригоден для Telegram.
    * Прогресс берётся из скачанных сегментов (сумма EXTINF) и из ffmpeg
      (-progress pipe:1), поэтому прогресс-бар и скорость честные.
    * Если параллельная схема недоступна (экзотический плейлист, CDN режет много
      соединений) — автоматический откат на старый режим: ffmpeg тянет поток сам.
    * Отмена — убиваем процесс ffmpeg и подчищаем .part вместе с сегментами.
    * Кэш файлов на диске + LRU-чистка, чтобы хостинг не забивался.
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import os
import random
import re
import shutil
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Awaitable, Callable, Dict, List, Optional, Tuple
from urllib.parse import urljoin

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


class StreamExpired(DownloadError):
    """Ссылка на поток устарела или закрыта (401/403 на плейлисте) — нужна свежая из API."""


class RateLimited(DownloadError):
    """CDN AniLibria ограничил запросы (429/403) — сработала защита от всплеска."""


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


def purge_episode_files(release_id: int | str, episode: int, quality: int) -> int:
    """Удаляет с диска всё по серии (mp4, превью, сегменты) после успешной отправки.

    Возвращает, сколько байт освободилось; пустая папка релиза тоже удаляется.
    file_id при этом остаётся в SQLite, поэтому повторная выдача серии мгновенная
    и диск для неё больше не нужен.
    """
    paths = episode_paths(release_id, episode, quality)
    freed = 0
    for path in (paths["video"], paths["thumb"], paths["part"]):
        try:
            if path.exists():
                freed += path.stat().st_size
                path.unlink()
        except OSError:
            continue
    seg_dir = paths["dir"] / (paths["video"].stem + ".segments")
    if seg_dir.exists():
        freed += directory_size(seg_dir)
        shutil.rmtree(seg_dir, ignore_errors=True)
    # остатки от прерванных задач: недокачанные куски и папки сегментов
    for leftover in list(paths["dir"].glob("*.part*")) + list(paths["dir"].glob("*.segments")):
        try:
            if leftover.is_dir():
                freed += directory_size(leftover)
                shutil.rmtree(leftover, ignore_errors=True)
            else:
                freed += leftover.stat().st_size
                leftover.unlink()
        except OSError:
            continue
    try:
        if not any(paths["dir"].iterdir()):
            paths["dir"].rmdir()
    except OSError:
        pass
    return freed


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


def _base_cmd(url: str, local: bool = False) -> list[str]:
    """Аргументы до -i. local=True — читаем локальный плейлист (сеть уже не нужна)."""
    if local:
        cmd = [
            ffmpeg_bin(), "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
            "-protocol_whitelist", "file,http,https,tcp,tls,crypto,data,httpproxy",
            "-allowed_extensions", "ALL",
            "-i", url,
        ]
    else:
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


def build_modes(url: str, out_path: str, local: bool = False) -> list[tuple[str, list[str]]]:
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
        ("copy", _base_cmd(url, local) + [
            "-map", "0:v:0?", "-map", "0:a:0?",
            "-c", "copy", "-bsf:a", "aac_adtstoasc", "-max_muxing_queue_size", "4096",
        ] + wrap),
        # 2. Видео как есть, звук в AAC (mp4 не всегда понимает AC-3/E-AC-3/MP2 из HLS)
        ("audio-aac", _base_cmd(url, local) + [
            "-map", "0:v:0?", "-map", "0:a:0?",
            "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-ac", "2",
            "-max_muxing_queue_size", "4096",
        ] + wrap),
        # 3. Полный транскод (HEVC/AV1/экзотика) — медленно, но всегда играбельно
        ("encode", _base_cmd(url, local) + [
            "-map", "0:v:0?", "-map", "0:a:0?",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "192k", "-ac", "2",
        ] + wrap),
        # 4. Совсем резерв: без faststart (если хостинг режет перезапись файла)
        ("encode-flat", _base_cmd(url, local) + [
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
    local: bool = False,
) -> str:
    """Прогоняет ffmpeg по цепочке стратегий, возвращает имя удачного режима."""
    last_error = "неизвестная ошибка"
    for mode, cmd in build_modes(str(url), str(out_path), local):
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
    log.debug("ffmpeg: %s", " ".join(str(part) for part in cmd))
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


async def _cleanup_tmp(part_path: Path, seg_dir: Path, keep_segments: bool = True) -> None:
    """Убирает недокачанный mp4; сегменты по умолчанию сохраняем — это задел для докачки."""
    try:
        Path(part_path).unlink(missing_ok=True)
    except OSError:
        pass
    if not keep_segments:
        await asyncio.to_thread(shutil.rmtree, seg_dir, True)


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
# ПАРАЛЛЕЛЬНОЕ СКАЧИВАНИЕ HLS  (главное ускорение: N соединений вместо одного)
# ---------------------------------------------------------------------------
_FORBIDDEN_CHARS = re.compile(r"[^A-Za-z0-9_.\-]")


@dataclass
class HlsPlaylist:
    segments: List[Tuple[str, float]] = field(default_factory=list)   # (url, длительность)
    variants: List[Tuple[str, int]] = field(default_factory=list)     # (url, bandwidth)
    init_url: Optional[str] = None
    key_lines: List[str] = field(default_factory=list)
    has_byterange: bool = False

    @property
    def duration(self) -> float:
        return sum(item[1] for item in self.segments)


def parse_playlist(text: str, base_url: str) -> HlsPlaylist:
    """Разбирает m3u8 (и master, и медиаплейлист), приводя ссылки к абсолютным."""
    playlist = HlsPlaylist()
    expect_variant = False
    pending_duration = 0.0
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("#EXT-X-STREAM-INF"):
            expect_variant = True
            match = re.search(r"BANDWIDTH=(\d+)", line)
            playlist.variants.append(("", int(match.group(1)) if match else 0))
        elif line.startswith("#EXTINF:"):
            try:
                pending_duration = float(line[len("#EXTINF:"):].split(",")[0])
            except ValueError:
                pending_duration = 0.0
        elif line.startswith("#EXT-X-MAP"):
            match = re.search(r'URI="([^"]+)"', line)
            if match:
                playlist.init_url = urljoin(base_url, match.group(1))
        elif line.startswith("#EXT-X-KEY"):
            playlist.key_lines.append(
                re.sub(r'URI="([^"]+)"', lambda m: 'URI="%s"' % urljoin(base_url, m.group(1)), line)
            )
        elif line.startswith("#EXT-X-BYTERANGE"):
            playlist.has_byterange = True
        elif not line.startswith("#"):
            absolute = urljoin(base_url, line)
            if expect_variant:
                expect_variant = False
                if playlist.variants:
                    playlist.variants[-1] = (absolute, playlist.variants[-1][1])
            else:
                playlist.segments.append((absolute, pending_duration))
                pending_duration = 0.0
    return playlist


def pick_variant(playlist: HlsPlaylist) -> Optional[str]:
    """Из master-плейлиста берём вариант с максимальным битрейтом."""
    real = [(url, bw) for url, bw in playlist.variants if url]
    if not real:
        return None
    return max(real, key=lambda item: item[1])[0]


def _segment_ext(url: str, default: str = ".ts") -> str:
    suffix = Path(url.split("?")[0]).suffix
    if suffix and 1 < len(suffix) <= 5 and _FORBIDDEN_CHARS.search(suffix) is None:
        return suffix
    return default


def _http_timeout() -> aiohttp.ClientTimeout:
    return aiohttp.ClientTimeout(total=None, connect=20, sock_read=60)


def _http_headers() -> Dict[str, str]:
    return {
        "User-Agent": config.DOWNLOAD_USER_AGENT,
        "Referer": config.DOWNLOAD_REFERER,
        "Accept": "*/*",
    }


async def _http_text(session: aiohttp.ClientSession, url: str) -> str:
    async with session.get(url, proxy=config.ANILIBRIA_PROXY) as response:
        if response.status in (401, 403):
            raise StreamExpired(f"HTTP {response.status}: ссылка на поток устарела или закрыта")
        if response.status >= 400:
            raise DownloadError(f"HTTP {response.status} на {url}")
        return await response.text()


def _retry_after_seconds(headers: Any) -> Optional[float]:
    value = headers.get("Retry-After")
    if not value:
        return None
    try:
        return max(0.0, float(str(value).strip()))
    except ValueError:
        return None


class AdaptiveLimiter:
    """Ограничитель одновременных запросов к CDN, общий на весь процесс.

    Зачем: одна связь к cache.libria.fun отдаёт ~200-500 КБ/с (серия «ехала» десятки
    минут), а 16 соединений залпом — это 429 от CDN (серия обрывалась на 33%).
    Поэтому: стартуем с нескольких соединений, плавно разгоняемся при успехах,
    при давлении CDN — общая пауза и мгновенное уменьшение числа потоков.
    """

    def __init__(self, start: int, maximum: int) -> None:
        self.maximum = max(1, int(maximum))
        self.limit = max(1, min(int(start), self.maximum))
        self.active = 0
        self._successes = 0
        self._cooldown_until = 0.0
        self._condition = asyncio.Condition()

    async def acquire(self) -> None:
        async with self._condition:
            while self.active >= self.limit:
                await self._condition.wait()
            self.active += 1

    async def release(self) -> None:
        async with self._condition:
            self.active = max(0, self.active - 1)
            self._condition.notify(1)

    def set_limit(self, value: int) -> None:
        self.limit = max(1, min(int(value), self.maximum))
        self._successes = 0

    def note_success(self) -> None:
        """После серии успешных сегментов осторожно добавляем ещё одно соединение."""
        self._successes += 1
        if self._successes >= config.HLS_RAMP_AFTER and self.limit < self.maximum:
            self.limit += 1
            self._successes = 0

    def note_pressure(self, delay: float) -> None:
        """CDN тормозит: пауза для всех воркеров + вдвое меньше соединений."""
        self._cooldown_until = max(self._cooldown_until, time.time() + max(0.5, float(delay)))
        self.set_limit(max(1, self.limit // 2))

    async def wait_if_paused(self) -> None:
        while True:
            delay = self._cooldown_until - time.time()
            if delay <= 0:
                return
            await asyncio.sleep(min(delay, 5.0))

    @property
    def cooldown_left(self) -> float:
        return max(0.0, self._cooldown_until - time.time())


_GLOBAL_LIMITER: Optional[AdaptiveLimiter] = None


def global_limiter() -> AdaptiveLimiter:
    """Один лимитер на процесс: две одновременные задачи не дадут двойной всплеск."""
    global _GLOBAL_LIMITER
    if _GLOBAL_LIMITER is None or _GLOBAL_LIMITER.maximum != max(1, config.HLS_CONNECTIONS):
        _GLOBAL_LIMITER = AdaptiveLimiter(config.HLS_START_CONNECTIONS, config.HLS_CONNECTIONS)
    return _GLOBAL_LIMITER


# Статусы, на которые имеет смысл подождать и повторить, а не падать
RETRYABLE_STATUSES = {408, 425, 429, 500, 502, 503, 504, 520, 522, 524}
PRESSURE_STATUSES = {403, 429}


async def _fetch_segment(
    session: aiohttp.ClientSession,
    limiter: AdaptiveLimiter,
    url: str,
    path: Path,
    cancel_event: Optional[asyncio.Event],
    stats: Dict[str, float],
) -> int:
    """Качает один сегмент: докачка, учёт давления CDN, пауза по Retry-After."""
    part = path.with_name(path.name + ".part")
    if path.exists() and path.stat().st_size > 0:
        return path.stat().st_size  # уже скачан раньше — файлы серии переиспользуются

    network_errors = 0
    while True:
        if cancel_event is not None and cancel_event.is_set():
            part.unlink(missing_ok=True)
            raise DownloadCancelled()

        await limiter.wait_if_paused()
        await limiter.acquire()
        try:
            if config.HLS_JITTER:
                # не бьём залпом: CDN видит разнесённые по времени запросы
                await asyncio.sleep(random.uniform(0, config.HLS_JITTER))
            stats["requests"] = stats.get("requests", 0) + 1
            async with session.get(url, proxy=config.ANILIBRIA_PROXY) as response:
                status = response.status
                if status in PRESSURE_STATUSES or status in RETRYABLE_STATUSES:
                    retry_after = _retry_after_seconds(response.headers)
                    if status == 403:
                        stats["forbidden"] = stats.get("forbidden", 0) + 1
                        if stats["forbidden"] > config.HLS_MAX_FORBIDDEN:
                            raise RateLimited(
                                "CDN отвечает 403 — похоже на блокировку IP или гео-ограничение"
                            )
                    else:
                        stats["rate_hits"] = stats.get("rate_hits", 0) + 1
                    hits = stats.get("rate_hits", 0) + stats.get("forbidden", 0)
                    if hits > config.HLS_MAX_RATE_HITS:
                        raise RateLimited(f"CDN держит лимит запросов (сработал {int(hits)} раз)")
                    delay = retry_after if retry_after is not None else min(
                        config.HLS_BACKOFF_BASE * (2 ** min(int(hits), 6)), 60.0
                    )
                    if delay > config.HLS_RETRY_AFTER_MAX:
                        raise RateLimited(f"CDN просит подождать {delay:.0f} с — это слишком долго")
                    stats["waited"] = stats.get("waited", 0.0) + delay
                    if stats["waited"] > config.HLS_MAX_TOTAL_WAIT:
                        raise RateLimited("ожидание лимитов CDN превысило допустимое")
                    limiter.note_pressure(delay)
                    log.warning(
                        "CDN AniLibria тормозит (%s): пауза %.1f с, соединений теперь %s",
                        status, delay, limiter.limit,
                    )
                    continue

                if status >= 400:
                    raise DownloadError(f"HTTP {status}")

                written = 0
                handle = open(part, "wb")
                try:
                    async for chunk in response.content.iter_chunked(256 * 1024):
                        if cancel_event is not None and cancel_event.is_set():
                            raise DownloadCancelled()
                        handle.write(chunk)
                        written += len(chunk)
                finally:
                    handle.close()
            if written <= 0:
                raise DownloadError("пустой сегмент")
            part.replace(path)
            limiter.note_success()
            return written
        except (DownloadCancelled, RateLimited, DownloadError):
            part.unlink(missing_ok=True)
            raise
        except Exception as exc:  # noqa: BLE001 - сеть/таймауты: повторяем
            network_errors += 1
            part.unlink(missing_ok=True)
            if network_errors > config.HLS_SEGMENT_RETRIES:
                raise DownloadError(f"сегмент не скачался ({exc})")
            await asyncio.sleep(min(0.6 * network_errors, 4.0) + random.uniform(0, 0.4))
        finally:
            await limiter.release()


async def parallel_fetch(
    playlist_url: str,
    work_dir: Path,
    on_progress: ProgressCb = None,
    cancel_event: Optional[asyncio.Event] = None,
    expected_duration: float = 0.0,
) -> Tuple[Path, float]:
    """Скачивает все сегменты HLS (адаптивно, параллельно) и собирает локальный плейлист.

    Возвращает (путь к локальному m3u8, длительность в секундах).
    Уже скачанные сегменты не перекачиваются — оборванная загрузка продолжается с места.
    """
    work_dir = Path(work_dir)
    work_dir.mkdir(parents=True, exist_ok=True)
    limiter = global_limiter()
    connector = aiohttp.TCPConnector(
        limit=max(4, config.HLS_CONNECTIONS + 4),
        limit_per_host=max(4, config.HLS_CONNECTIONS + 2),
        ttl_dns_cache=300,
        enable_cleanup_closed=True,
    )

    async with aiohttp.ClientSession(
        headers=_http_headers(), timeout=_http_timeout(), connector=connector
    ) as session:
        # 1) плейлист (если master — спускаемся к медиаплейлисту)
        playlist = HlsPlaylist()
        current_url = playlist_url
        for _hop in range(3):
            text = await _http_text(session, current_url)
            playlist = parse_playlist(text, current_url)
            if playlist.segments:
                break
            variant = pick_variant(playlist)
            if not variant:
                raise DownloadError("в HLS-плейлисте нет сегментов")
            current_url = variant
        if not playlist.segments:
            raise DownloadError("не удалось получить список сегментов HLS")

        total_duration = playlist.duration or expected_duration or 0.0

        # 2) init-сегмент (fMP4/CMAF), если он есть в плейлисте
        init_name: Optional[str] = None
        if playlist.init_url:
            init_name = "init" + _segment_ext(playlist.init_url, ".mp4")
            await _fetch_segment(session, limiter, playlist.init_url, work_dir / init_name,
                                 cancel_event, {"requests": 0})

        names = [f"seg_{index:05d}{_segment_ext(url)}" for index, (url, _dur) in enumerate(playlist.segments)]
        stats: Dict[str, float] = {"requests": 0, "rate_hits": 0, "forbidden": 0, "waited": 0.0}
        lock = asyncio.Lock()
        started = time.time()
        state = ProgressState(stage="download", duration=total_duration)
        counters = {"duration": 0.0, "bytes": 0, "last_push": 0.0}

        todo: List[int] = []
        for index, name in enumerate(names):
            existing = work_dir / name
            if existing.exists() and existing.stat().st_size > 0:
                counters["duration"] += playlist.segments[index][1]
                counters["bytes"] += existing.stat().st_size
            else:
                todo.append(index)
        if todo and len(todo) != len(names):
            log.info("Докачка: %s из %s сегментов уже на диске", len(names) - len(todo), len(names))

        async def push(force: bool = False) -> None:
            if on_progress is None:
                return
            now = time.time()
            if not force and now - counters["last_push"] < max(0.5, config.PROGRESS_INTERVAL / 2):
                return
            counters["last_push"] = now
            state.downloaded = int(counters["bytes"])
            state.position = counters["duration"]
            state.percent = (counters["duration"] / total_duration * 100) if total_duration else 0.0
            elapsed = max(now - started, 0.05)
            if counters["bytes"]:
                state.speed = f"{human_size(counters['bytes'] / elapsed)}/с"
            if cancel_event is not None and cancel_event.is_set():
                return
            await on_progress(state.touch(stage="download"))

        async def worker(index: int) -> None:
            size = await _fetch_segment(
                session, limiter, playlist.segments[index][0], work_dir / names[index], cancel_event, stats
            )
            async with lock:
                counters["duration"] += playlist.segments[index][1]
                counters["bytes"] += size
            await push()

        if on_progress:
            await on_progress(state.touch(stage="download"))
        await push(force=True)

        try:
            await asyncio.gather(*(worker(index) for index in todo))
        except RateLimited as exc:
            # CDN упёрся в лимит: аккуратный режим «по одной связи» с паузами
            log.warning("Лимит CDN (%s) — докачиваю по одной связи", exc)
            limiter.set_limit(1)
            stats["rate_hits"] = stats["forbidden"] = 0
            stats["waited"] = 0.0
            await asyncio.sleep(min(5.0 + random.uniform(0, 3), 15.0))
            for index in todo:
                target = work_dir / names[index]
                if target.exists() and target.stat().st_size > 0:
                    continue
                await worker(index)

        missing = [name for name in names if not (work_dir / name).exists() or (work_dir / name).stat().st_size == 0]
        if missing:
            raise DownloadError(f"не докачано сегментов: {len(missing)}")

        # 3) локальный плейлист: ffmpeg читает его с диска, сеть уже не нужна
        lines: List[str] = ["#EXTM3U", "#EXT-X-VERSION:3"]
        longest = max((dur for _url, dur in playlist.segments), default=10)
        lines.append(f"#EXT-X-TARGETDURATION:{max(int(longest) + 1, 2)}")
        lines.append("#EXT-X-MEDIA-SEQUENCE:0")
        lines.extend(playlist.key_lines)
        if init_name:
            lines.append(f'#EXT-X-MAP:URI="{init_name}"')
        for (_url, dur), segment_name in zip(playlist.segments, names):
            lines.append(f"#EXTINF:{dur:.3f},")
            lines.append(segment_name)
        lines.append("#EXT-X-ENDLIST")

        local_playlist = work_dir / "local.m3u8"
        local_playlist.write_text("\n".join(lines) + "\n", encoding="utf-8")

        # подчищаем недокачанные куски, чтобы они не мешали следующему запуску
        for stale in work_dir.glob("*.part"):
            stale.unlink(missing_ok=True)

        await push(force=True)
        log.info(
            "Сегменты: %s шт, запросов %s, лимитов CDN %s, пауз %.1f с, соединений на финише %s",
            len(names), int(stats["requests"]), int(stats["rate_hits"] + stats["forbidden"]),
            stats["waited"], limiter.limit,
        )
        return local_playlist, total_duration


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

    # --- быстрое скачивание: сегменты параллельно, затем локальный remux -----
    seg_dir: Path = paths["dir"] / (video_path.stem + ".segments")
    local_playlist: Optional[Path] = None
    duration = 0.0

    rate_limited: Optional[DownloadError] = None
    if config.HLS_PARALLEL:
        for attempt in range(2):
            try:
                local_playlist, duration = await parallel_fetch(
                    hls_url, seg_dir, on_progress=on_progress, cancel_event=cancel_event
                )
                break
            except DownloadCancelled:
                await _cleanup_tmp(part_path, seg_dir, keep_segments=True)
                raise
            except RateLimited as exc:
                if attempt == 0:
                    pause = float(config.HLS_LONG_PAUSE)
                    log.warning("CDN ограничил загрузку (%s) — пауза %.0f с и докачка остатка", exc, pause)
                    if on_progress:
                        await on_progress(ProgressState(
                            stage="download", duration=duration,
                            extra=f"CDN ограничил скорость — пауза {pause:.0f} с, потом докачаю остаток",
                        ))
                    await asyncio.sleep(pause)
                    continue
                rate_limited = exc
                local_playlist = None
                break
            except Exception as exc:  # noqa: BLE001 - падать не нужно, есть резервный путь
                log.warning("Параллельная загрузка не удалась (%s) — перехожу на прямое чтение потока ffmpeg", exc)
                local_playlist = None
                break

    try:
        if local_playlist is not None:
            try:
                mode = await run_ffmpeg(local_playlist, part_path, on_progress, cancel_event, duration, local=True)
            except DownloadError as exc:
                log.warning("Локальный remux не удался (%s) — пробую напрямую из сети", exc)
                local_playlist = None
        if local_playlist is None:
            duration = duration or await hls_duration(hls_url)
            mode = await run_ffmpeg(hls_url, part_path, on_progress, cancel_event, duration)
    except DownloadCancelled:
        # mp4-недокачка удаляется, а готовые сегменты оставляем: вернёшься — докачается
        await _cleanup_tmp(part_path, seg_dir, keep_segments=True)
        raise
    except DownloadError as exc:
        if rate_limited is not None:
            # сетевой резерв тоже не прошёл — оставляем главную причину (лимит CDN)
            tail = str(exc)
            if len(tail) > 120:
                tail = tail[:117] + "…"
            raise RateLimited(f"{rate_limited} · резерв: {tail}") from exc
        raise
    else:
        # успех: собранный mp4 самодостаточен, сегменты больше не нужны
        await asyncio.to_thread(shutil.rmtree, seg_dir, True)

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
    "purge_episode_files",
    "parallel_fetch", "parse_playlist", "pick_variant", "HlsPlaylist",
    "AdaptiveLimiter", "global_limiter", "RateLimited", "StreamExpired",
]
