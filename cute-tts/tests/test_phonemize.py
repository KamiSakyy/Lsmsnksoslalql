from cute_tts.phonemize import (
    build_phoneme_id_map,
    phonemize_chars,
    split_sentences,
    text_to_ids,
)


def test_split_sentences():
    parts = split_sentences("Привет! Как дела? Всё хорошо.")
    assert len(parts) == 3


def test_espeak_ids():
    m = build_phoneme_id_map("espeak")
    assert len(m) > 50
    chains = text_to_ids("Привет, мир!", m, "espeak", "ru")
    assert chains and all(len(c) > 2 for c in chains)
    assert chains[0][0] == m["^"] and chains[0][-1] == m["$"]


def test_char_fallback():
    m = build_phoneme_id_map("text")
    chains = text_to_ids("Привет", m, "text", "ru")
    assert chains and len(chains[0]) > 4
    assert phonemize_chars("раз два") == [["р", "а", "з", " ", "д", "в", "а"]]
