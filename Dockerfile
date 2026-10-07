FROM node:22-alpine

ENV NODE_ENV=production
WORKDIR /app

COPY handoff/railway/package.json handoff/railway/package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund

COPY handoff/railway/server.js ./server.js

ENV PORT=8080
EXPOSE 8080
USER node
CMD ["node", "server.js"]
