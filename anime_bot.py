# -*- coding: utf-8 -*-
"""
Аниме-бот «AniLibria → Telegram» на MTProto (Pyrogram), лимит файлов 2000 МБ (2 ГБ).

Почему MTProto, а не Bot API:
    Telegram Bot API (api.telegram.org) режет файлы: скачивание 20 МБ, отправка 50 МБ.
    Этот бот авторизуется тем же bot_token, но по MTProto — как userbot, поэтому
    принимает и отправляет файлы до 2000 МБ.

Что умеет:
    • Инлайн-поиск: @бот наруто → карточки тайтлов с постерами
    • Карточка релиза, список серий по страницам, выбор качества 1080/720/480
    • Сервер сам качает HLS с публичного API AniLibria v1 и собирает MP4 (ffmpeg)
    • Прогресс-бар, отмена, очередь задач, «смотреть подряд»
    • Кэш file_id: повторная отправка серии — мгновенно, без скачивания
    • Приём файлов от пользователей до 2 ГБ и отдача их обратно

Запуск: python anime_bot.py            (бот)
        python anime_bot.py --check    (проверка окружения без запуска бота)
"""

from __future__ import annotations

import asyncio
import logging
import os
import random
import re
import sys
import time
import uuid
from typing import Any, Dict, List, Optional

from pyrogram import Client, filters
from pyrogram.enums import ChatAction, ParseMode
from pyrogram.errors import (
    FloodWait,
    MessageNotModified,
    RPCError,
)
from pyrogram.handlers import CallbackQueryHandler, InlineQueryHandler, MessageHandler
from pyrogram.types import (
    BotCommand,
    CallbackQuery,
    InlineKeyboardButton,
    InlineKeyboardMarkup,
    InlineQuery,
    InlineQueryResultArticle,
    InlineQueryResultPhoto,
    InputMediaPhoto,
    InputTextMessageContent,
    Message,
)

import config
import credentials
import dl_engine
import media_index
import rtdb
import store
import ui
from anilibria_api import (
    AniLibriaClient,
    AniLibriaError,
    GeoBlocked,
    Release,
    ReleaseNotFound,
)

logging.basicConfig(
    level=getattr(logging, config.LOG_LEVEL, logging.INFO),
    format="%(asctime)s | %(levelname)-8s | %(name)s | %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("animebot")
logging.getLogger("pyrogram").setLevel(logging.WARNING)
logging.getLogger("pyrogram.session").setLevel(logging.ERROR)

# ---------------------------------------------------------------------------
# Глобальное состояние
# ---------------------------------------------------------------------------
api = AniLibriaClient()
storage = store.Storage(config.DB_PATH)
APP: Optional[Client] = None
BOT_USERNAME: str = ""

DL_SEMAPHORE = asyncio.Semaphore(config.CONCURRENT_DOWNLOADS)
UP_SEMAPHORE = asyncio.Semaphore(config.CONCURRENT_UPLOADS)
USER_LOCKS: Dict[int, asyncio.Lock] = {}
TASKS: Dict[str, Dict[str, Any]] = {}       # task_id -> состояние задачи
INFLIGHT: Dict[str, str] = {}               # media_key -> task_id
RELEASE_MEMO: Dict[int, tuple[float, Release]] = {}
STARTED_AT = time.time()

# Канал-хранилище: туда складываются все скачанные серии, оттуда люди получают их копией
CHANNEL_ID: int = config.TELEGRAM_CHANNEL_ID
CHANNEL_READY: bool = False
CHANNEL_TITLE: str = ""

PER_PAGE = 10

# Подписи постоянной клавиатуры (обычные кнопки Telegram внизу экрана)
BUTTON_FAVORITES = "⭐ Избранное"
BUTTON_SEARCH = "🔍 Поиск"
BUTTON_CONTINUE = "▶️ Продолжить"


def user_lock(user_id: int) -> asyncio.Lock:
    lock = USER_LOCKS.get(user_id)
    if lock is None:
        lock = asyncio.Lock()
        USER_LOCKS[user_id] = lock
    return lock


def media_key(release_id: int | str, episode: int, quality: int) -> str:
    return f"{release_id}:{int(episode)}:{int(quality)}"


def is_owner(user_id: int) -> bool:
    return bool(config.OWNER_ID) and user_id == config.OWNER_ID


def is_allowed(user_id: int) -> bool:
    if is_owner(user_id):
        return True
    if config.ALLOWED_USERS and user_id not in config.ALLOWED_USERS:
        return False
    return config.PUBLIC_BOT or bool(config.ALLOWED_USERS)


# ---------------------------------------------------------------------------
# Универсальные помощники Telegram
# ---------------------------------------------------------------------------
async def safe_edit(chat_id: int, message_id: int, text: str,
                    reply_markup: Optional[InlineKeyboardMarkup] = None) -> None:
    """Правка текста сообщения с защитой от FloodWait/MessageNotModified."""
    for attempt in range(3):
        try:
            await APP.edit_message_text(chat_id, message_id, text, reply_markup=reply_markup)
            return
        except MessageNotModified:
            return
        except FloodWait as exc:
            log.warning("FloodWait %ss при правке сообщения", exc.value)
            await asyncio.sleep(min(exc.value + 1, 60))
        except RPCError as exc:
            log.debug("Не удалось изменить сообщение: %s", exc)
            return


async def render(chat_id: int, message: Optional[Message], text: str,
                 reply_markup: Optional[InlineKeyboardMarkup] = None,
                 poster: Optional[str] = None) -> Optional[Message]:
    """Показывает карточку: сохраняет постер, если он уже есть в сообщении."""
    if message is None:
        if poster:
            try:
                return await APP.send_photo(chat_id, poster, caption=text, reply_markup=reply_markup)
            except Exception as exc:  # noqa: BLE001
                log.debug("Постер не отправился (%s), шлю текстом", exc)
        return await APP.send_message(chat_id, text, reply_markup=reply_markup, disable_web_page_preview=True)

    has_photo = bool(getattr(message, "photo", None))
    try:
        if poster and not has_photo:
            return await APP.edit_message_media(
                chat_id, message.id, InputMediaPhoto(media=poster, caption=text), reply_markup=reply_markup
            )
        if has_photo:
            return await APP.edit_message_caption(chat_id, message.id, caption=text, reply_markup=reply_markup)
        return await APP.edit_message_text(chat_id, message.id, text, reply_markup=reply_markup,
                                           disable_web_page_preview=True)
    except MessageNotModified:
        return message
    except FloodWait as exc:
        await asyncio.sleep(min(exc.value + 1, 60))
        return await render(chat_id, message, text, reply_markup, poster)
    except RPCError as exc:
        log.debug("render fallback (%s)", exc)
        return await APP.send_message(chat_id, text, reply_markup=reply_markup, disable_web_page_preview=True)


async def notify(chat_id: int, text: str) -> Optional[Message]:
    try:
        return await APP.send_message(chat_id, text, disable_web_page_preview=True)
    except Exception as exc:  # noqa: BLE001
        log.warning("Не смог отправить сообщение в %s: %s", chat_id, exc)
        return None


# ---------------------------------------------------------------------------
# Релизы
# ---------------------------------------------------------------------------
async def load_release(release_id: int | str, force: bool = False) -> Release:
    """Полный релиз (с сериями и HLS-ссылками) с коротким мемо-кэшем."""
    key = int(release_id) if str(release_id).isdigit() else release_id
    if not force and isinstance(key, int):
        cached = RELEASE_MEMO.get(key)
        if cached and time.time() - cached[0] < 900:
            return cached[1]
    release = await api.get_release(release_id)
    await storage.save_release(release)
    if isinstance(key, int):
        RELEASE_MEMO[key] = (time.time(), release)
        if len(RELEASE_MEMO) > 300:
            oldest = sorted(RELEASE_MEMO.items(), key=lambda item: item[1][0])[:100]
            for memo_key, _ in oldest:
                RELEASE_MEMO.pop(memo_key, None)
    return release


def deep_link(payload: str) -> str:
    return f"https://t.me/{BOT_USERNAME}?start={payload}" if BOT_USERNAME else "https://t.me"


async def show_release(chat_id: int, message: Optional[Message], release: Release, user_id: int) -> None:
    fav = await storage.is_favorite(user_id, release.id)
    text = ui.release_text(release, fav)
    kb = ui.release_kb(
        release.id, fav,
        has_episodes=bool(release.episodes),
        site_url=release.site_url,
        deep_link=deep_link(f"rel_{release.id}"),
    )
    await render(chat_id, message, text, kb, poster=release.poster or None)


async def show_episodes(chat_id: int, message: Optional[Message], release: Release, page: int) -> None:
    total = release.max_episode
    if not total:
        kb = InlineKeyboardMarkup([[InlineKeyboardButton("⬅️ К релизу", callback_data=f"R|{release.id}")]])
        await render(chat_id, message, "😔 У этого релиза пока нет серий.", kb, poster=release.poster or None)
        return
    text = ui.episodes_text(release, page, config.EPISODES_PER_PAGE)
    kb = ui.episodes_kb(release.id, total, page, config.EPISODES_PER_PAGE, release.available_qualities())
    await render(chat_id, message, text, kb, poster=release.poster or None)


async def show_qualities(chat_id: int, message: Optional[Message], release: Release,
                         episode_number: int, default_quality: int = 0) -> None:
    episode = release.episode(episode_number)
    if episode is None or not episode.hls:
        kb = InlineKeyboardMarkup([[
            InlineKeyboardButton("⬅️ Серии", callback_data=f"E|{release.id}|{max(0, (episode_number - 1) // config.EPISODES_PER_PAGE)}")
        ]])
        await render(chat_id, message, f"⚠️ Серия {episode_number} недоступна в HLS.", kb, poster=release.poster or None)
        return
    text = ui.quality_text(release, episode, episode_number)
    kb = ui.quality_kb(release.id, episode_number, episode.qualities, default_quality)
    await render(chat_id, message, text, kb, poster=release.poster or None)


# ---------------------------------------------------------------------------
# Скачивание и отправка
# ---------------------------------------------------------------------------
async def client_thumb(file_id: Optional[str]) -> Optional[str]:
    """Превью по file_id Pyrogram не принимает (он умеет только локальный файл),
    поэтому при повторной отправке по file_id превью не передаём — Telegram возьмёт своё."""
    return None


async def send_cached(chat_id: int, key: str, release: Release, episode_number: int, quality: int) -> bool:
    """Мгновенная отправка по file_id (если эта серия уже выдавалась)."""
    row = await storage.cache_get(key)
    if not row or not row.get("file_id"):
        return False
    caption = ui.video_caption(release.title, episode_number, quality, int(row.get("size") or 0))
    kb = ui.video_kb(release.id, episode_number, release.max_episode, quality)
    try:
        await APP.send_video(
            chat_id, row["file_id"],
            caption=caption, parse_mode=ParseMode.HTML,
            duration=int(float(row.get("duration") or 0)),
            width=int(row.get("width") or 0), height=int(row.get("height") or 0),
            thumb=await client_thumb(row.get("thumb_file_id")),
            file_name=f"{release.alias}_s{episode_number}_{quality}p.mp4",
            supports_streaming=True, reply_markup=kb,
        )
        await storage.cache_hit(key)
        return True
    except RPCError as exc:
        log.warning("file_id устарел (%s) — качаю заново", exc)
        return False


async def upload_media(chat_id: int, info: dl_engine.MediaInfo, release: Release, episode_number: int,
                       quality: int, work: Dict[str, Any]) -> Optional[Message]:
    """Отправляет готовый файл (video или document) с прогрессом загрузки."""
    key = media_key(release.id, episode_number, quality)
    caption = ui.video_caption(release.title, episode_number, quality, info.size)
    kb = ui.video_kb(release.id, episode_number, release.max_episode, quality)
    filename = f"{release.alias}_s{episode_number}_{quality}p.mp4"
    state: dl_engine.ProgressState = work["state"]
    last_edit = 0.0

    async def upload_progress(current: int, total: int, *_args) -> None:
        nonlocal last_edit
        now = time.time()
        state.touch(stage="upload", uploaded=current, total_upload=total or info.size,
                    percent=(current / total * 100) if total else 0.0)
        if now - last_edit < config.PROGRESS_INTERVAL:
            return
        last_edit = now
        await safe_edit(chat_id, work["message_id"],
                        ui.progress_text(state, release.title, episode_number, quality),
                        ui.progress_kb(work["task_id"]))

    try:
        await APP.send_chat_action(chat_id, ChatAction.UPLOAD_VIDEO)
    except Exception:  # noqa: BLE001
        pass

    as_video = (not config.SEND_AS_DOCUMENT) and info.size <= config.UPLOAD_AS_VIDEO_LIMIT_MB * 1024 * 1024
    async with UP_SEMAPHORE:
        if as_video:
            message = await APP.send_video(
                chat_id, info.path,
                caption=caption, parse_mode=ParseMode.HTML,
                duration=int(info.duration or 0), width=int(info.width or 0), height=int(info.height or 0),
                thumb=info.thumb_path, file_name=filename,
                supports_streaming=True,
                reply_markup=kb,
                progress=upload_progress,
                progress_args=(),
            )
        else:
            message = await APP.send_document(
                chat_id, info.path,
                caption=caption, parse_mode=ParseMode.HTML,
                thumb=info.thumb_path, file_name=filename,
                reply_markup=kb,
                progress=upload_progress,
                progress_args=(),
            )

    # Сохраняем file_id, чтобы в следующий раз отдать мгновенно
    try:
        media = getattr(message, "video", None) or getattr(message, "document", None)
        file_id = getattr(media, "file_id", None)
        thumb_id = None
        thumb_obj = getattr(media, "thumbs", None)
        if thumb_obj:
            thumb_id = getattr(thumb_obj[-1], "file_id", None)
        if file_id:
            await storage.cache_put(
                key, file_id, thumb_id, info.size, info.duration, info.width, info.height,
                info.path, release.id, episode_number, quality,
            )
    except Exception as exc:  # noqa: BLE001
        log.debug("Не удалось сохранить file_id: %s", exc)
    return message


async def upload_to_channel(info: dl_engine.MediaInfo, release: Release, episode_number: int,
                            quality: int, work: Dict[str, Any]) -> Optional[Message]:
    """Кладёт готовый файл в канал-хранилище (с тем же прогрессом загрузки)."""
    if not (config.MIRROR_TO_CHANNEL and CHANNEL_READY):
        return None
    caption = ui.video_caption(release.title, episode_number, quality, info.size)
    filename = f"{release.alias}_s{episode_number}_{quality}p.mp4"
    state: dl_engine.ProgressState = work["state"]
    last_edit = 0.0

    async def channel_progress(current: int, total: int, *_args) -> None:
        nonlocal last_edit
        now = time.time()
        state.touch(stage="upload", uploaded=current, total_upload=total or info.size,
                    percent=(current / total * 100) if total else 0.0)
        if now - last_edit < config.PROGRESS_INTERVAL:
            return
        last_edit = now
        await safe_edit(chat_id=work["chat_id"], message_id=work["message_id"],
                        text=ui.progress_text(state, release.title, episode_number, quality),
                        reply_markup=ui.progress_kb(work["task_id"]))

    try:
        as_video = (not config.SEND_AS_DOCUMENT) and info.size <= config.UPLOAD_AS_VIDEO_LIMIT_MB * 1024 * 1024
        async with UP_SEMAPHORE:
            if as_video:
                return await APP.send_video(
                    CHANNEL_ID, info.path,
                    caption=caption, parse_mode=ParseMode.HTML,
                    duration=int(info.duration or 0), width=int(info.width or 0), height=int(info.height or 0),
                    thumb=info.thumb_path, file_name=filename, supports_streaming=True,
                    progress=channel_progress, progress_args=(),
                )
            return await APP.send_document(
                CHANNEL_ID, info.path,
                caption=caption, parse_mode=ParseMode.HTML,
                thumb=info.thumb_path, file_name=filename,
                progress=channel_progress, progress_args=(),
            )
    except RPCError as exc:
        log.warning("Не удалось положить серию в канал %s: %s", CHANNEL_ID, exc)
        return None


async def copy_from_channel(chat_id: int, entry: Dict[str, Any], release: Release,
                            episode_number: int, quality: int, user_id: int) -> bool:
    """Мгновенно отдаёт серию копией из канала (без пометки «переслано», канал не виден)."""
    message_id = int(entry.get("message_id") or entry.get("channel_message_id") or 0)
    channel_id = int(entry.get("channel_id") or CHANNEL_ID)
    if not message_id:
        return False
    try:
        await APP.copy_message(
            chat_id, channel_id, message_id,
            caption=ui.video_caption(release.title, episode_number, quality, int(entry.get("size") or 0)),
            parse_mode=ParseMode.HTML,
            reply_markup=ui.video_kb(release.id, episode_number, release.max_episode, quality),
        )
        return True
    except RPCError as exc:
        log.warning("Копирование из канала не удалось (%s) — убираю запись и качаю заново", exc)
        await media_index.forget(release.id, episode_number, quality, storage)
        return False


async def send_from_index(chat_id: int, release: Release, episode_number: int, quality: int,
                          user_id: int) -> bool:
    """Есть ли серия в индексе (RTDB/SQLite)? Если да — отдаём моментально копией из канала."""
    entry = await media_index.lookup(release.id, episode_number, quality, storage)
    if not entry or not entry.get("message_id"):
        return False
    ok = await copy_from_channel(chat_id, entry, release, episode_number, quality, user_id)
    if ok:
        await storage.cache_hit(media_key(release.id, episode_number, quality))
        await storage.bump_stat("sent_from_channel")
        await storage.add_history(user_id, release.id, release.alias, release.title, episode_number, quality)
        await storage.bump_downloads(user_id)
        return True
    return False


async def try_shrink_quality(quality: int, available: List[int]) -> Optional[int]:
    """Подбирает более низкое качество, если файл не лезет в лимит Telegram."""
    if not config.TRY_LOWER_QUALITY_ON_TOOBIG:
        return None
    lower = [q for q in sorted(available, reverse=True) if q < quality]
    return lower[0] if lower else None


async def process_episode(work: Dict[str, Any], release: Release, episode_number: int,
                          quality: int, quiet: bool = False) -> bool:
    """Полный цикл: кэш → скачивание → отправка. Возвращает True при успехе."""
    chat_id: int = work["chat_id"]
    user_id: int = work["user_id"]
    state: dl_engine.ProgressState = work["state"]
    cancel: asyncio.Event = work["cancel"]

    if cancel.is_set():
        raise dl_engine.DownloadCancelled()

    episode = release.episode(episode_number)
    if episode is None:
        raise dl_engine.DownloadError(f"в релизе нет серии {episode_number}")
    chosen = episode.best_quality(quality)
    if not chosen:
        raise dl_engine.DownloadError("у серии нет HLS-ссылок")

    key = media_key(release.id, episode_number, chosen)

    # 1) Серия уже лежит в канале-хранилище (RTDB/SQLite)? Отдаём копией — моментально.
    if await send_from_index(chat_id, release, episode_number, chosen, user_id):
        await storage.bump_stat("sent_from_index")
        return True

    # 2) Серия уже отправлялась этому боту? Мгновенно по file_id.
    if await send_cached(chat_id, key, release, episode_number, chosen):
        await storage.add_history(user_id, release.id, release.alias, release.title, episode_number, chosen)
        await storage.bump_stat("sent_from_cache")
        return True

    hls_url = episode.url_for(chosen)
    assert hls_url

    async def on_progress(current: dl_engine.ProgressState) -> None:
        if cancel.is_set():
            return
        await safe_edit(chat_id, work["message_id"],
                        ui.progress_text(current, release.title, episode_number, chosen),
                        ui.progress_kb(work["task_id"]))

    async with user_lock(user_id):
        try:
            info = await dl_engine.download_episode(
                release.id, episode_number, chosen, hls_url,
                on_progress=on_progress, cancel_event=cancel,
            )
        except dl_engine.StreamExpired:
            # ссылка на HLS со временем истекает — берём свежую из API и пробуем ещё раз
            log.info("Ссылка на поток истекла — обновляю через AniLibria API")
            fresh = await load_release(work["release_id"], force=True)
            fresh_episode = fresh.episode(episode_number)
            fresh_url = fresh_episode.url_for(chosen) if fresh_episode is not None else None
            if not fresh_url:
                raise
            info = await dl_engine.download_episode(
                release.id, episode_number, chosen, fresh_url,
                on_progress=on_progress, cancel_event=cancel,
            )

    if info.size > config.MAX_FILE_BYTES:
        raise dl_engine.FileTooBig(f"{dl_engine.human_size(info.size)} — больше лимита {config.MAX_FILE_MB} МБ")

    state.touch(stage="upload", percent=0.0, uploaded=0, total_upload=info.size)
    await safe_edit(chat_id, work["message_id"],
                    ui.progress_text(state, release.title, episode_number, chosen),
                    ui.progress_kb(work["task_id"]))

    # 3) Отправляем: сначала в канал-хранилище, затем пользователю копией из канала
    #    (копия не показывает канал и занимает миллисекунды — файл уже в Telegram).
    delivered = False
    channel_message = await upload_to_channel(info, release, episode_number, chosen, work)
    if channel_message is not None:
        try:
            media = getattr(channel_message, "video", None) or getattr(channel_message, "document", None)
            entry = {
                "key": media_index.key_for(release.id, episode_number, chosen),
                "channel_id": CHANNEL_ID,
                "message_id": channel_message.id,
                "file_id": getattr(media, "file_id", "") or "",
                "size": info.size, "duration": info.duration, "width": info.width, "height": info.height,
                "title": release.title, "episode": episode_number, "quality": chosen,
                "release_id": release.id,
            }
            await media_index.remember(entry, storage)
            delivered = await copy_from_channel(chat_id, entry, release, episode_number, chosen, user_id)
        except Exception as exc:  # noqa: BLE001
            log.warning("Индексация в канале не удалась: %s", exc)
    if not delivered:
        # канал отключён/недоступен — отправляем файл пользователю напрямую, как раньше
        await upload_media(chat_id, info, release, episode_number, chosen, work)

    # Файл уже в Telegram (и file_id/индекс сохранены) — освобождаем диск сервера
    if config.DELETE_AFTER_SEND:
        freed = await asyncio.to_thread(dl_engine.purge_episode_files, release.id, episode_number, chosen)
        if freed:
            log.info("Серия %s (%sp): диск очищен, освобождено %s",
                     episode_number, chosen, dl_engine.human_size(freed))
    await storage.add_history(user_id, release.id, release.alias, release.title, episode_number, chosen)
    await storage.bump_downloads(user_id)
    await storage.bump_stat("sent_files")
    try:
        await storage.bump_stat("cached_bytes", info.size)
    except Exception:  # noqa: BLE001
        pass
    await asyncio.to_thread(dl_engine.cleanup_cache)
    if quiet:
        log.info("Серия %s (%sp) отправлена в %s", episode_number, chosen, chat_id)
    return True


async def download_worker(work: Dict[str, Any]) -> None:
    """Рабочий таск одиночного скачивания."""
    task_id: str = work["task_id"]
    chat_id: int = work["chat_id"]
    state: dl_engine.ProgressState = work["state"]
    cancel: asyncio.Event = work["cancel"]
    key = media_key(work["release_id"], work["episode"], work["quality"])
    INFLIGHT[key] = task_id
    try:
        # Очередь: ждём свободный слот ffmpeg, показываем позицию
        if DL_SEMAPHORE.locked():
            ahead = len([t for t in TASKS.values() if t.get("state") and t["state"].stage in ("queue", "download")])
            state.touch(stage="queue", extra=f"Перед вами задач: {max(0, ahead - 1)}")
            await safe_edit(chat_id, work["message_id"],
                            ui.progress_text(state, work["title"], work["episode"], work["quality"]),
                            ui.progress_kb(task_id))

        async with DL_SEMAPHORE:
            if cancel.is_set():
                raise dl_engine.DownloadCancelled()
            release = await load_release(work["release_id"], force=True)
            quality = work["quality"]
            try:
                await process_episode(work, release, work["episode"], quality)
            except dl_engine.FileTooBig as exc:
                lower = await try_shrink_quality(quality, release.available_qualities())
                if not lower:
                    raise
                await notify(chat_id, f"⚠️ {ui.esc(str(exc))}\nПробую <b>{lower}p</b>…")
                work["quality"] = lower
                await process_episode(work, release, work["episode"], lower)

        state.touch(stage="done", percent=100.0)
        try:
            await APP.delete_messages(chat_id, work["message_id"])
        except RPCError:
            pass
    except dl_engine.DownloadCancelled:
        await safe_edit(chat_id, work["message_id"], "❌ Скачивание отменено.")
        await asyncio.sleep(2)
        try:
            await APP.delete_messages(chat_id, work["message_id"])
        except RPCError:
            await safe_edit(chat_id, work["message_id"], "❌ Отменено.")
    except GeoBlocked as exc:
        await safe_edit(chat_id, work["message_id"],
                        f"🚫 {ui.esc(exc)}\n\nНужен VPN/прокси: задай <code>ANILIBRIA_PROXY</code> в переменных окружения.")
    except (AniLibriaError, ReleaseNotFound) as exc:
        await safe_edit(chat_id, work["message_id"], f"⚠️ API AniLibria: {ui.esc(exc)}")
    except dl_engine.RateLimited as exc:
        await safe_edit(
            chat_id, work["message_id"],
            f"⚠️ <b>CDN AniLibria ограничил запросы</b>\n{ui.esc(exc)}\n\n"
            "Бот уже сам снижает потоки и ждёт по <code>Retry-After</code>. "
            "Если повторяется — выстави в переменных <code>HLS_CONNECTIONS=4</code> "
            "(и <code>HLS_START_CONNECTIONS=2</code>), а на Railway выбери регион "
            "<b>EU West (Amsterdam)</b>: он ближе к CDN и реже попадает под лимиты.\n"
            "Файл можно докачать этой же кнопкой — уже скачанные сегменты сохраняются."
        )
    except dl_engine.DownloadError as exc:
        await safe_edit(chat_id, work["message_id"],
                        f"⚠️ Не получилось собрать файл: {ui.esc(exc)}\n\n"
                        "Попробуй другое качество или проверь связь сервера с AniLibria.")
    except ValueError as exc:
        await safe_edit(chat_id, work["message_id"],
                        f"⚠️ Telegram не принял файл: {ui.esc(exc)}\n"
                        f"Лимит MTProto — {config.MAX_FILE_MB} МБ, файл должен укладываться в него.")
    except Exception as exc:  # noqa: BLE001
        log.exception("Ошибка задачи %s", task_id)
        await safe_edit(chat_id, work["message_id"], f"⚠️ Ошибка: {ui.esc(exc)}")
    finally:
        INFLIGHT.pop(key, None)
        TASKS.pop(task_id, None)


async def queue_all_worker(work: Dict[str, Any]) -> None:
    """Скачивание подряд: серия за серией до конца релиза."""
    task_id: str = work["task_id"]
    chat_id: int = work["chat_id"]
    cancel: asyncio.Event = work["cancel"]
    start_episode: int = work["episode"]
    quality: int = work["quality"]
    try:
        release = await load_release(work["release_id"], force=True)
        total = release.max_episode
        if not total:
            raise dl_engine.DownloadError("серий не найдено")
        await notify(chat_id, ui.queued_all_text(release.title, start_episode, quality, total))
        for number in range(start_episode, total + 1):
            if cancel.is_set():
                raise dl_engine.DownloadCancelled()
            work.update({"episode": number, "quality": quality})
            state: dl_engine.ProgressState = dl_engine.ProgressState(stage="download")
            work["state"] = state
            async with DL_SEMAPHORE:
                if cancel.is_set():
                    raise dl_engine.DownloadCancelled()
                try:
                    await process_episode(work, release, number, quality, quiet=True)
                except dl_engine.FileTooBig:
                    lower = await try_shrink_quality(quality, release.available_qualities())
                    if lower:
                        await notify(chat_id, f"⚠️ Серия {number}: файл больше лимита, беру {lower}p")
                        await process_episode(work, release, number, lower, quiet=True)
                    else:
                        await notify(chat_id, f"⚠️ Серия {number}: файл больше {config.MAX_FILE_MB} МБ, пропускаю")
        await safe_edit(chat_id, work["message_id"], f"✅ Все серии до {total} отправлены.")
    except dl_engine.DownloadCancelled:
        await safe_edit(chat_id, work["message_id"], "❌ Очередь остановлена.")
    except Exception as exc:  # noqa: BLE001
        log.exception("Ошибка очереди %s", task_id)
        await safe_edit(chat_id, work["message_id"], f"⚠️ Очередь прервана: {ui.esc(exc)}")
    finally:
        TASKS.pop(task_id, None)


async def start_download(chat_id: int, user_id: int, release_id: int, episode: int, quality: int,
                         default_quality: int = 0, as_queue: bool = False) -> None:
    """Создаёт задачу скачивания и сообщение с прогрессом."""
    key = media_key(release_id, episode, quality)
    existing = INFLIGHT.get(key)
    if existing and existing in TASKS:
        await notify(chat_id, "⏳ Эта серия уже качается — дождись файла.")
        return

    task_id = uuid.uuid4().hex[:10]
    cancel = asyncio.Event()
    work: Dict[str, Any] = {
        "task_id": task_id,
        "chat_id": chat_id,
        "user_id": user_id,
        "release_id": release_id,
        "episode": int(episode),
        "quality": int(quality),
        "cancel": cancel,
        "state": dl_engine.ProgressState(stage="queue"),
        "title": f"#{release_id}",
        "message_id": 0,
    }

    # Кэш file_id в Telegram — отправляем сразу, без прогресса
    if not as_queue:
        release_cached = RELEASE_MEMO.get(release_id)
        if release_cached:
            try:
                if await send_cached(chat_id, key, release_cached[1], episode, quality):
                    await storage.add_history(user_id, release_id, release_cached[1].alias,
                                              release_cached[1].title, episode, quality)
                    return
            except Exception as exc:  # noqa: BLE001
                log.debug("send_cached не сработал: %s", exc)

    status = await APP.send_message(
        chat_id,
        f"⏳ Готовлю задачу…\n#{release_id} · серия {episode} · {quality}p",
        reply_markup=ui.progress_kb(task_id),
    )
    work["message_id"] = status.id

    try:
        release = await load_release(release_id)
        work["title"] = release.title
    except Exception as exc:  # noqa: BLE001
        await safe_edit(chat_id, status.id, f"⚠️ Не смог загрузить релиз: {ui.esc(exc)}")
        return

    TASKS[task_id] = work
    worker = queue_all_worker(work) if as_queue else download_worker(work)
    task = asyncio.create_task(worker, name=f"dl-{task_id}")
    work["task"] = task
    await safe_edit(
        chat_id, status.id,
        ui.progress_text(work["state"], work["title"], episode, quality),
        ui.progress_kb(task_id),
    )


# ---------------------------------------------------------------------------
# Обработчики: команды
# ---------------------------------------------------------------------------
async def cmd_start(client: Client, message: Message) -> None:
    user = message.from_user
    await storage.upsert_user(user.id, user.username or "", user.first_name or "")
    if not is_allowed(user.id):
        await message.reply_text("🚫 Доступ к боту ограничен владельцем.")
        return
    payload = ""
    if message.text and len(message.text.split()) > 1:
        payload = message.text.split(maxsplit=1)[1].strip()
    if payload:
        await handle_deep_link(message, payload)
        return
    await message.reply_text(
        ui.main_menu_text(user.first_name or "", is_owner(user.id)),
        reply_markup=ui.main_menu_kb(is_owner(user.id)),
        disable_web_page_preview=True,
    )
    # обычная (постоянная) клавиатура Telegram: ⭐ Избранное · 🔍 Поиск · ▶️ Продолжить
    await message.reply_text("⌨️ Кнопки закреплены внизу ⬇️", reply_markup=ui.reply_keyboard())


async def handle_deep_link(message: Message, payload: str) -> None:
    chat_id, user_id = message.chat.id, message.from_user.id
    try:
        if payload.startswith("rel_"):
            release = await load_release(payload[4:])
            await show_release(chat_id, None, release, user_id)
        elif payload.startswith("eps_"):
            release = await load_release(payload[4:])
            await show_episodes(chat_id, None, release, 0)
        elif payload.startswith("ep_"):
            _, rid, number = payload.split("_", 2)
            release = await load_release(rid)
            user = await storage.get_user(user_id) or {}
            await show_qualities(chat_id, None, release, int(number), int(user.get("quality") or 0))
        elif payload.startswith("q_"):
            _, rid, number, quality = payload.split("_", 3)
            await start_download(chat_id, user_id, int(rid), int(number), int(quality))
        elif payload.startswith("alias_"):
            release = await load_release(payload[6:])
            await show_release(chat_id, None, release, user_id)
        else:
            await do_search(chat_id, None, user_id, payload)
    except ReleaseNotFound:
        await message.reply_text("😔 Тайтл не найден на AniLibria.")
    except GeoBlocked as exc:
        await message.reply_text(f"🚫 {ui.esc(exc)}")
    except AniLibriaError as exc:
        await message.reply_text(f"⚠️ API недоступно: {ui.esc(exc)}")


async def cmd_help(client: Client, message: Message) -> None:
    await message.reply_text(ui.help_text(BOT_USERNAME), disable_web_page_preview=True)


async def cmd_search(client: Client, message: Message) -> None:
    query = message.text.split(maxsplit=1)[1].strip() if len(message.text.split()) > 1 else ""
    if not query:
        await message.reply_text("Напиши название: <code>/search наруто</code>")
        return
    await do_search(message.chat.id, None, message.from_user.id, query)


async def cmd_favorites(client: Client, message: Message) -> None:
    await show_favorites(message.chat.id, None, message.from_user.id, 0)


async def cmd_continue(client: Client, message: Message) -> None:
    await continue_watching(message.chat.id, message.from_user.id)


async def cmd_menu(client: Client, message: Message) -> None:
    await message.reply_text(
        ui.main_menu_text(message.from_user.first_name or "", is_owner(message.from_user.id)),
        reply_markup=ui.main_menu_kb(is_owner(message.from_user.id)),
        disable_web_page_preview=True,
    )
    await message.reply_text("⌨️ Обычные кнопки всегда внизу экрана", reply_markup=ui.reply_keyboard())


async def cmd_random(client: Client, message: Message) -> None:
    try:
        releases = await api.latest(40)
    except AniLibriaError as exc:
        await message.reply_text(f"⚠️ {ui.esc(exc)}")
        return
    import random

    if not releases:
        await message.reply_text("😔 Не удалось получить список.")
        return
    release = random.choice(releases)
    full = await load_release(release.id)
    await show_release(message.chat.id, None, full, message.from_user.id)


async def cmd_files(client: Client, message: Message) -> None:
    await show_files(message.chat.id, None, message.from_user.id, 0)


async def cmd_settings(client: Client, message: Message) -> None:
    user = await storage.upsert_user(message.from_user.id, message.from_user.username or "",
                                     message.from_user.first_name or "")
    quality = int(user.get("quality") or 0) or config.DEFAULT_QUALITY
    await message.reply_text(ui.settings_text(quality), reply_markup=ui.settings_kb(quality))


async def cmd_id(client: Client, message: Message) -> None:
    await message.reply_text(
        f"🆔 Твой ID: <code>{message.from_user.id}</code>\n"
        f"Чат: <code>{message.chat.id}</code>\n\n"
        "Впиши свой ID в переменную <code>OWNER_ID</code>, чтобы получить админ-статистику."
    )


async def cmd_stats(client: Client, message: Message) -> None:
    if not is_owner(message.from_user.id):
        await message.reply_text("🚫 Статистика доступна только владельцу (OWNER_ID).")
        return
    await message.reply_text(ui.stats_text(await collect_stats()), reply_markup=InlineKeyboardMarkup([[
        InlineKeyboardButton("🧹 Очистить кэш на диске", callback_data="T|cache_clear")
    ]]))


async def collect_stats() -> Dict[str, Any]:
    stats = await storage.stats()
    cache = await storage.cache_stats()
    channel = await storage.channel_media_stats()
    rtdb_status = rtdb.client().status if config.RTDB_ENABLED else "выключен"
    cred_health = credentials.rotator().health()
    disk = await asyncio.to_thread(dl_engine.directory_size, config.DOWNLOAD_DIR)
    uptime = time.time() - STARTED_AT
    return {
        "Пользователей": await storage.users_count(),
        "Отправлено файлов": stats.get("sent_files", 0),
        "Отдано из кэша Telegram": stats.get("sent_from_cache", 0),
        "Принято файлов от людей": stats.get("intake_files", 0),
        "Файлов в канале-хранилище": channel.get("files", 0),
        "Отдано копией из канала": stats.get("sent_from_index", 0),
        "Канал": CHANNEL_TITLE if CHANNEL_READY else "нет доступа",
        "RTDB": rtdb_status,
        "API-пара": (
            f"api_id={credentials.ACTIVE.api_id} ({credentials.ACTIVE.name})"
            if credentials.ACTIVE.api_id else "нет активной"
        ),
        "Пары api_id": (f"доступно {cred_health['ready']} из {cred_health['total']}, "
                        f"публичных заблокировано {cred_health['published']}"),
        "Файлов в кэше Telegram": cache.get("files", 0),
        "Попаданий в кэш": cache.get("hits", 0),
        "Кэш на диске": dl_engine.human_size(disk),
        "Активных задач": len(TASKS),
        "Аптайм": dl_engine.human_time(uptime),
        "Лимит файла": f"{config.MAX_FILE_MB} МБ (MTProto)",
        "Версия": f"{config.CODE_VERSION} ({config.BUILD_SHA or 'локальная сборка'})",
        "ffmpeg": config.FFMPEG,
    }


async def do_search(chat_id: int, message: Optional[Message], user_id: int, query: str) -> None:
    query = (query or "").strip()
    if len(query) < 2:
        await render(chat_id, message, "✍️ Введи хотя бы 2 символа названия.", None)
        return
    loading = None
    if message is None:
        loading = await notify(chat_id, "🔍 Ищу на AniLibria…")
    try:
        releases = await api.search(query, config.SEARCH_LIMIT)
    except AniLibriaError as exc:
        err_text = f"⚠️ API AniLibria недоступно: {ui.esc(exc)}\n\nПопробуй ещё раз через минуту."
        if loading:
            await safe_edit(chat_id, loading.id, err_text)
        else:
            await render(chat_id, message, err_text, None)
        return
    text = ui.search_results_text(query, releases)
    kb = ui.search_results_kb(releases)
    if not loading:
        await render(chat_id, message, text, kb)
        return
    try:
        await loading.delete()
    except RPCError:
        pass
    poster = releases[0].poster if releases else None
    if poster:
        try:
            await APP.send_photo(chat_id, poster, caption=text, reply_markup=kb)
            return
        except RPCError as exc:
            log.debug("Постер не отправился (%s), отвечаю текстом", exc)
    await APP.send_message(chat_id, text, reply_markup=kb, disable_web_page_preview=True)


# ---------------------------------------------------------------------------
# Обработчики: inline-поиск (@бота)
# ---------------------------------------------------------------------------
async def inline_query_handler(client: Client, query: InlineQuery) -> None:
    text = (query.query or "").strip()
    results: List[InlineQueryResultArticle] = []
    try:
        if text:
            releases = await api.search(text, config.INLINE_LIMIT)
        else:
            releases = await api.latest(min(config.INLINE_LIMIT, 20))
    except AniLibriaError as exc:
        log.warning("inline search error: %s", exc)
        releases = []

    for release in releases:
        try:
            await storage.save_release(release)
        except Exception:  # noqa: BLE001
            pass
        desc_parts = []
        if release.year:
            desc_parts.append(str(release.year))
        if release.type_description or release.type_value:
            desc_parts.append(release.type_description or release.type_value)
        if release.episodes_total:
            desc_parts.append(f"{release.episodes_total} серий")
        if release.name_english and release.name_english != release.title:
            desc_parts.append(release.name_english)
        # Одна кнопка — сразу список серий с выбором качества («где серии»). Результат —
        # обычная (не фото) карточка: тап мгновенный, без «галочки» и предпросмотра файла.
        # Иконка — миниатюра (маленькая и быстрая), а не полноразмерный постер.
        kb = InlineKeyboardMarkup([
            [InlineKeyboardButton("📺 Все серии", url=deep_link(f"eps_{release.id}"))],
        ])
        caption = ui.inline_card_text(release, BOT_USERNAME)
        poster = release.poster_thumbnail or release.poster_preview or release.poster
        results.append(
            InlineQueryResultArticle(
                id=f"r{release.id}",
                title=ui.truncate(release.title, 64),
                description=ui.truncate(" · ".join(desc_parts), 120),
                thumb_url=poster or None,       # миниатюра: быстрая загрузка списка результатов
                thumb_width=320,
                thumb_height=480,
                reply_markup=kb,
                input_message_content=InputTextMessageContent(
                    message_text=caption,
                    parse_mode=ParseMode.HTML,
                    disable_web_page_preview=True,
                ),
            )
        )

    if not results:
        results.append(
            InlineQueryResultArticle(
                id="empty",
                title="Ничего не найдено",
                description="Попробуй другое написание названия",
                input_message_content=InputTextMessageContent("😔 Ничего не нашлось на AniLibria"),
            )
        )
    try:
        await query.answer(results=results[:50], cache_time=30, is_gallery=False,
                           switch_pm_text="🎌 Открыть аниме-бота", switch_pm_parameter="start")
    except RPCError as exc:
        log.debug("inline answer: %s", exc)


# ---------------------------------------------------------------------------
# Обработчики: текстовые сообщения и входящие файлы
# ---------------------------------------------------------------------------
async def handle_inline_result(message: Message) -> bool:
    """Если пользователь тапнул результат инлайн-поиска прямо в чате с ботом,
    сразу открываем выбранный тайтл (выбор серий) — без лишних нажатий."""
    via = getattr(message, "via_bot", None)
    username = (getattr(via, "username", "") or "").lower()
    if not username or username != (BOT_USERNAME or "").lower():
        return False
    text = message.text or message.caption or ""
    match = re.search(r"(?:start=|\?start=)(rel|eps|ep)_(\d+)(?:_(\d+))?", text)
    if not match:
        return False
    kind, release_id = match.group(1), int(match.group(2))
    number = int(match.group(3)) if match.group(3) else None
    chat_id, user_id = message.chat.id, message.from_user.id
    try:
        release = await load_release(release_id)
    except (AniLibriaError, ReleaseNotFound):
        return False
    if kind == "eps" or number is None:
        await show_episodes(chat_id, None, release, 0)          # сразу выбор серий
    elif kind == "ep":
        user = await storage.get_user(user_id) or {}
        await show_qualities(chat_id, None, release, number, int(user.get("quality") or 0))
    else:
        await show_release(chat_id, None, release, user_id)
    # убираем саму карточку-сообщение, чтобы чат остался чистым
    try:
        await APP.delete_messages(chat_id, message.id)
    except RPCError:
        pass
    return True


async def text_handler(client: Client, message: Message) -> None:
    """Обычный текст бот НЕ обрабатывает: поиск только через инлайн (@бот …).

    Реагируем исключительно на кнопки постоянной клавиатуры, любой другой текст
    остаётся без ответа — как и просили.
    """
    user = message.from_user
    await storage.upsert_user(user.id, user.username or "", user.first_name or "")
    if not is_allowed(user.id):
        await message.reply_text("🚫 Доступ ограничен.")
        return
    if await handle_inline_result(message):
        return
    text = (message.text or "").strip()
    if text == BUTTON_FAVORITES:
        await show_favorites(message.chat.id, None, user.id, 0)
    elif text == BUTTON_SEARCH:
        await message.reply_text(
            ui.search_prompt_text(), reply_markup=ui.search_prompt_kb(), disable_web_page_preview=True
        )
    elif text == BUTTON_CONTINUE:
        await continue_watching(message.chat.id, user.id)
    # иначе — молчание (никакого поиска по тексту)


def extract_file(message: Message) -> Optional[Dict[str, Any]]:
    for attr, kind in (("video", "video"), ("document", "document"),
                       ("audio", "audio"), ("voice", "voice"), ("photo", "photo")):
        obj = getattr(message, attr, None)
        if obj is None:
            continue
        if kind == "photo":
            name = f"photo_{message.id}.jpg"
            mime = "image/jpeg"
            duration = 0
        else:
            name = getattr(obj, "file_name", None) or f"{kind}_{message.id}"
            mime = getattr(obj, "mime_type", "") or ""
            duration = int(getattr(obj, "duration", 0) or 0)
        return {
            "file_id": obj.file_id,
            "file_unique_id": getattr(obj, "file_unique_id", "") or "",
            "kind": kind,
            "name": name,
            "size": int(getattr(obj, "file_size", 0) or 0),
            "mime": mime,
            "duration": duration,
        }
    return None


async def file_handler(client: Client, message: Message) -> None:
    """Приём файлов до 2 ГБ: сохраняем file_id, умеем отдать обратно и скачать на сервер."""
    user = message.from_user
    await storage.upsert_user(user.id, user.username or "", user.first_name or "")
    if not is_allowed(user.id):
        return
    info = extract_file(message)
    if not info:
        return
    row_id = await storage.add_user_file(
        user.id, info["file_id"], info["file_unique_id"], info["kind"],
        info["name"], info["size"], info["mime"], info["duration"],
    )
    await storage.bump_stat("intake_files")
    size_text = dl_engine.human_size(info["size"]) if info["size"] else "размер неизвестен"
    text = (
        f"📥 <b>Файл принят</b>\n\n"
        f"Тип: {ui.esc(info['kind'])}\n"
        f"Имя: <code>{ui.esc(ui.truncate(info['name'], 64))}</code>\n"
        f"Размер: {size_text}\n\n"
        "Лимит MTProto — 2000 МБ, так что файлы до 2 ГБ проходят целиком. "
        "Могу отдать его обратно или скачать на сервер бота."
    )
    kb = InlineKeyboardMarkup([
        [InlineKeyboardButton("📤 Отправить обратно", callback_data=f"S|{row_id}"),
         InlineKeyboardButton("📥 Скачать на сервер", callback_data=f"U|{row_id}")],
        [InlineKeyboardButton("📁 Мои файлы", callback_data="L|0"),
         InlineKeyboardButton("🗑 Забыть", callback_data=f"W|{row_id}")],
    ])
    await message.reply_text(text, reply_markup=kb)


# ---------------------------------------------------------------------------
# Обработчики: callback-кнопки
# ---------------------------------------------------------------------------
async def send_back_file(chat_id: int, row: Dict[str, Any]) -> None:
    caption = f"📤 <b>{ui.esc(ui.truncate(row.get('name') or 'файл', 80))}</b>"
    kind = row.get("kind")
    if kind == "video":
        await APP.send_video(chat_id, row["file_id"], caption=caption, duration=int(row.get("duration") or 0),
                             supports_streaming=True)
    elif kind == "audio":
        await APP.send_audio(chat_id, row["file_id"], caption=caption)
    elif kind == "photo":
        await APP.send_photo(chat_id, row["file_id"], caption=caption)
    else:
        await APP.send_document(chat_id, row["file_id"], caption=caption,
                                file_name=row.get("name") or None)


async def callback_handler(client: Client, query: CallbackQuery) -> None:
    data = query.data or ""
    parts = data.split("|")
    action = parts[0]
    user_id = query.from_user.id
    chat_id = query.message.chat.id if query.message else None
    message = query.message

    async def answer(text: str = "", alert: bool = False, url: Optional[str] = None) -> None:
        try:
            await query.answer(text=text or None, show_alert=alert, url=url, cache_time=0)
        except RPCError as exc:
            log.debug("callback answer: %s", exc)

    try:
        if action == "M":
            await answer()
            if message:
                user = await storage.get_user(user_id) or {}
                await render(chat_id, message, ui.main_menu_text(query.from_user.first_name or "", is_owner(user_id)),
                             ui.main_menu_kb(is_owner(user_id)))
            return

        if action == "R":
            await answer()
            release = await load_release(parts[1])
            await show_release(chat_id, message, release, user_id)
            return

        if action == "E":
            await answer()
            release = await load_release(parts[1])
            await show_episodes(chat_id, message, release, int(parts[2]))
            return

        if action == "P":
            await answer()
            release = await load_release(parts[1])
            user = await storage.get_user(user_id) or {}
            await show_qualities(chat_id, message, release, int(parts[2]), int(user.get("quality") or 0))
            return

        if action in ("D", "A"):
            rid, episode, quality = int(parts[1]), int(parts[2]), int(parts[3])
            if not quality:
                await answer("Выбери качество")
                return
            user = await storage.get_user(user_id) or {}
            default_quality = int(user.get("quality") or 0)
            if action == "A":
                await answer("Ставлю серии в очередь")
                as_queue = True
            else:
                await answer("Скачиваю…")
                as_queue = False
            await start_download(chat_id or user_id, user_id, rid, episode, quality,
                                 default_quality=default_quality, as_queue=as_queue)
            return

        if action == "C":
            task_id = parts[1]
            work = TASKS.get(task_id)
            if not work:
                await answer("Задача уже завершена", alert=True)
                return
            work["cancel"].set()
            task = work.get("task")
            if task and not task.done():
                task.cancel()
            TASKS.pop(task_id, None)
            await answer("Отменяю…", alert=False)
            return

        if action == "F":
            release = await load_release(parts[1])
            state = await storage.toggle_favorite(user_id, release)
            await answer("Добавлено в избранное ⭐" if state else "Убрано из избранного 💔")
            await show_release(chat_id, message, release, user_id)
            return

        if action == "T":
            mode = parts[1] if len(parts) > 1 else "help"
            page = int(parts[2]) if len(parts) > 2 else 0
            if mode == "new":
                await answer()
                releases = await api.latest(PER_PAGE)
                if not releases:
                    await render(chat_id, message, "😔 Свежих релизов нет.", None)
                    return
                text = "🆕 <b>Свежие релизы AniLibria</b>\nВыбери тайтл 👇"
                if message and getattr(message, "photo", None):
                    await render(chat_id, message, text, ui.latest_kb(releases, page))
                else:
                    await render(chat_id, message, text, ui.latest_kb(releases, page))
                return
            if mode == "random":
                await answer("Ищу случайный тайтл 🎲")
                releases = await api.latest(40)
                import random

                release = random.choice(releases) if releases else None
                if release:
                    full = await load_release(release.id)
                    await show_release(chat_id, None, full, user_id)
                return
            if mode == "help":
                await answer()
                await render(chat_id, message, ui.help_text(BOT_USERNAME), None)
                return
            if mode == "settings":
                await answer()
                user = await storage.get_user(user_id) or {}
                quality = int(user.get("quality") or 0) or config.DEFAULT_QUALITY
                await render(chat_id, message, ui.settings_text(quality), ui.settings_kb(quality))
                return
            if mode == "stats":
                if not is_owner(user_id):
                    await answer("Только для владельца", alert=True)
                    return
                await answer()
                await render(chat_id, message, ui.stats_text(await collect_stats()),
                             InlineKeyboardMarkup([[InlineKeyboardButton("🧹 Очистить кэш", callback_data="T|cache_clear")]]))
                return
            if mode == "cache_clear":
                if not is_owner(user_id):
                    await answer("Только для владельца", alert=True)
                    return
                removed = await asyncio.to_thread(dl_engine.cleanup_cache, 0)
                await answer(f"Удалено {dl_engine.human_size(removed)}", alert=True)
                return
            await answer()
            return

        if action == "Q":
            quality = int(parts[1])
            await storage.set_quality(user_id, quality)
            await answer(f"Качество по умолчанию: {quality}p")
            await render(chat_id, message, ui.settings_text(quality), ui.settings_kb(quality))
            return

        if action == "V":
            page = int(parts[1])
            await answer()
            items = await storage.favorites(user_id, PER_PAGE, page * PER_PAGE)
            total = await storage.favorites_count(user_id)
            await render(chat_id, message, ui.favorites_text(page, total),
                         ui.favorites_kb(items, page, PER_PAGE, total))
            return

        if action == "H":
            page = int(parts[1])
            await answer()
            items = await storage.history(user_id, PER_PAGE, page * PER_PAGE)
            total = await storage.history_count(user_id)
            await render(chat_id, message, ui.history_text(page, total),
                         ui.history_kb(items, page, PER_PAGE, total))
            return

        if action == "L":
            page = int(parts[1])
            await answer()
            await show_files(chat_id, message, user_id, page)
            return

        if action == "S":
            row = await storage.get_user_file(int(parts[1]))
            if not row or row["user_id"] != user_id:
                await answer("Файл не найден", alert=True)
                return
            await answer("Отправляю…")
            await send_back_file(chat_id or user_id, row)
            return

        if action == "U":
            row = await storage.get_user_file(int(parts[1]))
            if not row or row["user_id"] != user_id:
                await answer("Файл не найден", alert=True)
                return
            await answer("Качаю на сервер…")
            await download_user_file(chat_id, row)
            return

        if action == "W":
            await storage.delete_user_file(int(parts[1]), user_id)
            await answer("Убрал из списка")
            await show_files(chat_id, message, user_id, 0)
            return

        if action == "X":
            await answer()
            try:
                await APP.delete_messages(chat_id, message.id)
            except RPCError:
                pass
            return

        await answer()
    except ReleaseNotFound:
        await answer("Тайтл не найден", alert=True)
    except GeoBlocked as exc:
        await answer(str(exc), alert=True)
    except AniLibriaError as exc:
        await answer(f"API недоступно: {exc}", alert=True)
    except Exception as exc:  # noqa: BLE001
        log.exception("callback %s", data)
        await answer(f"Ошибка: {exc}", alert=True)


async def show_favorites(chat_id: int, message: Optional[Message], user_id: int, page: int) -> None:
    items = await storage.favorites(user_id, PER_PAGE, page * PER_PAGE)
    total = await storage.favorites_count(user_id)
    await render(chat_id, message, ui.favorites_text(page, total), ui.favorites_kb(items, page, PER_PAGE, total))


async def continue_watching(chat_id: int, user_id: int) -> None:
    """Кнопка «Продолжить»: открывает следующую серию последнего просмотра."""
    history = await storage.history(user_id, 1)
    if not history:
        await notify(chat_id, ui.nothing_to_continue_text())
        return
    row = history[0]
    user = await storage.get_user(user_id) or {}
    quality = int(user.get("quality") or 0) or config.DEFAULT_QUALITY
    last_episode = int(row.get("episode") or 1)
    last_quality = int(row.get("quality") or quality)
    try:
        release = await load_release(row["release_id"])
    except (AniLibriaError, ReleaseNotFound):
        await notify(chat_id, "⚠️ Не удалось открыть тайтл — найди его через инлайн-поиск.")
        return
    total = release.max_episode or last_episode
    next_episode = last_episode + 1
    if next_episode > total:
        await notify(chat_id, ui.all_episodes_watched_text(release.title, last_episode))
        return
    episode = release.episode(next_episode)
    chosen = (episode.best_quality(quality) if episode else None) or quality
    await render(
        chat_id, None,
        ui.continue_text(release.title, last_episode, next_episode, chosen, last_quality),
        ui.continue_kb(release.id, next_episode, chosen, total),
        poster=release.poster or None,
    )


async def show_files(chat_id: int, message: Optional[Message], user_id: int, page: int) -> None:
    items = await storage.user_files(user_id, PER_PAGE, page * PER_PAGE)
    total = await storage.user_files_count(user_id)
    await render(chat_id, message, ui.files_text(page, total), ui.files_kb(items, page, PER_PAGE, total))


async def download_user_file(chat_id: int, row: Dict[str, Any]) -> None:
    """Скачивает присланный пользователем файл на сервер (до 2 ГБ)."""
    target_dir = config.DOWNLOAD_DIR / "intake" / str(row["user_id"])
    target_dir.mkdir(parents=True, exist_ok=True)
    safe_name = "".join(ch for ch in (row.get("name") or f"file_{row['id']}") if ch not in '/\\:*?"<>|')
    path = target_dir / f"{row['id']}_{safe_name}"
    status = await notify(chat_id, f"📥 Качаю на сервер: <code>{ui.esc(ui.truncate(safe_name, 60))}</code>\n0%")
    last = {"time": 0.0}

    async def progress(current: int, total: int, *_args) -> None:
        now = time.time()
        if now - last["time"] < config.PROGRESS_INTERVAL:
            return
        last["time"] = now
        percent = (current / total * 100) if total else 0.0
        text = (
            f"📥 Качаю на сервер: <code>{ui.esc(ui.truncate(safe_name, 60))}</code>\n"
            f"<code>{ui.progress_bar(percent)}</code> {percent:.1f}%\n"
            f"{dl_engine.human_size(current)}"
            + (f" / {dl_engine.human_size(total)}" if total else "")
        )
        if status:
            await safe_edit(chat_id, status.id, text)

    try:
        saved = await APP.download_media(row["file_id"], file_name=str(path),
                                         progress=progress, progress_args=())
    except Exception as exc:  # noqa: BLE001
        if status:
            await safe_edit(chat_id, status.id, f"⚠️ Не скачалось: {ui.esc(exc)}")
        return
    if not saved:
        if status:
            await safe_edit(chat_id, status.id, "⚠️ Telegram не отдал файл.")
        return
    await storage.set_user_file_path(int(row["id"]), str(saved))
    size = 0
    try:
        size = (await asyncio.to_thread(lambda: __import__("os").path.getsize(str(saved)))) or 0
    except Exception:  # noqa: BLE001
        pass
    if status:
        await safe_edit(chat_id, status.id,
                        f"✅ Файл сохранён на сервере\n<code>{ui.esc(str(saved))}</code>\n"
                        f"Размер: {dl_engine.human_size(size)}")


# ---------------------------------------------------------------------------
# Запуск
# ---------------------------------------------------------------------------
def register_handlers(client: Client) -> None:
    client.add_handler(MessageHandler(cmd_start, filters.command("start") & filters.private))
    client.add_handler(MessageHandler(cmd_help, filters.command(["help", "instruction"]) & filters.private))
    client.add_handler(MessageHandler(cmd_search, filters.command(["search", "s", "anime"]) & filters.private))
    client.add_handler(MessageHandler(cmd_random, filters.command(["random", "rand"]) & filters.private))
    client.add_handler(MessageHandler(cmd_favorites, filters.command(["favorites", "favourite", "fav"]) & filters.private))
    client.add_handler(MessageHandler(cmd_continue, filters.command(["continue", "resume"]) & filters.private))
    client.add_handler(MessageHandler(cmd_menu, filters.command(["menu"]) & filters.private))
    client.add_handler(MessageHandler(cmd_files, filters.command(["files", "myfiles"]) & filters.private))
    client.add_handler(MessageHandler(cmd_settings, filters.command(["settings", "quality"]) & filters.private))
    client.add_handler(MessageHandler(cmd_stats, filters.command(["stats", "stat"]) & filters.private))
    client.add_handler(MessageHandler(cmd_id, filters.command(["id", "myid"]) & filters.private))
    client.add_handler(CallbackQueryHandler(callback_handler))
    client.add_handler(InlineQueryHandler(inline_query_handler))
    if config.ENABLE_FILE_INTAKE:
        media_filter = filters.video | filters.document | filters.audio | filters.photo
        client.add_handler(MessageHandler(file_handler, filters.private & media_filter))
    # только текст: карточки инлайна — обычные (без медиа), поэтому перехват подписей не нужен,
    # а широкий filters.caption мог бы съедать присланные пользователем файлы
    client.add_handler(MessageHandler(text_handler, filters.private & filters.text))


def _published_hint(rotator: credentials.CredentialRotator) -> str:
    """Предупреждение, если Telegram считает api_id опубликованными (роутинг бессилен)."""
    blocked = rotator.published_blocked()
    if not blocked:
        return ""
    ids = ", ".join(f"{state.api_id} ({state.name})" for state in blocked[:8])
    return (
        "\n⚠ Telegram помечает эти api_id как ОПУБЛИКОВАННЫЕ (API_ID_PUBLISHED_FLOOD): "
        f"{ids}.\n"
        "   Такие пары сервер ограничивает для авторизации бота, и перебор тут не помогает.\n"
        "   Рабочее решение: своя пара из https://my.telegram.org → API development tools\n"
        "   (имя любое) → положить её в Railway → Variables: API_ID и API_HASH."
    )


async def start_client() -> Optional[Client]:
    """Поднимает MTProto-клиент с умным роутингом по парам api_id/api_hash.

    Одна проходка: пары берутся в порядке «сначала самая перспективная»
    (рабочая → непроверенные → с меньшим числом отказов), битые не трогаем вовсе —
    у каждой своя пауза (публичные — часы, FloodWait — по таймеру Telegram).
    Возвращает клиент или None: решение «что делать дальше» принимает супервизор,
    контейнер при этом НЕ падает (иначе Railway устраивает шторм перезапусков).
    """
    global APP, BOT_USERNAME
    rotator = credentials.rotator()
    candidates = rotator.candidates()[: max(1, config.MAX_API_KEY_ATTEMPTS or len(rotator.pairs))]
    if not candidates:
        log.warning("Все пары api_id/api_hash сейчас на паузе:\n%s%s", rotator.report(),
                    _published_hint(rotator))
        return None

    last_error: Optional[Exception] = None
    for index, (api_id, api_hash, name) in enumerate(candidates):
        if index:
            await asyncio.sleep(random.uniform(config.API_KEYS_TRY_DELAY_MIN, config.API_KEYS_TRY_DELAY_MAX))
        client = Client(
            name=f"animebot_{api_id}",
            api_id=api_id,
            api_hash=api_hash,
            bot_token=config.BOT_TOKEN,
            workdir=str(config.SESSION_DIR),
            workers=8,
            parse_mode=ParseMode.HTML,
            sleep_threshold=30,
            max_concurrent_transmissions=4,   # штатное значение Pyrogram: отправку не трогаем
            proxy=config.pyrogram_proxy(),
        )
        register_handlers(client)
        try:
            await client.start()
            me = await client.get_me()
        except Exception as exc:  # noqa: BLE001
            last_error = exc
            state = rotator.note_failure(api_id, name, exc)
            log.warning("api_id=%s (%s) не подошёл: %s → %s", api_id, name, exc, state.summary())
            try:
                await client.stop()
            except Exception:  # noqa: BLE001
                pass
            continue

        BOT_USERNAME = me.username or ""
        APP = client
        credentials.ACTIVE.set(api_id, name)
        # Авторизация удалась — это рабочая пара. Всё остальное (меню команд, доступ к
        # каналу) не должно «хоронить» рабочую пару, поэтому ошибки тут только в лог.
        for step, coro in (("меню команд", _set_bot_commands(client)),
                           ("канал-хранилище", check_channel(client))):
            try:
                await coro
            except Exception as exc:  # noqa: BLE001
                log.warning("Не удалось выполнить шаг «%s» (не критично): %s", step, exc)
        rotator.note_success(api_id, name)
        log.info("Бот запущен как @%s — api_id=%s (%s), MTProto, лимит %s МБ",
                 BOT_USERNAME or me.first_name, api_id, name, config.MAX_FILE_MB)
        if rotator.published_blocked():
            log.warning("Часть пар Telegram считает опубликованными (в паузе): %s",
                        ", ".join(str(state.api_id) for state in rotator.published_blocked()))
        return client

    log.error("Ни одна из %s пар не пустила бота. Последняя ошибка: %s\n%s%s",
              len(candidates), last_error, rotator.report(), _published_hint(rotator))
    return None


async def _watch_client(client: Client) -> None:
    """Держит клиент под присмотром: как только связь пропала — возвращает управление."""
    while True:
        await asyncio.sleep(max(1.0, config.SUPERVISOR_TICK))
        if not getattr(client, "is_connected", False):
            log.warning("Связь с Telegram потеряна (api_id=%s) — переподключаюсь",
                        credentials.ACTIVE.api_id)
            return


async def run_supervisor() -> None:
    """Главный цикл: поднять клиент → следить → при отказе сменить пару и снова.

    Бот не завершает процесс при проблемах с парами: он ждёт и пробует снова
    (паузы растут: 1 → 2 → 5 → 10 → 30 мин, не чаще). Это главное отличие от
    «упал и вышел», из-за которого Railway перезапускал контейнер и заново
    перебирал весь список пар.
    """
    global APP
    rotator = credentials.rotator()
    attempt = 0
    network_failures = 0
    while True:
        client = await start_client()
        if client is None:
            attempt += 1
            delay = min(config.API_KEYS_RETRY_MAX, config.API_KEYS_RETRY_BASE * (2 ** (attempt - 1)))
            ready_in = rotator.seconds_until_ready()
            if ready_in > delay:
                delay = min(config.API_KEYS_RETRY_MAX, ready_in + 5)
            log.error("Пар для попытки нет — жду %s и пробую снова", credentials.human_delay(delay))
            await asyncio.sleep(delay)
            continue

        attempt = 0
        await _watch_client(client)

        # Связь пропала: сначала пробуем ту же пару (сеть, миграция), потом меняем
        api_id = credentials.ACTIVE.api_id or 0
        name = credentials.ACTIVE.name
        reason: Optional[BaseException] = None
        try:
            await client.start()
            if getattr(client, "is_connected", False):
                network_failures = 0
                log.info("Переподключился к Telegram той же парой api_id=%s", api_id)
                continue
            reason = ConnectionError("клиент не подключился")
        except Exception as exc:  # noqa: BLE001
            reason = exc

        kind, code, _cooldown = credentials.classify(reason)
        if kind in (credentials.KIND_AUTH, credentials.KIND_PUBLISHED, credentials.KIND_FLOOD):
            if api_id:
                state = rotator.note_failure(api_id, name, reason)
                log.warning("Пара api_id=%s (%s) больше не годится: %s → %s",
                            api_id, name, reason, state.summary())
            network_failures = 0
        else:
            # временный сбой сети — пару не «хороним», но и не крутим цикл на максимальной скорости
            network_failures += 1
            delay = min(300.0, 5.0 * (2 ** min(network_failures, 6)))
            log.warning("Временный сбой связи с Telegram (%s) — пауза %s и повтор",
                        reason, credentials.human_delay(delay))
            await asyncio.sleep(delay)

        if APP is not None:
            try:
                await APP.stop()
            except Exception:  # noqa: BLE001
                pass
        APP = None
        credentials.ACTIVE.clear()
        await asyncio.sleep(3.0)


async def check_channel(client: Client) -> None:
    """Проверяет доступ бота к каналу-хранилищу (иначе серии идут напрямую в чат)."""
    global CHANNEL_READY, CHANNEL_TITLE
    if not config.MIRROR_TO_CHANNEL:
        CHANNEL_READY = False
        log.info("Канал-хранилище отключён (MIRROR_TO_CHANNEL=0)")
        return
    try:
        chat = await client.get_chat(CHANNEL_ID)
        CHANNEL_TITLE = getattr(chat, "title", "") or str(CHANNEL_ID)
        CHANNEL_READY = True
        log.info("Канал-хранилище доступен: «%s» (%s)", CHANNEL_TITLE, CHANNEL_ID)
    except RPCError as exc:
        CHANNEL_READY = False
        log.warning(
            "Канал-хранилище недоступен (%s). Добавь бота администратором в канал %s "
            "(id %s) с правом публикации — тогда серии будут отдаваться копией из канала.",
            exc, config.TELEGRAM_CHANNEL_LINK, CHANNEL_ID,
        )


async def _set_bot_commands(client: Client) -> None:
    """Меню команд Telegram (кнопка «Меню» слева от поля ввода)."""
    try:
        await client.set_bot_commands([
            BotCommand("favorites", "⭐ Избранное"),
            BotCommand("continue", "▶️ Продолжить"),
            BotCommand("search", "🔍 Поиск"),
            BotCommand("files", "📁 Мои файлы"),
            BotCommand("help", "❓ Помощь"),
        ])
    except Exception as exc:  # noqa: BLE001
        log.debug("Список команд не установился: %s", exc)


async def check_credentials(test_all: bool = False) -> int:
    """python anime_bot.py --creds — живая проверка: какие пары api_id/api_hash пускают бота.

    Пробуем пары в «умном» порядке (рабочая → непроверенные), уважая паузы:
    --creds-all — проверять вообще все, включая те, что в паузе.
    """
    config.ensure_dirs()
    rotator = credentials.rotator()
    if test_all:
        candidates = list(rotator.pairs)
    else:
        candidates = rotator.candidates() or rotator.candidates(include_cooling=True)
    print(f"Проверяю {len(candidates)} пар(ы) api_id/api_hash…\n")
    working: List[int] = []
    for index, (api_id, api_hash, name) in enumerate(candidates):
        if index:
            await asyncio.sleep(random.uniform(config.API_KEYS_TRY_DELAY_MIN,
                                               max(config.API_KEYS_TRY_DELAY_MAX, 1.0)))
        client = Client(
            name=f"animebot_{api_id}", api_id=api_id, api_hash=api_hash, bot_token=config.BOT_TOKEN,
            workdir=str(config.SESSION_DIR), workers=2, parse_mode=ParseMode.HTML,
            sleep_threshold=30, max_concurrent_transmissions=4, proxy=config.pyrogram_proxy(),
        )
        try:
            await client.start()
            me = await client.get_me()
            rotator.note_success(api_id, name)
            working.append(api_id)
            print(f"  ✅ api_id={api_id} ({name}) — пускает, бот @{me.username}")
        except Exception as exc:  # noqa: BLE001
            state = rotator.note_failure(api_id, name, exc)
            print(f"  ❌ api_id={api_id} ({name}) — {state.code or state.kind}: "
                  f"{str(exc)[:110]} → пауза {credentials.human_delay(state.cooldown_left)}")
        finally:
            try:
                await client.stop()
            except Exception:  # noqa: BLE001
                pass
    print()
    print(rotator.report())
    if working:
        print(f"\nРабочие пары: {', '.join(str(api_id) for api_id in working)} — бот запустится на первой из них.")
        return 0
    print(_published_hint(rotator) or "\nНи одна пара не пустила бота — проверь api_keys.py / прокси.")
    return 1


async def check_environment() -> int:
    """python anime_bot.py --check — быстрая проверка без запуска бота."""
    config.ensure_dirs()
    print(f"• Версия кода: {config.CODE_VERSION} ({config.BUILD_SHA or 'локальная сборка'})")
    print(f"• Токен бота: {'задан' if config.BOT_TOKEN else 'НЕТ'}")
    print(f"• Пара по умолчанию: api_id={config.API_ID}")
    print(f"• Файлы: до {config.MAX_FILE_MB} МБ, кэш до {config.MAX_CACHE_GB} ГБ, задач параллельно {config.CONCURRENT_DOWNLOADS}")
    print(f"• Директория данных: {config.DATA_DIR}")
    storage.init()
    print("• SQLite: OK")
    try:
        releases = await api.search("bleach", 3)
        print(f"• AniLibria API: OK ({len(releases)} результатов, первый: {releases[0].title if releases else '—'})")
        if releases:
            full = await api.get_release(releases[0].id)
            print(f"  серий: {len(full.episodes)}, качества: {full.available_qualities()}")
    except Exception as exc:  # noqa: BLE001
        print(f"• AniLibria API: ОШИБКА ({exc})")
    await api.close()
    import shutil

    print(f"• ffmpeg: {shutil.which(config.FFMPEG) or 'НЕ НАЙДЕН (нужен apt-get install ffmpeg)'}")
    print(f"• ffprobe: {shutil.which(config.FFPROBE) or 'нет (будет резервный режим)'}")
    return 0


async def start_health_server() -> Optional[Any]:
    """Мини HTTP-сервер для PaaS-хостингов (Render/Railway/Fly ждут слушающий порт).

    Включается, только если задан HEALTH_PORT или PORT. Боту для работы он не нужен.
    """
    raw = (os.environ.get("HEALTH_PORT") or os.environ.get("PORT") or "").strip()
    if not raw.isdigit() or int(raw) == 0:
        return None
    port = int(raw)
    try:
        from aiohttp import web
    except Exception:  # noqa: BLE001
        return None

    async def handle(_request: "web.Request") -> "web.Response":
        return web.json_response({
            "status": "ok",
            "bot": BOT_USERNAME or "starting",
            "file_limit_mb": config.MAX_FILE_MB,
            "transport": "MTProto (Pyrogram)",
            "active_tasks": len(TASKS),
            "uptime_sec": int(time.time() - STARTED_AT),
        })

    application = web.Application()
    application.router.add_get("/", handle)
    application.router.add_get("/health", handle)
    runner = web.AppRunner(application)
    await runner.setup()
    site = web.TCPSite(runner, "0.0.0.0", port)
    await site.start()
    log.info("Health-сервер: http://0.0.0.0:%s/ (для проверок хостинга)", port)
    return runner


async def main() -> None:
    if "--check" in sys.argv:
        sys.exit(await check_environment())
    if "--creds" in sys.argv:
        sys.exit(await check_credentials(test_all="--creds-all" in sys.argv))
    config.ensure_dirs()
    storage.init()
    log.info("Запуск: версия %s (%s), лимит файлов %s МБ (MTProto), кэш %s ГБ, задач %s",
             config.CODE_VERSION, config.BUILD_SHA or "локальная сборка",
             config.MAX_FILE_MB, config.MAX_CACHE_GB, config.CONCURRENT_DOWNLOADS)
    health = await start_health_server()
    try:
        await run_supervisor()
    finally:
        log.info("Остановка…")
        await api.close()
        storage.close()
        if health is not None:
            try:
                await health.cleanup()
            except Exception:  # noqa: BLE001
                pass
        if APP is not None:
            try:
                await APP.stop()
            except Exception:  # noqa: BLE001
                pass


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
