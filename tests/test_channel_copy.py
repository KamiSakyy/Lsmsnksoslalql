# -*- coding: utf-8 -*-
"""Ключевой сценарий: скачали → положили в канал → записали id в RTDB →
второй пользователь получает видео моментально копией из канала (без скачивания,
без имени канала). Плюс инлайн-карточка и кнопки выбора серий/качества.

Никаких обращений к Telegram и AniLibria: клиент Pyrogram подменён, RTDB — локальный
HTTP-сервер, скачивание подменено готовым файлом (ffmpeg не нужен).

Запуск:  python tests/test_channel_copy.py
"""

from __future__ import annotations

import asyncio
import json
import os
import pathlib
import shutil
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from types import SimpleNamespace
from urllib.parse import urlparse

ROOT = pathlib.Path(__file__).resolve().parent.parent
TMP = pathlib.Path("/tmp/test_channel_copy")
shutil.rmtree(TMP, ignore_errors=True)
os.environ.update({
    "DATA_DIR": str(TMP), "SESSION_DIR": str(TMP / "sessions"), "DOWNLOAD_DIR": str(TMP / "cache"),
    "DB_PATH": str(TMP / "db.sqlite3"), "RTDB_URL": "http://127.0.0.1:8907",
    "TELEGRAM_CHANNEL_ID": "-1004445072922", "PROGRESS_INTERVAL": "0.2",
})
sys.path.insert(0, str(ROOT))

import config                       # noqa: E402
import credentials as cred          # noqa: E402
import dl_engine                    # noqa: E402
import media_index                  # noqa: E402
import rtdb                         # noqa: E402
import store                        # noqa: E402
import ui                           # noqa: E402
from anilibria_api import Release   # noqa: E402

import anime_bot as B               # noqa: E402

config.ensure_dirs()
CHANNEL = -1004445072922
checks = 0


def ok(name: str) -> None:
    global checks
    checks += 1
    print(f"{checks}) {name}: OK")


# ------------------------- фейковый Firebase RTDB ---------------------------
STORE: dict = {}


class Firebase(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):  # noqa: D102
        pass

    def _route(self, method: str) -> None:
        path = urlparse(self.path).path
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""
        if method == "GET":
            data = STORE.get(path)
            payload = json.dumps(data).encode() if data else b"null"
        elif method == "PUT":
            STORE[path] = json.loads(body or b"null")
            payload = b"true"
        else:
            STORE.pop(path, None)
            payload = b"null"
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):            # noqa: N802
        self._route("GET")

    def do_PUT(self):            # noqa: N802
        self._route("PUT")

    def do_DELETE(self):         # noqa: N802
        self._route("DELETE")


server = ThreadingHTTPServer(("127.0.0.1", 8907), Firebase)
threading.Thread(target=server.serve_forever, daemon=True).start()


# ------------------------------ фейковый клиент ------------------------------
class FakeMessage:
    def __init__(self, mid=1, chat_id=100, text=None, caption=None, video=None):
        self.id, self.text, self.caption, self.video = mid, text, caption, video
        self.chat = SimpleNamespace(id=chat_id)
        self.from_user = SimpleNamespace(id=555, first_name="Тест", username="t")

    async def reply_text(self, text, **kwargs):
        return await B.APP.send_message(self.chat.id, text, **kwargs)

    async def delete(self):
        return True

    async def edit_text(self, text, **kwargs):
        return await B.APP.edit_message_text(self.chat.id, self.id, text, **kwargs)


class FakeClient:
    def __init__(self):
        self.n = 0
        self.calls: list = []
        self.channel_posts: list = []

    def _msg(self, chat_id, **kwargs):
        self.n += 1
        return FakeMessage(mid=self.n, chat_id=chat_id, **kwargs)

    async def send_message(self, chat_id, text, **kwargs):
        self.calls.append(("send_message", chat_id, text))
        return self._msg(chat_id, text=text)

    async def edit_message_text(self, chat_id, mid, text, **kwargs):
        self.calls.append(("edit_message_text", chat_id, text))
        return self._msg(chat_id, text=text)

    async def edit_message_caption(self, chat_id, mid, caption=None, **kwargs):
        self.calls.append(("edit_message_caption", chat_id, caption))
        return self._msg(chat_id, caption=caption)

    async def edit_message_media(self, chat_id, mid, media, **kwargs):
        self.calls.append(("edit_message_media", chat_id, str(media)))
        return self._msg(chat_id)

    async def delete_messages(self, chat_id, ids):
        self.calls.append(("delete_messages", chat_id, ids))
        return True

    async def send_chat_action(self, *args, **kwargs):
        return True

    async def send_video(self, chat_id, video, caption=None, **kwargs):
        self.calls.append(("send_video", chat_id, str(video)))
        if chat_id == CHANNEL:
            mid = 900 + len(self.channel_posts)
            self.channel_posts.append(mid)
            media = SimpleNamespace(file_id=f"CHANNELFILE{mid}", thumbs=[])
            return FakeMessage(mid=mid, chat_id=CHANNEL, video=media, caption=caption)
        return FakeMessage(chat_id=chat_id, video=SimpleNamespace(file_id="USERFILE", thumbs=[]),
                           caption=caption)

    async def send_document(self, chat_id, document, caption=None, **kwargs):
        self.calls.append(("send_document", chat_id, str(document)))
        return FakeMessage(chat_id=chat_id, video=SimpleNamespace(file_id="DOCFILE", thumbs=[]),
                           caption=caption)

    async def copy_message(self, chat_id, from_chat_id, message_id, caption=None, **kwargs):
        self.calls.append(("copy_message", chat_id, from_chat_id, message_id, caption))
        return FakeMessage(chat_id=chat_id, caption=caption)

    async def forward_messages(self, *args, **kwargs):
        self.calls.append(("forward_messages", args))
        return FakeMessage(chat_id=args[0] if args else 0)

    async def get_chat(self, chat_id):
        return SimpleNamespace(id=chat_id, title="Канал-хранилище")

    def calls_of(self, kind):
        return [call for call in self.calls if call[0] == kind]


# ------------------------------- фейковый релиз ------------------------------
def build_release() -> Release:
    raw = {
        "id": 8452, "alias": "bleach", "year": 2004,
        "name": {"main": "Блич", "english": "Bleach"},
        "type": {"value": "TV", "description": "Сериал"},
        "episodes_total": 3, "description": "Описание релиза для теста.",
        "poster": {"src": "https://anilibria.top/posters/bleach.jpg",
                   "preview": "https://anilibria.top/posters/bleach-preview.jpg",
                   "thumbnail": "https://anilibria.top/posters/bleach-thumb.jpg"},
        "genres": [{"name": "Экшен"}],
        "episodes": [
            {"id": f"e{n}", "ordinal": n, "duration": 1440, "name": f"Серия {n}",
             "hls_1080": f"https://cdn.test/{n}/1080.m3u8", "hls_720": f"https://cdn.test/{n}/720.m3u8",
             "hls_480": f"https://cdn.test/{n}/480.m3u8"}
            for n in (1, 2, 3)
        ],
    }
    return Release.from_api(raw)


RELEASE = build_release()


class StubQuery:
    def __init__(self, text=""):
        self.query = text
        self.from_user = SimpleNamespace(id=555, first_name="Тест", username="t")
        self.id = "inline1"
        self.answers: list = []


async def main() -> None:
    B.storage = store.Storage(config.DB_PATH)
    B.storage.init()
    B.APP = FakeClient()
    B.BOT_USERNAME = "anime_test_bot"
    B.CHANNEL_ID = CHANNEL
    await B.check_channel(B.APP)
    assert B.CHANNEL_READY, "канал-хранилище должен быть доступен (в тесте бот «админ»)"
    ok(f"Канал-хранилище доступен: {CHANNEL} (id вычислен из https://t.me/c/4445072922/42)")

    # ---------- инлайн: карточка сразу, без «галочки» ----------
    from pyrogram.types import InlineQueryResultArticle

    class InlineAnswer:
        def __init__(self, query): self.query = query
        async def answer(self, results=None, **kwargs):
            self.query.answers = results or []

    class ClientWrapper:
        def __init__(self, inner): self.inner = inner
        def __getattr__(self, item): return getattr(self.inner, item)

    B.APP = ClientWrapper(B.APP)
    query = StubQuery("блич")
    query.answer = InlineAnswer(query).answer
    B.api = SimpleNamespace(search=lambda *a, **kw: _async([RELEASE]),
                            latest=lambda *a, **kw: _async([RELEASE]))
    await B.inline_query_handler(None, query)
    assert query.answers and isinstance(query.answers[0], InlineQueryResultArticle), query.answers
    card = query.answers[0]
    assert "▶️ Смотреть" in str(card.reply_markup) and "t.me/anime_test_bot?start=eps_8452" in str(card.reply_markup)
    assert "start=eps_8452" in card.input_message_content.message_text
    ok("Инлайн: обычная карточка (тап мгновенный, без галочки) → кнопка ведёт сразу к выбору серий")

    # ---------- карточка релиза и кнопки выбора ----------
    kb = str(ui.release_kb(RELEASE.id, has_episodes=True, site_url=RELEASE.site_url))
    assert "▶️ Смотреть" in kb and "📺 Все серии" in kb and "anilibria.top/anime" in kb, kb
    ok("Карточка релиза: «▶️ Смотреть» + «📺 Все серии» + ссылка AniLibria (как раньше)")

    # ---------- первое скачивание: в канал + RTDB + копия пользователю ----------
    real = pathlib.Path(TMP) / "ep.mp4"
    real.write_bytes(b"\x00" * 4096)
    thumb = pathlib.Path(TMP) / "ep.jpg"
    thumb.write_bytes(b"\xff\xd8\xff\xd9")
    downloads: list = []

    async def fake_download(release_id, episode, quality, hls_url, on_progress=None, cancel_event=None):
        downloads.append((release_id, episode, quality))
        paths = dl_engine.episode_paths(release_id, episode, quality)
        paths["dir"].mkdir(parents=True, exist_ok=True)
        shutil.copy2(real, paths["video"])
        shutil.copy2(thumb, paths["thumb"])
        return dl_engine.MediaInfo(path=str(paths["video"]), size=real.stat().st_size, duration=5.0,
                                   width=640, height=360, video_codec="h264", audio_codec="aac",
                                   thumb_path=str(paths["thumb"]), mode="copy")

    B.dl_engine.download_episode = fake_download
    B.api = SimpleNamespace(get_release=lambda *a, **kw: _async(RELEASE))

    work = {"task_id": "t1", "chat_id": 100, "user_id": 555, "release_id": RELEASE.id,
            "episode": 1, "quality": 1080, "cancel": asyncio.Event(),
            "state": dl_engine.ProgressState(), "title": RELEASE.title, "message_id": 1}
    await B.process_episode(work, RELEASE, 1, 1080)
    inner = B.APP.inner if isinstance(B.APP, ClientWrapper) else B.APP
    assert downloads == [(RELEASE.id, 1, 1080)], downloads
    sent = inner.calls_of("send_video")
    assert sent and sent[0][1] == CHANNEL, sent
    copies = inner.calls_of("copy_message")
    assert copies and copies[0][1] == 100 and copies[0][2] == CHANNEL, copies
    assert not inner.calls_of("forward_messages"), "пересылка показала бы канал — нужна копия"
    ok(f"Первое скачивание: видео ушло в канал (сообщение {inner.channel_posts[0]}), "
       f"пользователю отдано КОПИЕЙ из канала")

    # ---------- что попало в RTDB ----------
    assert STORE, "RTDB пуст"
    path = next(p for p in STORE if "8452-1-1080" in p)
    entry = STORE[path]
    assert entry["message_id"] == inner.channel_posts[0] and entry["channel_id"] == CHANNEL
    assert entry["quality"] == 1080 and entry["title"] == "Блич"
    ok(f"RTDB: {path} → message_id={entry['message_id']}, quality=1080, title='Блич'")

    # ---------- второй пользователь: моментально, без скачивания ----------
    B.storage = store.Storage(str(TMP / "second.sqlite3"))
    B.storage.init()
    cred.reset()
    rtdb._CLIENT = None
    downloads.clear()
    inner.calls.clear()
    ok_second = await B.process_episode({**work, "chat_id": 777, "user_id": 777}, RELEASE, 1, 1080)
    assert ok_second and not downloads, f"без скачивания: {downloads}"
    copies = inner.calls_of("copy_message")
    assert copies and copies[0][1] == 777, inner.calls
    caption = copies[0][4] or ""
    assert "Серия 1" in caption and "🎬" in caption, caption
    ok("Второй пользователь (пустой локальный кэш, только RTDB): видео выдано МОМЕНТАЛЬНО "
       "копией из канала, скачиваний 0")

    # ---------- статистика показывает пары и канал ----------
    stats = await B.collect_stats()
    assert "API-пара" in stats and "Пары api_id" in stats and "Канал" in stats, list(stats)
    ok(f"Статистика: «{stats['API-пара']}», «{stats['Пары api_id']}», канал «{stats['Канал']}»")

    B.storage.close()                      # sync-закрытие SQLite
    await rtdb.client().close()


def _async(value):
    async def inner(*args, **kwargs):
        return value
    return inner()


asyncio.run(main())
server.shutdown()
print("\n✅ КЛЮЧЕВОЙ СЦЕНАРИЙ РАБОТАЕТ: инлайн → серия/качество → канал + RTDB → "
      "мгновенная копия из канала второму пользователю")
