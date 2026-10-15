# ============================================================================
#  Аниме-бот AniLibria -> Telegram (MTProto, файлы до 2000 МБ / 2 ГБ)
#  Докер в корне репозитория: хостинг (Railway/Render/VPS/Fly) подхватит сам.
#  Сборка:  docker build -t animebot .
#  Запуск:  docker run -d --name animebot -v anime_data:/data animebot
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
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg ca-certificates tzdata \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Зависимости отдельным слоем — быстрее пересборка
COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

# Код бота (в корне репозитория)
COPY api_keys.py config.py anilibria_api.py dl_engine.py store.py ui.py anime_bot.py /app/

RUN mkdir -p /data/cache /data/sessions /data/cache/intake \
 && python -c "import pyrogram, aiohttp; print('deps ok', pyrogram.__version__)"

# Данные (кэш видео, сессии, SQLite) живут в томе
VOLUME ["/data"]

# Необязательный health-эндпоинт: включится, если задан HEALTH_PORT или PORT
# (нужно для PaaS вроде Render/Railway, которые ждут слушающий порт)
ENV HEALTH_PORT=""

# Проверка живости: сессия MTProto появляется только после успешного входа бота
HEALTHCHECK --interval=2m --timeout=30s --start-period=90s --retries=3 \
  CMD python -c "import glob,sys; sys.exit(0 if glob.glob('/data/sessions/*.session') else 1)"

# Лимит файла 2000 МБ работает за счёт MTProto (Pyrogram), а не Bot API
CMD ["python", "-u", "anime_bot.py"]
