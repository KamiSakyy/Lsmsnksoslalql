# 🎌 AniLibria MTProto Userbot (Pyrofork)

Полноценный **MTProto userbot** (НЕ Bot API!) для просмотра аниме через
**AniLibria API v1 (2026)** прямо в Telegram.

## ⚡️ Возможности

- 📥 **Приём и отправка файлов до 2 ГБ** (лимит MTProto 2 GiB, у Bot API — 50 МБ)
- 🔍 **Инлайн-поиск**: в любом чате набери `@твой_бот название аниме`
- 📺 Выбор серии и качества: **480p / 720p / 1080p**
- ⚙️ Сервер скачивает HLS-поток, **конвертирует в MP4** (ffmpeg) и отправляет видео
- 🎲 `/random` — случайный тайтл, `/search` — поиск с кнопками
- 🗂 Хранилище файлов: `/files`, `/get`, `/del`

## 🚀 Быстрый запуск (Docker)

Dockerfile лежит в **корне** репозитория — хостинг подхватит его сам.

```bash
docker build -t anilibria-bot .
docker run -d --restart unless-stopped -v $PWD/data:/data anilibria-bot
```

или через docker-compose:

```bash
docker compose up -d --build
```

## 🔑 Ключи

Все ключи **встроены прямо в код** (`bot.py`):
- `BOT_TOKEN` — токен бота
- 14 пар `api_id` / `api_hash` (Public Android/Static/iOS/MacOS/Win Beta,
  Telegram Desktop, Telegram X, TDLib, CLI, Plus Messenger, Swift и др.)
  — пара выбирается **случайно** (рандомизация параметров MTProto) и
  закрепляется в `data/api_pair.json`, чтобы сессия не слетала.

### Полноценный userbot-режим (аккаунт пользователя)

Если нужен именно **userbot** (StringSession пользователя), задай переменную:

```bash
docker run -d -v $PWD/data:/data -e USER_SESSION="1BVtsOHwBu..." anilibria-bot
```

## 📋 Команды

| Команда | Описание |
|---|---|
| `@бот запрос` | Инлайн-поиск аниме |
| `/search запрос` | Поиск с кнопками |
| `/random` | Случайный тайтл |
| `/files` | Принятые файлы |
| `/get id` | Отправить файл обратно |
| `/del id` | Удалить файл |
| `/start`, `/help` | Помощь |

## 📁 Файлы

```
bot.py            — весь код бота
Dockerfile        — докер-образ (в корне!)
docker-compose.yml
requirements.txt
```
