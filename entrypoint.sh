#!/bin/sh
# НУЛЕВАЯ НАСТРОЙКА: всё узнаём сами (порт, домен, ключ).
# UUID стабилен: вычисляется из домена. Переменные задавать НЕ нужно.
set -e

PORT="${PORT:-8080}"
INTERNAL_PORT="${INTERNAL_PORT:-8081}"
XHTTP_PATH="${XHTTP_PATH:-/api/uploadFile/}"
XHTTP_MODE="${XHTTP_MODE:-packet-up}"

if [ "$PORT" = "$INTERNAL_PORT" ]; then
  INTERNAL_PORT=18081
fi
export PORT INTERNAL_PORT

case "$XHTTP_PATH" in /*) ;; *) XHTTP_PATH="/$XHTTP_PATH";; esac
export XHTTP_PATH XHTTP_MODE

# --- Домен узнаём сами ---
MANUAL_DOMAIN="${DOMAIN:-}"
DOMAIN="${RAILWAY_PUBLIC_DOMAIN:-${RAILWAY_STATIC_URL:-}}"
if [ -z "$DOMAIN" ] && [ -n "${RENDER_EXTERNAL_URL:-}" ]; then
  _r="${RENDER_EXTERNAL_URL#https://}"
  _r="${_r#http://}"
  DOMAIN="${_r%%/*}"
fi
if [ -z "$DOMAIN" ]; then DOMAIN="$MANUAL_DOMAIN"; fi

# --- UUID: 1) из Variables 2) стабильный из домена 3) временный ---
UUID_MODE=""
if [ -n "$UUID" ]; then
  UUID_MODE="ручной (из Variables)"
elif [ -n "$DOMAIN" ]; then
  UUID="$(python3 -c "import uuid,sys; print(uuid.uuid5(uuid.NAMESPACE_URL, 'vpn:'+sys.argv[1]))" "$DOMAIN" 2>/dev/null || echo '')"
  UUID_MODE="авто (стабильный, вычислен из домена)"
fi
if [ -z "$UUID" ]; then
  UUID="$(xray uuid 2>/dev/null || cat /proc/sys/kernel/random/uuid 2>/dev/null || echo '')"
  UUID_MODE="временный (домен ещё не выдан; после выдачи домена ключ станет постоянным)"
fi
if [ -z "$UUID" ]; then
  echo "❌ Не получилось создать UUID. Просто перезапусти сервис (Redeploy)."
  exit 1
fi
export UUID
echo "🔑 UUID: $UUID ($UUID_MODE)"

# --- Конфиг Xray из шаблона ---
sed -e "s/__UUID__/$UUID/g" \
    -e "s/\"__INTERNAL_PORT__\"/$INTERNAL_PORT/g" \
    -e "s|__XHTTP_PATH__|$XHTTP_PATH|g" \
    -e "s/__XHTTP_MODE__/$XHTTP_MODE/g" \
    config.template.json > /tmp/xray.json
echo "📝 Xray: 127.0.0.1:$INTERNAL_PORT, путь $XHTTP_PATH, режим $XHTTP_MODE"

# --- Готовая ссылка для клиента ---
SHOW_DOMAIN="${DOMAIN:-СОЗДАЙ-ДОМЕН-В-НАСТРОЙКАХ}"
ENC_PATH="$(printf '%s' "$XHTTP_PATH" | sed 's|/|%2F|g')"
LINK="vless://$UUID@$SHOW_DOMAIN:443?encryption=none&security=tls&sni=$SHOW_DOMAIN&fp=firefox&alpn=h2&type=xhttp&mode=$XHTTP_MODE&path=$ENC_PATH&host=$SHOW_DOMAIN#VPN-1клик"

echo ""
echo "================================================================"
echo "🔗 ССЫЛКА ДЛЯ v2rayNG (нажми и скопируй целиком):"
echo "$LINK"
echo "================================================================"
if [ -z "$DOMAIN" ]; then
  echo "⚠️ Домена пока нет! В Railway: сервис → Settings → Networking →"
  echo "   Generate Domain → дождись перезапуска → скопируй ссылку из НОВЫХ логов."
else
  echo "🌍 Домен: $DOMAIN"
fi
echo ""

# --- Запуск ---
xray run -c /tmp/xray.json &
XRAY_PID="$!"
sleep 2
if ! kill -0 "$XRAY_PID" 2>/dev/null; then
  echo "❌ Xray не стартовал, смотри ошибку выше."
  exit 1
fi
echo "✅ Xray работает. Поднимаю прокси на порту $PORT..."
exec python3 proxy.py
