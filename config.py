# -*- coding: utf-8 -*-
"""
Конфигурация аниме-бота (AniLibria / AniLiberty API v1 + Telegram MTProto).

Все ключи вшиты прямо в код, как просили. Любое значение можно переопределить
переменными окружения (в docker-compose.yml / .env / панели хостинга) — код
подхватит ENV, а если ENV пусто, возьмёт вшитое значение.
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from api_keys import BY_ID, credentials_for, iter_credentials, random_credentials

# ---------------------------------------------------------------------------
# 100% ВШИТЫЕ КЛЮЧИ
# ---------------------------------------------------------------------------
# Токен бота (тот же токен, что и у Bot API, но используется по MTProto => лимит 2 ГБ)
BOT_TOKEN: str = os.environ.get("BOT_TOKEN") or "8877653509:AAHkFOL8Vj6aqICiOg9Xwpn9ONE92affKtc"

# api_id / api_hash: если в ENV не заданы — берём из вшитого списка (см. api_keys.py)
_ENV_API_ID = (os.environ.get("API_ID") or "").strip()
_ENV_API_HASH = (os.environ.get("API_HASH") or "").strip()

API_ID: int = int(_ENV_API_ID) if _ENV_API_ID.isdigit() else 2040  # Public Win Beta
API_HASH: str = _ENV_API_HASH or "b18441a1ff607e10a989891a5462e627"

# Умный роутинг по парам: если одна не пускает — берём следующую (см. credentials.py)
API_KEYS_ROTATION: bool = (os.environ.get("API_KEYS_ROTATION", "1") not in ("0", "false", "False"))
API_KEYS_RANDOMIZE: bool = (os.environ.get("API_KEYS_RANDOMIZE", "0") in ("1", "true", "True"))
# Сколько пар пробовать за один проход (0 = все пары из api_keys.py)
MAX_API_KEY_ATTEMPTS: int = int(os.environ.get("MAX_API_KEY_ATTEMPTS", "0") or 0)

# Пауза для пары после отказа — Telegram режет пары по-разному:
API_KEYS_PUBLISHED_COOLDOWN: int = int(os.environ.get("API_KEYS_PUBLISHED_COOLDOWN", str(6 * 3600)))
API_KEYS_AUTH_COOLDOWN: int = int(os.environ.get("API_KEYS_AUTH_COOLDOWN", str(12 * 3600)))
API_KEYS_FLOOD_MAX: int = int(os.environ.get("API_KEYS_FLOOD_MAX", "900"))
API_KEYS_NETWORK_COOLDOWN: int = int(os.environ.get("API_KEYS_NETWORK_COOLDOWN", "60"))
API_KEYS_UNKNOWN_COOLDOWN: int = int(os.environ.get("API_KEYS_UNKNOWN_COOLDOWN", "300"))
# Небольшая задержка между попытками, чтобы не устраивать залп запросов
API_KEYS_TRY_DELAY_MIN: float = float(os.environ.get("API_KEYS_TRY_DELAY_MIN", "1.0"))
API_KEYS_TRY_DELAY_MAX: float = float(os.environ.get("API_KEYS_TRY_DELAY_MAX", "2.5"))
# Если ни одна пара не пускает — бот НЕ выходит (иначе Railway перезапускает контейнер
# и устраивает шторм попыток). Он ждёт и пробует снова:
API_KEYS_RETRY_BASE: float = float(os.environ.get("API_KEYS_RETRY_BASE", "60"))
API_KEYS_RETRY_MAX: float = float(os.environ.get("API_KEYS_RETRY_MAX", "1800"))
# Как часто супервизор проверяет, что клиент ещё жив, сек
SUPERVISOR_TICK: float = float(os.environ.get("SUPERVISOR_TICK", "10"))


def credential_candidates() -> List[Tuple[int, str, str]]:
    """Список пар (api_id, api_hash, name) для попыток запуска клиента."""
    candidates: List[Tuple[int, str, str]] = []
    if _ENV_API_ID.isdigit() and _ENV_API_HASH:
        candidates.append((int(_ENV_API_ID), _ENV_API_HASH, "env"))
    elif API_KEYS_RANDOMIZE:
        candidates.append(random_credentials())
    elif API_ID and API_HASH:
        candidates.append((API_ID, API_HASH, str(BY_ID.get(API_ID, {}).get("name", "default"))))
    if API_KEYS_ROTATION:
        for pair in iter_credentials():          # личные пары идут первыми (см. api_keys.py)
            if pair[0] not in {c[0] for c in candidates}:
                candidates.append(pair)
    if not candidates:
        candidates.append((API_ID, API_HASH, "default"))
    # 0/отрицательное = отдаём весь список: дальше порядок решает умный роутинг
    # (credentials.CredentialRotator: рабочая пара → непроверенные → с паузой в конце)
    limit = MAX_API_KEY_ATTEMPTS if MAX_API_KEY_ATTEMPTS > 0 else len(candidates)
    return candidates[: max(1, limit)]


# Версия кода — чтобы в /stats и логах было видно, какая сборка реально запущена на сервере
CODE_VERSION: str = os.environ.get("CODE_VERSION") or "stable-download + smart-routing"
BUILD_SHA: str = (os.environ.get("RAILWAY_GIT_COMMIT_SHA") or os.environ.get("GIT_COMMIT") or "")[:7]


# ---------------------------------------------------------------------------
# Владелец / доступ
# ---------------------------------------------------------------------------
def _int_env(name: str, default: int = 0) -> int:
    raw = (os.environ.get(name) or "").strip()
    try:
        return int(raw) if raw else default
    except ValueError:
        return default


OWNER_ID: int = _int_env("OWNER_ID", 0)              # 0 = владелец не задан (статистика только для себя)
ALLOWED_USERS: List[int] = [
    int(x) for x in (os.environ.get("ALLOWED_USERS") or "").replace(" ", "").split(",") if x.strip().isdigit()
]
PUBLIC_BOT: bool = (os.environ.get("PUBLIC_BOT", "1") not in ("0", "false", "False"))  # доступ всем

# ---------------------------------------------------------------------------
# Пути (в докере смонтирован том на /data)
# ---------------------------------------------------------------------------
BASE_DIR: Path = Path(__file__).resolve().parent
DATA_DIR: Path = Path(os.environ.get("DATA_DIR") or (BASE_DIR / "data"))
DOWNLOAD_DIR: Path = Path(os.environ.get("DOWNLOAD_DIR") or (DATA_DIR / "cache"))
SESSION_DIR: Path = Path(os.environ.get("SESSION_DIR") or (DATA_DIR / "sessions"))
DB_PATH: Path = Path(os.environ.get("DB_PATH") or (DATA_DIR / "anime_bot.sqlite3"))


def ensure_dirs() -> None:
    for path in (DATA_DIR, DOWNLOAD_DIR, SESSION_DIR):
        Path(path).mkdir(parents=True, exist_ok=True)


# ---------------------------------------------------------------------------
# Файлы и лимиты (MTProto: до 2000 МБ на файл)
# ---------------------------------------------------------------------------
MAX_FILE_MB: int = _int_env("MAX_FILE_MB", 2000)               # предел Telegram для MTProto
MAX_FILE_BYTES: int = MAX_FILE_MB * 1024 * 1024
UPLOAD_AS_VIDEO_LIMIT_MB: int = _int_env("UPLOAD_AS_VIDEO_LIMIT_MB", 2000)
MAX_CACHE_GB: float = float(os.environ.get("MAX_CACHE_GB", "25"))     # сколько держать скачанного на диске
MAX_CACHE_BYTES: int = int(MAX_CACHE_GB * 1024 ** 3)
# Удалять собранный mp4/превью/сегменты сразу после успешной отправки в Telegram.
# Экономит диск (важно при 512 МБ хранилища): повторные запросы всё равно мгновенные,
# потому что file_id уже в базе, а сам файл хранит Telegram.
DELETE_AFTER_SEND: bool = (os.environ.get("DELETE_AFTER_SEND", "1") not in ("0", "false", "False"))
CONCURRENT_DOWNLOADS: int = _int_env("CONCURRENT_DOWNLOADS", 2)       # одновременных ffmpeg-задач
CONCURRENT_UPLOADS: int = _int_env("CONCURRENT_UPLOADS", 2)           # одновременных выгрузок в TG
QUEUE_TIMEOUT: int = _int_env("QUEUE_TIMEOUT", 1800)                  # ожидание в очереди, сек

# ---------------------------------------------------------------------------
# AniLibria / AniLiberty API v1
# ---------------------------------------------------------------------------
ANILIBRIA_MIRRORS: List[str] = [
    u.strip().rstrip("/")
    for u in (os.environ.get("ANILIBRIA_MIRRORS")
              or "https://anilibria.top,https://api.anilibria.app").split(",")
    if u.strip()
]
ANILIBRIA_SITE: str = os.environ.get("ANILIBRIA_SITE") or ANILIBRIA_MIRRORS[0]
HTTP_TIMEOUT: int = _int_env("HTTP_TIMEOUT", 30)
HTTP_RETRIES: int = _int_env("HTTP_RETRIES", 3)
SEARCH_LIMIT: int = _int_env("SEARCH_LIMIT", 20)          # сколько находок показывать в чате
INLINE_LIMIT: int = _int_env("INLINE_LIMIT", 30)          # сколько результатов в inline-поиске
DEFAULT_QUALITY: int = _int_env("DEFAULT_QUALITY", 1080)  # 1080 / 720 / 480
QUALITY_CHOICES: List[int] = [int(q) for q in (os.environ.get("QUALITY_CHOICES") or "1080,720,480").split(",") if q.strip()]

# ---------------------------------------------------------------------------
# ffmpeg / ffprobe
# ---------------------------------------------------------------------------
FFMPEG: str = os.environ.get("FFMPEG") or "ffmpeg"
FFPROBE: str = os.environ.get("FFPROBE") or "ffprobe"
FFMPEG_THREADS: int = _int_env("FFMPEG_THREADS", 0)            # 0 = авто
DOWNLOAD_USER_AGENT: str = os.environ.get("DOWNLOAD_USER_AGENT") or (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)
DOWNLOAD_REFERER: str = os.environ.get("DOWNLOAD_REFERER") or (ANILIBRIA_SITE + "/")
FFMPEG_RW_TIMEOUT_US: int = _int_env("FFMPEG_RW_TIMEOUT_US", 30_000_000)

# --- скорость скачивания HLS -------------------------------------------------
# Качаем сегменты m3u8 параллельно (одна связь к CDN AniLibria отдаёт всего
# несколько сотен КБ/с — именно это и тормозило выдачу серий).
HLS_PARALLEL: bool = (os.environ.get("HLS_PARALLEL", "1") not in ("0", "false", "False"))
HLS_CONNECTIONS: int = _int_env("HLS_CONNECTIONS", 8)          # потолок одновременных соединений к CDN
HLS_START_CONNECTIONS: int = _int_env("HLS_START_CONNECTIONS", 3)   # с чего начинаем (защита от 429)
HLS_RAMP_AFTER: int = _int_env("HLS_RAMP_AFTER", 12)           # +1 соединение за столько успешных сегментов
HLS_JITTER: float = float(os.environ.get("HLS_JITTER", "0.35"))     # разброс старта запросов, сек
HLS_SEGMENT_RETRIES: int = _int_env("HLS_SEGMENT_RETRIES", 4)  # повторы на сетевые сбои
HLS_MAX_RATE_HITS: int = _int_env("HLS_MAX_RATE_HITS", 40)     # сколько лимитов CDN терпим за загрузку
HLS_MAX_FORBIDDEN: int = _int_env("HLS_MAX_FORBIDDEN", 6)      # сколько 403 терпим (гео/бан IP)
HLS_BACKOFF_BASE: float = float(os.environ.get("HLS_BACKOFF_BASE", "2.0"))
HLS_RETRY_AFTER_MAX: int = _int_env("HLS_RETRY_AFTER_MAX", 180)     # дольше столько секунд не ждём
HLS_MAX_TOTAL_WAIT: int = _int_env("HLS_MAX_TOTAL_WAIT", 900)       # суммарное ожидание лимитов
HLS_LONG_PAUSE: float = float(os.environ.get("HLS_LONG_PAUSE", "20.0"))  # пауза перед докачкой
THUMBNAIL_MAX_BYTES: int = 190 * 1024                           # Telegram: превью ≤ 200 КБ
THUMBNAIL_WIDTH: int = _int_env("THUMBNAIL_WIDTH", 320)

# ---------------------------------------------------------------------------
# Интерфейс / поведение бота
# ---------------------------------------------------------------------------
PROGRESS_INTERVAL: float = float(os.environ.get("PROGRESS_INTERVAL", "4.0"))     # частота правок прогресса
EPISODES_PER_PAGE: int = _int_env("EPISODES_PER_PAGE", 12)
TRY_LOWER_QUALITY_ON_TOOBIG: bool = (os.environ.get("TRY_LOWER_QUALITY_ON_TOOBIG", "1") not in ("0", "false", "False"))
SEND_AS_DOCUMENT: bool = (os.environ.get("SEND_AS_DOCUMENT", "0") in ("1", "true", "True"))
ENABLE_FILE_INTAKE: bool = (os.environ.get("ENABLE_FILE_INTAKE", "1") not in ("0", "false", "False"))
KEEP_INCOMING_FILES: bool = (os.environ.get("KEEP_INCOMING_FILES", "0") in ("1", "true", "True"))  # качать входящие на диск
LOG_LEVEL: str = (os.environ.get("LOG_LEVEL") or "INFO").upper()

# Прокси (если хостинг/провайдер блокирует Telegram или AniLibria)
# пример: TG_PROXY=socks5://user:pass@host:1080  ANILIBRIA_PROXY=http://host:3128
TG_PROXY: Optional[str] = (os.environ.get("TG_PROXY") or "").strip() or None
ANILIBRIA_PROXY: Optional[str] = (os.environ.get("ANILIBRIA_PROXY") or "").strip() or None


def pyrogram_proxy() -> Optional[dict]:
    """Парсит TG_PROXY в формат, который понимает Pyrogram."""
    raw = TG_PROXY
    if not raw:
        return None
    try:
        scheme, rest = raw.split("://", 1)
        auth, hostport = (rest.rsplit("@", 1) if "@" in rest else (None, rest))
        host, _, port = hostport.partition(":")
        proxy: dict = {"scheme": scheme.lower(), "hostname": host, "port": int(port or 1080)}
        if auth:
            user, _, pwd = auth.partition(":")
            proxy["username"], proxy["password"] = user, pwd
        return proxy
    except Exception:
        return None


USER_AGENT_APP: str = os.environ.get("USER_AGENT_APP") or "AnimeLibriaMTProto/1.0"

# ---------------------------------------------------------------------------
# Firebase Realtime Database (вшитый конфиг проекта) + канал-хранилище видео
# ---------------------------------------------------------------------------
FIREBASE_CONFIG: Dict[str, str] = {
    "apiKey": "AIzaSyBm0mIvHVznIeF2PoFk6dtdaiT5r877wyA",
    "authDomain": "meow-874ce.firebaseapp.com",
    "databaseURL": "https://meow-874ce-default-rtdb.europe-west1.firebasedatabase.app",
    "projectId": "meow-874ce",
    "storageBucket": "meow-874ce.firebasestorage.app",
    "messagingSenderId": "471541334599",
    "appId": "1:471541334599:web:567af3e7dbe70a37572762",
}

# RTDB: пишем/читаем id видео по REST (apiKey для RTDB не нужен, но оставлен выше для полноты)
RTDB_ENABLED: bool = (os.environ.get("RTDB_ENABLED", "1") not in ("0", "false", "False"))
RTDB_URL: str = os.environ.get("RTDB_URL") or FIREBASE_CONFIG["databaseURL"]
RTDB_AUTH: str = (os.environ.get("RTDB_AUTH") or "").strip()   # database secret или ID-токен (если правила закрыты)
RTDB_ROOT: str = (os.environ.get("RTDB_ROOT") or "anime").strip("/")

# Канал-хранилище: ссылка https://t.me/c/4445072922/42 => внутренний id 4445072922,
# полный id супергруппы/канала = -100 + 4445072922 = -1004445072922
TELEGRAM_CHANNEL_ID: int = _int_env("TELEGRAM_CHANNEL_ID", -1004445072922)
TELEGRAM_CHANNEL_LINK: str = os.environ.get("TELEGRAM_CHANNEL_LINK") or "https://t.me/c/4445072922/42"
# Отправлять каждую новую серию в канал и отдавать её людям копированием из канала
MIRROR_TO_CHANNEL: bool = (os.environ.get("MIRROR_TO_CHANNEL", "1") not in ("0", "false", "False"))


__all__ = [
    "BOT_TOKEN", "CODE_VERSION", "BUILD_SHA",
    "API_ID", "API_HASH", "credential_candidates",
    "API_KEYS_ROTATION", "API_KEYS_RANDOMIZE", "MAX_API_KEY_ATTEMPTS",
    "API_KEYS_PUBLISHED_COOLDOWN", "API_KEYS_AUTH_COOLDOWN", "API_KEYS_FLOOD_MAX",
    "API_KEYS_NETWORK_COOLDOWN", "API_KEYS_UNKNOWN_COOLDOWN",
    "API_KEYS_TRY_DELAY_MIN", "API_KEYS_TRY_DELAY_MAX",
    "API_KEYS_RETRY_BASE", "API_KEYS_RETRY_MAX", "SUPERVISOR_TICK",
    "OWNER_ID", "ALLOWED_USERS", "PUBLIC_BOT",
    "BASE_DIR", "DATA_DIR", "DOWNLOAD_DIR", "SESSION_DIR", "DB_PATH", "ensure_dirs",
    "MAX_FILE_MB", "MAX_FILE_BYTES", "MAX_CACHE_BYTES", "CONCURRENT_DOWNLOADS",
    "CONCURRENT_UPLOADS", "QUEUE_TIMEOUT", "UPLOAD_AS_VIDEO_LIMIT_MB",
    "ANILIBRIA_MIRRORS", "ANILIBRIA_SITE", "HTTP_TIMEOUT", "HTTP_RETRIES",
    "SEARCH_LIMIT", "INLINE_LIMIT", "DEFAULT_QUALITY", "QUALITY_CHOICES",
    "FFMPEG", "FFPROBE", "FFMPEG_THREADS", "DOWNLOAD_USER_AGENT", "DOWNLOAD_REFERER",
    "FFMPEG_RW_TIMEOUT_US", "HLS_PARALLEL", "HLS_CONNECTIONS", "HLS_SEGMENT_RETRIES",
    "HLS_START_CONNECTIONS", "HLS_RAMP_AFTER", "HLS_FAST_RAMP_AFTER", "HLS_JITTER", "HLS_MAX_RATE_HITS",
    "DELETE_AFTER_SEND",
    "HLS_MAX_FORBIDDEN", "HLS_BACKOFF_BASE", "HLS_RETRY_AFTER_MAX", "HLS_MAX_TOTAL_WAIT",
    "HLS_LONG_PAUSE", "THUMBNAIL_MAX_BYTES", "THUMBNAIL_WIDTH",
    "PROGRESS_INTERVAL", "EPISODES_PER_PAGE", "TRY_LOWER_QUALITY_ON_TOOBIG",
    "SEND_AS_DOCUMENT", "ENABLE_FILE_INTAKE", "KEEP_INCOMING_FILES", "LOG_LEVEL",
    "TG_PROXY", "ANILIBRIA_PROXY", "pyrogram_proxy", "USER_AGENT_APP", "credentials_for",
    "FIREBASE_CONFIG", "RTDB_ENABLED", "RTDB_URL", "RTDB_AUTH", "RTDB_ROOT",
    "TELEGRAM_CHANNEL_ID", "TELEGRAM_CHANNEL_LINK", "MIRROR_TO_CHANNEL",
]
