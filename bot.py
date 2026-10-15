#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AniLibria MTProto USERBOT (Pyrofork) — просмотр аниме прямо в Telegram.

НЕ Bot API! Работа идёт напрямую через MTProto (Pyrofork):
 - приём и отправка файлов до 2 ГБ (лимит MTProto 2 GiB);
 - инлайн-поиск аниме (@bot запрос) через AniLibria API v1 (anilibria.top/api/v1);
 - выбор серии и качества (480p / 720p / 1080p);
 - сервер качает HLS-поток, превращает его в MP4 (ffmpeg) и отправляет в чат.

Все ключи (api_id / api_hash / bot_token) встроены прямо в код.
"""

import asyncio
import glob
import html
import json
import logging
import os
import random
import re
import shutil
import time
import uuid
from pathlib import Path

import aiohttp

try:  # Pyrofork может импортироваться как "pyrofork" или как форк "pyrogram"
    from pyrofork import Client, enums, filters, idle
    from pyrofork.types import (
        InlineKeyboardButton,
        InlineKeyboardMarkup,
        InlineQueryResultArticle,
        InputTextMessageContent,
    )
except ImportError:  # pragma: no cover
    from pyrogram import Client, enums, filters, idle
    from pyrogram.types import (
        InlineKeyboardButton,
        InlineKeyboardMarkup,
        InlineQueryResultArticle,
        InputTextMessageContent,
    )

# ============================================================
#  ВСТРОЕННЫЕ КЛЮЧИ (все пары api_id / api_hash)
# ============================================================
BOT_TOKEN = "8877653509:AAHkFOL8Vj6aqICiOg9Xwpn9ONE92affKtc"

API_PAIRS = [
    (4, "014b35b6184100b085b0d0572f9b5103"),      # Public Android Beta
    (5, "1c5c96d5edd401b1ed40db3fb5633e2d"),      # Public Static Final
    (6, "eb06d4abfb49dc3eeb1aeb98ae0f581e"),      # TG for Android
    (8, "7245de8e747a0d6fbe11f7cc14fcc0bb"),      # Public iOS Beta
    (2834, "68875f756c9b437a8b916ca3de215815"),   # Public MacOs Beta
    (9, "3975f648bb682ee889f35483bc618d1c"),      # Public unknown Beta
    (2040, "b18441a1ff607e10a989891a5462e627"),   # Public Win Beta
    (17349, "344583e45741c457fe1862106095a5eb"),  # Telegram Desktop
    (21724, "3e0cb5efcd52300aec5994fdfc5bdc16"),  # Telegram X (Android)
    (2496, "8da85b0d5bfe62527e5b244c209159c3"),   # web.telegram.org
    (94575, "a3406de8d171bb422bb6ddf3bbd800e2"),  # TDLib
    (2899, "36722c72256a24c1225de00eb6a1ca74"),   # Telegram messenger CLI
    (16623, "8c9dbfe58437d1739540f5d53c72ae4b"),  # Plus Messenger
    (10840, "33c45224029d59cb3ad0c16134215aeb"),  # Telegram Swift
]

# Полноценный userbot-режим: если задана строка сессии пользователя (StringSession)
USER_SESSION = os.environ.get("USER_SESSION", "").strip()

# ============================================================
#  НАСТРОЙКИ
# ============================================================
DATA_DIR = Path(os.environ.get("DATA_DIR", "/data"))
FILES_DIR = DATA_DIR / "files"
TMP_DIR = DATA_DIR / "tmp"
META_FILE = DATA_DIR / "files.json"
PAIR_FILE = DATA_DIR / "api_pair.json"

MAX_FILE_SIZE = 2 * 1024 * 1024 * 1024 - 1024 * 1024  # 2 GiB минус запас 1 МиБ (лимит MTProto)

API_BASES = [
    "https://anilibria.top/api/v1",
    "https://aniliberty.top/api/v1",
]
SITE_BASE = "https://anilibria.top"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

EPISODES_PER_PAGE = 10
FFMPEG_TIMEOUT = 20 * 60  # 20 минут на конвертацию

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
log = logging.getLogger("anilibria-bot")

for _d in (DATA_DIR, FILES_DIR, TMP_DIR):
    _d.mkdir(parents=True, exist_ok=True)


# ============================================================
#  Выбор пары api_id / api_hash (рандомизация + закрепление)
# ============================================================
def pick_api_pair():
    """Случайная пара api_id/api_hash; выбор сохраняется, чтобы сессия не слетала."""
    if PAIR_FILE.exists():
        try:
            data = json.loads(PAIR_FILE.read_text())
            return int(data["api_id"]), str(data["api_hash"])
        except Exception:
            pass
    api_id, api_hash = random.choice(API_PAIRS)
    try:
        PAIR_FILE.write_text(json.dumps({"api_id": api_id, "api_hash": api_hash}))
    except Exception:
        pass
    return api_id, api_hash


API_ID, API_HASH = pick_api_pair()
log.info("Используется api_id=%s (рандомизация параметров MTProto)", API_ID)

# ============================================================
#  Pyrofork MTProto клиент (НЕ Bot API!) — файлы до 2 ГБ
# ============================================================
_client_kwargs = dict(
    name="anilibria_userbot",
    api_id=API_ID,
    api_hash=API_HASH,
    workdir=str(DATA_DIR),
    sleep_threshold=30,
    max_concurrent_transmissions=4,
)
if USER_SESSION:
    # Полноценный USERBOT (аккаунт пользователя через StringSession)
    _client_kwargs["session_string"] = USER_SESSION
else:
    # Бот-сессия через MTProto (тот же протокол, лимит 2 GiB, не Bot API)
    _client_kwargs["bot_token"] = BOT_TOKEN

app = Client(**_client_kwargs)

# ============================================================
#  HTTP-сессия и кэш
# ============================================================
_http = None
RELEASES_CACHE = {}   # release_id -> release dict


def get_http() -> aiohttp.ClientSession:
    global _http
    if _http is None or _http.closed:
        _http = aiohttp.ClientSession(
            timeout=aiohttp.ClientTimeout(total=30),
            headers={"User-Agent": UA, "Accept": "application/json"},
        )
    return _http


def as_list(data):
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        for key in ("data", "releases", "list", "results", "items"):
            val = data.get(key)
            if isinstance(val, list):
                return val
    return []


def safe_name(name: str) -> str:
    """Безопасное имя файла (кириллица сохраняется)."""
    return re.sub(r"[^\w\-. ]", "_", str(name), flags=re.UNICODE)


def abs_url(url):
    if not url:
        return None
    if isinstance(url, dict):
        url = url.get("preview") or url.get("thumbnail") or url.get("optimized", {}).get("preview")
    if not url:
        return None
    if url.startswith("http://") or url.startswith("https://"):
        return url
    return SITE_BASE + (url if url.startswith("/") else "/" + url)


async def api_get(path, params=None):
    """GET к AniLibria API v1 с перебором зеркал."""
    last_exc = None
    for base in API_BASES:
        try:
            async with get_http().get(base + path, params=params) as resp:
                if resp.status == 200:
                    return await resp.json(content_type=None)
                last_exc = RuntimeError(f"HTTP {resp.status} for {path}")
        except Exception as exc:  # noqa: BLE001
            last_exc = exc
    log.error("API %s не ответил: %s", path, last_exc)
    return None


async def api_search(query, limit=10):
    data = await api_get("/app/search/releases", params={"query": query})
    return as_list(data)[:limit]


async def api_release(release_id):
    data = await api_get(f"/anime/releases/{release_id}")
    if isinstance(data, dict) and data.get("id"):
        RELEASES_CACHE[str(data["id"])] = data
        return data
    return None


async def api_random_release():
    data = await api_get("/anime/releases/random", params={"limit": 1})
    items = as_list(data)
    return items[0] if items else None


# ============================================================
#  Форматирование карточек
# ============================================================
def title_names(rel):
    name = rel.get("name") or {}
    if isinstance(name, str):
        return name, ""
    return name.get("main") or name.get("english") or "Без названия", name.get("english") or ""


def title_type(rel):
    t = rel.get("type")
    if isinstance(t, dict):
        return t.get("description") or t.get("value") or ""
    return str(t or "")


def title_age(rel):
    ar = rel.get("age_rating")
    if isinstance(ar, dict):
        return ar.get("label") or ""
    return str(ar or "")


def title_rating(rel):
    r = rel.get("rating")
    if isinstance(r, dict):
        avg = r.get("average")
        return f"{avg:.2f}".rstrip("0").rstrip(".") if isinstance(avg, (int, float)) else ""
    return ""


def release_card_text(rel):
    main, eng = title_names(rel)
    year = rel.get("year") or "—"
    typ = title_type(rel) or "—"
    age = title_age(rel)
    rating = title_rating(rel)
    eps_total = rel.get("episodes_total") or len(rel.get("episodes") or []) or "?"
    ongoing = " 🔥онгоинг" if rel.get("is_ongoing") else ""
    adult = " 🔞" if "18" in str(age) else ""
    desc = (rel.get("description") or "").strip()
    if len(desc) > 700:
        desc = desc[:700] + "…"
    lines = [
        f"🎬 <b>{html.escape(str(main))}</b>{adult}{ongoing}",
    ]
    if eng and eng != main:
        lines.append(f"<i>{html.escape(str(eng))}</i>")
    meta = f"📅 {html.escape(str(year))} • {html.escape(str(typ))} • 📺 {eps_total} сер."
    if age:
        meta += f" • {html.escape(str(age))}"
    if rating:
        meta += f" • ⭐ {rating}"
    lines.append(meta)
    if desc:
        lines.append("")
        lines.append(html.escape(desc))
    return "\n".join(lines)


def episode_list_markup(release_id, page=0):
    rel = RELEASES_CACHE.get(str(release_id)) or {}
    episodes = rel.get("episodes") or []
    total = len(episodes)
    pages = max(1, (total + EPISODES_PER_PAGE - 1) // EPISODES_PER_PAGE)
    page = max(0, min(page, pages - 1))
    start = page * EPISODES_PER_PAGE
    chunk = episodes[start:start + EPISODES_PER_PAGE]

    rows = []
    row = []
    for idx, ep in enumerate(chunk, start=start):
        ordinal = ep.get("ordinal") if ep.get("ordinal") is not None else idx + 1
        row.append(InlineKeyboardButton(
            f"▶️ {ordinal}",
            callback_data=f"q:{release_id}:{idx}",
        ))
        if len(row) == 5:
            rows.append(row)
            row = []
    if row:
        rows.append(row)

    nav = []
    if page > 0:
        nav.append(InlineKeyboardButton("⬅️", callback_data=f"p:{release_id}:{page - 1}"))
    nav.append(InlineKeyboardButton(f"{page + 1}/{pages}", callback_data="noop"))
    if page < pages - 1:
        nav.append(InlineKeyboardButton("➡️", callback_data=f"p:{release_id}:{page + 1}"))
    rows.append(nav)
    rows.append([InlineKeyboardButton("🔍 Новый поиск", switch_inline_query_current_chat="")])
    return InlineKeyboardMarkup(rows)


def quality_markup(release_id, idx):
    rel = RELEASES_CACHE.get(str(release_id)) or {}
    episodes = rel.get("episodes") or []
    if idx >= len(episodes):
        return None
    ep = episodes[idx]
    buttons = []
    for q in (480, 720, 1080):
        if ep.get(f"hls_{q}"):
            buttons.append(InlineKeyboardButton(
                f"{q}p", callback_data=f"d:{release_id}:{idx}:{q}",
            ))
    if not buttons:
        return None
    rows = [buttons, [InlineKeyboardButton("⬅️ к сериям", callback_data=f"r:{release_id}")]]
    return InlineKeyboardMarkup(rows)


# ============================================================
#  Инлайн-поиск (@bot запрос)
# ============================================================
@app.on_inline_query()
async def inline_search(client, query):
    text = (query.query or "").strip()
    if not text:
        await query.answer(
            results=[],
            cache_time=5,
            is_personal=True,
            switch_pm_text="✏️ Введите название аниме",
            switch_pm_parameter="help",
        )
        return

    titles = await api_search(text, limit=10)
    results = []
    for rel in titles:
        rel_id = rel.get("id")
        if rel_id is None:
            continue
        RELEASES_CACHE[str(rel_id)] = rel
        main, eng = title_names(rel)
        year = rel.get("year") or "—"
        typ = title_type(rel) or "—"
        eps_total = rel.get("episodes_total") or len(rel.get("episodes") or []) or "?"
        age = title_age(rel)
        adult = " 🔞" if "18" in str(age) else ""
        desc = f"{year} • {typ} • {eps_total} сер.{(' • ' + age) if age else ''}"
        thumb = abs_url(rel.get("poster"))
        kb = InlineKeyboardMarkup([[
            InlineKeyboardButton("📺 Выбрать серию", callback_data=f"r:{rel_id}"),
        ]])
        results.append(InlineQueryResultArticle(
            id=f"{rel_id}",
            title=f"{main}{adult}",
            description=desc,
            thumb_url=thumb,
            input_message_content=InputTextMessageContent(
                message_text=(
                    f"🎬 <b>{html.escape(str(main))}</b>{adult}\n"
                    f"<i>{html.escape(str(eng))}</i>\n"
                    f"📅 {html.escape(str(year))} • {html.escape(str(typ))} • 📺 {eps_total} сер.\n\n"
                    f"Нажмите кнопку ниже, чтобы выбрать серию и качество 👇"
                ),
                parse_mode=enums.ParseMode.HTML,
            ),
            reply_markup=kb,
        ))

    if not results:
        results.append(InlineQueryResultArticle(
            id="empty",
            title="Ничего не найдено 😔",
            description=f"По запросу «{text}» нет результатов",
            input_message_content=InputTextMessageContent(
                message_text=f"😔 По запросу «{html.escape(text)}» ничего не найдено.",
                parse_mode=enums.ParseMode.HTML,
            ),
        ))

    await query.answer(results=results, cache_time=300, is_personal=False)


# ============================================================
#  Callback-кнопки
# ============================================================
async def edit_cq(cq, text, markup=None):
    """Редактирование сообщения (и обычного, и инлайн)."""
    try:
        await cq.edit_message_text(
            text,
            parse_mode=enums.ParseMode.HTML,
            reply_markup=markup,
        )
    except Exception:
        try:
            if cq.message:
                await cq.message.edit_text(
                    text, parse_mode=enums.ParseMode.HTML, reply_markup=markup,
                )
            elif getattr(cq, "inline_message_id", None):
                await app.edit_message_text(
                    inline_message_id=cq.inline_message_id,
                    text=text,
                    parse_mode=enums.ParseMode.HTML,
                    reply_markup=markup,
                )
        except Exception as exc:  # noqa: BLE001
            log.debug("edit_cq: %s", exc)


@app.on_callback_query(filters.regex(r"^noop$"))
async def cb_noop(client, cq):
    await cq.answer()


@app.on_callback_query(filters.regex(r"^r:(\d+)$"))
async def cb_release(client, cq):
    release_id = int(cq.matches[0].group(1))
    rel = RELEASES_CACHE.get(str(release_id)) or await api_release(release_id)
    if not rel:
        await cq.answer("Релиз не найден 😔", show_alert=True)
        return
    RELEASES_CACHE[str(release_id)] = rel
    await cq.answer()
    await edit_cq(cq, release_card_text(rel) + "\n\n📺 <b>Выберите серию:</b>", episode_list_markup(release_id, 0))


@app.on_callback_query(filters.regex(r"^p:(\d+):(\d+)$"))
async def cb_page(client, cq):
    release_id = int(cq.matches[0].group(1))
    page = int(cq.matches[0].group(2))
    rel = RELEASES_CACHE.get(str(release_id)) or await api_release(release_id)
    if not rel:
        await cq.answer("Релиз не найден 😔", show_alert=True)
        return
    RELEASES_CACHE[str(release_id)] = rel
    await cq.answer()
    await edit_cq(cq, release_card_text(rel) + "\n\n📺 <b>Выберите серию:</b>", episode_list_markup(release_id, page))


@app.on_callback_query(filters.regex(r"^q:(\d+):(\d+)$"))
async def cb_quality(client, cq):
    release_id = int(cq.matches[0].group(1))
    idx = int(cq.matches[0].group(2))
    rel = RELEASES_CACHE.get(str(release_id)) or await api_release(release_id)
    if not rel:
        await cq.answer("Релиз не найден 😔", show_alert=True)
        return
    RELEASES_CACHE[str(release_id)] = rel
    episodes = rel.get("episodes") or []
    if idx >= len(episodes):
        await cq.answer("Серия не найдена 😔", show_alert=True)
        return
    ep = episodes[idx]
    ordinal = ep.get("ordinal") if ep.get("ordinal") is not None else idx + 1
    ep_name = ep.get("name") or ""
    markup = quality_markup(release_id, idx)
    if markup is None:
        await cq.answer("Для этой серии нет доступных потоков 😔", show_alert=True)
        return
    main, _ = title_names(rel)
    text = (
        f"🎬 <b>{html.escape(str(main))}</b>\n"
        f"📺 Серия {ordinal}" + (f" — {html.escape(str(ep_name))}" if ep_name else "") + "\n\n"
        f"🎚 <b>Выберите качество:</b>"
    )
    await cq.answer()
    await edit_cq(cq, text, markup)


# ============================================================
#  Скачивание HLS → MP4 → отправка (до 2 ГБ)
# ============================================================
def hls_for_episode(ep, quality):
    url = ep.get(f"hls_{quality}")
    if isinstance(url, dict):
        url = url.get("url") or url.get("src")
    return url or None


async def run_ffmpeg(args, timeout=FFMPEG_TIMEOUT):
    proc = await asyncio.create_subprocess_exec(
        *args,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    try:
        _, err = await asyncio.wait_for(proc.communicate(), timeout=timeout)
    except asyncio.TimeoutError:
        proc.kill()
        await proc.wait()
        return 124, b"timeout"
    return proc.returncode, err


async def hls_to_mp4(hls_url, out_path: Path):
    """Конвертация HLS-потока в MP4 (сначала remux, затем fallback с перекодированием)."""
    headers = f"Referer: {SITE_BASE}/\r\n"
    common_in = ["-user_agent", UA, "-headers", headers, "-i", hls_url]

    # 1) Быстрый remux (без перекодирования)
    rc, err = await run_ffmpeg([
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        *common_in,
        "-c", "copy",
        "-bsf:a", "aac_adtstoasc",
        "-movflags", "+faststart",
        str(out_path),
    ])
    if rc == 0 and out_path.exists() and out_path.stat().st_size > 0:
        return True

    # 2) Fallback: полная перекодировка (если remux не удался)
    log.warning("Remux не удался (%s), перекодировка...", (err or b"")[-400:])
    rc, err = await run_ffmpeg([
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        *common_in,
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
        "-c:a", "aac", "-b:a", "160k",
        "-movflags", "+faststart",
        str(out_path),
    ])
    return rc == 0 and out_path.exists() and out_path.stat().st_size > 0


async def split_mp4(src: Path, max_size: int):
    """Разбивает файл на части меньше max_size (если вдруг больше 2 ГБ)."""
    size = src.stat().st_size
    if size <= max_size:
        return [src]
    # Оценка длительности через ffprobe
    rc, out = await run_ffmpeg([
        "ffprobe", "-v", "error", "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1", str(src),
    ])
    try:
        duration = float((out or b"").decode().strip())
    except Exception:
        duration = 0.0
    if duration <= 0:
        return [src]
    seg_time = max(60.0, duration * (max_size / size) * 0.9)
    pattern = str(src.with_name(src.stem + "_part_%03d.mp4"))
    rc, _ = await run_ffmpeg([
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-i", str(src),
        "-c", "copy",
        "-f", "segment",
        "-segment_time", str(int(seg_time)),
        "-reset_timestamps", "1",
        pattern,
    ])
    parts = sorted(Path(p) for p in glob.glob(str(src.with_name(src.stem + "_part_*.mp4"))))
    return parts if rc == 0 and parts else [src]


async def upload_progress(current, total, msg, tag="📤 Отправка"):
    pct = current * 100 / total if total else 0
    bar = "█" * int(pct // 5) + "░" * (20 - int(pct // 5))
    now = time.time()
    if now - getattr(msg, "_last_edit", 0) < 2:
        return
    msg._last_edit = now
    try:
        await msg.edit_text(
            f"{tag}\n<code>[{bar}]</code> {pct:.1f}%\n"
            f"{current / 1024 / 1024:.1f} / {total / 1024 / 1024:.1f} МБ",
            parse_mode=enums.ParseMode.HTML,
        )
    except Exception:  # noqa: BLE001
        pass


@app.on_callback_query(filters.regex(r"^d:(\d+):(\d+):(\d+)$"))
async def cb_download(client, cq):
    release_id = int(cq.matches[0].group(1))
    idx = int(cq.matches[0].group(2))
    quality = int(cq.matches[0].group(3))

    rel = RELEASES_CACHE.get(str(release_id)) or await api_release(release_id)
    if not rel:
        await cq.answer("Релиз не найден 😔", show_alert=True)
        return
    RELEASES_CACHE[str(release_id)] = rel
    episodes = rel.get("episodes") or []
    if idx >= len(episodes):
        await cq.answer("Серия не найдена 😔", show_alert=True)
        return
    ep = episodes[idx]
    hls_url = hls_for_episode(ep, quality)
    if not hls_url:
        await cq.answer(f"Поток {quality}p недоступен 😔", show_alert=True)
        return

    main, _ = title_names(rel)
    ordinal = ep.get("ordinal") if ep.get("ordinal") is not None else idx + 1

    await cq.answer("⏳ Загрузка началась...")
    status = None
    try:
        if cq.message:
            status = await cq.message.reply_text("⚙️ Качаю поток и конвертирую в MP4...")
        else:
            status = await client.send_message(
                cq.from_user.id, "⚙️ Качаю поток и конвертирую в MP4...",
            )
    except Exception:
        pass

    workdir = TMP_DIR / uuid.uuid4().hex
    workdir.mkdir(parents=True, exist_ok=True)
    out_path = workdir / f"{safe_name(main)}_ep{ordinal}_{quality}p.mp4"

    try:
        if status:
            await status.edit_text(f"⚙️ Конвертация в MP4 (ffmpeg)...\n🎬 {html.escape(str(main))} — серия {ordinal} ({quality}p)")
        ok = await hls_to_mp4(hls_url, out_path)
        if not ok:
            if status:
                await status.edit_text("❌ Ошибка конвертации потока 😔")
            return

        parts = await split_mp4(out_path, MAX_FILE_SIZE)
        # Определяем целевой чат: куда нажали кнопку, иначе — в личку
        chat_id = cq.message.chat.id if cq.message else cq.from_user.id

        for part in parts:
            part_size = part.stat().st_size
            if part_size > MAX_FILE_SIZE:
                if status:
                    await status.edit_text("❌ Файл больше 2 ГБ даже после разбиения 😔")
                return
            caption = (
                f"🎬 <b>{html.escape(str(main))}</b>\n"
                f"📺 Серия {ordinal} • {quality}p"
            )
            tag = f"📤 Отправка: {part.name}"
            if status:
                try:
                    await status.edit_text(f"{tag}\n⏳ 0%")
                except Exception:
                    pass
            is_video = part.suffix.lower() == ".mp4"
            try:
                if is_video:
                    await client.send_video(
                        chat_id,
                        video=str(part),
                        caption=caption,
                        parse_mode=enums.ParseMode.HTML,
                        supports_streaming=True,
                        file_name=part.name,
                        progress=upload_progress,
                        progress_args=(status, tag) if status else (),
                    )
                else:
                    await client.send_document(
                        chat_id,
                        document=str(part),
                        caption=caption,
                        parse_mode=enums.ParseMode.HTML,
                        file_name=part.name,
                        progress=upload_progress,
                        progress_args=(status, tag) if status else (),
                    )
            except Exception as exc:  # noqa: BLE001
                log.error("Ошибка отправки: %s", exc)
                if status:
                    await status.edit_text(f"❌ Ошибка отправки: {html.escape(str(exc))}")
                return

        if status:
            await status.edit_text(
                f"✅ Готово! {html.escape(str(main))} — серия {ordinal} ({quality}p) "
                f"отправлена{' частями' if len(parts) > 1 else ''}."
            )
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


# ============================================================
#  Приём и выдача файлов до 2 ГБ
# ============================================================
def load_meta():
    if META_FILE.exists():
        try:
            return json.loads(META_FILE.read_text())
        except Exception:
            pass
    return {}


def save_meta(meta):
    META_FILE.write_text(json.dumps(meta, ensure_ascii=False, indent=2))


@app.on_message((filters.document | filters.video | filters.audio | filters.animation) & filters.private)
async def on_file(client, message):
    media = message.document or message.video or message.audio or message.animation
    size = getattr(media, "file_size", 0) or 0
    if size > MAX_FILE_SIZE:
        await message.reply_text("❌ Файл больше 2 ГБ — MTProto-лимит 😔")
        return
    name = getattr(media, "file_name", None) or f"file_{uuid.uuid4().hex[:8]}"
    file_id = uuid.uuid4().hex[:10]
    status = await message.reply_text(f"📥 Приём файла (до 2 ГБ)...\n<b>{html.escape(name)}</b>")

    dest = FILES_DIR / f"{file_id}_{safe_name(name)}"
    try:
        await message.download(
            file_name=str(dest),
            progress=upload_progress,
            progress_args=(status, "📥 Приём:"),
        )
    except Exception as exc:  # noqa: BLE001
        await status.edit_text(f"❌ Ошибка приёма: {html.escape(str(exc))}")
        return

    meta = load_meta()
    meta[file_id] = {
        "name": name,
        "path": str(dest),
        "size": size,
        "user_id": message.from_user.id if message.from_user else 0,
        "ts": int(time.time()),
    }
    save_meta(meta)
    kb = InlineKeyboardMarkup([[
        InlineKeyboardButton("📤 Отправить обратно", callback_data=f"f:{file_id}"),
    ]])
    await status.edit_text(
        f"✅ Файл принят!\n<b>{html.escape(name)}</b> ({size / 1024 / 1024:.1f} МБ)\n"
        f"🆔 <code>{file_id}</code>\n\nКоманда /get {file_id} — отправить обратно.",
        reply_markup=kb,
    )


@app.on_callback_query(filters.regex(r"^f:([0-9a-f]{6,32})$"))
async def cb_send_file(client, cq):
    file_id = cq.matches[0].group(1)
    meta = load_meta()
    item = meta.get(file_id)
    if not item:
        await cq.answer("Файл не найден 😔", show_alert=True)
        return
    await cq.answer("📤 Отправляю...")
    chat_id = cq.message.chat.id if cq.message else cq.from_user.id
    path = Path(item["path"])
    if not path.exists():
        await cq.answer("Файл удалён с диска 😔", show_alert=True)
        return
    status = None
    try:
        status = await client.send_message(chat_id, f"📤 Отправляю {html.escape(item['name'])}...")
    except Exception:
        pass
    try:
        await client.send_document(
            chat_id,
            document=str(path),
            file_name=item["name"],
            caption=f"📤 <b>{html.escape(item['name'])}</b> ({item['size'] / 1024 / 1024:.1f} МБ)",
            parse_mode=enums.ParseMode.HTML,
            progress=upload_progress,
            progress_args=(status, "📤 Отправка:") if status else (),
        )
        if status:
            await status.edit_text(f"✅ Отправлено: {html.escape(item['name'])}")
    except Exception as exc:  # noqa: BLE001
        if status:
            await status.edit_text(f"❌ Ошибка отправки: {html.escape(str(exc))}")


# ============================================================
#  Команды
# ============================================================
HELP_TEXT = (
    "🎌 <b>AniLibria MTProto userbot</b> — смотри аниме прямо в Telegram!\n\n"
    "⚡️ <b>Инлайн-поиск</b> — в любом чате напиши:\n"
    "<code>@{bot} название аниме</code>\n\n"
    "📋 <b>Команды:</b>\n"
    "/search <code>запрос</code> — поиск аниме\n"
    "/random — случайный тайтл\n"
    "/files — мои принятые файлы\n"
    "/get <code>id</code> — отправить файл обратно\n"
    "/del <code>id</code> — удалить файл\n\n"
    "💾 Просто скинь мне файл до <b>2 ГБ</b> — я его приму и смогу вернуть.\n"
    "⚙️ Серии скачиваются с AniLibria API v1, конвертируются в MP4 и отправляются "
    "в выбранном качестве (480p / 720p / 1080p).\n\n"
    "🔧 Протокол: <b>MTProto (Pyrofork)</b>, не Bot API — лимит файла <b>2 ГБ</b>."
)


@app.on_message(filters.command(["start", "help"]))
async def cmd_start(client, message):
    me = await client.get_me()
    await message.reply_text(
        HELP_TEXT.format(bot=me.username or "bot"),
        parse_mode=enums.ParseMode.HTML,
    )


@app.on_message(filters.command("search"))
async def cmd_search(client, message):
    query = message.text.split(maxsplit=1)[1] if len(message.text.split(maxsplit=1)) > 1 else ""
    if not query:
        await message.reply_text("Использование: <code>/search название</code>", parse_mode=enums.ParseMode.HTML)
        return
    titles = await api_search(query, limit=10)
    if not titles:
        await message.reply_text("😔 Ничего не найдено.")
        return
    rows = []
    for rel in titles:
        rel_id = rel.get("id")
        if rel_id is None:
            continue
        RELEASES_CACHE[str(rel_id)] = rel
        main, _ = title_names(rel)
        year = rel.get("year") or "—"
        rows.append([InlineKeyboardButton(
            f"🎬 {main} ({year})", callback_data=f"r:{rel_id}",
        )])
    rows.append([InlineKeyboardButton("🔍 Инлайн-поиск", switch_inline_query_current_chat=query)])
    await message.reply_text(
        f"🔎 Результаты по запросу <b>{html.escape(query)}</b>:",
        parse_mode=enums.ParseMode.HTML,
        reply_markup=InlineKeyboardMarkup(rows),
    )


@app.on_message(filters.command("random"))
async def cmd_random(client, message):
    rel = await api_random_release()
    if not rel:
        await message.reply_text("😔 Не удалось получить случайный тайтл.")
        return
    rel_id = rel.get("id")
    RELEASES_CACHE[str(rel_id)] = rel
    kb = InlineKeyboardMarkup([[
        InlineKeyboardButton("📺 Выбрать серию", callback_data=f"r:{rel_id}"),
        InlineKeyboardButton("🎲 Ещё раз", callback_data="rnd"),
    ]])
    await message.reply_text(
        release_card_text(rel), parse_mode=enums.ParseMode.HTML, reply_markup=kb,
    )


@app.on_callback_query(filters.regex(r"^rnd$"))
async def cb_random(client, cq):
    rel = await api_random_release()
    if not rel:
        await cq.answer("😔 Ошибка API", show_alert=True)
        return
    rel_id = rel.get("id")
    RELEASES_CACHE[str(rel_id)] = rel
    kb = InlineKeyboardMarkup([[
        InlineKeyboardButton("📺 Выбрать серию", callback_data=f"r:{rel_id}"),
        InlineKeyboardButton("🎲 Ещё раз", callback_data="rnd"),
    ]])
    await cq.answer()
    await edit_cq(cq, release_card_text(rel), kb)


@app.on_message(filters.command("files"))
async def cmd_files(client, message):
    meta = load_meta()
    user_id = message.from_user.id if message.from_user else 0
    items = [(fid, it) for fid, it in meta.items() if it.get("user_id") in (0, user_id)]
    if not items:
        await message.reply_text("🗂 У вас нет принятых файлов. Скиньте мне файл до 2 ГБ!")
        return
    lines = ["🗂 <b>Ваши файлы:</b>\n"]
    rows = []
    for fid, it in items[-25:]:
        lines.append(
            f"• <code>{fid}</code> — {html.escape(it['name'])} "
            f"({it['size'] / 1024 / 1024:.1f} МБ)"
        )
        rows.append([InlineKeyboardButton(
            f"📤 {it['name'][:30]}", callback_data=f"f:{fid}",
        )])
    await message.reply_text(
        "\n".join(lines),
        parse_mode=enums.ParseMode.HTML,
        reply_markup=InlineKeyboardMarkup(rows) if rows else None,
    )


@app.on_message(filters.command("get"))
async def cmd_get(client, message):
    parts = message.text.split(maxsplit=1)
    if len(parts) < 2:
        await message.reply_text("Использование: <code>/get id_файла</code>", parse_mode=enums.ParseMode.HTML)
        return
    file_id = parts[1].strip()
    meta = load_meta()
    item = meta.get(file_id)
    if not item:
        await message.reply_text("😔 Файл не найден.")
        return
    path = Path(item["path"])
    if not path.exists():
        await message.reply_text("😔 Файл удалён с диска.")
        return
    status = await message.reply_text(f"📤 Отправляю {html.escape(item['name'])}...")
    try:
        await client.send_document(
            message.chat.id,
            document=str(path),
            file_name=item["name"],
            caption=f"📤 <b>{html.escape(item['name'])}</b>",
            parse_mode=enums.ParseMode.HTML,
            progress=upload_progress,
            progress_args=(status, "📤 Отправка:"),
        )
        await status.edit_text(f"✅ Отправлено: {html.escape(item['name'])}")
    except Exception as exc:  # noqa: BLE001
        await status.edit_text(f"❌ Ошибка: {html.escape(str(exc))}")


@app.on_message(filters.command("del"))
async def cmd_del(client, message):
    parts = message.text.split(maxsplit=1)
    if len(parts) < 2:
        await message.reply_text("Использование: <code>/del id_файла</code>", parse_mode=enums.ParseMode.HTML)
        return
    file_id = parts[1].strip()
    meta = load_meta()
    item = meta.pop(file_id, None)
    if not item:
        await message.reply_text("😔 Файл не найден.")
        return
    Path(item["path"]).unlink(missing_ok=True)
    save_meta(meta)
    await message.reply_text(f"🗑 Удалено: {html.escape(item['name'])}")


# ============================================================
#  Запуск
# ============================================================
async def _boot():
    await app.start()
    me = await app.get_me()
    kind = "USERBOT (аккаунт)" if USER_SESSION else "MTProto-бот"
    log.info("🚀 Запущен как %s: @%s (id=%s) | api_id=%s", kind, me.username or me.id, me.id, API_ID)
    print(f"🚀 AniLibria MTProto userbot запущен: @{me.username or me.id} | лимит файла 2 ГБ")
    await idle()
    await app.stop()


if __name__ == "__main__":
    app.loop.run_until_complete(_boot())
