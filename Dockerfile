# --- Stage 1: build the console -------------------------------------------
FROM node:22-alpine AS web

WORKDIR /build/web
COPY web/package*.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# --- Stage 2: the runtime image -------------------------------------------
FROM node:22-alpine

ENV NODE_ENV=production
WORKDIR /app

COPY server/package*.json ./server/
RUN cd server && npm ci --omit=dev

COPY server/ ./server/
# The API serves the built console from ../../web/dist relative to server/src.
COPY --from=web /build/web/dist ./web/dist

# Never run as root.
USER node
EXPOSE 4001

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:4001/api/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

WORKDIR /app/server
CMD ["node", "src/index.js"]
