# -*- coding: utf-8 -*-
"""
Тексты и inline-клавиатуры бота (русский, HTML-разметка).

Схема callback_data (жёстко ≤64 байт, поэтому только id и номера):
    R|{rid}                карточка релиза
    E|{rid}|{page}         список серий (страница)
    P|{rid}|{ep}           выбор качества
    D|{rid}|{ep}|{q}       скачать серию в качестве q
    A|{rid}|{ep}|{q}       скачать все серии подряд, начиная с ep
    N|{rid}|{ep}|{q}       сразу следующая серия
    F|{rid}                избранное (вкл/выкл)
    H|{page}               история
    V|{page}               избранное списком
    L|{page}               мои файлы (которые присылали боту)
    S|{fid}                отправить мой файл обратно
    U|{fid}                сохранить мой файл на сервер
    W|{fid}                удалить мой файл из списка
    C|{task}               отмена задачи
    Q|{q}                  качество по умолчанию
    X|{rid}                удалить сообщение
    M|{rid}                главное меню
    T|{mode}               разделы: новые, рандом, помощь, настройки
"""

from __future__ import annotations

import html
from typing import Any, Dict, Iterable, List, Optional, Sequence

from pyrogram.types import (
    InlineKeyboardButton,
    InlineKeyboardMarkup,
    KeyboardButton,
    ReplyKeyboardMarkup,
)

import config
import dl_engine


def esc(text: Any) -> str:
    return html.escape(str(text or ""), quote=False)


def truncate(text: str, limit: int) -> str:
    text = (text or "").strip()
    if len(text) <= limit:
        return text
    cut = text[: limit - 1]
    if " " in cut:
        cut = cut[: cut.rfind(" ")]
    return cut.rstrip() + "…"


def progress_bar(percent: float, length: int = 12) -> str:
    percent = max(0.0, min(100.0, percent))
    filled = int(round(percent / 100 * length))
    return "▰" * filled + "▱" * (length - filled)


# ---------------------------------------------------------------------------
# Меню
# ---------------------------------------------------------------------------
def main_menu_text(first_name: str = "", is_owner: bool = False) -> str:
    name = f", {esc(first_name)}" if first_name else ""
    return (
        f"🎌 <b>Аниме-бот на AniLibria API v1</b>\n\n"
        f"Привет{name}! Я умею искать аниме прямо в Telegram и присылать серии "
        f"<b>файлами до 2 ГБ</b> (MTProto, а не урезанный Bot API).\n\n"
        f"🔍 <b>Поиск:</b> напиши название сюда или вызови меня в любом чате через "
        f"<code>@{esc(_bot_username_hint())}</code> (инлайн-поиск).\n"
        f"📥 <b>Скачивание:</b> сервер сам скачает HLS с AniLibria и соберёт MP4.\n"
        f"📤 <b>Файлы:</b> можешь прислать мне свой файл до 2 ГБ — сохраню и верну по кнопке.\n"
    )


def _bot_username_hint() -> str:
    return "имя_вашего_бота"


def main_menu_kb(is_owner: bool = False) -> InlineKeyboardMarkup:
    rows = [
        [
            InlineKeyboardButton("🔍 Поиск", switch_inline_query_current_chat=""),
            InlineKeyboardButton("🆕 Новинки", callback_data="T|new"),
        ],
        [
            InlineKeyboardButton("🎲 Рандом", callback_data="T|random"),
            InlineKeyboardButton("⭐ Избранное", callback_data="V|0"),
        ],
        [
            InlineKeyboardButton("🕓 История", callback_data="H|0"),
            InlineKeyboardButton("📁 Мои файлы", callback_data="L|0"),
        ],
        [
            InlineKeyboardButton("⚙️ Качество", callback_data="T|settings"),
            InlineKeyboardButton("❓ Помощь", callback_data="T|help"),
        ],
    ]
    if is_owner:
        rows.append([InlineKeyboardButton("📊 Статистика", callback_data="T|stats")])
    return InlineKeyboardMarkup(rows)


def help_text(bot_username: str = "") -> str:
    mention = f"@{bot_username}" if bot_username else "@ваш_бот"
    return (
        "❓ <b>Как пользоваться</b>\n\n"
        f"1. <b>Инлайн-поиск:</b> в любом чате набери <code>{esc(mention)} наруто</code> — "
        "выбери тайтл из подсказок, откроется карточка (в боте по тексту поиск не работает).\n"
        "2. <b>Кнопки внизу:</b> «⭐ Избранное», «🔍 Поиск», «▶️ Продолжить» — всегда под полем ввода.\n"
        "3. <b>Серии:</b> в карточке релиза → «📺 Серии» → выбираешь серию → качество → ждёшь MP4.\n"
        "4. <b>2 ГБ:</b> работа идёт через MTProto (как у userbot), поэтому лимит файла — 2000 МБ, "
        "а не 50 МБ, как у обычных ботов на Bot API.\n"
        "5. <b>Приём файлов:</b> пришли видео/документ до 2 ГБ — сохраню и отдам обратно по кнопке.\n\n"
        "<b>Команды:</b>\n"
        "/start — меню\n"
        "/search &lt;название&gt; — поиск\n"
        "/random — случайный тайтл\n"
        "/files — мои присланные файлы\n"
        "/settings — качество по умолчанию\n"
        "/stats — статистика (владелец)\n"
        "/id — мой Telegram ID\n\n"
        "⚠️ Видео берётся с публичного API AniLibria (AniLiberty) и не хранится у бота: "
        "сервер скачивает поток, собирает MP4 и отправляет его тебе.\n"
    )


def settings_text(quality: int) -> str:
    return (
        "⚙️ <b>Настройки</b>\n\n"
        f"Качество по умолчанию: <b>{quality}p</b>\n\n"
        "Если для серии нет выбранного качества, бот автоматически возьмёт ближайшее доступное."
    )


def settings_kb(current: int) -> InlineKeyboardMarkup:
    buttons = []
    for quality in config.QUALITY_CHOICES:
        mark = "✅ " if quality == current else ""
        buttons.append(InlineKeyboardButton(f"{mark}{quality}p", callback_data=f"Q|{quality}"))
    return InlineKeyboardMarkup([buttons, [InlineKeyboardButton("🏠 Меню", callback_data="M|0")]])


# ---------------------------------------------------------------------------
# Релизы
# ---------------------------------------------------------------------------
def release_text(release: Any, is_fav: bool = False) -> str:
    parts: List[str] = [f"🎬 <b>{esc(release.title)}</b>"]
    alt: List[str] = []
    if release.name_english and release.name_english != release.title:
        alt.append(esc(release.name_english))
    if release.name_alternative:
        alt.append(esc(truncate(release.name_alternative, 120)))
    if alt:
        parts.append("<i>" + " / ".join(alt) + "</i>")
    meta: List[str] = []
    if release.year:
        meta.append(str(release.year))
    if release.type_description or release.type_value:
        meta.append(esc(release.type_description or release.type_value))
    if release.episodes_total:
        meta.append(f"серий: {release.episodes_total}")
    if release.is_ongoing:
        meta.append("онгоинг")
    if meta:
        parts.append(" · ".join(meta))
    if release.genres:
        parts.append("🏷 " + esc(", ".join(release.genres[:8])))
    quality = release.available_qualities()
    if quality:
        parts.append("🎚 Доступные качества: " + ", ".join(f"{q}p" for q in quality))
    if release.description:
        parts.append("\n📖 " + esc(truncate(release.description, 700)))
    if is_fav:
        parts.append("\n⭐ В избранном")
    return "\n".join(parts)


def release_kb(release_id: int, is_fav: bool = False, has_episodes: bool = True,
               site_url: Optional[str] = None, deep_link: Optional[str] = None) -> InlineKeyboardMarkup:
    """Карточка тайтла: только «Смотреть», избранное, поделиться и меню."""
    rows: List[List[InlineKeyboardButton]] = []
    if has_episodes:
        rows.append([InlineKeyboardButton("▶️ Смотреть", callback_data=f"E|{release_id}|0")])
    fav_text = "💔 Убрать из избранного" if is_fav else "⭐ В избранное"
    rows.append([InlineKeyboardButton(fav_text, callback_data=f"F|{release_id}")])
    if deep_link:
        rows.append([InlineKeyboardButton("📤 Поделиться", url=deep_link)])
    rows.append([InlineKeyboardButton("🏠 Меню", callback_data="M|0")])
    return InlineKeyboardMarkup(rows)


def inline_card_text(release: Any) -> str:
    """Карточка, которую отправляет инлайн-режим (ссылка в конце = превью постера)."""
    text = release_text(release)
    if len(text) > 900:
        text = text[:900].rsplit("\n", 1)[0] + "…"
    site = getattr(release, "site_url", "") or ""
    return f"{text}\n\n{site}" if site else text


def search_results_text(query: str, releases: Sequence[Any]) -> str:
    if not releases:
        return (
            f"😔 По запросу <b>{esc(query)}</b> ничего не нашлось.\n\n"
            "Попробуй другое название (русское, английское или ромадзи)."
        )
    return (
        f"🔍 Результаты по <b>{esc(query)}</b> — {len(releases)} шт.\n"
        "Выбери тайтл кнопкой ниже 👇"
    )


def search_results_kb(releases: Sequence[Any]) -> InlineKeyboardMarkup:
    rows: List[List[InlineKeyboardButton]] = []
    for release in releases[:12]:
        label = truncate(release.title, 52)
        if release.year:
            label += f" ({release.year})"
        rows.append([InlineKeyboardButton(label, callback_data=f"R|{int(release.id)}")])
    rows.append([InlineKeyboardButton("🏠 Меню", callback_data="M|0")])
    return InlineKeyboardMarkup(rows)


def latest_kb(releases: Sequence[Any], page: int = 0) -> InlineKeyboardMarkup:
    rows: List[List[InlineKeyboardButton]] = []
    for release in releases:
        label = truncate(release.title, 52)
        if release.year:
            label += f" ({release.year})"
        rows.append([InlineKeyboardButton(label, callback_data=f"R|{int(release.id)}")])
    nav: List[InlineKeyboardButton] = []
    if page > 0:
        nav.append(InlineKeyboardButton("◀️ Назад", callback_data=f"T|new|{page - 1}"))
    nav.append(InlineKeyboardButton("🔄 Ещё", callback_data=f"T|new|{page + 1}"))
    if nav:
        rows.append(nav)
    rows.append([InlineKeyboardButton("🏠 Меню", callback_data="M|0")])
    return InlineKeyboardMarkup(rows)


# ---------------------------------------------------------------------------
# Серии и качество
# ---------------------------------------------------------------------------
def episodes_text(release: Any, page: int, per_page: int) -> str:
    total = release.max_episode
    pages = max(1, (total + per_page - 1) // per_page)
    first = page * per_page + 1
    last = min(total, first + per_page - 1)
    qualities = release.available_qualities()
    return (
        f"📺 <b>{esc(release.title)}</b>\n"
        f"Серии {first}–{last} из {total} · стр. {page + 1}/{pages}\n"
        f"Качества: {', '.join(f'{q}p' for q in qualities) if qualities else '—'}\n\n"
        "Выбери серию 👇"
    )


def episodes_kb(release_id: int, total: int, page: int, per_page: int,
                qualities: Optional[Sequence[int]] = None) -> InlineKeyboardMarkup:
    rows: List[List[InlineKeyboardButton]] = []
    first = page * per_page + 1
    numbers = [n for n in range(first, min(total, first + per_page - 1) + 1)]
    row: List[InlineKeyboardButton] = []
    for number in numbers:
        row.append(InlineKeyboardButton(str(number), callback_data=f"P|{release_id}|{number}"))
        if len(row) == 4:
            rows.append(row)
            row = []
    if row:
        rows.append(row)

    nav: List[InlineKeyboardButton] = []
    if page > 0:
        nav.append(InlineKeyboardButton("◀️", callback_data=f"E|{release_id}|{page - 1}"))
    pages = max(1, (total + per_page - 1) // per_page)
    nav.append(InlineKeyboardButton(f"{page + 1}/{pages}", callback_data="M|0"))
    if (page + 1) * per_page < total:
        nav.append(InlineKeyboardButton("▶️", callback_data=f"E|{release_id}|{page + 1}"))
    rows.append(nav)
    rows.append([InlineKeyboardButton("⬅️ К релизу", callback_data=f"R|{release_id}")])
    return InlineKeyboardMarkup(rows)


def quality_text(release: Any, episode: Any, episode_number: int) -> str:
    lines = [f"🎚 <b>{esc(release.title)}</b>", f"Серия {episode_number}"]
    if episode is not None and getattr(episode, "duration", 0):
        lines.append(f"Длительность: {dl_engine.human_time(episode.duration)}")
    qualities = episode.qualities if episode is not None else []
    if qualities:
        lines.append("Доступно: " + ", ".join(f"{q}p" for q in sorted(qualities, reverse=True)))
    lines.append("\nВыбери качество — сервер скачает поток и соберёт MP4:")
    return "\n".join(lines)


def quality_kb(release_id: int, episode_number: int, qualities: Sequence[int],
               default_quality: int = 0) -> InlineKeyboardMarkup:
    rows: List[List[InlineKeyboardButton]] = []
    row: List[InlineKeyboardButton] = []
    for quality in sorted(qualities, reverse=True):
        mark = "⭐ " if quality == default_quality else ""
        row.append(InlineKeyboardButton(f"{mark}{quality}p",
                                        callback_data=f"D|{release_id}|{episode_number}|{quality}"))
        if len(row) == 3:
            rows.append(row)
            row = []
    if row:
        rows.append(row)
    rows.append([
        InlineKeyboardButton("🔁 Смотреть подряд", callback_data=f"A|{release_id}|{episode_number}|{max(qualities) if qualities else 0}"),
        InlineKeyboardButton("⬅️ Серии", callback_data=f"E|{release_id}|{(episode_number - 1) // config.EPISODES_PER_PAGE}"),
    ])
    return InlineKeyboardMarkup(rows)


# ---------------------------------------------------------------------------
# Прогресс
# ---------------------------------------------------------------------------
def progress_text(state: dl_engine.ProgressState, title: str, episode: int, quality: int,
                  queue_note: str = "") -> str:
    head = f"🎬 <b>{esc(truncate(title, 64))}</b>\n📺 Серия {episode} · {quality}p\n\n"
    if state.stage == "queue":
        return head + f"⏳ В очереди на скачивание…\n{esc(queue_note)}"
    if state.stage == "download":
        elapsed = max(0.0, state.updated_at - state.started_at)
        speed = f" · скорость {esc(state.speed)}" if state.speed else ""
        eta = ""
        if state.percent > 1 and state.duration:
            remaining = state.duration * (100 - state.percent) / max(state.percent, 0.1)
            eta = f" · осталось ~{dl_engine.human_time(remaining)}"
        return (
            head
            + f"⬇️ <b>Скачиваю с AniLibria</b>\n"
            + f"<code>{progress_bar(state.percent)}</code> {state.percent:.1f}%\n"
            + f"Собрано: {dl_engine.human_time(state.position)}"
            + (f" / {dl_engine.human_time(state.duration)}" if state.duration else "")
            + speed + eta
            + (f"\nРазмер: {dl_engine.human_size(state.downloaded)}" if state.downloaded else "")
            + f"\n<i>Прошло {dl_engine.human_time(elapsed)}</i>"
            + (f"\n\n⚠️ {esc(state.extra)}" if state.extra else "")
        )
    if state.stage == "upload":
        percent = state.percent
        return (
            head
            + f"⬆️ <b>Отправляю в Telegram (лимит 2 ГБ)</b>\n"
            + f"<code>{progress_bar(percent)}</code> {percent:.1f}%\n"
            + f"Загружено: {dl_engine.human_size(state.uploaded)}"
            + (f" / {dl_engine.human_size(state.total_upload)}" if state.total_upload else "")
        )
    if state.stage == "done":
        return head + "✅ Готово!"
    return head + "⚠️ Ошибка"


def progress_kb(task_id: str) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup([[InlineKeyboardButton("❌ Отменить", callback_data=f"C|{task_id}")]])


def video_caption(title: str, episode: int, quality: int, size_bytes: int) -> str:
    """Подпись под видео: только то, что полезно зрителю (без служебных деталей)."""
    return (
        f"🎬 <b>{esc(truncate(title, 80))}</b>\n"
        f"📺 Серия {episode} · 🎚 {quality}p · 💾 {dl_engine.human_size(size_bytes)}"
    )


def reply_keyboard() -> ReplyKeyboardMarkup:
    """Обычная (постоянная) клавиатура Telegram — всегда видна под полем ввода."""
    return ReplyKeyboardMarkup(
        [
            [KeyboardButton("⭐ Избранное"), KeyboardButton("🔍 Поиск"), KeyboardButton("▶️ Продолжить")],
        ],
        resize_keyboard=True,
        is_persistent=True,
        placeholder="⭐ Избранное · 🔍 Поиск · ▶️ Продолжить",
    )


def search_prompt_text() -> str:
    """Короткая подсказка при нажатии обычной кнопки «🔍 Поиск»."""
    return "🔍 <b>Поиск аниме</b>\n\nНажми кнопку ниже — откроется поиск."


def search_prompt_kb() -> InlineKeyboardMarkup:
    """Инлайн-кнопка, открывающая поиск в текущем чате (то же, что @бот + запрос)."""
    return InlineKeyboardMarkup([
        [InlineKeyboardButton("🔍 Открыть поиск", switch_inline_query_current_chat="")],
    ])


def continue_text(title: str, last_episode: int, next_episode: int, quality: int, last_quality: int) -> str:
    return (
        "▶️ <b>Продолжить просмотр</b>\n\n"
        f"🎬 <b>{esc(truncate(title, 70))}</b>\n"
        f"Остановился на серии {last_episode} ({last_quality}p)\n"
        f"Дальше: <b>серия {next_episode}</b> · {quality}p"
    )


def continue_kb(release_id: int, next_episode: int, quality: int, max_episode: int) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup([
        [InlineKeyboardButton(f"▶️ Продолжить с серии {next_episode}",
                              callback_data=f"D|{release_id}|{next_episode}|{quality}")],
        [InlineKeyboardButton("🎚 Другое качество", callback_data=f"P|{release_id}|{next_episode}")],
        [InlineKeyboardButton("🕓 История", callback_data="H|0"), InlineKeyboardButton("🏠 Меню", callback_data="M|0")],
    ])


def nothing_to_continue_text() -> str:
    return (
        "▶️ <b>Продолжить просмотр</b>\n\n"
        "Пока нечего продолжать: ты ещё ничего не скачивал.\n"
        "Найди аниме через инлайн-поиск — и после первой серии тут появится кнопка «Продолжить»."
    )


def all_episodes_watched_text(title: str, last_episode: int) -> str:
    return (
        "🎉 <b>Продолжить просмотр</b>\n\n"
        f"По «{esc(truncate(title, 60))}» ты досмотрел до конца (серия {last_episode}).\n"
        "Можно начать заново или выбрать другой тайтл через инлайн-поиск."
    )


def video_kb(release_id: int, episode: int, max_episode: int, quality: int) -> InlineKeyboardMarkup:
    rows: List[List[InlineKeyboardButton]] = []
    nav: List[InlineKeyboardButton] = []
    if episode > 1:
        nav.append(InlineKeyboardButton("⬅️ Пред. серия", callback_data=f"P|{release_id}|{episode - 1}"))
    if max_episode and episode < max_episode:
        nav.append(InlineKeyboardButton("➡️ След. серия", callback_data=f"D|{release_id}|{episode + 1}|{quality}"))
    if nav:
        rows.append(nav)
    rows.append([InlineKeyboardButton("🎚 Другое качество", callback_data=f"P|{release_id}|{episode}")])
    rows.append([InlineKeyboardButton("🗑 Удалить сообщение", callback_data="X|0")])
    return InlineKeyboardMarkup(rows)


def queued_all_text(title: str, from_episode: int, quality: int, total: int) -> str:
    return (
        f"🔁 <b>{esc(truncate(title, 64))}</b>\n\n"
        f"Ставлю в очередь серии с {from_episode} по {total} в {quality}p.\n"
        "Файлы будут приходить по одному. Отменить можно кнопкой в сообщении прогресса."
    )


# ---------------------------------------------------------------------------
# Списки: избранное, история, файлы
# ---------------------------------------------------------------------------
def favorites_text(page: int, total: int) -> str:
    if not total:
        return "⭐ Избранное пусто.\n\nОткрой любой релиз и нажми «⭐ В избранное»."
    return f"⭐ <b>Избранное</b> — {total} тайтлов (стр. {page + 1})"


def favorites_kb(items: Sequence[Dict[str, Any]], page: int, per_page: int, total: int) -> InlineKeyboardMarkup:
    rows: List[List[InlineKeyboardButton]] = []
    for item in items:
        rows.append([InlineKeyboardButton(truncate(item.get("title") or "—", 52),
                                          callback_data=f"R|{int(item.get('release_id') or 0)}")])
    nav: List[InlineKeyboardButton] = []
    if page > 0:
        nav.append(InlineKeyboardButton("◀️", callback_data=f"V|{page - 1}"))
    pages = max(1, (total + per_page - 1) // per_page)
    nav.append(InlineKeyboardButton(f"{page + 1}/{pages}", callback_data="M|0"))
    if (page + 1) * per_page < total:
        nav.append(InlineKeyboardButton("▶️", callback_data=f"V|{page + 1}"))
    rows.append(nav)
    rows.append([InlineKeyboardButton("🏠 Меню", callback_data="M|0")])
    return InlineKeyboardMarkup(rows)


def history_text(page: int, total: int) -> str:
    if not total:
        return "🕓 История пуста — тут появятся серии, которые ты скачивал."
    return f"🕓 <b>История</b> — {total} записей (стр. {page + 1})"


def history_kb(items: Sequence[Dict[str, Any]], page: int, per_page: int, total: int) -> InlineKeyboardMarkup:
    rows: List[List[InlineKeyboardButton]] = []
    for item in items:
        label = f"{truncate(item.get('title') or '—', 36)} · серия {item.get('episode')} · {item.get('quality')}p"
        rows.append([InlineKeyboardButton(label, callback_data=f"D|{int(item.get('release_id') or 0)}|{int(item.get('episode') or 1)}|{int(item.get('quality') or 0)}")])
    nav: List[InlineKeyboardButton] = []
    if page > 0:
        nav.append(InlineKeyboardButton("◀️", callback_data=f"H|{page - 1}"))
    pages = max(1, (total + per_page - 1) // per_page)
    nav.append(InlineKeyboardButton(f"{page + 1}/{pages}", callback_data="M|0"))
    if (page + 1) * per_page < total:
        nav.append(InlineKeyboardButton("▶️", callback_data=f"H|{page + 1}"))
    rows.append(nav)
    rows.append([InlineKeyboardButton("🏠 Меню", callback_data="M|0")])
    return InlineKeyboardMarkup(rows)


def files_text(page: int, total: int) -> str:
    if not total:
        return (
            "📁 <b>Мои файлы</b>\n\n"
            "Пока пусто. Пришли мне видео или документ до <b>2 ГБ</b> — сохраню file_id "
            "и отдам обратно по кнопке (мгновенно, без повторной загрузки)."
        )
    return f"📁 <b>Мои файлы</b> — {total} шт. (стр. {page + 1})"


def files_kb(items: Sequence[Dict[str, Any]], page: int, per_page: int, total: int) -> InlineKeyboardMarkup:
    rows: List[List[InlineKeyboardButton]] = []
    for item in items:
        name = truncate(item.get("name") or "файл", 28)
        size = dl_engine.human_size(float(item.get("size") or 0))
        rows.append([InlineKeyboardButton(f"📤 {name} · {size}", callback_data=f"S|{int(item.get('id'))}")])
        second: List[InlineKeyboardButton] = []
        if item.get("saved_path"):
            second.append(InlineKeyboardButton("✅ на сервере", callback_data="M|0"))
        else:
            second.append(InlineKeyboardButton("📥 Скачать на сервер", callback_data=f"U|{int(item.get('id'))}"))
        second.append(InlineKeyboardButton("🗑 Убрать из списка", callback_data=f"W|{int(item.get('id'))}"))
        rows.append(second)
    nav: List[InlineKeyboardButton] = []
    if page > 0:
        nav.append(InlineKeyboardButton("◀️", callback_data=f"L|{page - 1}"))
    pages = max(1, (total + per_page - 1) // per_page)
    nav.append(InlineKeyboardButton(f"{page + 1}/{pages}", callback_data="M|0"))
    if (page + 1) * per_page < total:
        nav.append(InlineKeyboardButton("▶️", callback_data=f"L|{page + 1}"))
    rows.append(nav)
    rows.append([InlineKeyboardButton("🏠 Меню", callback_data="M|0")])
    return InlineKeyboardMarkup(rows)


def stats_text(data: Dict[str, Any]) -> str:
    lines = ["📊 <b>Статистика</b>", ""]
    for key, value in data.items():
        if isinstance(value, float):
            lines.append(f"• {key}: {value:.2f}")
        else:
            lines.append(f"• {key}: {value}")
    return "\n".join(lines)


__all__ = [
    "esc", "truncate", "progress_bar", "main_menu_text", "main_menu_kb", "help_text",
    "reply_keyboard", "search_prompt_text", "search_prompt_kb", "continue_text", "continue_kb",
    "inline_card_text",
    "nothing_to_continue_text", "all_episodes_watched_text",
    "settings_text", "settings_kb", "release_text", "release_kb", "search_results_text",
    "search_results_kb", "latest_kb", "episodes_text", "episodes_kb", "quality_text",
    "quality_kb", "progress_text", "progress_kb", "video_caption", "video_kb",
    "queued_all_text", "favorites_text", "favorites_kb", "history_text", "history_kb",
    "files_text", "files_kb", "stats_text",
]
