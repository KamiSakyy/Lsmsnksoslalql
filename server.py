import asyncio
import hashlib
import hmac
import json
import logging
import os
import secrets
import sqlite3
import sys
import time
import uuid
from aiohttp import web, WSMsgType

# Детальные русские логи в stdout
def log_info(msg):
    now = time.strftime('%d.%m %H:%M:%S')
    print(f"[{now}] [СЕРВЕР] {msg}", flush=True)

def log_error(msg):
    now = time.strftime('%d.%m %H:%M:%S')
    print(f"[{now}] [ОШИБКА] {msg}", flush=True)

raw_port = os.environ.get("PORT", "8080")
try:
    PORT = int(raw_port)
except Exception:
    PORT = 8080

DB_PATH = os.environ.get("DB_PATH", "tsuyu_data.db")
UPLOAD_DIR = os.environ.get("UPLOAD_DIR", "uploads")
os.makedirs(UPLOAD_DIR, exist_ok=True)

# ----------------- Database Setup (SQLite WAL) -----------------
def init_db():
    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    cur.execute("PRAGMA journal_mode=WAL;")
    cur.execute("PRAGMA synchronous=NORMAL;")
    
    # Пользователи
    cur.execute("""
        CREATE TABLE IF NOT EXISTS users (
            uid TEXT PRIMARY KEY,
            email TEXT UNIQUE,
            username TEXT UNIQUE,
            password_hash TEXT,
            display_name TEXT,
            avatar_url TEXT,
            about TEXT,
            last_seen INTEGER,
            created_at INTEGER
        )
    """)

    # E2EE Ключи шифрования
    cur.execute("""
        CREATE TABLE IF NOT EXISTS user_keys (
            uid TEXT PRIMARY KEY,
            key_bundle TEXT,
            updated_at INTEGER
        )
    """)

    # Сообщения
    cur.execute("""
        CREATE TABLE IF NOT EXISTS messages (
            id TEXT PRIMARY KEY,
            chat_id TEXT,
            sender_uid TEXT,
            recipient_uid TEXT,
            payload TEXT,
            cipher TEXT,
            signal_type INTEGER DEFAULT 1,
            kind TEXT,
            reply_to TEXT,
            forward_from TEXT,
            created_at INTEGER,
            is_read INTEGER DEFAULT 0
        )
    """)

    # Чаты и участники
    cur.execute("""
        CREATE TABLE IF NOT EXISTS chat_members (
            chat_id TEXT,
            uid TEXT,
            peer_uid TEXT,
            is_pinned INTEGER DEFAULT 0,
            is_archived INTEGER DEFAULT 0,
            unread_count INTEGER DEFAULT 0,
            PRIMARY KEY (chat_id, uid)
        )
    """)

    # Медиафайлы (без Base64!)
    cur.execute("""
        CREATE TABLE IF NOT EXISTS files (
            id TEXT PRIMARY KEY,
            owner_uid TEXT,
            filename TEXT,
            content_type TEXT,
            size INTEGER,
            storage_path TEXT,
            created_at INTEGER
        )
    """)

    conn.commit()
    conn.close()
    log_info("База данных SQLite (WAL) успешно инициализирована")

init_db()

def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn

# ----------------- Авторизация и хэширование -----------------
def hash_password(password: str) -> str:
    salt = secrets.token_hex(16)
    hashed = hashlib.sha256((password + salt).encode('utf-8')).hexdigest()
    return f"{salt}:{hashed}"

def verify_password(password: str, stored_hash: str) -> bool:
    try:
        salt, hashed = stored_hash.split(":")
        return hashlib.sha256((password + salt).encode('utf-8')).hexdigest() == hashed
    except Exception:
        return False

# Сессии
sessions = {}  # token -> uid
connected_clients = {}  # uid -> set of websockets

def authenticate(request):
    auth_header = request.headers.get("Authorization", "")
    token = ""
    if auth_header.startswith("Bearer "):
        token = auth_header[7:].strip()
    elif "token" in request.query:
        token = request.query["token"]

    if not token or token not in sessions:
        return None
    return sessions[token]

# ----------------- WebSocket TsuyuProto (Live Transport) -----------------
async def ws_handler(request):
    ws = web.WebSocketResponse(heartbeat=25.0)
    await ws.prepare(request)

    uid = None
    try:
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                try:
                    data = json.loads(msg.data)
                except Exception:
                    continue

                action = data.get("action")
                payload = data.get("payload", {})
                req_id = data.get("req_id")

                if action == "auth":
                    token = payload.get("token")
                    if token in sessions:
                        uid = sessions[token]
                        if uid not in connected_clients:
                            connected_clients[uid] = set()
                        connected_clients[uid].add(ws)

                        conn = get_db()
                        now_ms = int(time.time() * 1000)
                        conn.execute("UPDATE users SET last_seen = ? WHERE uid = ?", (now_ms, uid))
                        row = conn.execute("SELECT username, display_name FROM users WHERE uid = ?", (uid,)).fetchone()
                        conn.commit()
                        conn.close()

                        u_name = row["username"] if row else uid
                        log_info(f"🟢 Подключился онлайн по WebSocket: @{u_name} (UID: {uid})")

                        await ws.send_json({
                            "type": "auth_ok",
                            "req_id": req_id,
                            "uid": uid
                        })
                    else:
                        await ws.send_json({"type": "error", "message": "Неверный токен авторизации", "req_id": req_id})

                elif action == "ping":
                    await ws.send_json({"type": "pong", "time": int(time.time() * 1000)})

                elif action == "send_msg" and uid:
                    recipient_uid = payload.get("recipient_uid")
                    content = payload.get("content") or payload.get("payload")
                    cipher = payload.get("cipher")
                    signal_type = payload.get("signalType", 1)
                    kind = payload.get("kind", "text")
                    chat_id = payload.get("chat_id") or "_".join(sorted([uid, recipient_uid]))
                    msg_id = payload.get("id") or str(uuid.uuid4())
                    now = int(time.time() * 1000)

                    conn = get_db()
                    conn.execute("""
                        INSERT INTO messages (id, chat_id, sender_uid, recipient_uid, payload, cipher, signal_type, kind, created_at, is_read)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                    """, (msg_id, chat_id, uid, recipient_uid, content, cipher, signal_type, kind, now))

                    # Обновить участников чата
                    conn.execute("INSERT OR IGNORE INTO chat_members (chat_id, uid, peer_uid) VALUES (?, ?, ?)", (chat_id, uid, recipient_uid))
                    conn.execute("INSERT OR IGNORE INTO chat_members (chat_id, uid, peer_uid) VALUES (?, ?, ?)", (chat_id, recipient_uid, uid))
                    conn.commit()
                    conn.close()

                    log_info(f"💬 Новое сообщение в чате {chat_id}: от {uid} к {recipient_uid} (ID: {msg_id})")

                    event = {
                        "type": "new_message",
                        "message": {
                            "id": msg_id,
                            "chat_id": chat_id,
                            "sender_uid": uid,
                            "recipient_uid": recipient_uid,
                            "payload": content,
                            "cipher": cipher,
                            "signalType": signal_type,
                            "kind": kind,
                            "created_at": now
                        }
                    }

                    await ws.send_json({"type": "msg_sent", "req_id": req_id, "id": msg_id, "created_at": now})

                    if recipient_uid in connected_clients:
                        for sock in list(connected_clients[recipient_uid]):
                            try:
                                await sock.send_json(event)
                            except Exception:
                                pass

                elif action == "typing" and uid:
                    peer_uid = payload.get("peer_uid")
                    if peer_uid in connected_clients:
                        for sock in list(connected_clients[peer_uid]):
                            try:
                                await sock.send_json({"type": "user_typing", "uid": uid})
                            except Exception:
                                pass

    finally:
        if uid and uid in connected_clients:
            connected_clients[uid].discard(ws)
            if not connected_clients[uid]:
                del connected_clients[uid]
                log_info(f"⚪ Клиент отключился: {uid}")

    return ws

# ----------------- REST API -----------------
async def ping(request):
    return web.json_response({
        "status": "ok",
        "service": "Tsuyu Core Engine 2026",
        "version": "1.45",
        "online_users": len(connected_clients),
        "time": int(time.time())
    })

async def api_register(request):
    try:
        data = await request.json()
        email = data.get("email", "").strip().lower()
        password = data.get("password", "")
        username = data.get("username", "").strip().replace("@", "") or email.split("@")[0]
        display_name = data.get("display_name", "").strip() or username

        if not email or not password:
            return web.json_response({"error": "Введите email и пароль"}, status=400)

        conn = get_db()
        cur = conn.cursor()
        cur.execute("SELECT uid FROM users WHERE email = ? OR username = ?", (email, username))
        if cur.fetchone():
            conn.close()
            log_error(f"Попытка регистрации уже существующего пользователя: {email} / @{username}")
            return web.json_response({"error": "Пользователь с таким email или юзернеймом уже существует"}, status=400)

        uid = str(uuid.uuid4())
        pwd_hash = hash_password(password)
        now = int(time.time() * 1000)

        cur.execute("""
            INSERT INTO users (uid, email, username, password_hash, display_name, avatar_url, about, last_seen, created_at)
            VALUES (?, ?, ?, ?, ?, '', '', ?, ?)
        """, (uid, email, username, pwd_hash, display_name, now, now))
        conn.commit()
        conn.close()

        token = secrets.token_hex(32)
        sessions[token] = uid

        log_info(f"🎉 Успешная РЕГИСТРАЦИЯ: @{username} ({display_name}), Email: {email}, UID: {uid}")

        return web.json_response({
            "status": "success",
            "token": token,
            "user": {
                "uid": uid,
                "email": email,
                "username": username,
                "display_name": display_name,
                "avatar_url": "",
                "about": ""
            }
        })
    except Exception as e:
        log_error(f"Сбой при регистрации: {e}")
        return web.json_response({"error": str(e)}, status=500)

async def api_login(request):
    try:
        data = await request.json()
        account = data.get("email", "").strip().lower()
        password = data.get("password", "")

        conn = get_db()
        cur = conn.cursor()
        cur.execute("SELECT * FROM users WHERE email = ? OR username = ?", (account, account.replace("@", "")))
        row = cur.fetchone()
        conn.close()

        if not row or not verify_password(password, row["password_hash"]):
            log_error(f"Неудачная попытка входа: {account}")
            return web.json_response({"error": "Неверный логин или пароль"}, status=401)

        token = secrets.token_hex(32)
        sessions[token] = row["uid"]

        log_info(f"🔑 Успешный ВХОД в аккаунт: @{row['username']} ({row['display_name']}), UID: {row['uid']}")

        return web.json_response({
            "status": "success",
            "token": token,
            "user": {
                "uid": row["uid"],
                "email": row["email"],
                "username": row["username"],
                "display_name": row["display_name"],
                "avatar_url": row["avatar_url"],
                "about": row["about"]
            }
        })
    except Exception as e:
        log_error(f"Сбой при входе: {e}")
        return web.json_response({"error": str(e)}, status=500)

# E2EE Ключи
async def api_keys(request):
    target_uid = request.match_info.get("uid")
    conn = get_db()
    cur = conn.cursor()

    if request.method == "GET":
        cur.execute("SELECT key_bundle FROM user_keys WHERE uid = ?", (target_uid,))
        row = cur.fetchone()
        conn.close()
        if not row:
            # Если ключа еще нет, генерируем базовый
            return web.json_response({"bundle": {}})
        return web.json_response({"bundle": json.loads(row["key_bundle"])})

    elif request.method == "POST":
        uid = authenticate(request)
        if not uid or uid != target_uid:
            conn.close()
            return web.json_response({"error": "Unauthorized"}, status=401)
        data = await request.json()
        bundle_str = json.dumps(data)
        now = int(time.time() * 1000)
        conn.execute("INSERT OR REPLACE INTO user_keys (uid, key_bundle, updated_at) VALUES (?, ?, ?)", (uid, bundle_str, now))
        conn.commit()
        conn.close()
        log_info(f"🔐 Обновлен E2EE публичный ключ для пользователя: {uid}")
        return web.json_response({"status": "success"})

# Отправка сообщений через REST API (гарантированная доставка)
async def api_send_message(request):
    uid = authenticate(request)
    if not uid:
        return web.json_response({"error": "Unauthorized"}, status=401)

    data = await request.json()
    recipient_uid = data.get("recipient_uid")
    chat_id = data.get("chat_id") or "_".join(sorted([uid, recipient_uid]))
    content = data.get("content") or data.get("payload")
    cipher = data.get("cipher")
    signal_type = data.get("signalType", 1)
    kind = data.get("kind", "text")
    msg_id = data.get("id") or str(uuid.uuid4())
    now = int(time.time() * 1000)

    conn = get_db()
    conn.execute("""
        INSERT INTO messages (id, chat_id, sender_uid, recipient_uid, payload, cipher, signal_type, kind, created_at, is_read)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
    """, (msg_id, chat_id, uid, recipient_uid, content, cipher, signal_type, kind, now))
    conn.execute("INSERT OR IGNORE INTO chat_members (chat_id, uid, peer_uid) VALUES (?, ?, ?)", (chat_id, uid, recipient_uid))
    conn.execute("INSERT OR IGNORE INTO chat_members (chat_id, uid, peer_uid) VALUES (?, ?, ?)", (chat_id, recipient_uid, uid))
    conn.commit()
    conn.close()

    log_info(f"📨 Сообщение успешно доставлено на сервер: chat={chat_id}, sender={uid}, id={msg_id}")

    # Мгновенный пуш в сокет получателю, если он в сети
    event = {
        "type": "new_message",
        "message": {
            "id": msg_id,
            "chat_id": chat_id,
            "sender_uid": uid,
            "recipient_uid": recipient_uid,
            "payload": content,
            "cipher": cipher,
            "signalType": signal_type,
            "kind": kind,
            "created_at": now
        }
    }
    if recipient_uid in connected_clients:
        for sock in list(connected_clients[recipient_uid]):
            try:
                await sock.send_json(event)
            except Exception:
                pass

    return web.json_response({"status": "success", "messageId": msg_id, "sentAt": now})

async def api_messages(request):
    uid = authenticate(request)
    if not uid:
        return web.json_response({"error": "Unauthorized"}, status=401)

    peer_uid = request.query.get("peer_uid")
    chat_id = request.query.get("chat_id")
    if not chat_id and peer_uid:
        chat_id = "_".join(sorted([uid, peer_uid]))

    conn = get_db()
    cur = conn.cursor()
    cur.execute("""
        SELECT * FROM messages 
        WHERE chat_id = ? OR ((sender_uid = ? AND recipient_uid = ?) OR (sender_uid = ? AND recipient_uid = ?))
        ORDER BY created_at ASC
    """, (chat_id, uid, peer_uid, peer_uid, uid))
    msgs = [dict(m) for m in cur.fetchall()]
    conn.close()

    return web.json_response({"messages": msgs})

async def api_dialogs(request):
    uid = authenticate(request)
    if not uid:
        return web.json_response({"error": "Unauthorized"}, status=401)

    conn = get_db()
    cur = conn.cursor()
    cur.execute("""
        SELECT DISTINCT 
            CASE WHEN sender_uid = ? THEN recipient_uid ELSE sender_uid END AS peer_uid
        FROM messages 
        WHERE sender_uid = ? OR recipient_uid = ?
    """, (uid, uid, uid))
    peers = [row["peer_uid"] for row in cur.fetchall()]

    dialog_list = []
    for peer in peers:
        cur.execute("SELECT uid, username, display_name, avatar_url, last_seen FROM users WHERE uid = ?", (peer,))
        user_row = cur.fetchone()
        if not user_row:
            continue

        cur.execute("""
            SELECT * FROM messages 
            WHERE (sender_uid = ? AND recipient_uid = ?) OR (sender_uid = ? AND recipient_uid = ?)
            ORDER BY created_at DESC LIMIT 1
        """, (uid, peer, peer, uid))
        last_msg = cur.fetchone()

        dialog_list.append({
            "peer": dict(user_row),
            "last_message": dict(last_msg) if last_msg else None
        })

    conn.close()
    return web.json_response({"dialogs": dialog_list})

# Поиск пользователей
async def api_search(request):
    query = request.query.get("q", "").strip().replace("@", "")
    if not query:
        return web.json_response({"users": []})

    conn = get_db()
    cur = conn.cursor()
    cur.execute("""
        SELECT uid, username, display_name, avatar_url, about, last_seen FROM users 
        WHERE username LIKE ? OR display_name LIKE ? LIMIT 30
    """, (f"%{query}%", f"%{query}%"))
    users = [dict(u) for u in cur.fetchall()]
    conn.close()

    log_info(f"🔍 Поиск пользователей по запросу '{query}': найдено {len(users)}")
    return web.json_response({"users": users})

# Загрузка файлов
async def api_upload(request):
    uid = authenticate(request)
    if not uid:
        return web.json_response({"error": "Unauthorized"}, status=401)

    reader = await request.multipart()
    field = await reader.next()
    if not field:
        return web.json_response({"error": "Файл не передан"}, status=400)

    filename = field.filename or "file"
    file_id = str(uuid.uuid4())
    ext = os.path.splitext(filename)[1]
    storage_filename = f"{file_id}{ext}"
    storage_path = os.path.join(UPLOAD_DIR, storage_filename)

    size = 0
    with open(storage_path, 'wb') as f:
        while True:
            chunk = await field.read_chunk()
            if not chunk:
                break
            size += len(chunk)
            f.write(chunk)

    content_type = field.headers.get("Content-Type", "application/octet-stream")
    now = int(time.time() * 1000)

    conn = get_db()
    conn.execute("""
        INSERT INTO files (id, owner_uid, filename, content_type, size, storage_path, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
    """, (file_id, uid, filename, content_type, size, storage_path, now))
    conn.commit()
    conn.close()

    log_info(f"📁 Загружен файл: {filename} ({size} байт), ID: {file_id}")

    url = f"/api/files/{file_id}"
    return web.json_response({
        "status": "success",
        "file_id": file_id,
        "url": url,
        "filename": filename,
        "size": size,
        "content_type": content_type
    })

async def api_get_file(request):
    file_id = request.match_info.get("file_id")
    conn = get_db()
    cur = conn.cursor()
    cur.execute("SELECT * FROM files WHERE id = ?", (file_id,))
    row = cur.fetchone()
    conn.close()

    if not row or not os.path.exists(row["storage_path"]):
        return web.json_response({"error": "Файл не найден"}, status=404)

    return web.FileResponse(row["storage_path"])

# Создание приложения
def create_app():
    app = web.Application()
    
    @web.middleware
    async def cors_middleware(req, handler):
        if req.method == "OPTIONS":
            resp = web.Response(status=200)
        else:
            resp = await handler(req)
        resp.headers["Access-Control-Allow-Origin"] = "*"
        resp.headers["Access-Control-Allow-Methods"] = "GET, POST, PUT, DELETE, OPTIONS"
        resp.headers["Access-Control-Allow-Headers"] = "Authorization, Content-Type"
        return resp

    app.middlewares.append(cors_middleware)

    # Маршруты
    app.router.add_get("/", ping)
    app.router.add_get("/api/ping", ping)
    app.router.add_get("/ws", ws_handler)
    app.router.add_post("/api/auth/register", api_register)
    app.router.add_post("/api/auth/login", api_login)
    app.router.add_get("/api/keys/{uid}", api_keys)
    app.router.add_post("/api/keys/{uid}", api_keys)
    app.router.add_post("/api/messages/send", api_send_message)
    app.router.add_get("/api/messages", api_messages)
    app.router.add_get("/api/dialogs", api_dialogs)
    app.router.add_get("/api/search", api_search)
    app.router.add_post("/api/upload", api_upload)
    app.router.add_get("/api/files/{file_id}", api_get_file)

    return app

if __name__ == "__main__":
    log_info(f"🚀 Высокопроизводительный сервер Tsuyu 2026 запущен и слушает 0.0.0.0:{PORT}")
    web_app = create_app()
    web.run_app(web_app, host="0.0.0.0", port=PORT, access_log=None, handle_signals=True)
