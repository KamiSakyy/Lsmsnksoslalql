# -*- coding: utf-8 -*-
"""Роутинг в бою: фейковый Telegram, который режет пары как настоящий.

Проверяем:
  • старт идёт по «умному» порядку и переживает API_ID_PUBLISHED_FLOOD / FLOOD_WAIT / AUTH;
  • после перезапуска контейнера бот сразу берёт рабочую пару (одна попытка, без перебора);
  • когда все пары заблокированы — процесс НЕ падает (нет шторма перезапусков Railway),
    а ждёт с растущей паузой и почти не тратит попытки;
  • если рабочая пара отвалилась в работе — бот сам меняет пару.

Запуск:  python tests/test_routing.py
"""

from __future__ import annotations

import asyncio as real_asyncio
import os
import pathlib
import random
import shutil
import sys
from types import SimpleNamespace

ROOT = pathlib.Path(__file__).resolve().parent.parent
TMP = pathlib.Path("/tmp/test_routing")
shutil.rmtree(TMP, ignore_errors=True)
os.environ.update({
    "DATA_DIR": str(TMP), "SESSION_DIR": str(TMP / "sessions"), "DOWNLOAD_DIR": str(TMP / "cache"),
    "DB_PATH": str(TMP / "db.sqlite3"), "API_KEYS_RETRY_BASE": "60", "API_KEYS_RETRY_MAX": "1800",
    "API_KEYS_TRY_DELAY_MIN": "0", "API_KEYS_TRY_DELAY_MAX": "0", "SUPERVISOR_TICK": "10",
})
sys.path.insert(0, str(ROOT))

import config                       # noqa: E402
import credentials as cred          # noqa: E402
config.ensure_dirs()

import anime_bot as B               # noqa: E402

PAIRS = [
    (4, "hash4", "public_android_beta"),
    (6, "hash6", "tg_android"),
    (2040, "hash2040", "public_win_beta"),
    (21724, "hash21724", "telegram_x_android"),
]
STATE = TMP / "state.json"
checks = 0


def ok(name: str) -> None:
    global checks
    checks += 1
    print(f"{checks}) {name}: OK")


def published() -> Exception:
    return Exception("[400 API_ID_PUBLISHED_FLOOD] - You are using an API key that is limited "
                     "on the server side because it was published somewhere")


def flood(wait: int = 30) -> Exception:
    from pyrogram.errors import FloodWait
    return FloodWait(wait)


def auth_dead() -> Exception:
    return Exception("[401 AUTH_KEY_UNREGISTERED] - The key is not registered in the system")


def bad_api_id() -> Exception:
    return Exception("[400 API_ID_INVALID] - The API ID is invalid")


def net_down() -> Exception:
    return ConnectionError("Cannot connect to host 149.154.167.50:443 ssl:default")


# --------------------------------------------------------------------------
#  «Мир»: скрипт поведения Telegram по каждой паре + запись попыток
# --------------------------------------------------------------------------
WORLD: dict = {"script": {}, "calls": [], "clients": [], "ticks": []}


class StopTest(Exception):
    """Сигнал закончить тест (вместо бесконечного цикла супервизора)."""


class FakeClient:
    def __init__(self, **kwargs):
        self.api_id = int(kwargs["api_id"])
        self.kwargs = kwargs
        self.is_connected = False
        self.drop_in = None            # через сколько «тиков» связь пропадёт
        WORLD["clients"].append(self)

    def _next_outcome(self) -> str:
        script = WORLD["script"].setdefault(self.api_id, ["OK"])
        return script.pop(0) if len(script) > 1 else script[0]

    async def start(self):
        outcome = self._next_outcome()
        WORLD["calls"].append((self.api_id, outcome))
        if outcome == "OK":
            self.is_connected = True
            return self
        if outcome == "DROP":          # старт удался, но связь тут же теряется
            self.is_connected = True
            self.drop_in = 0
            return self
        raise {"PUBLISHED": published, "FLOOD": flood, "AUTH": auth_dead,
               "BAD_ID": bad_api_id, "NET": net_down}[outcome]()

    async def get_me(self):
        return SimpleNamespace(username=f"bot_{self.api_id}", first_name="bot")

    async def stop(self):
        self.is_connected = False

    def _tick(self):
        if self.drop_in is not None and self.is_connected:
            if self.drop_in <= 0:
                self.is_connected = False
                self.drop_in = None
            else:
                self.drop_in -= 1


class FakeAsyncio:
    """Паузы не настоящие: копим «виртуальное время» и вовремя останавливаем тест."""

    def __init__(self, budget_minutes: float = 90.0):
        self.delays: list[float] = []
        self.time = 0.0
        self.budget = budget_minutes * 60
        self.steps = 0

    async def sleep(self, delay: float = 0, *args, **kwargs):
        delay = float(delay or 0)
        self.delays.append(delay)
        self.time += delay
        self.steps += 1
        WORLD["ticks"].append(round(self.time, 1))
        for client in WORLD["clients"]:
            client._tick()
        await real_asyncio.sleep(0)          # отдаём управление, но не ждём реально
        if self.time >= self.budget:
            raise StopTest()

    def __getattr__(self, item):
        return getattr(real_asyncio, item)


def install(budget_minutes: float = 90.0) -> FakeAsyncio:
    shim = FakeAsyncio(budget_minutes)
    B.asyncio = shim
    B.Client = FakeClient
    B.register_handlers = lambda client: None

    async def noop(*args, **kwargs):
        return None

    B._set_bot_commands = noop
    B.check_channel = noop
    return shim


def reset_world(script: dict) -> None:
    WORLD["script"] = {api_id: list(outcomes) for api_id, outcomes in script.items()}
    WORLD["calls"] = []
    WORLD["clients"] = []
    WORLD["ticks"] = []
    cred.reset()


# --------------------------------------------------------------------------
#  1. Первый запуск: Telegram режет три пары, четвёртая пускает
# --------------------------------------------------------------------------
install()
reset_world({4: ["PUBLISHED"], 6: ["FLOOD"], 2040: ["PUBLISHED"], 21724: ["OK"]})
cred.rotator(pairs=PAIRS, state_path=STATE, fresh=True)
random.seed(7)

client = real_asyncio.run(B.start_client())
assert client is not None, "бот обязан подняться на рабочей паре"
tried = [api_id for api_id, _ in WORLD["calls"]]
assert 21724 in tried and client.api_id == 21724, tried
assert cred.ACTIVE.api_id == 21724 and cred.ACTIVE.name == "telegram_x_android", cred.ACTIVE.api_id
rot = cred.rotator()
assert rot.state_of(4).kind == cred.KIND_PUBLISHED and rot.state_of(4).cooling
assert rot.state_of(6).kind == cred.KIND_FLOOD and 25 <= rot.state_of(6).cooldown_left <= 45
assert rot.state_of(21724).ok == 1
ok(f"Первый запуск: Telegram отказал парам {[api_id for api_id in tried if api_id != 21724]}, "
   f"бот поднялся на api_id={client.api_id} — флуд-лимит каждой пары учтён отдельно")

# --------------------------------------------------------------------------
#  2. Перезапуск контейнера: одна попытка, никакого перебора
# --------------------------------------------------------------------------
reset_world({4: ["PUBLISHED"], 6: ["FLOOD"], 2040: ["PUBLISHED"], 21724: ["OK"]})
cred.rotator(pairs=PAIRS, state_path=STATE, fresh=True)      # состояние читается с диска
client2 = real_asyncio.run(B.start_client())
assert client2 is not None and client2.api_id == 21724, client2
assert [api_id for api_id, _ in WORLD["calls"]] == [21724], WORLD["calls"]
ok("Перезапуск: состояние подхвачено с диска — первая же попытка рабочая, "
   "заблокированные пары не трогаются повторно")

# --------------------------------------------------------------------------
#  3. Все пары заблокированы: процесс жив, попытки ограничены, пауза растёт
# --------------------------------------------------------------------------
reset_world({4: ["PUBLISHED"], 6: ["PUBLISHED"], 2040: ["PUBLISHED"], 21724: ["PUBLISHED"]})
rot = cred.rotator(pairs=PAIRS, state_path=STATE / "all_blocked.json", fresh=True)
shim = install(budget_minutes=120)
try:
    real_asyncio.run(real_asyncio.wait_for(B.run_supervisor(), timeout=30))
except (StopTest, real_asyncio.TimeoutError):
    pass
attempts = len(WORLD["calls"])
waits = [d for d in shim.delays if d >= 10]
assert attempts == len(PAIRS), f"каждую пару пробуем ровно один раз, а не по кругу: {attempts} попыток"
assert all(api_id in {4, 6, 2040, 21724} for api_id, _ in WORLD["calls"])
assert waits and waits[0] >= 60, waits[:5]
assert any(w >= 120 for w in waits), f"паузы должны расти: {waits[:6]}"
assert all(w <= config.API_KEYS_RETRY_MAX + 5 for w in waits), waits[:6]
ok(f"Все пары — опубликованные: бот не падает и не крутит шторм (попыток {attempts} за "
   f"{len(WORLD['ticks'])} тиков), ждёт до {cred.human_delay(max(waits))} — Railway не перезапускает контейнер")

# --------------------------------------------------------------------------
#  4. Пара отвалилась в работе → Telegram банит её → бот меняет пару сам
# --------------------------------------------------------------------------
reset_world({4: ["PUBLISHED"], 6: ["FLOOD"], 2040: ["DROP", "PUBLISHED"], 21724: ["OK"]})
rot = cred.rotator(pairs=PAIRS, state_path=STATE / "runtime.json", fresh=True)
shim = install(budget_minutes=3)
try:
    real_asyncio.run(real_asyncio.wait_for(B.run_supervisor(), timeout=30))
except (StopTest, real_asyncio.TimeoutError):
    pass
calls = [api_id for api_id, _ in WORLD["calls"]]
assert cred.ACTIVE.api_id == 21724, f"после бана рабочей пары бот должен перейти на другую: {calls}"
assert calls.count(2040) >= 2, calls          # была попытка переподключиться той же парой
state = cred.rotator().state_of(2040)
assert state.kind == cred.KIND_PUBLISHED and state.cooldown_left > 3600, state
ok(f"Отвал рабочей пары в работе: переподключение → бан api_id=2040 → автоматический переход "
   f"на api_id={cred.ACTIVE.api_id} ({calls})")

# --------------------------------------------------------------------------
#  5. Пара с неверным api_id и сетевые сбои — не «хоронят» рабочие пары
# --------------------------------------------------------------------------
install(budget_minutes=30)          # свежий бюджет «виртуального времени»
reset_world({4: ["BAD_ID"], 6: ["NET", "NET", "OK"], 2040: ["OK"], 21724: ["OK"]})
rot = cred.rotator(pairs=PAIRS, state_path=STATE / "mixed.json", fresh=True)
client5 = real_asyncio.run(B.start_client())
assert client5 is not None, "сетевые сбои не должны мешать подъёму"
assert rot.state_of(4).kind == cred.KIND_AUTH and rot.state_of(4).cooling
assert rot.state_of(6).kind == cred.KIND_NETWORK and rot.state_of(6).cooldown_left <= 60
ok("Неверный api_id уходит в длинную паузу, а сетевые ошибки — только короткая (60 с), "
   "пары не теряются")

print("\n✅ РОУТИНГ В БОЮ: умный порядок, память между перезапусками, "
      "без шторма попыток, автосмена пары на ходу")
