#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Прокси для PaaS-хостингов (Railway / Render / Koyeb / Fly).
- Отвечает 200 OK на /, /health, /status (health-check хостинга).
- Все остальное — сырой TCP-пайп на локальный Xray.
  XHTTP работает поверх обычного HTTP, поэтому стрим не ломается,
  а TLS терминирует сам хостинг.
Только стандартная библиотека Python, зависимостей нет.
"""
import os
import socket
import select
import threading

LISTEN_PORT = int(os.environ.get("PORT", "8080"))
UPSTREAM_HOST = "127.0.0.1"
UPSTREAM_PORT = int(os.environ.get("INTERNAL_PORT", "8081"))
XHTTP_PATH = os.environ.get("XHTTP_PATH", "/api/uploadFile/")

HEALTH_PATHS = {"/", "/health", "/status", "/ping"}

HTML_OK = """<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>VPN работает</title></head>
<body style="font-family:sans-serif;text-align:center;margin-top:15vh">
<h1>VPN сервер запущен</h1>
<p>Это техническая страница для проверки хостинга.</p>
<p>Подключайся через <b>v2rayNG</b> по VLESS-ссылке из логов деплоя.</p>
</body></html>""".encode("utf-8")


def log(*a):
    print(*a, flush=True)


def send_ok(conn):
    body = HTML_OK
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
        method, path = first_line_info(first)
        if method in ("GET", "HEAD") and path in HEALTH_PATHS:
            send_ok(conn)
            conn.close()
            return
        if method in ("GET", "HEAD") and path == "/favicon.ico":
            send_text(conn, "204 No Content", "")
            conn.close()
            return
        # Все остальное — к Xray сырым TCP
        try:
            up = socket.create_connection((UPSTREAM_HOST, UPSTREAM_PORT), timeout=5)
        except OSError:
            send_text(conn, "503 Service Unavailable",
                      "Xray ещё стартует, повтори через пару секунд.")
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
            log("proxy error:", e)
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
    log("Прокси слушает 0.0.0.0:%d -> %s:%d" % (LISTEN_PORT, UPSTREAM_HOST, UPSTREAM_PORT))
    log("XHTTP путь: %s" % XHTTP_PATH)
    while True:
        conn, _addr = srv.accept()
        t = threading.Thread(target=handle, args=(conn,), daemon=True)
        t.start()


if __name__ == "__main__":
    main()
