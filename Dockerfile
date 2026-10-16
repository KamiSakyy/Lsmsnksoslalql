FROM node:20-alpine

WORKDIR /app

ENV NODE_ENV=production \
    PORT=8080

COPY package.json ./
RUN npm install --omit=dev || true

COPY . .

EXPOSE 8080

CMD ["node", "--max-old-space-size=256", "server.js"]
