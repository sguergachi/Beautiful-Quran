FROM node:22-alpine

RUN apk add --no-cache python3
WORKDIR /app
COPY backend/package.json ./backend/package.json
COPY backend/src ./backend/src
COPY tools/build_db.py tools/normalize_runtime_timings.py ./tools/
COPY tools/timing_corrections ./tools/timing_corrections
COPY tools/timing_repairs ./tools/timing_repairs
COPY tools/audio_onsets ./tools/audio_onsets
COPY data/quran.db ./data/quran.db

ENV PORT=8787
ENV CACHE_DIR=/var/lib/beautiful-quran-cache
ENV PYTHONDONTWRITEBYTECODE=1
RUN mkdir -p /var/lib/beautiful-quran-cache && chown node:node /var/lib/beautiful-quran-cache
VOLUME ["/var/lib/beautiful-quran-cache"]
EXPOSE 8787

USER node
CMD ["node", "backend/src/server.mjs"]
