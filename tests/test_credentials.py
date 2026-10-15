# -*- coding: utf-8 -*-
"""Умный роутинг по api_id/api_hash: классификация ошибок, порядок, память.

Запуск:  python tests/test_credentials.py
"""

from __future__ import annotations

import os
import pathlib
import shutil
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
TMP = pathlib.Path("/tmp/test_credentials")
shutil.rmtree(TMP, ignore_errors=True)
os.environ.update({"DATA_DIR": str(TMP), "SESSION_DIR": str(TMP / "sessions"),
                   "DOWNLOAD_DIR": str(TMP / "cache"), "DB_PATH": str(TMP / "db.sqlite3")})
sys.path.insert(0, str(ROOT))

import config                       # noqa: E402
import credentials as cred          # noqa: E402

config.ensure_dirs()
checks = 0


def ok(name: str) -> None:
    global checks
    checks += 1
    print(f"{checks}) {name}: OK")


# --- 1. Классификация ошибок ------------------------------------------------
kind, code, cooldown = cred.classify("[400 API_ID_PUBLISHED_FLOOD] - You are using an API key "
                                     "that is limited on the server side because it was published")
assert kind == cred.KIND_PUBLISHED and code == "API_ID_PUBLISHED_FLOOD", (kind, code)
ok(f"Публичный api_id распознан → пауза {cred.human_delay(cooldown)} (а не вечные попытки)")

kind, code, cooldown = cred.classify("[420 FLOOD_WAIT_37] - A wait of 37 seconds is required")
assert kind == cred.KIND_FLOOD and code == "FLOOD_WAIT_37" and 37 <= cooldown <= 45, (kind, code, cooldown)
ok("FloodWait распознан и уважается ровно на столько секунд, сколько просит Telegram")

kind, code, _ = cred.classify("pyrogram.errors.AuthKeyUnregistered: [401 AUTH_KEY_UNREGISTERED]")
assert kind == cred.KIND_AUTH and code == "AUTH_KEY_UNREGISTERED", (kind, code)
ok("Сломанная сессия (AUTH_KEY_UNREGISTERED) распознана → пара уходит в длинную паузу")

kind, code, _ = cred.classify("ConnectionError: Cannot connect to host 149.154.167.50 (proxy)")
assert kind == cred.KIND_NETWORK, (kind, code)
ok("Сетевой сбой распознан как временный (пару не «хороним»)")

# --- 2. Порядок пар: рабочая → непроверенные → с отказами -------------------
PAIRS = [(4, "hash4", "public_android_beta"), (6, "hash6", "tg_android"),
         (2040, "hash2040", "public_win_beta"), (17349, "hash17349", "telegram_desktop")]
rot = cred.CredentialRotator(pairs=PAIRS, state_path=TMP / "state.json")
order_before = [p[0] for p in rot.candidates()]
rot.note_failure(4, "public_android_beta", "[400 API_ID_PUBLISHED_FLOOD] published somewhere")
rot.note_failure(6, "tg_android", "[420 FLOOD_WAIT_30] wait")
rot.note_success(17349, "telegram_desktop")
order_after = [p[0] for p in rot.candidates()]
assert order_after[0] == 17349, order_after
assert set(order_after) == {2040, 17349}, order_after       # 4 и 6 в паузе — не трогаем
ok(f"Порядок пар: рабочая api_id={order_after[0]} первой, битые в паузе не перебираются "
   f"({order_before} → {order_after})")

# --- 3. Память между перезапусками контейнера -------------------------------
rot2 = cred.CredentialRotator(pairs=PAIRS, state_path=TMP / "state.json")
states = {p[0]: rot2.state_of(p[0]) for p in PAIRS}
assert states[17349].last_ok_at > 0 and states[4].kind == cred.KIND_PUBLISHED, states[17349]
assert [p[0] for p in rot2.candidates()][0] == 17349
ok("Состояние сохраняется в /data: после перезапуска бот сразу берёт рабочую пару")

# --- 4. Когда все пары в паузе — понятная диагностика, а не перебор ---------
rot3 = cred.CredentialRotator(pairs=PAIRS, state_path=TMP / "state3.json")
for api_id, _h, name in PAIRS:
    rot3.note_failure(api_id, name, "[400 API_ID_PUBLISHED_FLOOD] - published somewhere")
assert rot3.candidates() == []
wait_for = rot3.seconds_until_ready()
assert wait_for > 0, wait_for
report = rot3.report()
assert "API_ID_PUBLISHED_FLOOD" in report and "пауза" in report, report
assert len(rot3.published_blocked()) == 4
ok(f"Все пары опубликованные → бот не долбит их, а ждёт {cred.human_delay(wait_for)} "
   f"и говорит об этом прямо: «{report.splitlines()[0]}»")

# --- 5. Сломанная сессия удаляется, чтобы не мешать -------------------------
session = pathlib.Path(config.SESSION_DIR) / "animebot_999.session"
session.parent.mkdir(parents=True, exist_ok=True)
session.write_text("broken")
rot3.note_failure(999, "test", "[401 AUTH_KEY_UNREGISTERED]")
assert not session.exists(), session
ok("Битая сессия пары удаляется автоматически")

# --- 6. Ограничение «сколько пар за проход» ---------------------------------
config.MAX_API_KEY_ATTEMPTS = 0
assert len(config.credential_candidates()) == len(cred.CredentialRotator().pairs)
ok(f"За проход берутся ВСЕ пары из api_keys.py ({len(config.credential_candidates())} шт.), "
   f"а не первые шесть")

print("\n✅ РОУТИНГ ПО ПАРАМ: ошибки различаются, битые пары не перебираются, "
      "рабочая запоминается между перезапусками")
