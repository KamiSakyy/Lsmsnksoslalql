from cute_tts.normalize import normalize_text


def test_numbers():
    assert normalize_text("5 яблок") == "пять яблок"
    assert normalize_text("22 кошки") == "двадцать две кошки"
    assert normalize_text("1 кот") == "один кот"
    assert "сто двадцать пять тысяч" in normalize_text("125000 рублей")


def test_decimal():
    assert normalize_text("3.14") == "три целых четырнадцать сотых"


def test_time_and_date():
    assert "четырнадцать часов тридцать минут" in normalize_text("14:30")
    assert "двенадцатое мая" in normalize_text("12.05.2024")


def test_ordinals():
    assert normalize_text("1-й") == "первый"
    assert normalize_text("10-го") == "десятого"
    assert normalize_text("21-е") == "двадцать первое"


def test_symbols():
    out = normalize_text("Скидка 15%!")
    assert "процентов" in out
    assert "%" not in out


def test_phone():
    out = normalize_text("+7 (900) 123-45-67")
    assert "плюс" in out
    assert "(" not in out
