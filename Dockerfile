# ============================================================================
#  Аниме-бот AniLibria -> Telegram (MTProto, файлы до 2000 МБ / 2 ГБ)
#
#  Dockerfile сделан «скучным» и совместимым с Railway 2026:
#    • нет инструкции постоянного тома (Railway её отклоняет и требует свои Railway Volumes)
#    • нет собственного healthcheck и EXPOSE (Railway проверяет контейнер сам)
#    • pip с ретраями и резервной установкой (если TgCrypto не собрался — бот всё равно поставится)
#    • сборка полностью автономна: никаких секретов и внешних файлов не требуется
#
#  Railway:
#    1) Settings → Source → Branch = arena/9afa2ee0-lsmsnksoslalql (там лежит этот Dockerfile)
#       Root Directory = / (пусто), и Railway сам напишет в логах "Using detected Dockerfile!"
#    2) Settings → Volumes → новый том с Mount path = /data
#       (там хранятся кэш видео, сессия MTProto и SQLite с file_id)
#    3) Variables → при желании BOT_TOKEN / OWNER_ID / MAX_FILE_MB (по умолчанию ключи уже вшиты в код)
#
#  Локально:  docker build -t animebot .   &&   docker run -d -v anime_data:/data animebot
# ============================================================================
FROM python:3.11-slim-bookworm

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    TZ=Europe/Amsterdam \
    DATA_DIR=/data \
    DOWNLOAD_DIR=/data/cache \
    SESSION_DIR=/data/sessions \
    DB_PATH=/data/anime_bot.sqlite3 \
    FFMPEG=ffmpeg \
    FFPROBE=ffprobe \
    MAX_FILE_MB=2000 \
    MAX_CACHE_GB=25

# ffmpeg/ffprobe — сборка mp4 из HLS-потоков AniLibria; ca-certificates — HTTPS к API и Telegram
# DEBIAN_FRONTEND=noninteractive — чтобы tzdata не задавал вопросов во время сборки на Railway
RUN DEBIAN_FRONTEND=noninteractive apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      ffmpeg ca-certificates tzdata \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Зависимости отдельным слоем — быстрее пересборка
COPY requirements.txt /app/requirements.txt
# Резерв: если TgCrypto не соберётся под эту архитектуру, ставим минимум (бот работает и без него)
RUN pip install --no-cache-dir --retries 5 --timeout 60 -r requirements.txt \
 || pip install --no-cache-dir --retries 5 --timeout 60 pyrogram==2.0.106 aiohttp

# Код бота (в корне репозитория)
COPY api_keys.py config.py anilibria_api.py dl_engine.py store.py ui.py anime_bot.py /app/

# Проверка зависимостей прямо на этапе сборки: если тут упало — сразу видно в build logs Railway
RUN mkdir -p /data/cache /data/sessions \
 && python -c "import pyrogram, aiohttp, sys; print('зависимости ok, pyrogram', pyrogram.__version__, '| python', sys.version.split()[0])"

# Постоянное хранилище подключается томом хостинга: Railway → Volumes → Mount path = /data,
# docker compose / VPS → -v anime_data:/data. Директория /data уже создана выше.
# Лимит файла 2000 МБ работает за счёт MTProto (Pyrogram), а не урезанного Bot API
CMD ["python", "-u", "anime_bot.py"]
