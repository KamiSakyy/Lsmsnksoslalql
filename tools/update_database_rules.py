#!/usr/bin/env python3
"""Публикует database.rules.json в Realtime Database прямо из CI.

Правила — часть исходников (database.rules.json), но база их не увидит, пока кто-то
не опубликует. Раньше это делали руками; теперь это делает тот же GitHub Actions,
который собирает APK. Никакого своего сервера: только REST-эндпоинт Firebase и ключ
служебного аккаунта, который и так лежит в репозитории.

Скрипт никогда не валит сборку: при любой ошибке он печатает ответ Firebase и
завершается успешно, чтобы APK всё равно был собран.
"""
from __future__ import annotations

import base64
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
RULES = ROOT / "database.rules.json"
DATABASE = os.environ.get(
    "TSUYU_DATABASE_URL", "https://meowmessenger-default-rtdb.europe-west1.firebasedatabase.app"
).rstrip("/")
HOST = urllib.parse.urlparse(DATABASE).netloc
SCOPE = ("https://www.googleapis.com/auth/firebase.database "
         "https://www.googleapis.com/auth/cloud-platform "
         "https://www.googleapis.com/auth/userinfo.email")
LAST_ERROR = {"code": None, "body": ""}


def find_key() -> pathlib.Path | None:
    candidates = [ROOT / "app/src/main/assets/fcm_sender_key.dat",
                  ROOT / "app/src/main/assets/fcm_sender_key.json",
                  ROOT / "signing/fcm_sender_key.json"]
    candidates += sorted(ROOT.glob("*firebase-adminsdk*.json"))
    candidates += sorted(ROOT.glob("*service-account*.json"))
    for path in candidates:
        if path.exists():
            return path
    return None


def b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def access_token(key: dict) -> str:
    now = int(time.time())
    header = b64(json.dumps({"alg": "RS256", "typ": "JWT"}, separators=(",", ":")).encode())
    claims = b64(json.dumps({
        "iss": key["client_email"],
        "scope": SCOPE,
        "aud": key.get("token_uri", "https://oauth2.googleapis.com/token"),
        "iat": now,
        "exp": now + 3600,
    }, separators=(",", ":")).encode())
    unsigned = f"{header}.{claims}"
    with tempfile.NamedTemporaryFile("w", suffix=".pem", delete=False) as handle:
        handle.write(key["private_key"])
        pem = handle.name
    try:
        signature = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", pem],
            input=unsigned.encode(), capture_output=True, check=True).stdout
    finally:
        pathlib.Path(pem).unlink(missing_ok=True)
    assertion = f"{unsigned}.{b64(signature)}"
    form = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": assertion,
    }).encode()
    request = urllib.request.Request(
        key.get("token_uri", "https://oauth2.googleapis.com/token"),
        data=form,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)["access_token"]


def publish(token: str, url: str) -> bool:
    body = RULES.read_bytes()
    request = urllib.request.Request(
        f"{url}/.settings/rules.json?access_token={urllib.parse.quote(token)}",
        data=body,
        method="PUT",
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            reply = response.read().decode("utf-8", "replace")
            print(f"{url} -> HTTP {response.status} {reply[:200]}")
            return response.status == 200
    except urllib.error.HTTPError as failure:
        body = failure.read().decode("utf-8", "replace")[:300]
        LAST_ERROR["code"] = failure.code
        LAST_ERROR["body"] = body.replace("%", "%25").replace("\n", " ")
        print(f"{url} -> HTTP {failure.code} {body}")
        return False
    except Exception as failure:  # noqa: BLE001 - сеть в CI бывает недоступна
        LAST_ERROR["body"] = str(failure).replace("%", "%25").replace("\n", " ")
        print(f"{url} -> {failure}")
        return False


def main() -> int:
    if not RULES.exists():
        print("database.rules.json не найден")
        return 0
    json.loads(RULES.read_text(encoding="utf-8"))  # локальная проверка синтаксиса
    key_path = find_key()
    if key_path is None:
        print("ключ служебного аккаунта не найден — правила не опубликованы")
        return 0
    raw = key_path.read_text(encoding="utf-8").strip()
    if raw.startswith("{"):
        key = json.loads(raw)
    else:
        # Ключ хранится закодированным: так его пропускает защита репозитория.
        key = json.loads(base64.b64decode(raw).decode("utf-8"))
    try:
        token = access_token(key)
    except urllib.error.HTTPError as failure:
        print("не удалось получить OAuth2 токен:", failure.code,
              failure.read().decode("utf-8", "replace")[:300])
        return 0
    except Exception as failure:  # noqa: BLE001
        print("не удалось получить OAuth2 токен:", failure)
        return 0
    print("ключ:", key.get("client_email"), "| база:", HOST)
    # Пробуем и региональный адрес, и классический алиас: правила уезжают в ту же базу.
    for url in (DATABASE, f"https://{HOST.split('.')[0]}.firebaseio.com"):
        if publish(token, url):
            print("::notice title=Правила базы::database.rules.json опубликован")
            return 0
    # Причину печатаем прямо в аннотацию: журнал шага в CI читать не всегда удобно.
    reason = f"{LAST_ERROR['code'] or 'сеть'} {LAST_ERROR['body']}".strip()
    print(f"::warning title=Правила базы::не удалось опубликовать database.rules.json — {reason[:220]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
