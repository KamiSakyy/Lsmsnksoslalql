# ============================================================
#  VPN в 1 клик: VLESS + XHTTP. Настроек НОЛЬ — всё авто.
#  Railway / Render / Koyeb / Fly / VPS — просто нажми Deploy.
# ============================================================
FROM alpine:3.21

RUN apk add --no-cache ca-certificates curl unzip python3

WORKDIR /app

# Последний стабильный Xray (умеет VLESS + XHTTP)
RUN curl -fsSL -o /tmp/xray.zip https://github.com/XTLS/Xray-core/releases/latest/download/Xray-linux-64.zip \
    && unzip /tmp/xray.zip -d /usr/local/bin/ \
    && chmod +x /usr/local/bin/xray \
    && rm -f /tmp/xray.zip \
    && xray version

ENV XRAY_LOCATION_ASSET=/usr/local/bin

COPY proxy.py entrypoint.sh config.template.json ./

RUN chmod +x entrypoint.sh proxy.py

# Значения по умолчанию. PORT подставит сам хостинг.
ENV PORT=8080 \
    INTERNAL_PORT=8081 \
    XHTTP_PATH=/api/uploadFile/ \
    XHTTP_MODE=packet-up

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD python3 -c "import os,socket;socket.create_connection(('127.0.0.1',int(os.environ.get('PORT','8080'))),timeout=3)" || exit 1

CMD ["./entrypoint.sh"]
