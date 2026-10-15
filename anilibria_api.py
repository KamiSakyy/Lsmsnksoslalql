# -*- coding: utf-8 -*-
"""
Клиент публичного AniLibria / AniLiberty API v1 (актуальная версия 2026).

Документация (Swagger): https://anilibria.top/api/docs/v1
Зеркало API:              https://api.anilibria.app/api/v1

Используемые эндпоинты (публичные, без токена):
    GET /anime/catalog/releases?f[search]=...&limit=...&page=...
    GET /app/search/releases?query=...&limit=...
    GET /anime/releases/latest?limit=...
    GET /anime/releases/{alias|id}
    GET /anime/genres

Отдача: у каждого релиза есть список серий, где лежат готовые HLS-ссылки:
    hls_480 / hls_720 / hls_1080 (+ тайминги опенинга/эндинга, id, ordinal, duration)
Их мы и качаем на сервере (ffmpeg) и превращаем в mp4 для отправки в Telegram.
"""

from __future__ import annotations

import asyncio
import html
import logging
import re
from dataclasses import dataclass, field
from typing import Any, Dict, Iterable, List, Optional

import aiohttp

import config

log = logging.getLogger("anilibria")

_TAG_RE = re.compile(r"<[^>]+>")
_HLS_RE = re.compile(r"^hls_(\d{3,4})$")
_QUALITY_RE = re.compile(r"(\d{3,4})p?")


class AniLibriaError(Exception):
    """Базовая ошибка API."""


class AniLibriaUnavailable(AniLibriaError):
    """Ни одно зеркало не ответило."""


class ReleaseNotFound(AniLibriaError):
    """Релиз не найден."""


class GeoBlocked(AniLibriaError):
    """Релиз заблокирован по региону (is_blocked_by_geo)."""


def strip_html(text: Optional[str]) -> str:
    if not text:
        return ""
    text = text.replace("<br>", "\n").replace("<br/>", "\n").replace("<br />", "\n")
    text = text.replace("</p>", "\n").replace("<p>", "")
    text = _TAG_RE.sub("", text)
    return html.unescape(text).strip()


# ---------------------------------------------------------------------------
# Модели
# ---------------------------------------------------------------------------
@dataclass
class Episode:
    id: str
    ordinal: int
    name: Optional[str] = None
    duration: int = 0
    hls: Dict[int, str] = field(default_factory=dict)
    opening: Optional[tuple] = None
    ending: Optional[tuple] = None

    @property
    def qualities(self) -> List[int]:
        return sorted(self.hls.keys())

    def best_quality(self, preferred: Optional[int] = None) -> Optional[int]:
        if not self.hls:
            return None
        if preferred and preferred in self.hls:
            return preferred
        if preferred:
            # ближайшее снизу (1080 -> 720/480), иначе самое высокое доступное
            lower = [q for q in self.hls if q <= preferred]
            if lower:
                return max(lower)
            return min(self.hls)
        return max(self.hls)

    def url_for(self, quality: Optional[int] = None) -> Optional[str]:
        q = self.best_quality(quality)
        return self.hls.get(q) if q else None

    @classmethod
    def from_api(cls, raw: Dict[str, Any]) -> "Episode":
        hls: Dict[int, str] = {}
        for key, value in (raw or {}).items():
            match = _HLS_RE.match(str(key))
            if match and isinstance(value, str) and value.startswith("http"):
                hls[int(match.group(1))] = value
        opening = _skip_range(raw.get("opening"))
        ending = _skip_range(raw.get("ending"))
        return cls(
            id=str(raw.get("id") or raw.get("ordinal") or ""),
            ordinal=int(raw.get("ordinal") or 0),
            name=raw.get("name") or None,
            duration=int(raw.get("duration") or raw.get("episode_duration") or 0),
            hls=hls,
            opening=opening,
            ending=ending,
        )


def _skip_range(block: Any) -> Optional[tuple]:
    if isinstance(block, dict):
        start, stop = block.get("start"), block.get("stop")
        if isinstance(start, (int, float)) and isinstance(stop, (int, float)) and stop > start:
            return int(start), int(stop)
    return None


@dataclass
class Release:
    id: int
    alias: str
    name_main: str = ""
    name_english: str = ""
    name_alternative: str = ""
    year: int = 0
    type_value: str = ""
    type_description: str = ""
    episodes_total: int = 0
    is_ongoing: bool = False
    is_geo_blocked: bool = False
    description: str = ""
    poster: str = ""
    poster_preview: str = ""
    poster_thumbnail: str = ""
    genres: List[str] = field(default_factory=list)
    average_duration: int = 0
    episodes: List[Episode] = field(default_factory=list)
    raw: Dict[str, Any] = field(default_factory=dict)

    @property
    def title(self) -> str:
        return self.name_main or self.name_english or self.alias

    @property
    def site_url(self) -> str:
        return f"{config.ANILIBRIA_SITE}/anime/releases/release/{self.alias}"

    def episode(self, ordinal: int) -> Optional[Episode]:
        for item in self.episodes:
            if item.ordinal == ordinal:
                return item
        return None

    @property
    def max_episode(self) -> int:
        if self.episodes:
            return max(item.ordinal for item in self.episodes)
        return self.episodes_total or 0

    def available_qualities(self) -> List[int]:
        found: set[int] = set()
        for item in self.episodes:
            found.update(item.hls.keys())
        return sorted(found, reverse=True)

    @classmethod
    def from_api(cls, raw: Dict[str, Any]) -> "Release":
        raw = raw or {}
        names = raw.get("name") or {}
        poster = raw.get("poster") or {}
        poster_src = _absolute(poster.get("src") or "")
        preview = _absolute(poster.get("preview") or poster.get("src") or "")
        thumb = _absolute(poster.get("thumbnail") or poster.get("preview") or poster.get("src") or "")
        ep_type = raw.get("type") or {}
        genres: List[str] = []
        for item in raw.get("genres") or []:
            if isinstance(item, dict):
                name = item.get("name") or item.get("title")
                if name:
                    genres.append(str(name))
            elif isinstance(item, str):
                genres.append(item)
        episodes_raw = raw.get("episodes")
        episodes = [Episode.from_api(item) for item in episodes_raw] if isinstance(episodes_raw, list) else []
        episodes.sort(key=lambda e: e.ordinal)
        return cls(
            id=int(raw.get("id") or 0),
            alias=str(raw.get("alias") or raw.get("code") or raw.get("id") or ""),
            name_main=str(names.get("main") or raw.get("name") or ""),
            name_english=str(names.get("english") or ""),
            name_alternative=str(names.get("alternative") or ""),
            year=int(raw.get("year") or 0),
            type_value=str(ep_type.get("value") or "") if isinstance(ep_type, dict) else str(ep_type or ""),
            type_description=str(ep_type.get("description") or "") if isinstance(ep_type, dict) else "",
            episodes_total=int(raw.get("episodes_total") or len(episodes) or 0),
            is_ongoing=bool(raw.get("is_ongoing")),
            is_geo_blocked=bool(raw.get("is_blocked_by_geo")),
            description=strip_html(raw.get("description")),
            poster=poster_src,
            poster_preview=preview,
            poster_thumbnail=thumb,
            genres=genres,
            average_duration=int(raw.get("average_duration_of_episode") or 0),
            episodes=episodes,
            raw=raw,
        )


def _absolute(url: str) -> str:
    if not url:
        return ""
    if url.startswith("//"):
        return "https:" + url
    if url.startswith("http"):
        return url
    return config.ANILIBRIA_SITE + ("" if url.startswith("/") else "/") + url


# ---------------------------------------------------------------------------
# Клиент
# ---------------------------------------------------------------------------
class AniLibriaClient:
    """Асинхронный клиент API v1 с фейловером по зеркалам."""

    def __init__(
        self,
        mirrors: Optional[Iterable[str]] = None,
        timeout: Optional[int] = None,
        proxy: Optional[str] = None,
    ) -> None:
        self.mirrors: List[str] = [m.rstrip("/") for m in (mirrors or config.ANILIBRIA_MIRRORS)]
        self.timeout = timeout or config.HTTP_TIMEOUT
        self.proxy = proxy if proxy is not None else config.ANILIBRIA_PROXY
        self._session: Optional[aiohttp.ClientSession] = None
        self._good_mirror: Optional[str] = None
        self._lock = asyncio.Lock()

    # -- инфраструктура -----------------------------------------------------
    async def session(self) -> aiohttp.ClientSession:
        async with self._lock:
            if self._session is None or self._session.closed:
                self._session = aiohttp.ClientSession(
                    timeout=aiohttp.ClientTimeout(total=self.timeout, connect=10, sock_read=self.timeout),
                    headers={
                        "User-Agent": config.DOWNLOAD_USER_AGENT,
                        "Accept": "application/json",
                        "Accept-Language": "ru-RU,ru;q=0.9,en;q=0.8",
                    },
                )
            return self._session

    async def close(self) -> None:
        if self._session and not self._session.closed:
            await self._session.close()
        self._session = None

    async def _get(self, path: str, params: Optional[Dict[str, Any]] = None) -> Any:
        last_error: Optional[Exception] = None
        mirrors = self.mirrors if not self._good_mirror else [self._good_mirror] + [
            m for m in self.mirrors if m != self._good_mirror
        ]
        for mirror in mirrors:
            url = f"{mirror}/api/v1{path}"
            for attempt in range(1, config.HTTP_RETRIES + 1):
                try:
                    session = await self.session()
                    async with session.get(url, params=params, proxy=self.proxy) as resp:
                        if resp.status == 404:
                            raise ReleaseNotFound(f"404 для {url}")
                        if resp.status in (429, 500, 502, 503, 504):
                            raise AniLibriaError(f"HTTP {resp.status} от {mirror}")
                        resp.raise_for_status()
                        data = await resp.json(content_type=None)
                        self._good_mirror = mirror
                        return data
                except ReleaseNotFound:
                    raise
                except Exception as exc:  # noqa: BLE001 - нужен фейловер по зеркалам
                    last_error = exc
                    log.warning("Запрос %s (попытка %s/%s) не удался: %s", url, attempt, config.HTTP_RETRIES, exc)
                    await asyncio.sleep(min(2 ** attempt * 0.4, 5))
            log.warning("Зеркало %s не отвечает, пробую следующее", mirror)
        raise AniLibriaUnavailable(f"API AniLibria недоступно: {last_error}")

    @staticmethod
    def _as_list(data: Any, keys: Iterable[str] = ("results", "data", "items", "releases")) -> List[Dict[str, Any]]:
        if isinstance(data, list):
            return [item for item in data if isinstance(item, dict)]
        if isinstance(data, dict):
            for key in keys:
                value = data.get(key)
                if isinstance(value, list):
                    return [item for item in value if isinstance(item, dict)]
            # одиночный объект-релиз
            if "episodes" in data or "alias" in data or data.get("id"):
                return [data]
        return []

    # -- публичные методы ---------------------------------------------------
    async def search(self, query: str, limit: Optional[int] = None) -> List[Release]:
        """Поиск релизов (используется для inline и обычного поиска в чате)."""
        query = (query or "").strip()
        limit = limit or config.SEARCH_LIMIT
        if not query:
            return await self.latest(limit)

        # 1) Быстрый поиск приложения
        try:
            data = await self._get("/app/search/releases", {"query": query, "limit": limit})
            releases = [Release.from_api(item) for item in self._as_list(data)[:limit]]
            if releases:
                return releases
        except ReleaseNotFound:
            pass

        # 2) Полнотекстовый поиск каталога (f[search]) — резерв
        try:
            data = await self._get(
                "/anime/catalog/releases",
                {"f[search]": query, "limit": limit, "page": 1},
            )
            return [Release.from_api(item) for item in self._as_list(data)[:limit]]
        except ReleaseNotFound:
            return []

    async def catalog(
        self,
        search: Optional[str] = None,
        page: int = 1,
        limit: int = 30,
        genres: Optional[Iterable[int]] = None,
        year: Optional[int] = None,
    ) -> List[Release]:
        params: Dict[str, Any] = {"limit": limit, "page": max(1, page)}
        if search:
            params["f[search]"] = search
        if year:
            params["f[years][from]"] = year
        for index, genre in enumerate(genres or []):
            params[f"f[genres][{index}]"] = genre
        data = await self._get("/anime/catalog/releases", params)
        return [Release.from_api(item) for item in self._as_list(data)]

    async def latest(self, limit: Optional[int] = None) -> List[Release]:
        data = await self._get("/anime/releases/latest", {"limit": limit or config.SEARCH_LIMIT})
        return [Release.from_api(item) for item in self._as_list(data)]

    async def get_release(self, alias_or_id: str | int) -> Release:
        """Полный релиз вместе со списком серий и HLS-ссылками."""
        data = await self._get(f"/anime/releases/{alias_or_id}")
        raw = data
        if isinstance(data, dict) and not data.get("id"):
            items = self._as_list(data)
            if items:
                raw = items[0]
        if not isinstance(raw, dict) or not raw.get("id"):
            raise ReleaseNotFound(f"Релиз {alias_or_id} не найден")
        release = Release.from_api(raw)
        if release.is_geo_blocked:
            raise GeoBlocked(f"Релиз «{release.title}» заблокирован по региону")
        return release

    async def genres(self) -> List[str]:
        try:
            data = await self._get("/anime/genres")
        except AniLibriaError:
            return []
        items = self._as_list(data, keys=("data", "genres", "items"))
        names: List[str] = []
        for item in items:
            name = item.get("name") or item.get("title")
            if name:
                names.append(str(name))
        return sorted(set(names))

    async def random_release(self) -> Optional[Release]:
        """Случайный релиз из свежих (кнопка «Рандом»)."""
        import random

        releases = await self.latest(40)
        if not releases:
            return None
        return random.choice(releases)


__all__ = [
    "AniLibriaClient",
    "AniLibriaError",
    "AniLibriaUnavailable",
    "ReleaseNotFound",
    "GeoBlocked",
    "Release",
    "Episode",
    "strip_html",
]
