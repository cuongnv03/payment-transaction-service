# syntax=docker/dockerfile:1.7

# ─── Stage 1: build the static bundle ────────────────────────────────────────
FROM node:20-alpine AS builder

WORKDIR /app

# Copy lockfile + manifest first so npm install layer caches when source changes.
COPY package.json package-lock.json* ./
RUN npm ci || npm install

# Then copy the rest and build.
COPY tsconfig*.json vite.config.ts index.html ./
COPY src ./src
RUN npm run build

# ─── Stage 2: nginx serving the static bundle + reverse-proxy ────────────────
FROM nginx:1.27-alpine AS runner

# Replace the default config with one that:
#   1. Serves /app/dist as a SPA (history-API fallback to index.html)
#   2. Reverse-proxies /api and /actuator to the backend container
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

# nginx:alpine already runs nginx in the foreground via the default CMD — keep
# the parent image's HEALTHCHECK and entrypoint defaults.
