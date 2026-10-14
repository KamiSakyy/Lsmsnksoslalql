#!/bin/sh
# НУЛЕВАЯ НАСТРОЙКА: порт, домен и ключ узнаём САМИ.
# Домен ловим из Host входящих запросов — сработает, даже если
# Generate Domain нажали ПОСЛЕ деплоя. Переменные НЕ нужны.
set -e

PORT="${PORT:-8080}"
INTERNAL_PORT="${INTERNAL_PORT:-8081}"
XHTTP_PATH="${XHTTP_PATH:-/api/uploadFile/}"
XHTTP_MODE="${XHTTP_MODE:-packet-up}"
DETECT_FILE="${DETECT_FILE:-/tmp/detected_domain}"

if [ "$PORT" = "$INTERNAL_PORT" ]; then
  INTERNAL_PORT=18081
fi
export PORT INTERNAL_PORT DETECT_FILE

case "$XHTTP_PATH" in /*) ;; *) XHTTP_PATH="/$XHTTP_PATH";; esac
export XHTTP_PATH XHTTP_MODE

# --- Домен: сначала из окружения (если хостинг дал), иначе узнаем сами ---
MANUAL_DOMAIN="${DOMAIN:-}"
DOMAIN="${RAILWAY_PUBLIC_DOMAIN:-${RAILWAY_STATIC_URL:-${KOYEB_PUBLIC_DOMAIN:-}}}"
if [ -z "$DOMAIN" ] && [ -n "${RENDER_EXTERNAL_URL:-}" ]; then
  _r="${RENDER_EXTERNAL_URL#https://}"
  _r="${_r#http://}"
  DOMAIN="${_r%%/*}"
fi
if [ -z "$DOMAIN" ] && [ -n "${FLY_APP_NAME:-}" ]; then
  DOMAIN="${FLY_APP_NAME}.fly.dev"
fi
if [ -z "$DOMAIN" ]; then DOMAIN="$MANUAL_DOMAIN"; fi
[ -n "$DOMAIN" ] && echo "🌍 Домен из окружения: $DOMAIN"

# --- UUID ---
UUID_MANUAL=""
if [ -n "$UUID" ]; then
  UUID_MANUAL=1
  echo "🔑 UUID ручной (из Variables)."
fi

make_uuid_from_domain() {
  python3 -c "import uuid,sys; print(uuid.uuid5(uuid.NAMESPACE_URL, 'vpn:'+sys.argv[1]))" "$1" 2>/dev/null || echo ''
}

make_random_uuid() {
  xray uuid 2>/dev/null || cat /proc/sys/kernel/random/uuid 2>/dev/null || echo ''
}

if [ -z "$UUID" ] && [ -n "$DOMAIN" ]; then
  UUID="$(make_uuid_from_domain "$DOMAIN")"
  [ -n "$UUID" ] && echo "🔑 UUID авто (стабильный, из домена)."
fi
if [ -z "$UUID" ]; then
  UUID="$(make_random_uuid)"
  echo "🔑 UUID временный (домен ещё узнаю сам...)."
fi
if [ -z "$UUID" ]; then
  echo "❌ Не получилось создать UUID. Нажми Redeploy."
  exit 1
fi
export UUID

build_config() {
  sed -e "s/__UUID__/$UUID/g" \
      -e "s/\"__INTERNAL_PORT__\"/$INTERNAL_PORT/g" \
      -e "s|__XHTTP_PATH__|$XHTTP_PATH|g" \
      -e "s/__XHTTP_MODE__/$XHTTP_MODE/g" \
      config.template.json > /tmp/xray.json
}

print_link() {
  _d="${DOMAIN:-СОЗДАЙ-ДОМЕН-И-ОТКРОЙ-ЕГО-В-БРАУЗЕРЕ}"
  _enc="$(printf '%s' "$XHTTP_PATH" | sed 's|/|%2F|g')"
  _link="vless://$UUID@$_d:443?encryption=none&security=tls&sni=$_d&fp=firefox&alpn=h2&type=xhttp&mode=$XHTTP_MODE&path=$_enc&host=$_d#VPN-1клик"
  echo ""
  echo "================================================================"
  echo "$1"
  echo "$_link"
  echo "================================================================"
  echo ""
}

start_xray() {
  xray run -c /tmp/xray.json &
  XRAY_PID=$!
  sleep 2
  if ! kill -0 "$XRAY_PID" 2>/dev/null; then
    echo "❌ Xray не стартовал!"
    return 1
  fi
  return 0
}

build_config
echo "📝 Xray: 127.0.0.1:$INTERNAL_PORT, путь $XHTTP_PATH, режим $XHTTP_MODE"
start_xray || exit 1
echo "✅ Xray работает."

if [ -n "$DOMAIN" ]; then
  print_link "🔗 ССЫЛКА ДЛЯ v2rayNG (нажми и скопируй целиком):"
else
  echo ""
  echo "⏳ Домена пока нет — НИЧЕГО ВВОДИТЬ НЕ НАДО."
  echo "   Как только придёт первый запрос (health-check или ты откроешь"
  echo "   свой домен в браузере) — я узнаю домен САМ и напечатаю ссылку сюда."
  echo ""
fi

# --- Прокси в фон + надзор ---
python3 proxy.py &
PROXY_PID=$!
echo "✅ Прокси на порту $PORT. Слежу за доменом сам 👀"

shutdown() {
  echo "🛑 Остановка..."
  kill "$XRAY_PID" "$PROXY_PID" 2>/dev/null
  wait 2>/dev/null
  exit 0
}
trap shutdown TERM INT

CURRENT_DOMAIN="$DOMAIN"
while true; do
  sleep 5
  # 1) Узнали домен сами?
  if [ -f "$DETECT_FILE" ]; then
    NEW_D="$(head -n1 "$DETECT_FILE" 2>/dev/null | tr -d ' \t\r\n')"
    if [ -n "$NEW_D" ] && [ "$NEW_D" != "$CURRENT_DOMAIN" ]; then
      echo "🎉 ДОМЕН УЗНАН САМ: $NEW_D"
      DOMAIN="$NEW_D"
      CURRENT_DOMAIN="$NEW_D"
      if [ -z "$UUID_MANUAL" ]; then
        NEW_UUID="$(make_uuid_from_domain "$NEW_D")"
        if [ -n "$NEW_UUID" ] && [ "$NEW_UUID" != "$UUID" ]; then
          UUID="$NEW_UUID"
          export UUID
          echo "🔑 Ключ обновлён под домен (стабильный)."
          build_config
          kill "$XRAY_PID" 2>/dev/null
          wait "$XRAY_PID" 2>/dev/null
          start_xray || { echo "❌ Не перезапустился, жду 10с..."; sleep 10; start_xray || exit 1; }
          echo "✅ Xray перезапущен с новым ключом."
        fi
      else
        echo "🔑 UUID ручной — ключ не меняю, обновляю только ссылку."
      fi
      print_link "🔗 НОВАЯ ССЫЛКА ДЛЯ v2rayNG (домен узнан сам, копируй целиком):"
    fi
  fi
  # 2) Надзор за процессами
  if ! kill -0 "$XRAY_PID" 2>/dev/null; then
    echo "⚠️ Xray упал — перезапускаю..."
    start_xray || echo "⚠️ Не вышло, попробую через 5с..."
  fi
  if ! kill -0 "$PROXY_PID" 2>/dev/null; then
    echo "❌ Прокси умер — перезапускаю контейнер."
    exit 1
  fi
done
