"""Dataset discovery + opaque id resolution (HEL-90).

A dataset is a Parquet object (or a folder of Parquet parts) inside an ALLOWED
MinIO bucket. The browser only ever sees an opaque `dataset_id`; the real
bucket/key stays server-side and is exposed to SQL solely as the table `data`.

The id is a reversible encoding, but resolution ALWAYS re-checks the bucket
against the allow-list and the key shape — so a forged id can't escape the
allowed buckets or point at a non-parquet object.
"""
from __future__ import annotations

import base64
import re
from dataclasses import dataclass

from minio import Minio

from .config import config

_SEP = "\x00"
# object keys must be plain S3-style paths — no quotes/backslashes/control chars
# that could matter to any downstream consumer (defence in depth alongside the
# non-interpolated read_parquet path in engine.py).
_SAFE_KEY = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._\-/=]*$")


@dataclass(frozen=True)
class Dataset:
    dataset_id: str
    bucket: str
    key: str          # object key, or prefix ending in / for a folder dataset
    is_folder: bool

    @property
    def label(self) -> str:
        """A logical, browser-safe display name (HEL-90) — the final path segment
        only, never the bucket or the internal key structure."""
        return display_label(self.key, self.is_folder)


def display_label(key: str, is_folder: bool) -> str:
    """Logical display label from an object key: the final segment, minus the
    `.parquet` extension for files. No bucket, no internal path — so the browser
    never learns the storage layout."""
    k = key.rstrip("/")
    base = k.rsplit("/", 1)[-1] if "/" in k else k
    if not is_folder and base.lower().endswith(".parquet"):
        base = base[: -len(".parquet")]
    return base or "dataset"


_S3_RE = re.compile(r"s3://[^\s'\"]+")


def redact(msg: str | None) -> str:
    """Strip internal storage identifiers (s3://bucket/key and bare bucket names)
    from any error text before it reaches the browser (HEL-90). Defence in depth
    so a DuckDB/MinIO error can't leak the path the opaque id hides."""
    if not msg:
        return msg or ""
    out = _S3_RE.sub("<dataset>", msg)
    for b in config.allowed_buckets:
        out = out.replace(b, "<dataset>")
    return out


def _client() -> Minio:
    return Minio(config.minio_endpoint, access_key=config.minio_access_key,
                 secret_key=config.minio_secret_key, secure=config.minio_secure)


def encode_id(bucket: str, key: str) -> str:
    raw = f"{bucket}{_SEP}{key}".encode()
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def resolve_id(dataset_id: str) -> Dataset:
    """Opaque id -> Dataset, RE-VALIDATED against the allow-list. Raises
    ValueError on anything outside policy (forged/expired/illegal id)."""
    try:
        pad = "=" * (-len(dataset_id) % 4)
        raw = base64.urlsafe_b64decode(dataset_id + pad).decode()
        bucket, key = raw.split(_SEP, 1)
    except Exception as exc:  # noqa: BLE001
        raise ValueError("invalid dataset id") from exc
    if bucket not in config.allowed_buckets:
        raise ValueError("dataset not in an allowed bucket")
    if ".." in key or not _SAFE_KEY.match(key):
        raise ValueError("illegal key")
    is_folder = key.endswith("/")
    if not is_folder and not key.lower().endswith(".parquet"):
        raise ValueError("not a parquet dataset")
    return Dataset(dataset_id, bucket, key, is_folder)


def s3_uri(ds: Dataset) -> str:
    """The read_parquet target the SERVER (never the user) uses. A folder
    dataset globs its parts."""
    if ds.is_folder:
        return f"s3://{ds.bucket}/{ds.key}*.parquet"
    return f"s3://{ds.bucket}/{ds.key}"


def list_datasets() -> list[dict]:
    """Every parquet object across the allowed buckets, plus one folder-dataset
    entry per prefix that holds multiple parts."""
    client = _client()
    out: list[dict] = []
    folders: dict[tuple[str, str], int] = {}
    for bucket in config.allowed_buckets:
        try:
            # materialize inside the try — list_objects is lazy, so an
            # AccessDenied/missing-bucket error surfaces during iteration, not at
            # the call. Skip such buckets rather than 500 the whole listing.
            objects = list(client.list_objects(bucket, recursive=True))
        except Exception:  # noqa: BLE001
            continue
        for obj in objects:
            if not obj.object_name.lower().endswith(".parquet"):
                continue
            out.append({
                "dataset_id": encode_id(bucket, obj.object_name),
                # browser-safe: opaque id + logical label only — no bucket, no key
                "name": display_label(obj.object_name, False),
                "kind": "file",
                "size": obj.size,
                "modified": obj.last_modified.isoformat() if obj.last_modified else None,
            })
            prefix = obj.object_name.rsplit("/", 1)[0] + "/" if "/" in obj.object_name else ""
            if prefix:
                folders[(bucket, prefix)] = folders.get((bucket, prefix), 0) + 1
    for (bucket, prefix), n in folders.items():
        if n > 1:
            out.append({
                "dataset_id": encode_id(bucket, prefix),
                "name": f"{display_label(prefix, True)} ({n} parts)",
                "kind": "folder", "parts": n,
                "size": None, "modified": None,
            })
    out.sort(key=lambda d: d["name"])
    return out


def object_metadata(ds: Dataset) -> dict:
    if ds.is_folder:
        return {"kind": "folder", "name": ds.label}
    client = _client()
    st = client.stat_object(ds.bucket, ds.key)
    # browser-safe metadata only: logical label + size/modified. No bucket, key,
    # etag (internal storage id) or object path (HEL-90).
    return {
        "kind": "file", "name": ds.label,
        "size": st.size,
        "modified": st.last_modified.isoformat() if st.last_modified else None,
        "content_type": st.content_type,
    }
