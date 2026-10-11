# VK Tsuyu — anime messenger

Anime-styled web messenger with a Flask API. The production deployment is packaged with Docker Compose.

## Start

```bash
cp .env.example .env
# Set a strong FLASK_SECRET in .env
docker compose up --build -d
```

Open `http://localhost:8080`. Check deployment health with:

```bash
curl http://localhost:8080/api/ping
docker compose ps
```

The host port can be changed with `APP_PORT`. Persistent runtime data is stored in the `tsuyu-data` Docker volume.
