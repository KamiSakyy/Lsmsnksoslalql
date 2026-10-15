# -*- coding: utf-8 -*-
"""
Хранилище состояния бота (SQLite, асинхронные обёртки).

Что хранится:
    * users        — пользователи и их настройки (качество по умолчанию)
    * releases     — мини-кэш карточек релизов (для callback-кнопок по id)
    * favorites    — избранное
    * history      — что человек уже смотрел/качал
    * media_cache  — file_id уже отправленных видео (мгновенная повторная отправка, 2 ГБ без перекачки)
    * user_files   — файлы, которые пользователи прислали боту (до 2 ГБ)
    * stats        — счётчики
"""

from __future__ import annotations

import asyncio
import logging
import sqlite3
import threading
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

log = logging.getLogger("storage")

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    user_id     INTEGER PRIMARY KEY,
    username    TEXT,
    first_name  TEXT,
    quality     INTEGER DEFAULT 0,
    created_at  REAL,
    last_seen   REAL,
    downloads   INTEGER DEFAULT 0
);
CREATE TABLE IF NOT EXISTS releases (
    release_id      INTEGER PRIMARY KEY,
    alias           TEXT,
    title           TEXT,
    poster          TEXT,
    year            INTEGER,
    type            TEXT,
    episodes_total  INTEGER,
    updated_at      REAL
);
CREATE TABLE IF NOT EXISTS favorites (
    user_id     INTEGER,
    release_id  INTEGER,
    alias       TEXT,
    title       TEXT,
    poster      TEXT,
    added_at    REAL,
    PRIMARY KEY (user_id, release_id)
);
CREATE TABLE IF NOT EXISTS history (
    user_id     INTEGER,
    release_id  INTEGER,
    alias       TEXT,
    title       TEXT,
    episode     INTEGER,
    quality     INTEGER,
    at          REAL,
    PRIMARY KEY (user_id, release_id, episode, quality)
);
CREATE TABLE IF NOT EXISTS media_cache (
    key             TEXT PRIMARY KEY,
    file_id         TEXT,
    thumb_file_id   TEXT,
    size            INTEGER,
    duration        REAL,
    width           INTEGER,
    height          INTEGER,
    path            TEXT,
    release_id      INTEGER,
    episode         INTEGER,
    quality         INTEGER,
    created_at      REAL,
    hits            INTEGER DEFAULT 0
);
CREATE TABLE IF NOT EXISTS user_files (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER,
    file_id         TEXT,
    file_unique_id  TEXT,
    kind            TEXT,
    name            TEXT,
    size            INTEGER,
    mime            TEXT,
    duration        INTEGER,
    saved_path      TEXT,
    created_at      REAL
);
CREATE TABLE IF NOT EXISTS channel_media (
    key         TEXT PRIMARY KEY,
    channel_id  INTEGER,
    message_id  INTEGER,
    file_id     TEXT,
    size        INTEGER,
    duration    REAL,
    width       INTEGER,
    height      INTEGER,
    title       TEXT,
    episode     INTEGER,
    quality     INTEGER,
    source      TEXT,
    created_at  REAL
);
CREATE TABLE IF NOT EXISTS stats (
    key     TEXT PRIMARY KEY,
    value   INTEGER
);
CREATE INDEX IF NOT EXISTS idx_history_user ON history(user_id, at DESC);
CREATE INDEX IF NOT EXISTS idx_favorites_user ON favorites(user_id, added_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_files ON user_files(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_media_release ON media_cache(release_id, episode, quality);
"""


class Storage:
    def __init__(self, path: Path | str) -> None:
        self._path = str(path)
        self._conn: Optional[sqlite3.Connection] = None
        self._lock = threading.Lock()

    # -- низкий уровень -----------------------------------------------------
    def _connect(self) -> sqlite3.Connection:
        if self._conn is None:
            Path(self._path).parent.mkdir(parents=True, exist_ok=True)
            self._conn = sqlite3.connect(self._path, check_same_thread=False, timeout=30)
            self._conn.row_factory = sqlite3.Row
            self._conn.execute("PRAGMA journal_mode=WAL")
            self._conn.execute("PRAGMA synchronous=NORMAL")
        return self._conn

    def init(self) -> None:
        with self._lock:
            conn = self._connect()
            conn.executescript(SCHEMA)
            conn.commit()
        log.info("База готова: %s", self._path)

    def _exec(self, sql: str, params: tuple = (), fetch: str = "none") -> Any:
        with self._lock:
            conn = self._connect()
            cur = conn.execute(sql, params)
            if fetch == "one":
                row = cur.fetchone()
                return dict(row) if row else None
            if fetch == "all":
                return [dict(row) for row in cur.fetchall()]
            conn.commit()
            return cur.lastrowid

    async def _a(self, sql: str, params: tuple = (), fetch: str = "none") -> Any:
        return await asyncio.to_thread(self._exec, sql, params, fetch)

    # -- пользователи -------------------------------------------------------
    async def upsert_user(self, user_id: int, username: str = "", first_name: str = "") -> Dict[str, Any]:
        now = time.time()
        await self._a(
            """INSERT INTO users (user_id, username, first_name, created_at, last_seen)
               VALUES (?, ?, ?, ?, ?)
               ON CONFLICT(user_id) DO UPDATE SET
                 username = excluded.username,
                 first_name = excluded.first_name,
                 last_seen = excluded.last_seen""",
            (user_id, username or "", first_name or "", now, now),
        )
        user = await self.get_user(user_id)
        return user or {}

    async def get_user(self, user_id: int) -> Optional[Dict[str, Any]]:
        return await self._a("SELECT * FROM users WHERE user_id = ?", (user_id,), "one")

    async def set_quality(self, user_id: int, quality: int) -> None:
        await self._a("UPDATE users SET quality = ? WHERE user_id = ?", (quality, user_id))

    async def bump_downloads(self, user_id: int) -> None:
        await self._a("UPDATE users SET downloads = downloads + 1 WHERE user_id = ?", (user_id,))

    async def users_count(self) -> int:
        row = await self._a("SELECT COUNT(*) AS total FROM users", fetch="one")
        return int(row["total"]) if row else 0

    # -- релизы -------------------------------------------------------------
    async def save_release(self, release: Any) -> None:
        await self._a(
            """INSERT INTO releases (release_id, alias, title, poster, year, type, episodes_total, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(release_id) DO UPDATE SET
                 alias = excluded.alias, title = excluded.title, poster = excluded.poster,
                 year = excluded.year, type = excluded.type,
                 episodes_total = excluded.episodes_total, updated_at = excluded.updated_at""",
            (
                int(getattr(release, "id", 0) or 0),
                str(getattr(release, "alias", "") or ""),
                str(getattr(release, "title", "") or ""),
                str(getattr(release, "poster", "") or ""),
                int(getattr(release, "year", 0) or 0),
                str(getattr(release, "type_description", "") or getattr(release, "type_value", "") or ""),
                int(getattr(release, "episodes_total", 0) or 0),
                time.time(),
            ),
        )

    async def get_release(self, release_id: int) -> Optional[Dict[str, Any]]:
        return await self._a("SELECT * FROM releases WHERE release_id = ?", (int(release_id),), "one")

    # -- избранное ----------------------------------------------------------
    async def is_favorite(self, user_id: int, release_id: int) -> bool:
        row = await self._a(
            "SELECT 1 AS x FROM favorites WHERE user_id = ? AND release_id = ?",
            (user_id, int(release_id)), "one",
        )
        return bool(row)

    async def toggle_favorite(self, user_id: int, release: Any) -> bool:
        release_id = int(getattr(release, "id", 0) or 0)
        if await self.is_favorite(user_id, release_id):
            await self._a("DELETE FROM favorites WHERE user_id = ? AND release_id = ?", (user_id, release_id))
            return False
        await self._a(
            """INSERT OR REPLACE INTO favorites (user_id, release_id, alias, title, poster, added_at)
               VALUES (?, ?, ?, ?, ?, ?)""",
            (
                user_id, release_id,
                str(getattr(release, "alias", "") or ""),
                str(getattr(release, "title", "") or ""),
                str(getattr(release, "poster", "") or ""),
                time.time(),
            ),
        )
        return True

    async def favorites(self, user_id: int, limit: int = 10, offset: int = 0) -> List[Dict[str, Any]]:
        return await self._a(
            "SELECT * FROM favorites WHERE user_id = ? ORDER BY added_at DESC LIMIT ? OFFSET ?",
            (user_id, limit, offset), "all",
        )

    async def favorites_count(self, user_id: int) -> int:
        row = await self._a("SELECT COUNT(*) AS total FROM favorites WHERE user_id = ?", (user_id,), "one")
        return int(row["total"]) if row else 0

    # -- история ------------------------------------------------------------
    async def add_history(self, user_id: int, release_id: int, alias: str, title: str,
                          episode: int, quality: int) -> None:
        await self._a(
            """INSERT OR REPLACE INTO history (user_id, release_id, alias, title, episode, quality, at)
               VALUES (?, ?, ?, ?, ?, ?, ?)""",
            (user_id, int(release_id), alias, title, int(episode), int(quality), time.time()),
        )

    async def history(self, user_id: int, limit: int = 10, offset: int = 0) -> List[Dict[str, Any]]:
        return await self._a(
            "SELECT * FROM history WHERE user_id = ? ORDER BY at DESC LIMIT ? OFFSET ?",
            (user_id, limit, offset), "all",
        )

    async def history_count(self, user_id: int) -> int:
        row = await self._a("SELECT COUNT(*) AS total FROM history WHERE user_id = ?", (user_id,), "one")
        return int(row["total"]) if row else 0

    # -- кэш отправленных файлов (file_id) ----------------------------------
    async def cache_get(self, key: str) -> Optional[Dict[str, Any]]:
        return await self._a("SELECT * FROM media_cache WHERE key = ?", (key,), "one")

    async def cache_put(self, key: str, file_id: str, thumb_file_id: Optional[str], size: int,
                        duration: float, width: int, height: int, path: str,
                        release_id: int, episode: int, quality: int) -> None:
        await self._a(
            """INSERT OR REPLACE INTO media_cache
               (key, file_id, thumb_file_id, size, duration, width, height, path,
                release_id, episode, quality, created_at, hits)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE((SELECT hits FROM media_cache WHERE key = ?), 0))""",
            (key, file_id, thumb_file_id, int(size), float(duration), int(width), int(height), path,
             int(release_id), int(episode), int(quality), time.time(), key),
        )

    async def cache_hit(self, key: str) -> None:
        await self._a("UPDATE media_cache SET hits = hits + 1 WHERE key = ?", (key,))

    async def cache_stats(self) -> Dict[str, Any]:
        row = await self._a(
            "SELECT COUNT(*) AS files, COALESCE(SUM(size), 0) AS bytes, COALESCE(SUM(hits), 0) AS hits FROM media_cache",
            fetch="one",
        )
        return row or {"files": 0, "bytes": 0, "hits": 0}

    async def cache_drop_release(self, release_id: int) -> int:
        row = await self._a("SELECT COUNT(*) AS total FROM media_cache WHERE release_id = ?",
                            (int(release_id),), "one")
        await self._a("DELETE FROM media_cache WHERE release_id = ?", (int(release_id),))
        return int(row["total"]) if row else 0

    # -- файлы пользователей ------------------------------------------------
    async def add_user_file(self, user_id: int, file_id: str, file_unique_id: str, kind: str,
                            name: str, size: int, mime: str, duration: int = 0,
                            saved_path: Optional[str] = None) -> int:
        return int(await self._a(
            """INSERT INTO user_files
               (user_id, file_id, file_unique_id, kind, name, size, mime, duration, saved_path, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (user_id, file_id, file_unique_id, kind, name, int(size or 0), mime or "", int(duration or 0),
             saved_path, time.time()),
        ) or 0)

    async def user_files(self, user_id: int, limit: int = 10, offset: int = 0) -> List[Dict[str, Any]]:
        return await self._a(
            "SELECT * FROM user_files WHERE user_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
            (user_id, limit, offset), "all",
        )

    async def get_user_file(self, row_id: int) -> Optional[Dict[str, Any]]:
        return await self._a("SELECT * FROM user_files WHERE id = ?", (int(row_id),), "one")

    async def user_files_count(self, user_id: int) -> int:
        row = await self._a("SELECT COUNT(*) AS total FROM user_files WHERE user_id = ?", (user_id,), "one")
        return int(row["total"]) if row else 0

    async def delete_user_file(self, row_id: int, user_id: int) -> None:
        await self._a("DELETE FROM user_files WHERE id = ? AND user_id = ?", (int(row_id), user_id))

    async def set_user_file_path(self, row_id: int, path: str) -> None:
        await self._a("UPDATE user_files SET saved_path = ? WHERE id = ?", (str(path), int(row_id)))

    # -- канал-хранилище (медиа, уже отправленное в канал) -------------------
    async def put_channel_media(self, key: str, channel_id: int, message_id: int, file_id: str,
                                size: int, duration: float, width: int, height: int,
                                title: str, episode: int, quality: int, source: str = "local") -> None:
        await self._a(
            """INSERT OR REPLACE INTO channel_media
               (key, channel_id, message_id, file_id, size, duration, width, height,
                title, episode, quality, source, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (key, int(channel_id), int(message_id), file_id or "", int(size or 0), float(duration or 0),
             int(width or 0), int(height or 0), title or "", int(episode or 0), int(quality or 0),
             source, time.time()),
        )

    async def get_channel_media(self, key: str) -> Optional[Dict[str, Any]]:
        row = await self._a("SELECT * FROM channel_media WHERE key = ?", (key,), "one")
        if not row:
            return None
        row["channel_message_id"] = row.get("message_id")
        return row

    async def delete_channel_media(self, key: str) -> None:
        await self._a("DELETE FROM channel_media WHERE key = ?", (key,))

    async def channel_media_stats(self) -> Dict[str, Any]:
        row = await self._a(
            "SELECT COUNT(*) AS files, COALESCE(SUM(size), 0) AS bytes FROM channel_media", fetch="one"
        )
        return row or {"files": 0, "bytes": 0}

    # -- статистика ---------------------------------------------------------
    async def bump_stat(self, key: str, amount: int = 1) -> None:
        await self._a(
            """INSERT INTO stats (key, value) VALUES (?, ?)
               ON CONFLICT(key) DO UPDATE SET value = value + ?""",
            (key, int(amount), int(amount)),
        )

    async def stats(self) -> Dict[str, int]:
        rows = await self._a("SELECT key, value FROM stats", fetch="all")
        return {str(row["key"]): int(row["value"]) for row in rows}

    def close(self) -> None:
        with self._lock:
            if self._conn is not None:
                self._conn.commit()
                self._conn.close()
                self._conn = None


__all__ = ["Storage", "SCHEMA"]
