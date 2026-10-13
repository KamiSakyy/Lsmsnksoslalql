#!/usr/bin/env bash
# Скачать готовый женский русский голос (Piper «Ирина», ~67 МБ fp32 / 21 МБ int8)
# для приложения «Мила». Нужен интернет — один раз. Дальше всё работает оффлайн.
#
# Использование:
#   bash scripts/get_voice.sh           # fp32 (максимальное качество, ~67 МБ)
#   bash scripts/get_voice.sh int8      # компактная (~21 МБ)
set -euo pipefail

VARIANT="${1:-fp32}"
DEST="$(cd "$(dirname "$0")/.." && pwd)/models"
mkdir -p "$DEST"

case "$VARIANT" in
  fp32) ASSET="vits-piper-ru_RU-irina-medium.tar.bz2" ;;
  int8) ASSET="vits-piper-ru_RU-irina-medium-int8.tar.bz2" ;;
  fp16) ASSET="vits-piper-ru_RU-irina-medium-fp16.tar.bz2" ;;
  *) echo "вариант: fp32 | int8 | fp16"; exit 1 ;;
esac

URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${ASSET}"
echo "скачиваю $ASSET …"
if command -v curl >/dev/null 2>&1; then
  curl -L -o "/tmp/$ASSET" "$URL"
elif command -v wget >/dev/null 2>&1; then
  wget -O "/tmp/$ASSET" "$URL"
elif command -v gh >/dev/null 2>&1; then
  gh release download tts-models -R k2-fsa/sherpa-onnx -p "$ASSET" -D /tmp --clobber
else
  echo "нужен curl, wget или gh"; exit 1
fi

tar -xjf "/tmp/$ASSET" -C /tmp
MODEL_DIR="$(find /tmp -maxdepth 2 -name 'model.onnx' -path '*irina*' | head -1 | xargs dirname)"
if [ -z "$MODEL_DIR" ]; then
  MODEL_DIR="$(dirname "$(find /tmp -maxdepth 3 -name '*.onnx' | head -1)")"
fi

cp "$MODEL_DIR"/*.onnx "$DEST/irina.onnx" 2>/dev/null || cp "$MODEL_DIR"/*.int8.onnx "$DEST/irina.onnx"
# конфиг в формате Piper генерируем из карты фонем espeak
python3 - <<'PYEOF'
import json, sys, os
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath("scripts/get_voice.sh")), "."))
try:
    from cute_tts.phonemize import build_phoneme_id_map
    phoneme_id_map = build_phoneme_id_map("espeak")
except Exception:
    phoneme_id_map = {}
dest = os.path.join("models", "irina.onnx.json")
payload = {
    "audio": {"sample_rate": 22050, "hop_length": 256, "win_length": 1024,
              "n_fft": 1024, "num_mels": 80},
    "text": {"language": "ru", "phoneme_type": "espeak", "phoneme_id_map": phoneme_id_map},
    "inference": {"noise_scale": 0.667, "length_scale": 1.0, "noise_w": 0.8},
    "model": {"type": "piper-vits", "note": "готовый голос Piper ru_RU-irina-medium"},
}
with open(dest, "w", encoding="utf-8") as f:
    json.dump(payload, f, ensure_ascii=False)
print("конфиг:", dest)
PYEOF
echo "готово: $DEST/irina.onnx"
echo "запуск:  python -m cute_tts 'Привет!' -m models/irina.onnx -o hello.wav"
