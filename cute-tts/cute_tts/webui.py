"""Локальный веб-интерфейс MilaTTS (127.0.0.1, без интернета)."""
from __future__ import annotations

import base64
import io
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PAGE = """<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Мила — озвучка текста</title>
<style>
  :root { --pink: #ff6fa5; --bg: #fff5f9; --card: #ffffff; --ink: #4a2b3a; }
  * { box-sizing: border-box; }
  body {
    margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
    background: radial-gradient(1200px 600px at 20% -10%, #ffe3ee, var(--bg));
    font-family: "Segoe UI", system-ui, sans-serif; color: var(--ink);
  }
  .card {
    background: var(--card); border-radius: 28px; padding: 36px 40px; width: min(680px, 92vw);
    box-shadow: 0 24px 60px rgba(255, 111, 165, .18);
  }
  h1 { margin: 0 0 4px; font-size: 32px; }
  h1 .cat { font-size: 34px; }
  p.sub { margin: 0 0 22px; opacity: .75; }
  textarea {
    width: 100%; min-height: 130px; border: 2px solid #ffd3e4; border-radius: 18px;
    padding: 16px; font-size: 17px; resize: vertical; outline: none; color: var(--ink);
  }
  textarea:focus { border-color: var(--pink); }
  .row { display: flex; gap: 14px; margin: 18px 0; flex-wrap: wrap; }
  label { display: flex; flex-direction: column; gap: 6px; font-size: 14px; opacity: .85; }
  input[type=range] { width: 180px; accent-color: var(--pink); }
  button {
    border: 0; border-radius: 999px; padding: 15px 30px; font-size: 17px; font-weight: 600;
    background: linear-gradient(135deg, var(--pink), #ff9770); color: white; cursor: pointer;
    box-shadow: 0 12px 26px rgba(255, 111, 165, .35); transition: transform .15s;
  }
  button:hover { transform: translateY(-2px); }
  button:disabled { opacity: .55; cursor: wait; transform: none; }
  audio { width: 100%; margin-top: 20px; }
  .status { margin-top: 12px; font-size: 14px; opacity: .7; min-height: 20px; }
  .heart { color: var(--pink); }
</style>
</head>
<body>
<div class="card">
  <h1><span class="cat">🎀</span> Мила</h1>
  <p class="sub">оффлайн-озвучка текста · работает без интернета</p>
  <textarea id="text" placeholder="Напиши текст, который Мила озвучит…">Привет! Я Мила, твой оффлайн голос. Рада познакомиться!</textarea>
  <div class="row">
    <label>Скорость <input type="range" id="speed" min="0.5" max="1.8" step="0.05" value="1"><span id="speedv">1.00</span></label>
    <label>Выразительность <input type="range" id="expr" min="0" max="1" step="0.05" value="0.5"><span id="exprv">0.50</span></label>
  </div>
  <button id="go">Озвучить <span class="heart">♥</span></button>
  <div class="status" id="status"></div>
  <audio id="player" controls></audio>
</div>
<script>
const $ = (id) => document.getElementById(id);
$("speed").oninput = () => $("speedv").textContent = (+$("speed").value).toFixed(2);
$("expr").oninput = () => $("exprv").textContent = (+$("expr").value).toFixed(2);
$("go").onclick = async () => {
  const text = $("text").value.trim();
  if (!text) { $("status").textContent = "Напиши текст 🙂"; return; }
  $("go").disabled = true;
  $("status").textContent = "Синтезирую…";
  try {
    const r = await fetch("/api/synthesize", {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({
        text,
        speed: +$("speed").value,
        expressiveness: +$("expr").value,
      }),
    });
    if (!r.ok) throw new Error(await r.text());
    const data = await r.json();
    $("player").src = "data:audio/wav;base64," + data.audio;
    $("status").textContent = "Готово! ♪";
    $("player").play().catch(() => {});
  } catch (e) {
    $("status").textContent = "Ошибка: " + e.message;
  } finally {
    $("go").disabled = false;
  }
};
</script>
</body>
</html>"""


def serve(model_path=None, host: str = "127.0.0.1", port: int = 8765):
    from .engine import MilaTTS

    tts = MilaTTS(model_path=model_path)
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def _send(self, code: int, body: bytes, ctype: str = "application/json"):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path in ("/", "/index.html"):
                self._send(200, PAGE.encode("utf-8"), "text/html; charset=utf-8")
            else:
                self._send(404, b"{}", "application/json")

        def do_POST(self):
            if self.path != "/api/synthesize":
                self._send(404, b"{}", "application/json")
                return
            try:
                n = int(self.headers.get("Content-Length", "0"))
                req = json.loads(self.rfile.read(n) or b"{}")
                text = str(req.get("text", ""))
                speed = float(req.get("speed", 1.0))
                expr = float(req.get("expressiveness", 0.5))
                length_scale = 1.0 / max(0.25, min(3.0, speed))
                noise_scale = 0.3 + 0.65 * max(0.0, min(1.0, expr))
                with lock:
                    audio = tts.synthesize(
                        text, length_scale=length_scale, noise_scale=noise_scale
                    )
                import soundfile as sf

                buf = io.BytesIO()
                sf.write(buf, audio, tts.sample_rate, format="WAV")
                b64 = base64.b64encode(buf.getvalue()).decode("ascii")
                self._send(200, json.dumps({"audio": b64}).encode("utf-8"))
            except Exception as e:  # noqa: BLE001
                self._send(500, json.dumps({"error": str(e)}).encode("utf-8"))

    httpd = ThreadingHTTPServer((host, port), Handler)
    print(f"🎀 Мила слушает: http://{host}:{port}  (Ctrl+C — выйти)")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nпока! ♥")
    finally:
        httpd.server_close()
