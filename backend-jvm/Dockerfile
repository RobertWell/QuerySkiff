# QuerySkiff backend-jvm (HEL-95) — multi-stage: React build → Maven/Quarkus
# build → JRE runtime. Mirrors the Python image's shape: same frontend bundle,
# same env contract (QUERYSKIFF_*/MINIO_*), same port. Build context = repo root:
#   docker build -f backend-jvm/Dockerfile -t queryskiff-backend-jvm .
FROM node:20-slim AS frontend
WORKDIR /fe
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm install --no-audit --no-fund
COPY frontend/ ./
RUN npx vite build --outDir /fe-dist --emptyOutDir

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /w
COPY backend-jvm/pom.xml ./
# warm the dependency cache before sources so code edits don't re-download
RUN mvn -q -B dependency:go-offline || true
COPY backend-jvm/src ./src
# the SPA route serves this exact resource path (SpaResource); Quarkus static
# handling serves the assets — matching the Python STATIC_DIR mount
COPY --from=frontend /fe-dist ./src/main/resources/META-INF/resources/queryskiff
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /w/target/quarkus-app/ ./
# DuckDB's httpfs extension is fetched at first INSTALL; place it in the
# extension cache at BUILD time so the runtime never needs network access to
# extensions.duckdb.org (version dir must match duckdb_jdbc in pom.xml)
RUN mkdir -p /root/.duckdb/extensions/v1.1.3/linux_amd64 \
 && curl -fsSL http://extensions.duckdb.org/v1.1.3/linux_amd64/httpfs.duckdb_extension.gz \
      | gunzip > /root/.duckdb/extensions/v1.1.3/linux_amd64/httpfs.duckdb_extension
EXPOSE 5400
ENV QUARKUS_HTTP_PORT=5400 QUARKUS_HTTP_HOST=0.0.0.0
CMD ["java", "-jar", "quarkus-run.jar"]
