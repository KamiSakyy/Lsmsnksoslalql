#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Шлюз для PaaS-хостингов (Railway / Render / Koyeb / Fly).
- Отвечает 200 OK на /, /health, /status (health-check хостинга).
- Всё остальное — сырой TCP-пайп на локальный движок.
- Ловит домен сам: читает Host из входящих запросов.
Только стандартная библиотека Python, зависимостей нет.
"""
import os
import re
import socket
import select
import threading

LISTEN_PORT = int(os.environ.get("PORT", "8080"))
UPSTREAM_HOST = "127.0.0.1"
UPSTREAM_PORT = int(os.environ.get("INTERNAL_PORT", "8081"))
RELAY_PATH = os.environ.get("RELAY_PATH", "/api/uploadFile/")
DETECT_FILE = os.environ.get("DETECT_FILE", "/tmp/detected_domain")

HEALTH_PATHS = {"/", "/health", "/status", "/ping"}

HTML_TOP = """<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Сервис запущен</title></head>
<body style="font-family:sans-serif;text-align:center;margin-top:12vh">
<h1>Сервис запущен</h1>
<p>Техническая страница для проверки хостинга.</p>
"""

HTML_BOTTOM = """<p>Ссылку для клиентского приложения забери из логов деплоя.</p>
</body></html>"""


def log(*a):
    print(*a, flush=True)


def save_detected_domain(host):
    """Первый настоящий домен побеждает — дальше не перезаписываем."""
    try:
        if os.path.exists(DETECT_FILE):
            with open(DETECT_FILE, "r") as f:
                if f.read().strip():
                    return
        clean = host.split(":")[0].strip().lower()
        with open(DETECT_FILE, "w") as f:
            f.write(clean + "\n")
        log("ОБНАРУЖЕН ДОМЕН САМ: %s" % clean)
    except Exception as e:
        log("detect save error:", e)


def get_detected_domain():
    try:
        with open(DETECT_FILE, "r") as f:
            return f.read().strip()
    except Exception:
        return ""


def get_host_header(data: bytes):
    try:
        head = data.split(b"\r\n\r\n", 1)[0].decode("latin-1")
        for line in head.split("\r\n")[1:]:
            if ":" in line:
                k, v = line.split(":", 1)
                if k.strip().lower() == "host":
                    return v.strip()
    except Exception:
        pass
    return ""


def is_public_host(host):
    """Настоящий публичный домен? Отсекаем IP, localhost, внутренние зоны."""
    if not host:
        return False
    h = host.split(":")[0].strip().lower().rstrip(".")
    if not h or " " in h or len(h) < 4 or "." not in h:
        return False
    if h == "localhost" or h.endswith(".local") or h.endswith(".internal") or h.endswith(".invalid"):
        return False
    try:
        import ipaddress
        ipaddress.ip_address(h)
        return False
    except Exception:
        pass
    if h.startswith("10.") or h.startswith("192.168.") or h.startswith("127."):
        return False
    if re.match(r"^172\.(1[6-9]|2\d|3[01])\.", h):
        return False
    return True


def send_ok(conn):
    d = get_detected_domain()
    if d:
        extra = "<p>Домен определён сам: <b>%s</b><br>Забери ссылку из логов деплоя.</p>" % d
    else:
        extra = "<p>Домен определяю... Обнови страницу через 10 секунд,<br>а ссылку забери из логов деплоя.</p>"
    body = (HTML_TOP + extra + HTML_BOTTOM).encode("utf-8")
    head = (
        "HTTP/1.1 200 OK\r\n"
        "Content-Type: text/html; charset=utf-8\r\n"
        "Content-Length: %d\r\n"
        "Connection: close\r\n\r\n" % len(body)
    ).encode()
    conn.sendall(head + body)


def send_text(conn, code, text):
    body = text.encode("utf-8")
    head = (
        "HTTP/1.1 %s\r\n"
        "Content-Type: text/plain; charset=utf-8\r\n"
        "Content-Length: %d\r\n"
        "Connection: close\r\n\r\n" % (code, len(body))
    ).encode()
    conn.sendall(head + body)


def pipe(a, b):
    """Двусторонний TCP-пайп до закрытия одной из сторон."""
    a.setblocking(False)
    b.setblocking(False)
    conns = [a, b]
    try:
        while True:
            r, _, _ = select.select(conns, [], [], 300)
            if not r:
                break  # 5 минут тишины — закрываем
            for s in r:
                try:
                    data = s.recv(65536)
                except BlockingIOError:
                    continue
                except OSError:
                    return
                if not data:
                    return
                other = b if s is a else a
                try:
                    other.sendall(data)
                except OSError:
                    return
    finally:
        for s in (a, b):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                s.close()
            except OSError:
                pass


def first_line_info(data: bytes):
    try:
        line = data.split(b"\r\n", 1)[0].decode("latin-1")
        parts = line.split(" ")
        if len(parts) >= 2:
            return parts[0].upper(), parts[1].split("?")[0]
    except Exception:
        pass
    return "", ""


def handle(conn):
    try:
        conn.settimeout(5)
        try:
            first = conn.recv(65536)
        except socket.timeout:
            conn.close()
            return
        if not first:
            conn.close()
            return
        # Узнаём домен сами из Host — на ЛЮБОМ запросе
        host = get_host_header(first)
        if is_public_host(host):
            save_detected_domain(host)
        method, path = first_line_info(first)
        if method in ("GET", "HEAD") and path in HEALTH_PATHS:
            send_ok(conn)
            conn.close()
            return
        if method in ("GET", "HEAD") and path == "/favicon.ico":
            send_text(conn, "204 No Content", "")
            conn.close()
            return
        # Всё остальное — к движку сырым TCP
        try:
            up = socket.create_connection((UPSTREAM_HOST, UPSTREAM_PORT), timeout=5)
        except OSError:
            send_text(conn, "503 Service Unavailable",
                      "Движок ещё стартует, повтори через пару секунд.")
            conn.close()
            return
        try:
            up.sendall(first)
        except OSError:
            conn.close()
            up.close()
            return
        conn.settimeout(None)
        pipe(conn, up)  # закрывает оба сокета сам
    except Exception as e:
        try:
            log("gateway error:", e)
        except Exception:
            pass
        try:
            conn.close()
        except Exception:
            pass


def main():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", LISTEN_PORT))
    srv.listen(128)
    log("Шлюз слушает 0.0.0.0:%d -> %s:%d" % (LISTEN_PORT, UPSTREAM_HOST, UPSTREAM_PORT))
    log("Путь: %s" % RELAY_PATH)
    log("Ловля домена ВКЛ: читаю Host из входящих запросов")
    while True:
        conn, _addr = srv.accept()
        t = threading.Thread(target=handle, args=(conn,), daemon=True)
        t.start()


if __name__ == "__main__":
    main()
