# Лёгкий relay-сервис: Python-шлюз + движок (движок подтягивается при старте).
# Настроек не требует: порт и домен определяются автоматически.
FROM alpine:3.21

RUN apk add --no-cache ca-certificates curl unzip python3

WORKDIR /app

COPY server.py start.sh app-config.template.json ./

RUN chmod +x start.sh server.py

ENV PORT=8080 \
    INTERNAL_PORT=8081 \
    RELAY_PATH=/api/uploadFile/

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD python3 -c "import os,socket;socket.create_connection(('127.0.0.1',int(os.environ.get('PORT','8080'))),timeout=3)" || exit 1

CMD ["./start.sh"]
