# -*- coding: utf-8 -*-
"""
Firebase Realtime Database (RTDB) — хранение ID видео по публичному REST API.

Зачем: одна и та же серия не должна скачиваться дважды. Первый раз бот качает и
кладёт видео в канал-хранилище, а в RTDB записывает id сообщения этого канала.
Дальше любой пользователь получает серию моментально копированием из канала
(без имени канала — copy, не forward) и без скачивания.

Безопасность/деградация:
  • если правила базы запрещают запись (401/403) — бот пишет это в лог один раз и
    продолжает работать на локальном кэше SQLite (HTTP-сборка остаётся рабочей);
  • если база недоступна — так же, тихо, с записью в лог;
  • необязательный ключ доступа задаётся переменной RTDB_AUTH (database secret или
    ID-токен), тогда запросы идут с параметром ?auth=...
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any, Dict, Optional

import aiohttp

import config

log = logging.getLogger("rtdb")


class Rtdb:
    """Минимальный клиент RTDB: get / put / patch / delete."""

    def __init__(self, url: Optional[str] = None, auth: Optional[str] = None) -> None:
        self.url = (url if url is not None else config.RTDB_URL).rstrip("/")
        self.auth = auth if auth is not None else config.RTDB_AUTH
        self.status = "unknown"          # unknown | ok | denied | unreachable | disabled
        self.last_error: str = ""
        self._warned = False
        self._session: Optional[aiohttp.ClientSession] = None
        self._token_tried = bool(self.auth)

    # -- инфраструктура -----------------------------------------------------
    @property
    def enabled(self) -> bool:
        return bool(self.url) and config.RTDB_ENABLED

    async def session(self) -> aiohttp.ClientSession:
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession(
                timeout=aiohttp.ClientTimeout(total=12, connect=8),
                headers={"Accept": "application/json", "Content-Type": "application/json"},
            )
        return self._session

    async def close(self) -> None:
        if self._session and not self._session.closed:
            await self._session.close()

    def _url(self, path: str) -> str:
        clean = path.strip("/")
        base = f"{self.url}/{clean}.json" if clean else f"{self.url}/.json"
        if self.auth:
            joiner = "&" if "?" in base else "?"
            base = f"{base}{joiner}auth={self.auth}"
        return base

    def _note(self, status: str, error: str = "") -> None:
        self.status, self.last_error = status, error
        if status in ("denied", "unreachable") and not self._warned:
            self._warned = True
            if status == "denied":
                log.warning(
                    "RTDB: запись запрещена правилами базы (%s). "
                    "Дай право записи в правилах (например: {\"anime\": {\".read\": true, \".write\": true}}) "
                    "или задай RTDB_AUTH. Пока работаю на локальном кэше SQLite.",
                    error or "401/403",
                )
            else:
                log.warning("RTDB недоступен (%s) — работаю на локальном кэше SQLite.", error)

    async def _anon_token(self) -> Optional[str]:
        """Анонимный вход Firebase (apiKey из конфига) — помогает, если правила пускают
        только авторизованных. Не получилось — просто работаем без токена."""
        key = str((config.FIREBASE_CONFIG or {}).get("apiKey") or "")
        if not key:
            return None
        url = f"https://identitytoolkit.googleapis.com/v1/accounts:signUp?key={key}"
        try:
            session = await self.session()
            async with session.post(url, json={"returnSecureToken": True},
                                    timeout=aiohttp.ClientTimeout(total=8, connect=5)) as response:
                if response.status != 200:
                    return None
                data = await response.json(content_type=None)
                token = str((data or {}).get("idToken") or "")
                return token or None
        except Exception as exc:  # noqa: BLE001
            log.debug("RTDB: анонимный вход не удался (%s)", exc)
            return None

    async def _request(self, method: str, path: str, payload: Any = None) -> Any:
        if not self.enabled:
            self.status = "disabled"
            return None
        try:
            session = await self.session()
            kwargs: Dict[str, Any] = {}
            if payload is not None:
                kwargs["json"] = payload
            async with session.request(method, self._url(path), **kwargs) as response:
                if response.status in (401, 403):
                    if not self._token_tried and not self.auth:
                        # один раз пробуем анонимный вход Firebase (проект может требовать auth)
                        self._token_tried = True
                        token = await self._anon_token()
                        if token:
                            self.auth = token
                            return await self._request(method, path, payload)
                    self._note("denied", f"HTTP {response.status}")
                    return None
                if response.status >= 400:
                    self._note("unreachable", f"HTTP {response.status}")
                    return None
                self.status = "ok"
                text = await response.text()
                if not text or text == "null":
                    return None
                try:
                    return await response.json(content_type=None)
                except Exception:  # noqa: BLE001
                    return text
        except Exception as exc:  # noqa: BLE001
            self._note("unreachable", str(exc))
            return None

    # -- операции -----------------------------------------------------------
    async def get(self, path: str) -> Optional[Any]:
        return await self._request("GET", path)

    async def put(self, path: str, value: Any) -> bool:
        result = await self._request("PUT", path, value)
        return self.status == "ok"

    async def patch(self, path: str, value: Dict[str, Any]) -> bool:
        result = await self._request("PATCH", path, value)
        return self.status == "ok"

    async def delete(self, path: str) -> bool:
        result = await self._request("DELETE", path)
        return self.status == "ok"

    async def ping(self) -> str:
        await self.get(config.RTDB_ROOT)
        return self.status


# Единый клиент на процесс
_CLIENT: Optional[Rtdb] = None


def client() -> Rtdb:
    global _CLIENT
    if _CLIENT is None:
        _CLIENT = Rtdb()
    return _CLIENT


__all__ = ["Rtdb", "client"]
