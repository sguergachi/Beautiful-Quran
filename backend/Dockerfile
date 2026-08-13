FROM node:22-alpine

WORKDIR /app
COPY package.json ./
COPY src ./src

ENV PORT=8787
ENV CACHE_DIR=/var/lib/beautiful-quran-cache
RUN mkdir -p /var/lib/beautiful-quran-cache && chown node:node /var/lib/beautiful-quran-cache
VOLUME ["/var/lib/beautiful-quran-cache"]
EXPOSE 8787

USER node
CMD ["node", "src/server.mjs"]
