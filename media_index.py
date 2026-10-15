# -*- coding: utf-8 -*-
"""
Индекс медиа: где лежит уже отправленная серия (канал-хранилище + file_id).

Схема работы:
    1. Первый запрос серии: бот скачивает её, отправляет в канал-хранилище
       (напр. -1004445072922) и запоминает id сообщения канала в RTDB и SQLite.
    2. Любой следующий запрос: бот копирует сообщение из канала в чат пользователя
       (copy_message — без пометки «переслано», пользователь не видит канал)
       и делает это моментально, без скачивания и без загрузки.

Ключ записи: "anime/media/{release_id}-{episode}-{quality}" (RTDB не разрешает точки
и слэши в ключах, поэтому разделитель — дефис).
"""

from __future__ import annotations

import logging
import time
from typing import Any, Dict, Optional

import config
import rtdb
import store

log = logging.getLogger("media")


def key_for(release_id: int | str, episode: int, quality: int) -> str:
    return f"{int(release_id)}-{int(episode)}-{int(quality)}"


def rtdb_path(release_id: int | str, episode: int, quality: int) -> str:
    return f"{config.RTDB_ROOT}/media/{key_for(release_id, episode, quality)}"


async def lookup(release_id: int | str, episode: int, quality: int,
                 storage: Optional[store.Storage] = None) -> Optional[Dict[str, Any]]:
    """Ищет запись о видео: сначала в RTDB, потом в локальном SQLite."""
    key = key_for(release_id, episode, quality)

    if config.RTDB_ENABLED:
        data = await rtdb.client().get(rtdb_path(release_id, episode, quality))
        if isinstance(data, dict) and data.get("message_id"):
            # подтягиваем запись и в локальный кэш, чтобы потом не ходить в сеть
            if storage is not None:
                try:
                    await storage.put_channel_media(
                        key,
                        int(data.get("channel_id") or config.TELEGRAM_CHANNEL_ID),
                        int(data["message_id"]),
                        str(data.get("file_id") or ""),
                        int(data.get("size") or 0),
                        float(data.get("duration") or 0),
                        int(data.get("width") or 0),
                        int(data.get("height") or 0),
                        str(data.get("title") or ""),
                        int(data.get("episode") or episode),
                        int(data.get("quality") or quality),
                        "rtdb",
                    )
                except Exception as exc:  # noqa: BLE001
                    log.debug("Не удалось закэшировать запись RTDB: %s", exc)
            data.setdefault("channel_id", config.TELEGRAM_CHANNEL_ID)
            return data

    if storage is not None:
        local = await storage.get_channel_media(key)
        if local:
            return local
    return None


async def remember(entry: Dict[str, Any], storage: Optional[store.Storage] = None) -> None:
    """Сохраняет запись о видео: локально и (best effort) в RTDB."""
    key = entry["key"]
    payload = {
        "channel_id": int(entry.get("channel_id") or config.TELEGRAM_CHANNEL_ID),
        "message_id": int(entry.get("message_id") or 0),
        "file_id": str(entry.get("file_id") or ""),
        "size": int(entry.get("size") or 0),
        "duration": float(entry.get("duration") or 0),
        "width": int(entry.get("width") or 0),
        "height": int(entry.get("height") or 0),
        "title": str(entry.get("title") or ""),
        "episode": int(entry.get("episode") or 0),
        "quality": int(entry.get("quality") or 0),
        "release_id": int(entry.get("release_id") or 0),
        "created_at": float(entry.get("created_at") or time.time()),
    }

    if storage is not None:
        try:
            await storage.put_channel_media(
                key, payload["channel_id"], payload["message_id"], payload["file_id"],
                payload["size"], payload["duration"], payload["width"], payload["height"],
                payload["title"], payload["episode"], payload["quality"], "local",
            )
        except Exception as exc:  # noqa: BLE001
            log.debug("Локальная запись медиа не удалась: %s", exc)

    if config.RTDB_ENABLED:
        # RTDB не принимает None внутри значений и требует примитивы
        clean = {k: v for k, v in payload.items() if v not in (None, "")}
        ok = await rtdb.client().put(
            f"{config.RTDB_ROOT}/media/{key}", clean
        )
        if ok:
            log.info("RTDB: запись %s сохранена (сообщение канала %s)", key, payload["message_id"])


async def forget(release_id: int | str, episode: int, quality: int,
                 storage: Optional[store.Storage] = None) -> None:
    """Убирает запись (например, если сообщение в канале удалили)."""
    key = key_for(release_id, episode, quality)
    if storage is not None:
        try:
            await storage.delete_channel_media(key)
        except Exception:  # noqa: BLE001
            pass
    if config.RTDB_ENABLED:
        await rtdb.client().delete(f"{config.RTDB_ROOT}/media/{key}")


__all__ = ["key_for", "rtdb_path", "lookup", "remember", "forget"]
