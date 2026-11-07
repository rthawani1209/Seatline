# The Next.js app as a small standalone server.
#   docker build -f infra/docker/web.Dockerfile -t seatline-web .

FROM node:22-alpine AS build
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend .
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM node:22-alpine
RUN adduser -S -u 10001 seatline
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
COPY --from=build /src/.next/standalone ./
COPY --from=build /src/.next/static ./.next/static
USER seatline
EXPOSE 3000
CMD ["node", "server.js"]
