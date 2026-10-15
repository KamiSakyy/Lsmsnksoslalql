# -*- coding: utf-8 -*-
"""
Умный роутинг по парам api_id / api_hash (MTProto).

Задача: если Telegram не пускает бота с одной парой — бот сам берёт следующую,
а не падает. При этом Telegram режет пары по-разному, и «долбить» одну и ту же
бессмысленно (иногда ещё и вредно):

    API_ID_PUBLISHED_FLOOD  — api_id «засветился» в интернете, сервер ограничил
                              авторизацию для него на часы. Пару надо отложить.
    FLOOD_WAIT_<n>          — этой паре просят подождать n секунд.
    AUTH_KEY_UNREGISTERED   — сессия пары сломана (файл сессии удаляем).
    SESSION_REVOKED /
    USER_DEACTIVATED /
    ACCESS_TOKEN_EXPIRED    — пара больше не годится.
    сетевые ошибки          — временные: пару не «хороним», просто короткая пауза.

Состояние каждой пары запоминается в <SESSION_DIR>/cred_state.json (том /data),
поэтому после перезапуска контейнера бот сразу стартует с рабочей пары, а битые
пропускает — вместо повторного перебора всего списка и повторных блокировок.
"""

from __future__ import annotations

import json
import logging
import os
import re
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple

import config

log = logging.getLogger("credentials")

Cred = Tuple[int, str, str]          # (api_id, api_hash, name)

# Виды отказов
KIND_OK = "ok"
KIND_PUBLISHED = "published"         # API_ID_PUBLISHED_FLOOD — публичный api_id, сервер ограничил
KIND_FLOOD = "flood"                 # FLOOD_WAIT — надо просто подождать
KIND_AUTH = "auth"                   # сессия/пара больше не работает
KIND_NETWORK = "network"             # сеть/прокси — временно
KIND_UNKNOWN = "unknown"

_PUBLISHED = ("API_ID_PUBLISHED_FLOOD", "API_ID_PUBLISHED")
_AUTH = (
    "AUTH_KEY_UNREGISTERED", "AUTH_KEY_INVALID", "AUTH_KEY_DUPLICATED", "SESSION_REVOKED",
    "SESSION_EXPIRED", "USER_DEACTIVATED", "USER_DEACTIVATED_BAN", "ACCESS_TOKEN_EXPIRED",
    "API_ID_INVALID", "API_ID_PUBLISHED", "BOT_TOKEN_INVALID", "AUTH_KEY_PERM_EMPTY",
)
_NETWORK = (
    "TimeoutError", "ConnectionError", "ConnectionResetError", "ConnectError", "OSError",
    "Connection attempt failed", "Timeout", "Proxy", "Cannot connect", "ServerClosed",
    "Network is unreachable", "Temporary failure in name resolution", "ServiceUnavailable",
    "Gateway", "Socket", "EOF",
)
_FLOOD_RE = re.compile(r"FLOOD_WAIT[_\s]*(?:\d+)?|\bFLOOD_WAIT_(\d+)", re.I)
_FLOOD_NUM_RE = re.compile(r"FLOOD_WAIT_(\d+)", re.I)
_CODE_RE = re.compile(r"\b([A-Z][A-Z0-9_]{4,})\b")
_WAIT_RE = re.compile(r"(?:подождать|wait)\D{0,12}(\d+)", re.I)


def _now() -> float:
    return time.time()


@dataclass
class CredState:
    """Состояние одной пары: сколько раз сработала, когда и почему отвалилась."""

    api_id: int
    name: str
    ok: int = 0                       # успешных запусков
    fails: int = 0                    # неудачных попыток
    kind: str = ""                    # последний вид отказа
    code: str = ""                    # последний код ошибки Telegram
    error: str = ""                   # текст ошибки (обрезанный)
    cooldown_until: float = 0.0        # до какого времени пару не трогаем
    last_ok_at: float = 0.0
    last_fail_at: float = 0.0

    @property
    def cooling(self) -> bool:
        return self.cooldown_until > _now()

    @property
    def cooldown_left(self) -> float:
        return max(0.0, self.cooldown_until - _now())

    def summary(self) -> str:
        if self.cooldown_left > 0:
            return f"пауза ещё {human_delay(self.cooldown_left)} ({self.code or self.kind})"
        if self.last_ok_at:
            return f"работала ({human_delay(_now() - self.last_ok_at)} назад)"
        if self.fails:
            return f"отказов {self.fails} ({self.code or self.kind})"
        return "ещё не проверялась"


def human_delay(seconds: float) -> str:
    seconds = max(0.0, float(seconds))
    if seconds < 60:
        return f"{seconds:.0f} с"
    if seconds < 3600:
        return f"{seconds / 60:.0f} мин"
    return f"{seconds / 3600:.1f} ч"


def classify(error: BaseException | str) -> Tuple[str, str, float]:
    """По ошибке Telegram понимает: что случилось, код ошибки и пауза для пары.

    Возвращает (kind, code, cooldown_seconds).
    """
    text = error if isinstance(error, str) else f"{type(error).__name__}: {error}"
    upper = text.upper()

    if any(token in upper for token in _PUBLISHED):
        return KIND_PUBLISHED, "API_ID_PUBLISHED_FLOOD", float(config.API_KEYS_PUBLISHED_COOLDOWN)

    if _FLOOD_RE.search(upper) or "FLOOD" in upper:
        wait = None
        match = _FLOOD_NUM_RE.search(upper)
        if match:
            wait = int(match.group(1))
        else:
            value = getattr(error, "value", None)
            if isinstance(value, (int, float)) and value > 0:
                wait = int(value)
            else:
                alt = _WAIT_RE.search(text)
                if alt:
                    wait = int(alt.group(1))
        wait = int(min(wait or 60, config.API_KEYS_FLOOD_MAX))
        return KIND_FLOOD, f"FLOOD_WAIT_{wait}", float(wait + 5)

    if any(token in upper for token in _AUTH):
        code = next((t for t in _AUTH if t in upper), "AUTH_ERROR")
        return KIND_AUTH, code, float(config.API_KEYS_AUTH_COOLDOWN)

    if any(token in upper for token in (t.upper() for t in _NETWORK)):
        return KIND_NETWORK, "NETWORK", float(config.API_KEYS_NETWORK_COOLDOWN)

    code = ""
    match = _CODE_RE.search(upper)
    if match:
        code = match.group(1)
    return KIND_UNKNOWN, code or "UNKNOWN", float(config.API_KEYS_UNKNOWN_COOLDOWN)


class CredentialRotator:
    """Держит список пар и решает, какую пробовать следующей."""

    def __init__(self, pairs: Optional[Iterable[Cred]] = None,
                 state_path: Optional[Path] = None) -> None:
        self.pairs: List[Cred] = [tuple(pair) for pair in (pairs if pairs is not None else config.credential_candidates())]
        # убираем дубли api_id, сохраняя порядок
        seen: set[int] = set()
        unique: List[Cred] = []
        for api_id, api_hash, name in self.pairs:
            if api_id in seen:
                continue
            seen.add(api_id)
            unique.append((int(api_id), str(api_hash), str(name)))
        self.pairs = unique
        self.state_path = Path(state_path) if state_path is not None else (
            Path(config.SESSION_DIR) / "cred_state.json"
        )
        self.states: Dict[int, CredState] = {
            api_id: CredState(api_id=api_id, name=name) for api_id, _hash, name in self.pairs
        }
        self._hash_by_id: Dict[int, str] = {api_id: api_hash for api_id, api_hash, _name in self.pairs}
        self.load()

    # -- файл состояния -----------------------------------------------------
    def load(self) -> None:
        try:
            raw = json.loads(self.state_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return
        stored = raw.get("pairs") if isinstance(raw, dict) else None
        if not isinstance(stored, dict):
            return
        for api_id_str, data in stored.items():
            try:
                api_id = int(api_id_str)
            except (TypeError, ValueError):
                continue
            if api_id not in self.states or not isinstance(data, dict):
                continue
            state = self.states[api_id]
            for key in ("ok", "fails"):
                try:
                    setattr(state, key, int(data.get(key) or 0))
                except (TypeError, ValueError):
                    pass
            for key in ("kind", "code", "error"):
                setattr(state, key, str(data.get(key) or ""))
            for key in ("cooldown_until", "last_ok_at", "last_fail_at"):
                try:
                    setattr(state, key, float(data.get(key) or 0.0))
                except (TypeError, ValueError):
                    pass

    def save(self) -> None:
        payload: Dict[str, Any] = {
            "version": 1,
            "updated_at": _now(),
            "pairs": {str(api_id): asdict(state) for api_id, state in self.states.items()},
        }
        try:
            self.state_path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.state_path.with_suffix(".json.tmp")
            tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")
            os.replace(tmp, self.state_path)
        except OSError as exc:  # том может быть только для чтения — не критично
            log.debug("Не смог записать состояние пар: %s", exc)

    # -- выбор пар ----------------------------------------------------------
    def state_of(self, api_id: int) -> CredState:
        return self.states.setdefault(
            int(api_id), CredState(api_id=int(api_id), name=str(api_id))
        )

    def pair(self, api_id: int) -> Optional[Cred]:
        for cred in self.pairs:
            if cred[0] == int(api_id):
                return cred
        return None

    def candidates(self, include_cooling: bool = False) -> List[Cred]:
        """Пары в порядке «сначала самая перспективная».

        Сортировка: рабочая (последняя успешная) → ещё не проверенные →
        с меньшим числом отказов → те, у кого пауза уже истекла (по времени освобождения).
        Пары в паузе по умолчанию не отдаём (include_cooling=True — отдаём в конце).
        """
        ready: List[Cred] = []
        cooling: List[Cred] = []
        for cred in self.pairs:
            (cooling if self.state_of(cred[0]).cooling else ready).append(cred)

        def rank(cred: Cred) -> Tuple[int, int, float]:
            state = self.state_of(cred[0])
            group = 0 if state.last_ok_at else (1 if state.fails == 0 else 2)
            return (group, state.fails, -state.last_ok_at)

        ready.sort(key=rank)
        cooling.sort(key=lambda cred: self.state_of(cred[0]).cooldown_until)
        return ready + (cooling if include_cooling else [])

    def seconds_until_ready(self) -> float:
        """Сколько ждать, пока освободится хоть одна пара (0 — можно пробовать сейчас)."""
        if self.candidates():
            return 0.0
        left = [self.state_of(cred[0]).cooldown_left for cred in self.pairs]
        return min(left) if left else 0.0

    # -- результаты попыток --------------------------------------------------
    def note_success(self, api_id: int, name: str = "") -> CredState:
        state = self.state_of(api_id)
        state.ok += 1
        state.last_ok_at = _now()
        state.cooldown_until = 0.0
        state.kind = KIND_OK
        if name:
            state.name = name
        self.save()
        return state

    def note_failure(self, api_id: int, name: str, error: BaseException | str) -> CredState:
        kind, code, cooldown = classify(error)
        state = self.state_of(api_id)
        if name:
            state.name = name
        state.fails += 1
        state.kind = kind
        state.code = code
        state.error = (error if isinstance(error, str) else str(error))[:300]
        state.last_fail_at = _now()
        if kind == KIND_AUTH:
            self.drop_session(api_id)
        state.cooldown_until = _now() + max(cooldown, 1.0)
        self.save()
        return state

    def drop_session(self, api_id: int) -> None:
        """Сломанную сессию пары удаляем, чтобы не мешала следующему запуску."""
        try:
            session_dir = Path(config.SESSION_DIR)
        except Exception:  # noqa: BLE001
            return
        for suffix in (".session", ".session-journal"):
            path = session_dir / f"animebot_{int(api_id)}{suffix}"
            try:
                path.unlink(missing_ok=True)
            except OSError as exc:  # noqa: BLE001
                log.debug("Не удалось удалить %s: %s", path, exc)

    # -- отчёты -------------------------------------------------------------
    def report(self, limit: int = 6) -> str:
        """Короткая сводка: что работает, что заблокировано и почему."""
        alive = [c for c in self.pairs if not self.state_of(c[0]).cooling]
        cooling = [c for c in self.pairs if self.state_of(c[0]).cooling]
        parts = [f"пар всего {len(self.pairs)}: доступно {len(alive)}, на паузе {len(cooling)}"]
        for cred, state in sorted(
            ((c, self.state_of(c[0])) for c in self.pairs),
            key=lambda item: (item[1].cooling, item[1].fails),
        )[:limit]:
            parts.append(f"  • api_id={cred[0]} ({cred[2]}): {state.summary()}")
        return "\n".join(parts)

    def published_blocked(self) -> List[CredState]:
        """Пары, которые Telegram считает опубликованными (роутинг по ним бессилен)."""
        return [
            state for state in self.states.values()
            if state.kind == KIND_PUBLISHED
        ]

    def health(self) -> Dict[str, Any]:
        alive = [c for c in self.pairs if not self.state_of(c[0]).cooling]
        good = [c for c in self.pairs if self.state_of(c[0]).last_ok_at]
        return {
            "total": len(self.pairs),
            "ready": len(alive),
            "working": len(good),
            "published": len(self.published_blocked()),
            "active": ACTIVE.api_id or 0,
            "active_name": ACTIVE.name,
        }


# Активная (рабочая) пара — чтобы /stats и логи показывали, на чём едет бот
class _Active:
    api_id: Optional[int] = None
    name: str = ""
    since: float = 0.0

    def set(self, api_id: int, name: str) -> None:
        self.api_id, self.name, self.since = int(api_id), name, _now()

    def clear(self) -> None:
        self.api_id, self.name, self.since = None, "", 0.0


ACTIVE = _Active()

_ROTATOR: Optional[CredentialRotator] = None


def rotator(pairs: Optional[Iterable[Cred]] = None, state_path: Optional[Path] = None,
            fresh: bool = False) -> CredentialRotator:
    """Единый ротатор на процесс (для тестов можно создать свой)."""
    global _ROTATOR
    if fresh or _ROTATOR is None:
        _ROTATOR = CredentialRotator(pairs=pairs, state_path=state_path)
    return _ROTATOR


def reset() -> None:
    """Сбросить кэш ротатора (используется в тестах)."""
    global _ROTATOR
    _ROTATOR = None


__all__ = [
    "Cred", "CredState", "CredentialRotator", "ACTIVE",
    "classify", "rotator", "reset", "human_delay",
    "KIND_OK", "KIND_PUBLISHED", "KIND_FLOOD", "KIND_AUTH", "KIND_NETWORK", "KIND_UNKNOWN",
]
