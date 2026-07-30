"""QuerySkiff API + static host (HEL-90).

Everything is served under the gateway base path (default /queryskiff). The API
lives at {base}/api/...; the built React app is served for everything else so
client-side routes survive a hard refresh. Authentication is entirely the
gateway's job — QuerySkiff trusts that any request reaching it is already
authorized, and never sees MinIO credentials leave the server.
"""
from __future__ import annotations

import json
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from . import datasets, engine, workspace
from .config import config
from .sqlsafety import UnsafeSQL
from .workspace import WorkspaceError

BASE = config.base_path.rstrip("/")
STATIC_DIR = Path(__file__).resolve().parent / "static"

app = FastAPI(title="QuerySkiff", docs_url=None, redoc_url=None, openapi_url=None)
api = FastAPI(title="QuerySkiff API", docs_url=None, redoc_url=None, openapi_url=None)


@api.get("/datasets")
def list_datasets():
    return {"datasets": datasets.list_datasets()}


def _resolve(dataset_id: str) -> datasets.Dataset:
    try:
        return datasets.resolve_id(dataset_id)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


@api.get("/datasets/{dataset_id}/schema")
def dataset_schema(dataset_id: str):
    ds = _resolve(dataset_id)
    try:
        return {"schema": engine.schema_of(ds)}
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=502,
                            detail=f"could not read schema: {datasets.redact(str(exc))}") from exc


@api.get("/datasets/{dataset_id}/metadata")
def dataset_metadata(dataset_id: str):
    ds = _resolve(dataset_id)
    try:
        return datasets.object_metadata(ds)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=502,
                            detail=f"could not read metadata: {datasets.redact(str(exc))}") from exc


def _entries_from_body(body: dict) -> list[tuple[datasets.Dataset, str]]:
    """HEL-112: `datasets: [{dataset_id, alias}]` workspace payload, or the
    legacy `dataset_id` (implicit alias `data`). Both validated server-side."""
    ws = body.get("datasets")
    if ws is not None:
        entries = workspace.resolve_entries(ws)
        return [(e.dataset, e.alias) for e in entries]
    dataset_id = body.get("dataset_id")
    if not dataset_id:
        raise HTTPException(status_code=400, detail="dataset_id or datasets required")
    return [(_resolve(dataset_id), "data")]


@api.post("/queries")
async def submit_query(request: Request):
    body = await request.json() or {}
    sql = body.get("sql", "")
    try:
        entries = _entries_from_body(body)
    except WorkspaceError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    try:
        q = engine.create_query(entries, sql)
    except UnsafeSQL as exc:
        raise HTTPException(status_code=400, detail=f"unsafe SQL: {exc}") from exc
    return {"query_id": q.id, "status": q.status}


@api.post("/workspace/hints")
async def workspace_hints(request: Request):
    """Join-column compatibility hints + editable starter JOIN SQL for a
    workspace. Read-only; schemas resolved server-side per dataset."""
    body = await request.json() or {}
    try:
        entries = workspace.resolve_entries(body.get("datasets") or [])
    except WorkspaceError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    schemas: dict[str, list[dict]] = {}
    for e in entries:
        try:
            schemas[e.alias] = engine.schema_of(e.dataset)
        except Exception as exc:  # noqa: BLE001
            raise HTTPException(
                status_code=502,
                detail=f"could not read schema for alias {e.alias!r}: "
                       f"{datasets.redact(str(exc))}") from exc
    hints = workspace.join_hints(schemas)
    return {"hints": hints,
            "starter_sql": workspace.starter_join_sql(entries, hints),
            "schemas": schemas}


def _query_or_404(query_id: str) -> engine.Query:
    q = engine.get_query(query_id)
    if not q:
        raise HTTPException(status_code=404, detail="unknown query")
    return q


@api.get("/queries/{query_id}")
def query_status(query_id: str):
    q = _query_or_404(query_id)
    return {"query_id": q.id, "status": q.status, "error": q.error,
            "row_count": q.row_count, "truncated": q.truncated}


@api.get("/queries/{query_id}/results")
def query_results(query_id: str):
    q = _query_or_404(query_id)
    if q.status == "error":
        raise HTTPException(status_code=400, detail=q.error or "query failed")
    return {"query_id": q.id, "status": q.status, "columns": q.columns,
            "rows": q.rows, "row_count": q.row_count, "truncated": q.truncated}


@api.delete("/queries/{query_id}")
def cancel(query_id: str):
    ok = engine.cancel_query(query_id)
    return {"cancelled": ok}


@api.get("/health")
def health():
    return {"ok": True}


# mount the API under {base}/api and the SPA under {base}
app.mount(f"{BASE}/api", api)

if STATIC_DIR.exists():
    app.mount(f"{BASE}/assets", StaticFiles(directory=STATIC_DIR / "assets"), name="assets")


@app.get(f"{BASE}/health")
def root_health():
    return {"ok": True}


@app.get("/{full_path:path}")
def spa(full_path: str):
    """Serve index.html for any non-API path so client-side routes survive a
    refresh. 404 for anything if the frontend isn't built in."""
    index = STATIC_DIR / "index.html"
    if not index.exists():
        return JSONResponse({"error": "frontend not built"}, status_code=404)
    return FileResponse(index)
