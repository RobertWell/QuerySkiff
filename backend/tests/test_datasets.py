"""Dataset id resolution + forged-id / injection defence (HEL-90)."""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from queryskiff import datasets
from queryskiff.config import config


def test_roundtrip_and_resolve_allowed():
    bucket = config.allowed_buckets[0]
    did = datasets.encode_id(bucket, "sub/dir/file.parquet")
    ds = datasets.resolve_id(did)
    assert ds.bucket == bucket and ds.key == "sub/dir/file.parquet"
    assert not ds.is_folder
    assert datasets.s3_uri(ds) == f"s3://{bucket}/sub/dir/file.parquet"


def test_folder_dataset_globs_parts():
    bucket = config.allowed_buckets[0]
    ds = datasets.resolve_id(datasets.encode_id(bucket, "parts/"))
    assert ds.is_folder
    assert datasets.s3_uri(ds) == f"s3://{bucket}/parts/*.parquet"


def test_rejects_bucket_outside_allowlist():
    with pytest.raises(ValueError):
        datasets.resolve_id(datasets.encode_id("secret-bucket", "x.parquet"))


def test_rejects_non_parquet():
    bucket = config.allowed_buckets[0]
    with pytest.raises(ValueError):
        datasets.resolve_id(datasets.encode_id(bucket, "passwords.txt"))


def test_rejects_traversal_and_absolute():
    bucket = config.allowed_buckets[0]
    for key in ("../etc/passwd.parquet", "/abs/path.parquet"):
        with pytest.raises(ValueError):
            datasets.resolve_id(datasets.encode_id(bucket, key))


@pytest.mark.parametrize("evil", [
    "x' ); DROP TABLE data;--.parquet",      # SQL-literal breakout attempt
    "a'||''.parquet",
    "with\nnewline.parquet",
    "quote\"key.parquet",
    "back\\slash.parquet",
])
def test_rejects_injection_chars_in_key(evil):
    bucket = config.allowed_buckets[0]
    with pytest.raises(ValueError):
        datasets.resolve_id(datasets.encode_id(bucket, evil))


def test_garbage_id_rejected():
    with pytest.raises(ValueError):
        datasets.resolve_id("not-valid-base64!!!")


def test_display_label_hides_bucket_and_path():
    # file: final segment, no extension, no path
    assert datasets.display_label("datasets/stock_history.parquet", False) == "stock_history"
    assert datasets.display_label("a/b/c/deep.parquet", False) == "deep"
    assert datasets.display_label("root.parquet", False) == "root"
    # folder: final segment of the prefix
    assert datasets.display_label("datasets/quarterly/", True) == "quarterly"
    # a resolved Dataset's label never contains the bucket or a slash
    bucket = config.allowed_buckets[0]
    ds = datasets.resolve_id(datasets.encode_id(bucket, "sub/dir/file.parquet"))
    assert ds.label == "file" and "/" not in ds.label and bucket not in ds.label


def test_redact_strips_s3_uri_and_bucket():
    bucket = config.allowed_buckets[0]
    msg = f"IO Error: Cannot open s3://{bucket}/sub/dir/file.parquet (Access Denied)"
    out = datasets.redact(msg)
    assert "s3://" not in out and bucket not in out and "file.parquet" not in out
    assert datasets.redact(None) == ""
    assert datasets.redact("") == ""
