"""CLI MilaTTS: python -m cute_tts "Привет!" -o hello.wav"""
from __future__ import annotations

import argparse
import sys


def main(argv=None):
    ap = argparse.ArgumentParser(
        prog="mila-tts",
        description="Мила — оффлайн-конвертер текста в голос (TTS). Интернет не нужен.",
    )
    ap.add_argument("text", nargs="?", help="текст для озвучки")
    ap.add_argument("-o", "--out", default="speech.wav", help="выходной wav-файл")
    ap.add_argument("-m", "--model", default=None, help="путь к voice.onnx")
    ap.add_argument("--speed", type=float, default=1.0,
                    help="скорость речи (0.5 — медленно, 2.0 — быстро)")
    ap.add_argument("--expressiveness", type=float, default=None,
                    help="выразительность/вариативность 0..1 (по умолчанию из модели)")
    ap.add_argument("--read-stdin", action="store_true", help="читать текст из stdin")
    ap.add_argument("--web", action="store_true", help="запустить локальный веб-интерфейс")
    ap.add_argument("--port", type=int, default=8765, help="порт веб-интерфейса")
    ap.add_argument("--host", default="127.0.0.1", help="адрес веб-интерфейса")
    args = ap.parse_args(argv)

    if args.web:
        from .webui import serve

        serve(model_path=args.model, host=args.host, port=args.port)
        return 0

    text = args.text
    if args.read_stdin or not text:
        text = sys.stdin.read()
    if not text.strip():
        ap.error("не передан текст")

    from .engine import MilaTTS

    tts = MilaTTS(model_path=args.model)
    noise_scale = None
    if args.expressiveness is not None:
        noise_scale = 0.3 + 0.65 * max(0.0, min(1.0, args.expressiveness))
    length_scale = 1.0 / max(0.25, min(3.0, args.speed))
    path = tts.synthesize_to_file(
        text, args.out, length_scale=length_scale, noise_scale=noise_scale
    )
    print(f"готово: {path} (модель: {tts.model_path})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
