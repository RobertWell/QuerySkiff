"""Multi-dataset query workspace (HEL-112).

A workspace is a per-request list of (dataset, alias) entries. The browser
sends opaque dataset ids + aliases; the server resolves each id (re-validated
against the allow-list — a forged id fails exactly like the single-dataset
path), validates every alias, and exposes each dataset to SQL ONLY under its
alias. Storage paths and credentials never appear in the SQL or the browser.

Aliases become CTE/view names, so they must be plain lowercase identifiers and
must not collide with each other or shadow SQL keywords in confusing ways.
The legacy single-dataset flow is the degenerate one-entry workspace with the
implicit alias `data`.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from . import datasets
from .datasets import Dataset

_ALIAS_RE = re.compile(r"^[a-z][a-z0-9_]{0,29}$")
# names that would confuse validation or shadow engine namespaces
_RESERVED = {
    "select", "from", "where", "join", "on", "with", "as", "group", "order",
    "by", "limit", "union", "all", "distinct", "having", "case", "when",
    "then", "else", "end", "and", "or", "not", "null", "true", "false",
    "table", "values", "minio", "pg", "oracle", "system",
}
MAX_DATASETS = 8


class WorkspaceError(ValueError):
    pass


@dataclass(frozen=True)
class Entry:
    dataset: Dataset
    alias: str


def resolve_entries(payload: list[dict]) -> list[Entry]:
    """[{dataset_id, alias}] -> validated entries. Raises WorkspaceError with a
    browser-safe message on any forged id, bad alias, or collision."""
    if not payload:
        raise WorkspaceError("workspace needs at least one dataset")
    if len(payload) > MAX_DATASETS:
        raise WorkspaceError(f"at most {MAX_DATASETS} datasets per workspace")
    entries: list[Entry] = []
    seen_aliases: set[str] = set()
    seen_ids: set[str] = set()
    for item in payload:
        alias = str(item.get("alias", "")).strip().lower()
        if not _ALIAS_RE.match(alias):
            raise WorkspaceError(
                f"invalid alias {alias!r} (lowercase letters/digits/underscore, "
                f"must start with a letter, max 30 chars)")
        if alias in _RESERVED:
            raise WorkspaceError(f"alias {alias!r} is reserved")
        if alias in seen_aliases:
            raise WorkspaceError(f"duplicate alias {alias!r}")
        try:
            ds = datasets.resolve_id(str(item.get("dataset_id", "")))
        except ValueError as exc:
            raise WorkspaceError(f"dataset for alias {alias!r}: {exc}") from exc
        if ds.dataset_id in seen_ids:
            raise WorkspaceError(f"dataset appears twice (alias {alias!r})")
        seen_aliases.add(alias)
        seen_ids.add(ds.dataset_id)
        entries.append(Entry(dataset=ds, alias=alias))
    return entries


def join_hints(schemas: dict[str, list[dict]]) -> list[dict]:
    """Column-compatibility hints across workspace datasets: same column name in
    two aliases, with a type-compatibility note. Hints only — never enforced."""
    _NUM = {"tinyint", "smallint", "integer", "bigint", "float", "double",
            "real", "decimal", "hugeint", "ubigint", "uinteger"}

    def kind(t: str) -> str:
        t = t.lower().split("(")[0].strip()
        if t in _NUM:
            return "numeric"
        if "timestamp" in t or t == "date":
            return "temporal"
        if "char" in t or t in ("varchar", "text", "string"):
            return "text"
        return t

    cols: dict[str, list[tuple[str, str]]] = {}
    for alias, schema in schemas.items():
        for col in schema:
            name = str(col.get("column_name") or col.get("name") or "").lower()
            ctype = str(col.get("column_type") or col.get("type") or "")
            if name:
                cols.setdefault(name, []).append((alias, ctype))
    hints = []
    for name, owners in sorted(cols.items()):
        if len(owners) < 2:
            continue
        kinds = {kind(t) for _, t in owners}
        hints.append({
            "column": name,
            "aliases": [{"alias": a, "type": t} for a, t in owners],
            "compatible": len(kinds) == 1,
            "note": None if len(kinds) == 1 else
                    f"type kinds differ ({', '.join(sorted(kinds))}) — cast before joining",
        })
    return hints


def starter_join_sql(entries: list[Entry], hints: list[dict]) -> str:
    """Editable starter SQL: joins the first two aliases on the best hint
    (compatible common column), else a cross-join skeleton with a TODO."""
    if len(entries) < 2:
        return f"SELECT * FROM {entries[0].alias} LIMIT 100"
    a, b = entries[0].alias, entries[1].alias
    best = next((h for h in hints if h["compatible"]
                 and {x["alias"] for x in h["aliases"]} >= {a, b}), None)
    if best:
        c = best["column"]
        return (f"SELECT *\nFROM {a}\nJOIN {b} ON {a}.{c} = {b}.{c}\nLIMIT 100")
    return (f"SELECT *\nFROM {a}\nJOIN {b} ON /* TODO: join condition */ 1=1\nLIMIT 100")
