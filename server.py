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

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] TsuyuProtoCore: %(message)s',
    stream=sys.stdout
)
logger = logging.getLogger("TsuyuProtoCore")

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
    
    # Users
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

    # Keys / E2EE Bundles
    cur.execute("""
        CREATE TABLE IF NOT EXISTS user_keys (
            uid TEXT PRIMARY KEY,
            key_bundle TEXT,
            updated_at INTEGER
        )
    """)

    # Messages
    cur.execute("""
        CREATE TABLE IF NOT EXISTS messages (
            id TEXT PRIMARY KEY,
            chat_id TEXT,
            sender_uid TEXT,
            recipient_uid TEXT,
            payload TEXT,
            kind TEXT,
            reply_to TEXT,
            forward_from TEXT,
            created_at INTEGER,
            is_read INTEGER DEFAULT 0
        )
    """)

    # Chats / Folders / Pins
    cur.execute("""
        CREATE TABLE IF NOT EXISTS chat_members (
            chat_id TEXT,
            uid TEXT,
            is_pinned INTEGER DEFAULT 0,
            is_archived INTEGER DEFAULT 0,
            unread_count INTEGER DEFAULT 0,
            PRIMARY KEY (chat_id, uid)
        )
    """)

    # Files / Media
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

init_db()

def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn

# ----------------- Auth Helper -----------------
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

# Session tokens in memory + cache
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

# ----------------- WebSocket Live Connection (TsuyuProto / MTProto-like) -----------------
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

                # Protocol Handshake / Auth
                if action == "auth":
                    token = payload.get("token")
                    if token in sessions:
                        uid = sessions[token]
                        if uid not in connected_clients:
                            connected_clients[uid] = set()
                        connected_clients[uid].add(ws)

                        # Update last seen
                        conn = get_db()
                        conn.execute("UPDATE users SET last_seen = ? WHERE uid = ?", (int(time.time() * 1000), uid))
                        conn.commit()
                        conn.close()

                        await ws.send_json({
                            "type": "auth_ok",
                            "req_id": req_id,
                            "uid": uid
                        })
                    else:
                        await ws.send_json({"type": "error", "message": "Invalid auth token", "req_id": req_id})

                elif action == "ping":
                    await ws.send_json({"type": "pong", "time": int(time.time() * 1000)})

                elif action == "send_msg" and uid:
                    recipient_uid = payload.get("recipient_uid")
                    content = payload.get("content")
                    kind = payload.get("kind", "text")
                    chat_id = payload.get("chat_id") or "_".join(sorted([uid, recipient_uid]))
                    msg_id = payload.get("id") or str(uuid.uuid4())
                    now = int(time.time() * 1000)

                    conn = get_db()
                    conn.execute("""
                        INSERT INTO messages (id, chat_id, sender_uid, recipient_uid, payload, kind, created_at, is_read)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                    """, (msg_id, chat_id, uid, recipient_uid, content, kind, now))
                    conn.commit()
                    conn.close()

                    event = {
                        "type": "new_message",
                        "message": {
                            "id": msg_id,
                            "chat_id": chat_id,
                            "sender_uid": uid,
                            "recipient_uid": recipient_uid,
                            "payload": content,
                            "kind": kind,
                            "created_at": now
                        }
                    }

                    # Echo to sender
                    await ws.send_json({"type": "msg_sent", "req_id": req_id, "id": msg_id, "created_at": now})

                    # Dispatch to recipient if online
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

    return ws

# ----------------- REST API Endpoints -----------------
async def ping(request):
    return web.json_response({
        "status": "ok",
        "service": "Tsuyu Core",
        "version": "2026.1",
        "time": int(time.time())
    })

async def health(request):
    return web.Response(text="OK", status=200)

async def api_register(request):
    try:
        data = await request.json()
        email = data.get("email", "").strip().lower()
        password = data.get("password", "")
        username = data.get("username", "").strip() or email.split("@")[0]
        display_name = data.get("display_name", "").strip() or username

        if not email or not password:
            return web.json_response({"error": "Email and password are required"}, status=400)

        conn = get_db()
        cur = conn.cursor()
        cur.execute("SELECT uid FROM users WHERE email = ? OR username = ?", (email, username))
        if cur.fetchone():
            conn.close()
            return web.json_response({"error": "User with this email or username already exists"}, status=400)

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
        logger.exception("Register error")
        return web.json_response({"error": str(e)}, status=500)

async def api_login(request):
    try:
        data = await request.json()
        email = data.get("email", "").strip().lower()
        password = data.get("password", "")

        conn = get_db()
        cur = conn.cursor()
        cur.execute("SELECT * FROM users WHERE email = ? OR username = ?", (email, email))
        row = cur.fetchone()
        conn.close()

        if not row or not verify_password(password, row["password_hash"]):
            return web.json_response({"error": "Invalid email or password"}, status=401)

        token = secrets.token_hex(32)
        sessions[token] = row["uid"]

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
        logger.exception("Login error")
        return web.json_response({"error": str(e)}, status=500)

async def api_profile(request):
    uid = authenticate(request)
    if not uid:
        return web.json_response({"error": "Unauthorized"}, status=401)

    conn = get_db()
    if request.method == "GET":
        target_uid = request.query.get("uid", uid)
        cur = conn.cursor()
        cur.execute("SELECT uid, email, username, display_name, avatar_url, about, last_seen FROM users WHERE uid = ?", (target_uid,))
        user = cur.fetchone()
        conn.close()
        if not user:
            return web.json_response({"error": "User not found"}, status=404)
        return web.json_response({"user": dict(user)})

    elif request.method == "POST":
        data = await request.json()
        display_name = data.get("display_name")
        about = data.get("about")
        avatar_url = data.get("avatar_url")
        username = data.get("username")

        cur = conn.cursor()
        if username:
            cur.execute("UPDATE users SET username = ? WHERE uid = ?", (username, uid))
        if display_name:
            cur.execute("UPDATE users SET display_name = ? WHERE uid = ?", (display_name, uid))
        if about is not None:
            cur.execute("UPDATE users SET about = ? WHERE uid = ?", (about, uid))
        if avatar_url is not None:
            cur.execute("UPDATE users SET avatar_url = ? WHERE uid = ?", (avatar_url, uid))
        conn.commit()
        conn.close()
        return web.json_response({"status": "success"})

# Media / File Upload
async def api_upload(request):
    uid = authenticate(request)
    if not uid:
        return web.json_response({"error": "Unauthorized"}, status=401)

    reader = await request.multipart()
    field = await reader.next()
    if not field:
        return web.json_response({"error": "No file uploaded"}, status=400)

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
        return web.json_response({"error": "File not found"}, status=404)

    return web.FileResponse(row["storage_path"])

# Dialogs / Search
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

async def api_messages(request):
    uid = authenticate(request)
    if not uid:
        return web.json_response({"error": "Unauthorized"}, status=401)

    peer_uid = request.query.get("peer_uid")
    if not peer_uid:
        return web.json_response({"error": "peer_uid is required"}, status=400)

    conn = get_db()
    cur = conn.cursor()
    cur.execute("""
        SELECT * FROM messages 
        WHERE (sender_uid = ? AND recipient_uid = ?) OR (sender_uid = ? AND recipient_uid = ?)
        ORDER BY created_at ASC
    """, (uid, peer_uid, peer_uid, uid))
    msgs = [dict(m) for m in cur.fetchall()]
    conn.close()

    return web.json_response({"messages": msgs})

async def api_search(request):
    query = request.query.get("q", "").strip()
    if not query:
        return web.json_response({"users": []})

    conn = get_db()
    cur = conn.cursor()
    cur.execute("""
        SELECT uid, username, display_name, avatar_url FROM users 
        WHERE username LIKE ? OR display_name LIKE ? LIMIT 30
    """, (f"%{query}%", f"%{query}%"))
    users = [dict(u) for u in cur.fetchall()]
    conn.close()

    return web.json_response({"users": users})

# ----------------- App Creation -----------------
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

    # Routes
    app.router.add_get("/", ping)
    app.router.add_get("/health", health)
    app.router.add_get("/api/ping", ping)
    app.router.add_get("/ws", ws_handler)
    app.router.add_post("/api/auth/register", api_register)
    app.router.add_post("/api/auth/login", api_login)
    app.router.add_get("/api/profile", api_profile)
    app.router.add_post("/api/profile", api_profile)
    app.router.add_post("/api/upload", api_upload)
    app.router.add_get("/api/files/{file_id}", api_get_file)
    app.router.add_get("/api/dialogs", api_dialogs)
    app.router.add_get("/api/messages", api_messages)
    app.router.add_get("/api/search", api_search)

    return app

if __name__ == "__main__":
    print(f"Tsuyu Modern 2026 Core listening on 0.0.0.0:{PORT}", flush=True)
    web_app = create_app()
    # Чистый биндинг на 0.0.0.0 — официальный стандарт Railway для контейнеров
    web.run_app(web_app, host="0.0.0.0", port=PORT, access_log=None, handle_signals=True)
