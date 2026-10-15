# AniLibria MTProto userbot (Pyrofork) — файлы до 2 ГБ
# Докер-файл в КОРНЕ репозитория: хостинг подхватит его автоматически.
FROM python:3.12-slim

# ffmpeg нужен для конвертации HLS -> MP4
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY bot.py .

# Папка для сессии, принятых файлов и временных MP4
RUN mkdir -p /data/files /data/tmp

ENV DATA_DIR=/data \
    PYTHONUNBUFFERED=1

VOLUME ["/data"]

CMD ["python", "bot.py"]
