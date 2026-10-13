"""Создаёт демо-датасет синтезом espeak (для проверки пайплайна и CI).

Это НЕ обучение на живом голосе: нужно лишь убедиться, что весь конвейер
(данные → обучение → экспорт → озвучка) работает. Для своего голоса
клади mp3 в data/voice_samples и запускай training/preprocess.py.
"""
from __future__ import annotations

import argparse
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

PHRASES = [
    "Привет! Меня зовут Мила.",
    "Сегодня прекрасная погода для прогулки.",
    "Я люблю слушать музыку и читать книги.",
    "Давай познакомимся поближе.",
    "Как у тебя дела сегодня?",
    "Это демо-голос для проверки пайплайна.",
    "Москва — столица России.",
    "Время уже позднее, пора отдыхать.",
    "Я расскажу тебе интересную историю.",
    "Улыбайся чаще, и мир улыбнётся тебе.",
    "Спасибо, что воспользуешься нашим приложением.",
    "Завтра будет новый день и новые возможности.",
    "Мой любимый цвет — розовый.",
    "Кофе по утру поднимает настроение.",
    "Прочитай мне, пожалуйста, сказку на ночь.",
    "Я обожаю путешествовать и открывать новое.",
    "Погода за окном дождливая, но уютная.",
    "Скоро мы увидимся снова.",
    "Эта фраза нужна для разнообразия данных.",
    "Каждый голос уникален и неповторим.",
    "Ты слышишь мой голос? Он звучит мягко.",
    "Давай попробуем сказать это иначе.",
    "В лесу родилась ёлочка, в лесу она росла.",
    "Раз, два, три, четыре, пять — начинаем считать.",
    "Хорошего тебе дня и отличного настроения!",
]


def main():
    ap = argparse.ArgumentParser(description="Демо-датасет через espeak")
    ap.add_argument("--out", default="data/demo")
    ap.add_argument("--variant", default="f2", help="вариант голоса espeak (f1..f4)")
    ap.add_argument("--count", type=int, default=len(PHRASES))
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    from training.espeak_synth import synth_to_wav

    meta = []
    for i, phrase in enumerate(PHRASES[: args.count]):
        name = f"demo_{i:03d}.wav"
        synth_to_wav(phrase, os.path.join(args.out, name), voice="ru",
                     rate=155, pitch=58, variant=args.variant)
        meta.append(f"{name}|{phrase}")
    with open(os.path.join(args.out, "metadata.csv"), "w", encoding="utf-8") as f:
        f.write("\n".join(meta) + "\n")
    print(f"демо-датасет: {len(meta)} фраз → {args.out}/")


if __name__ == "__main__":
    main()
