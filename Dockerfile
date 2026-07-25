# Dataraft (HEL-90) — multi-stage: React build → Python runtime with DuckDB.
FROM node:20-slim AS frontend
WORKDIR /fe
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm install --no-audit --no-fund
COPY frontend/ ./
# vite outDir is ../backend/dataraft/static relative to frontend/ — override to
# a local path inside this stage
RUN npx vite build --outDir /fe-dist --emptyOutDir

FROM python:3.11-slim
WORKDIR /app
COPY backend/requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
# pre-install the httpfs extension at BUILD time so the runtime never needs
# network access to extensions.duckdb.org (and engine's INSTALL is a no-op hit
# on the local cache)
RUN python -c "import duckdb; c = duckdb.connect(); c.execute('INSTALL httpfs'); c.close()"
COPY backend/dataraft ./dataraft
COPY --from=frontend /fe-dist ./dataraft/static
EXPOSE 5400
CMD ["python", "-m", "uvicorn", "dataraft.app:app", "--host", "0.0.0.0", "--port", "5400"]
