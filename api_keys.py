# -*- coding: utf-8 -*-
"""
Публичные пары api_id / api_hash для MTProto-клиента (Telegram API, НЕ Bot API HTTP).

Зачем это нужно:
    * Обычный Bot API (api.telegram.org) жёстко ограничивает размер файла:
      скачивание до 20 МБ, отправка до 50 МБ.
    * MTProto-клиент (Pyrogram/Telethon/Kurigram) с bot_token работает напрямую
      с серверами Telegram и умеет отдавать/принимать файлы до 2000 МБ (~2 ГБ).
      Это и есть "userbot-схема", о которой речь: тот же bot_token, но транспорт MTProto.

Все пары ниже — публично известные пары клиентов Telegram (Android/iOS/Desktop/web/TDLib).
Они нужны только для рукопожатия MTProto, никаких чужих аккаунтов не затрагивают.
"""

from __future__ import annotations

import itertools
import random
from typing import Dict, Iterator, List, Tuple

# ---------------------------------------------------------------------------
# Полный список пар (имя -> api_id / api_hash)
# ---------------------------------------------------------------------------
API_CREDENTIALS: List[Dict[str, object]] = [
    {"name": "public_android_beta", "title": "Public Android Beta", "api_id": 4,
     "api_hash": "014b35b6184100b085b0d0572f9b5103"},
    {"name": "public_static_final", "title": "Public Static Final", "api_id": 5,
     "api_hash": "1c5c96d5edd401b1ed40db3fb5633e2d"},
    {"name": "tg_android", "title": "TG for Android", "api_id": 6,
     "api_hash": "eb06d4abfb49dc3eeb1aeb98ae0f581e"},
    {"name": "public_ios_beta", "title": "Public iOS Beta", "api_id": 8,
     "api_hash": "7245de8e747a0d6fbe11f7cc14fcc0bb"},
    {"name": "public_macos_beta", "title": "Public MacOs Beta", "api_id": 2834,
     "api_hash": "68875f756c9b437a8b916ca3de215815"},
    {"name": "public_unknown_beta", "title": "Public unknown Beta", "api_id": 9,
     "api_hash": "3975f648bb682ee889f35483bc618d1c"},
    {"name": "public_win_beta", "title": "Public Win Beta", "api_id": 2040,
     "api_hash": "b18441a1ff607e10a989891a5462e627"},
    {"name": "telegram_desktop", "title": "Telegram Desktop", "api_id": 17349,
     "api_hash": "344583e45741c457fe1862106095a5eb"},
    {"name": "telegram_x_android", "title": "Telegram X (Android)", "api_id": 21724,
     "api_hash": "3e0cb5efcd52300aec5994fdfc5bdc16"},
    {"name": "web_telegram_org", "title": "web.telegram.org", "api_id": 2496,
     "api_hash": "8da85b0d5bfe62527e5b244c209159c3"},
    {"name": "tdlib", "title": "TDLib (example)", "api_id": 94575,
     "api_hash": "a3406de8d171bb422bb6ddf3bbd800e2"},
    {"name": "telegram_cli", "title": "Telegram messenger CLI", "api_id": 2899,
     "api_hash": "36722c72256a24c1225de00eb6a1ca74"},
    {"name": "plus_messenger", "title": "Plus Messenger", "api_id": 16623,
     "api_hash": "8c9dbfe58437d1739540f5d53c72ae4b"},
    {"name": "telegram_swift", "title": "Telegram Swift", "api_id": 10840,
     "api_hash": "33c45224029d59cb3ad0c16134215aeb"},
]

# Пары, которые исторически лучше всего живут под ботами (стабильны, не банятся за частые
# переподключения). Используются первыми, остальные — как резерв при ошибках авторизации.
PREFERRED_ORDER: List[int] = [2040, 17349, 6, 21724, 94575, 4, 5, 8, 9, 2834, 2899, 16623, 10840, 2496]

BY_ID: Dict[int, Dict[str, object]] = {int(c["api_id"]): c for c in API_CREDENTIALS}


def _ordered_list() -> List[Dict[str, object]]:
    """Все пары в порядке предпочтения (предпочтительные — вперёд)."""
    seen, out = set(), []
    for api_id in PREFERRED_ORDER:
        cred = BY_ID.get(api_id)
        if cred and api_id not in seen:
            seen.add(api_id)
            out.append(cred)
    for cred in API_CREDENTIALS:
        if int(cred["api_id"]) not in seen:
            seen.add(int(cred["api_id"]))
            out.append(cred)
    return out


def iter_credentials() -> Iterator[Tuple[int, str, str]]:
    """Итератор (api_id, api_hash, имя_клиента) в порядке предпочтения."""
    for cred in _ordered_list():
        yield int(cred["api_id"]), str(cred["api_hash"]), str(cred["name"])


def random_credentials(rng: random.Random | None = None) -> Tuple[int, str, str]:
    """Случайная пара из списка (рандомизация параметров клиента как просили)."""
    rng = rng or random
    cred = rng.choice(_ordered_list())
    return int(cred["api_id"]), str(cred["api_hash"]), str(cred["name"])


def credentials_for(api_id: int) -> Tuple[int, str, str] | None:
    cred = BY_ID.get(int(api_id))
    if not cred:
        return None
    return int(cred["api_id"]), str(cred["api_hash"]), str(cred["name"])


def rotating_credentials(start_with: int | None = None) -> Iterator[Tuple[int, str, str]]:
    """Бесконечный цикл по парам, начиная с указанной (или с начала списка)."""
    ordered = list(iter_credentials())
    if not ordered:
        return
    cycle = itertools.cycle(ordered)
    if start_with is not None:
        for _ in range(len(ordered)):
            item = next(cycle)
            if item[0] == int(start_with):
                yield item
                break
    for item in cycle:
        yield item


__all__ = [
    "API_CREDENTIALS",
    "PREFERRED_ORDER",
    "BY_ID",
    "iter_credentials",
    "random_credentials",
    "credentials_for",
    "rotating_credentials",
]
