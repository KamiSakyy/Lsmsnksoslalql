"""Русская нормализация текста перед озвучкой (числа, даты, символы и т.д.).

Работает полностью оффлайн, без внешних сервисов.
"""
from __future__ import annotations

import re
import unicodedata

_UNITS = [
    "", "один", "два", "три", "четыре", "пять", "шесть",
    "семь", "восемь", "девять", "десять", "одиннадцать", "двенадцать",
    "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать",
    "семнадцать", "восемнадцать", "девятнадцать",
]
_UNITS_F = [
    "", "одна", "две", "три", "четыре", "пять", "шесть",
    "семь", "восемь", "девять", "десять", "одиннадцать", "двенадцать",
    "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать",
    "семнадцать", "восемнадцать", "девятнадцать",
]
_TENS = [
    "", "", "двадцать", "тридцать", "сорок", "пятьдесят",
    "шестьдесят", "семьдесят", "восемьдесят", "девяносто",
]
_HUNDREDS = [
    "", "сто", "двести", "триста", "четыреста", "пятьсот",
    "шестьсот", "семьсот", "восемьсот", "девятьсот",
]
_SCALES = [
    (1, "", "", "", 0),
    (1000, "тысяча", "тысячи", "тысяч", 1),
    (10**6, "миллион", "миллиона", "миллионов", 0),
    (10**9, "миллиард", "миллиарда", "миллиардов", 0),
]

_ORDINAL_M = {
    1: "первый", 2: "второй", 3: "третий", 4: "четвёртый", 5: "пятый",
    6: "шестой", 7: "седьмой", 8: "восьмой", 9: "девятый", 10: "десятый",
    11: "одиннадцатый", 12: "двенадцатый", 13: "тринадцатый",
    14: "четырнадцатый", 15: "пятнадцатый", 16: "шестнадцатый",
    17: "семнадцатый", 18: "восемнадцатый", 19: "девятнадцатый",
    20: "двадцатый", 30: "тридцатый", 40: "сороковой", 50: "пятидесятый",
    60: "шестидесятый", 70: "семидесятый", 80: "восьмидесятый",
    90: "девяностый", 100: "сотый", 200: "двухсотый", 300: "трёхсотый",
    400: "четырёхсотый", 500: "пятисотый", 600: "шестисотый",
    700: "семисотый", 800: "восьмисотый", 900: "девятисотый",
    1000: "тысячный",
}

_ORDINAL_DATE = {
    1: "первое", 2: "второе", 3: "третье", 4: "четвёртое", 5: "пятое",
    6: "шестое", 7: "седьмое", 8: "восьмое", 9: "девятое", 10: "десятое",
    11: "одиннадцатое", 12: "двенадцатое", 13: "тринадцатое",
    14: "четырнадцатое", 15: "пятнадцатое", 16: "шестнадцатое",
    17: "семнадцатое", 18: "восемнадцатое", 19: "девятнадцатое",
    20: "двадцатое", 21: "двадцать первое", 22: "двадцать второе",
    23: "двадцать третье", 24: "двадцать четвёртое", 25: "двадцать пятое",
    26: "двадцать шестое", 27: "двадцать седьмое", 28: "двадцать восьмое",
    29: "двадцать девятое", 30: "тридцатое", 31: "тридцать первое",
}

_MONTHS = {
    1: "января", 2: "февраля", 3: "марта", 4: "апреля", 5: "мая",
    6: "июня", 7: "июля", 8: "августа", 9: "сентября", 10: "октября",
    11: "ноября", 12: "декабря",
}

_SYMBOLS = {
    "%": "процентов", "№": "номер", "@": "собака", "&": "и",
    "+": "плюс", "=": "равно", "<": "меньше", ">": "больше",
    "/": "слэш", "\\": "слэш", "|": "вертикальная черта", "~": "тильда",
    "^": "степень", "*": "умножить на", "§": "параграф",
    "©": "копирайт", "™": "товарный знак", "°": "градусов",
    "₽": "рублей", "$": "долларов", "€": "евро", "£": "фунтов",
}

_QUOTE_PAIRS = [
    ("«", '"'), ("»", '"'), ("“", '"'), ("”", '"'), ("„", '"'),
    ("‘", "'"), ("’", "'"), ("–", "-"), ("—", "-"), ("…", "..."),
    ("\u00a0", " "), ("\u200b", ""),
]

_ORD_SUFFIX_MAP = {
    "": "", "й": "й", "ый": "ый", "ой": "ой",
    "а": "ая", "ая": "ая", "я": "яя", "яя": "яя",
    "е": "ое", "ое": "ое", "ю": "ую",
    "го": "ого", "ого": "ого", "му": "ому", "ому": "ому",
    "ым": "ым", "ом": "ом", "ой": "ой", "х": "х",
}


def _plural(n: int, one: str, few: str, many: str) -> str:
    n = abs(n) % 100
    if 11 <= n <= 19:
        return many
    n %= 10
    if n == 1:
        return one
    if 2 <= n <= 4:
        return few
    return many


def _int_words(n: int, feminine: bool = False) -> str:
    if n == 0:
        return "ноль"
    parts = []
    for value, one, few, many, fem in reversed(_SCALES):
        cur = n // value
        if cur == 0:
            continue
        n %= value
        use_fem = bool(fem) or (value == 1 and feminine)
        chunk = []
        h = cur // 100
        rest = cur % 100
        if h:
            chunk.append(_HUNDREDS[h])
        if rest < 20:
            if rest:
                chunk.append((_UNITS_F if use_fem else _UNITS)[rest])
        else:
            chunk.append(_TENS[rest // 10])
            if rest % 10:
                chunk.append((_UNITS_F if use_fem else _UNITS)[rest % 10])
        if value > 1:
            chunk.append(_plural(cur, one, few, many))
        parts.append(" ".join(chunk))
    return " ".join(parts)


def _ordinal_words(n: int) -> str:
    """Порядковое число, именительный падеж, мужской род: 21 → «двадцать первый»."""
    if n in _ORDINAL_M:
        return _ORDINAL_M[n]
    if n <= 0:
        return _int_words(n)
    if n < 100:
        tens, unit = divmod(n, 10)
        return (_TENS[tens] + " " + _ORDINAL_M[unit]).strip()
    if n < 1000:
        h, rest = divmod(n, 100)
        if rest:
            return _HUNDREDS[h] + " " + _ordinal_words(rest)
        return _ORDINAL_M.get(n, _int_words(n))
    return _int_words(n)


def _ordinal_case(n: int, suffix: str) -> str:
    """Порядковое число с падежом/родом по суффиксу: 12-го → «двенадцатого»."""
    word = _ordinal_words(n)
    target = _ORD_SUFFIX_MAP.get(suffix, "")
    if not target or target in ("й", "ый", "ой", "ий"):
        return word
    if word == "третий":
        special = {
            "ая": "третья", "яя": "третья", "ое": "третье", "ую": "третью",
            "ого": "третьего", "ому": "третьему", "ым": "третьим", "ом": "третьем",
            "й": "третий", "ый": "третий", "ой": "третий",
        }
        return special.get(target, word)
    for ending in ("ый", "ой", "ий"):
        if word.endswith(ending):
            stem = word[: -len(ending)]
            if target in ("ый", "ой", "ий"):
                return stem + target
            if target == "яя":
                return stem + "яя"
            return stem + target
    return word


def _feminine_hint(after: str) -> bool:
    """Эвристика: «одна минута», «две секунды» — женский род по следующему слову."""
    m = re.match(r"\s*([^\s]+)", after)
    if not m:
        return False
    w = m.group(1).lower().strip("«»\"'.,!?;:()")
    return w.endswith(("а", "я", "у", "ю", "ой", "ей", "и", "ы")) and not w.endswith(
        ("ко", "ие", "ые", "ние", "ний", "ция", "сия")
    )


def _expand_decimal(whole: str, frac: str) -> str:
    w = int(whole) if whole else 0
    words = [_int_words(w)]
    words.append("целых" if w != 1 else "целая")
    fval = int(frac)
    words.append(_int_words(fval, True))
    ln = len(frac)
    unit = {1: ("десятая", "десятых"), 2: ("сотая", "сотых"),
            3: ("тысячная", "тысячных"), 4: ("десятитысячная", "десятитысячных")}.get(
        ln, ("сотая", "сотых"))
    words.append(unit[0] if fval == 1 else unit[1])
    return " ".join(words)


def _expand_phone(token: str) -> str:
    digits = [d for d in token if d.isdigit()]
    words = []
    if token.strip().startswith("+"):
        words.append("плюс")
    words += ["ноль" if d == "0" else _UNITS[int(d)] for d in digits]
    return " ".join(words)


def normalize_text(text: str) -> str:
    """Нормализует русский текст для синтеза речи."""
    text = unicodedata.normalize("NFC", text)
    for src, dst in _QUOTE_PAIRS:
        text = text.replace(src, dst)
    text = "".join(
        ch for ch in text
        if unicodedata.category(ch) not in ("So", "Cf", "Cs")
    )
    text = text.replace("\n", " ").replace("\r", " ")

    out = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        rest = text[i:]
        before = text[:i]

        # телефон: +7 (900) 123-45-67
        if ch == "+" or ch.isdigit():
            if ch == "+":
                pm = re.match(r"\+\d[\d\s\-()]{8,}\d", rest)
            else:
                pm = None
                if (not before) or before[-1] in " \t(,":
                    pm = re.match(r"\d[\d\s\-()]{8,}\d", rest)
            if pm:
                out.append(_expand_phone(pm.group(0)))
                i += pm.end()
                continue

        if ch.isdigit():
            m = re.match(r"\d+(?:[.,]\d+)?", rest)
            token = m.group(0).replace(",", ".")
            after = rest[len(m.group(0)):]
            # дата 12.05.2024 (только с годом)
            dm = re.match(r"(\d{1,2})[.](\d{1,2})[.](\d{2,4})(?!\d)", rest)
            if dm and int(dm.group(2)) <= 12 and int(dm.group(1)) <= 31:
                day, month = int(dm.group(1)), int(dm.group(2))
                y = int(dm.group(3))
                if 20 <= y <= 30:
                    y += 2000
                elif y < 100:
                    y += 1900
                parts = [_ORDINAL_DATE.get(day, _int_words(day))]
                parts.append(_MONTHS.get(month, _int_words(month)))
                parts.append(_int_words(y) + " года")
                out.append(" ".join(parts))
                i += dm.end()
                continue
            # время 14:30
            tm = re.match(r"(\d{1,2}):(\d{2})(?!\d)", rest)
            if tm:
                h, mi = int(tm.group(1)), int(tm.group(2))
                words = [_int_words(h), _plural(h, "час", "часа", "часов")]
                if mi:
                    words += [_int_words(mi, True), _plural(mi, "минута", "минуты", "минут")]
                else:
                    words.append("ровно")
                out.append(" ".join(words))
                i += tm.end()
                continue
            # ординал 5-й / 10-го / 21-е
            om = re.match(r"(\d+)-(й|ый|ой|ая|яя|ое|е|ю|ую|го|ого|му|ому|ым|ом|х)\b", rest)
            if om:
                out.append(_ordinal_case(int(om.group(1)), om.group(2)))
                i += om.end()
                continue
            if "." in token:
                whole, frac = token.split(".", 1)
                out.append(_expand_decimal(whole, frac))
            else:
                out.append(_int_words(int(token), _feminine_hint(after)))
            i += len(m.group(0))
            continue

        if ch in _SYMBOLS:
            out.append(" " + _SYMBOLS[ch] + " ")
            i += 1
            continue

        out.append(ch)
        i += 1

    text = "".join(out)
    text = re.sub(r"[^\w\s\"'.,!?;:()\-ёЁа-яА-Яa-zA-Z]", " ", text)
    text = re.sub(r"\s+", " ", text).strip()
    text = re.sub(r"([!?,.;:])\1+", r"\1", text)
    return text
