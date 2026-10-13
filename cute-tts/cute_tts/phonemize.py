"""Текст → фонемы → id. Общий фронтенд для обучения и инференса.

Бэкенды:
1. espeak-ng через piper-phonemize (лучшее качество, фонемы как у Piper);
2. espeak-ng через phonemizer + espeakng-loader;
3. посимвольный (запасной, без внешних библиотек).
"""
from __future__ import annotations

import re
from typing import Dict, List, Sequence

from .normalize import normalize_text

BOS = "^"
EOS = "$"
PAD = "_"

_SPOKEN_PUNCT = {
    ",": "пауза", ".": "пауза", "!": "пауза", "?": "пауза",
    ":": "пауза", ";": "пауза",
}


def _backend_piper():
    import piper_phonemize

    return piper_phonemize


def espeak_id_map() -> Dict[str, int]:
    """Фиксированная карта фонем espeak (как у Piper)."""
    pp = _backend_piper()
    raw = pp.get_espeak_map()
    mapping: Dict[str, int] = {PAD: 0, BOS: 1, EOS: 2}
    for symbol, ids in raw.items():
        if symbol not in mapping:
            mapping[symbol] = int(ids[0])
    return mapping


def char_id_map() -> Dict[str, int]:
    alphabet = list(" абвгдеёжзийклмнопрстуфхцчшщъыьэюя0123456789.,!?;:-'\"()") + [
        "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m",
        "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
    ]
    mapping: Dict[str, int] = {PAD: 0, BOS: 1, EOS: 2}
    for ch in alphabet:
        if ch not in mapping:
            mapping[ch] = len(mapping)
    return mapping


def split_sentences(text: str) -> List[str]:
    """Разбивает на фразы для пофразового синтеза."""
    parts = re.split(r"(?<=[.!?…])\s+|(?<=[;:])\s+", text)
    out = []
    for part in parts:
        part = part.strip()
        if not part:
            continue
        while len(part) > 320:
            cut = part.rfind(" ", 0, 320)
            if cut <= 0:
                cut = 320
            out.append(part[:cut].strip())
            part = part[cut:].strip()
        if part:
            out.append(part)
    return out or ([text] if text else [])


def phonemize_espeak(text: str, language: str = "ru") -> List[List[str]]:
    """Текст → список фонемных цепочек (по предложениям)."""
    text = normalize_text(text)
    sentences = split_sentences(text)
    if not sentences:
        return []
    pp = _backend_piper()
    result = []
    for sent in sentences:
        phones = pp.phonemize_espeak(sent, language)
        # piper_phonemize возвращает список списков (разбиение внутри предложения)
        for chunk in phones:
            if chunk:
                result.append(list(chunk))
    return result


def phonemize_chars(text: str) -> List[List[str]]:
    text = normalize_text(text)
    sentences = split_sentences(text)
    return [list(s.lower()) for s in sentences]


def phonemes_to_ids(phonemes: Sequence[str], phoneme_id_map: Dict[str, int]) -> List[int]:
    unk = phoneme_id_map.get(" ", 3)
    ids = [phoneme_id_map.get(BOS, 1)]
    for ph in phonemes:
        ids.append(phoneme_id_map.get(ph, unk))
    ids.append(phoneme_id_map.get(EOS, 2))
    return ids


def text_to_ids(
    text: str,
    phoneme_id_map: Dict[str, int],
    phoneme_type: str = "espeak",
    language: str = "ru",
) -> List[List[int]]:
    """Текст → список id-цепочек (по предложениям)."""
    if phoneme_type == "espeak":
        try:
            chains = phonemize_espeak(text, language)
        except Exception:
            chains = phonemize_chars(text)
            phoneme_type = "text"
    else:
        chains = phonemize_chars(text)

    if phoneme_type == "text":
        # посимвольная карта может отличаться — добираем unk
        chains = [[c if c in phoneme_id_map else " " for c in ch] for ch in chains]
    return [phonemes_to_ids(ch, phoneme_id_map) for ch in chains]


def build_phoneme_id_map(phoneme_type: str = "espeak") -> Dict[str, int]:
    if phoneme_type == "espeak":
        try:
            return espeak_id_map()
        except Exception:
            return char_id_map()
    return char_id_map()
