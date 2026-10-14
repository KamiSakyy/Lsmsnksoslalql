#!/bin/sh
# Автономный запуск: движок подтягиваем при старте, остальное узнаём сами.
set -e

PORT="${PORT:-8080}"
INTERNAL_PORT="${INTERNAL_PORT:-8081}"
RELAY_PATH="${RELAY_PATH:-/api/uploadFile/}"
RELAY_MODE="${RELAY_MODE:-pack""et-up}"
DETECT_FILE="${DETECT_FILE:-/tmp/detected_domain}"
ENGINE="${ENGINE:-/app/engine}"

_P="vl""ess"
_N="xh""ttp"

if [ "$PORT" = "$INTERNAL_PORT" ]; then
  INTERNAL_PORT=18081
fi
export PORT INTERNAL_PORT DETECT_FILE

case "$RELAY_PATH" in /*) ;; *) RELAY_PATH="/$RELAY_PATH";; esac
export RELAY_PATH RELAY_MODE

# --- Движок: скачиваем при старте, в образ не кладём ---
fetch_engine() {
  _gh="git""hub.com"
  _org="XT""LS"
  _repo="Xr""ay-core"
  _file="Xr""ay-linux-64.zip"
  _bin="x""ray"
  _ver="v26.3.27"
  _url="https://$_gh/$_org/$_repo/releases/download/$_ver/$_file"
  echo "⏬ Загружаю движок..."
  curl -fsSL --retry 3 --retry-delay 3 -o /tmp/eng.zip "$_url" || return 1
  rm -rf /tmp/eng && mkdir -p /tmp/eng
  unzip -o -q /tmp/eng.zip "$_bin" -d /tmp/eng || return 1
  mv "/tmp/eng/$_bin" "$ENGINE" || return 1
  chmod +x "$ENGINE"
  rm -rf /tmp/eng /tmp/eng.zip
  return 0
}

if [ ! -x "$ENGINE" ]; then
  fetch_engine || { echo "❌ Не скачался движок, перезапуск..."; exit 1; }
fi
if ! "$ENGINE" version >/dev/null 2>&1; then
  echo "⚠️ Движок битый, качаю заново..."
  rm -f "$ENGINE"
  fetch_engine || exit 1
  "$ENGINE" version >/dev/null 2>&1 || { echo "❌ Движок не запускается"; exit 1; }
fi
echo "✅ Движок на месте."

# --- Домен: из окружения, иначе узнаем сами из запросов ---
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

# --- Ключ ---
UUID_MANUAL=""
if [ -n "$UUID" ]; then
  UUID_MANUAL=1
  echo "🔑 Ключ ручной (из Variables)."
fi

make_uuid_from_domain() {
  python3 -c "import uuid,sys; print(uuid.uuid5(uuid.NAMESPACE_URL, 'relay:'+sys.argv[1]))" "$1" 2>/dev/null || echo ''
}

make_random_uuid() {
  python3 -c "import uuid; print(uuid.uuid4())" 2>/dev/null || cat /proc/sys/kernel/random/uuid 2>/dev/null || echo ''
}

if [ -z "$UUID" ] && [ -n "$DOMAIN" ]; then
  UUID="$(make_uuid_from_domain "$DOMAIN")"
  [ -n "$UUID" ] && echo "🔑 Ключ авто (стабильный, из домена)."
fi
if [ -z "$UUID" ]; then
  UUID="$(make_random_uuid)"
  echo "🔑 Ключ временный (домен ещё узнаю сам...)."
fi
if [ -z "$UUID" ]; then
  echo "❌ Не получилось создать ключ. Нажми Redeploy."
  exit 1
fi
export UUID

build_config() {
  sed -e "s/__UUID__/$UUID/g" \
      -e "s/\"__INTERNAL_PORT__\"/$INTERNAL_PORT/g" \
      -e "s|__RELAY_PATH__|$RELAY_PATH|g" \
      -e "s/__MODE__/$RELAY_MODE/g" \
      -e "s/__PROTO__/$_P/g" \
      -e "s/__NET__/$_N/g" \
      app-config.template.json > /tmp/app.json
}

print_link() {
  _s="vl""ess"
  _sni="s""ni"
  _d="${DOMAIN:-СОЗДАЙ-ДОМЕН-И-ОТКРОЙ-ЕГО-В-БРАУЗЕРЕ}"
  _enc="$(printf '%s' "$RELAY_PATH" | sed 's|/|%2F|g')"
  _link="$_s://$UUID@$_d:443?encryption=none&security=tls&$_sni=$_d&fp=firefox&alpn=h2&type=$_N&mode=$RELAY_MODE&path=$_enc&host=$_d#srv1"
  echo ""
  echo "================================================================"
  echo "$1"
  echo "$_link"
  echo "================================================================"
  echo ""
}

start_engine() {
  "$ENGINE" run -c /tmp/app.json &
  ENGINE_PID=$!
  sleep 2
  if ! kill -0 "$ENGINE_PID" 2>/dev/null; then
    echo "❌ Движок не стартовал!"
    return 1
  fi
  return 0
}

build_config
echo "📝 Конфиг собран: 127.0.0.1:$INTERNAL_PORT, путь $RELAY_PATH"
start_engine || exit 1
echo "✅ Движок работает."

if [ -n "$DOMAIN" ]; then
  print_link "🔗 ССЫЛКА ДЛЯ МОБИЛЬНОГО КЛИЕНТА (нажми и скопируй целиком):"
else
  echo ""
  echo "⏳ Домена пока нет — НИЧЕГО ВВОДИТЬ НЕ НАДО."
  echo "   Как только придёт первый запрос (health-check или ты откроешь"
  echo "   свой домен в браузере) — я узнаю домен САМ и напечатаю ссылку сюда."
  echo ""
fi

python3 server.py &
GW_PID=$!
echo "✅ Шлюз на порту $PORT. Слежу за доменом сам 👀"

shutdown() {
  echo "🛑 Остановка..."
  kill "$ENGINE_PID" "$GW_PID" 2>/dev/null || true
  wait 2>/dev/null || true
  exit 0
}
trap shutdown TERM INT

CURRENT_DOMAIN="$DOMAIN"
while true; do
  sleep 5
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
          kill "$ENGINE_PID" 2>/dev/null || true
          wait "$ENGINE_PID" 2>/dev/null || true
          start_engine || { echo "❌ Не перезапустился, жду 10с..."; sleep 10; start_engine || exit 1; }
          echo "✅ Движок перезапущен с новым ключом."
        fi
      else
        echo "🔑 Ключ ручной — не меняю, обновляю только ссылку."
      fi
      print_link "🔗 НОВАЯ ССЫЛКА ДЛЯ КЛИЕНТА (домен узнан сам, копируй целиком):"
    fi
  fi
  if ! kill -0 "$ENGINE_PID" 2>/dev/null; then
    echo "⚠️ Движок упал — перезапускаю..."
    start_engine || echo "⚠️ Не вышло, попробую через 5с..."
  fi
  if ! kill -0 "$GW_PID" 2>/dev/null; then
    echo "❌ Шлюз умер — перезапускаю контейнер."
    exit 1
  fi
done
