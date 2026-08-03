import Editor from "@monaco-editor/react";
import type { ColDef } from "ag-grid-community";
import { AgGridReact } from "ag-grid-react";
import "ag-grid-community/styles/ag-grid.css";
import "ag-grid-community/styles/ag-theme-quartz.css";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  DatasetInfo, JoinHint, QueryResults, SchemaCol, WorkspaceEntry,
  cancelQuery, getMetadata, getSchema, listDatasets, queryResults, queryStatus,
  submitQuery, submitWorkspaceQuery, workspaceHints,
  listVirtualDatasets, saveVirtualDataset, deleteVirtualDataset, type VirtualDatasetInfo,
} from "./api";

const STARTER_SQL = "SELECT *\nFROM data\nLIMIT 500;";

// derive a valid, unique alias (^[a-z][a-z0-9_]{0,29}$) from a dataset name
export function aliasFor(name: string, taken: Set<string>): string {
  let base = name.toLowerCase().replace(/[^a-z0-9_]/g, "_").replace(/^[^a-z]+/, "");
  if (!base) base = "t";
  base = base.slice(0, 30);
  let a = base;
  let i = 1;
  while (taken.has(a)) a = `${base}_${i++}`.slice(0, 30);
  return a;
}

export function fmtBytes(n: number | null): string {
  if (n == null) return "";
  if (n > 1 << 30) return `${(n / (1 << 30)).toFixed(1)} GB`;
  if (n > 1 << 20) return `${(n / (1 << 20)).toFixed(1)} MB`;
  if (n > 1 << 10) return `${(n / (1 << 10)).toFixed(1)} KB`;
  return `${n} B`;
}

// HEL-121: a workspace entry is keyed by whichever id it carries.
export const keyOf = (e: { dataset_id?: string; virtual_id?: string }) =>
  e.dataset_id ?? e.virtual_id ?? "";

export default function App() {
  const [datasets, setDatasets] = useState<DatasetInfo[]>([]);
  const [dsError, setDsError] = useState("");
  const [selected, setSelected] = useState<DatasetInfo | null>(null);
  const [schema, setSchema] = useState<SchemaCol[]>([]);
  const [meta, setMeta] = useState<Record<string, unknown> | null>(null);
  const [sql, setSql] = useState(STARTER_SQL);
  const [running, setRunning] = useState(false);
  const [queryId, setQueryId] = useState<string | null>(null);
  const [results, setResults] = useState<QueryResults | null>(null);
  const [error, setError] = useState("");
  const [filter, setFilter] = useState("");
  // HEL-112 workspace: entries (dataset + alias). Empty => single-dataset mode.
  const [workspace, setWorkspace] = useState<(WorkspaceEntry & { name: string })[]>([]);
  const [hints, setHints] = useState<JoinHint[]>([]);
  // HEL-121: saved virtual datasets (file selections) for browse/load/delete.
  const [virtuals, setVirtuals] = useState<VirtualDatasetInfo[]>([]);
  const [vdMsg, setVdMsg] = useState("");
  // HEL-148: schema policy chosen at save time. UNION_BY_NAME merges columns by
  // name across files; STRICT requires every member's schema to match exactly.
  const [schemaPolicy, setSchemaPolicy] = useState<"STRICT" | "UNION_BY_NAME">("UNION_BY_NAME");
  // HEL-150: elapsed-time feedback while a query runs + a reason when join hints
  // can't load.
  const [elapsedMs, setElapsedMs] = useState(0);
  const [hintsMsg, setHintsMsg] = useState("");
  const pollRef = useRef<number | null>(null);   // status poll (setTimeout id)
  const tickRef = useRef<number | null>(null);    // elapsed-display ticker (setInterval id)
  const startRef = useRef<number>(0);
  const wsMode = workspace.length > 0;

  useEffect(() => {
    listDatasets()
      .then((d) => setDatasets(d.datasets))
      .catch((e) => setDsError(`could not list datasets: ${e.message}`));
    loadVirtuals();
  }, []);

  const loadVirtuals = useCallback(() => {
    listVirtualDatasets().then((d) => setVirtuals(d.virtual_datasets)).catch(() => {});
  }, []);

  const saveWorkspaceAsVirtual = useCallback(async () => {
    const ids = workspace.filter((e) => e.dataset_id).map((e) => e.dataset_id!);
    if (ids.length === 0) return;
    const name = window.prompt("Name this saved dataset:", `dataset (${ids.length} files)`);
    if (!name) return;
    setVdMsg("");
    try {
      const rec = await saveVirtualDataset(name, ids, schemaPolicy);
      loadVirtuals();
      setVdMsg(`saved "${rec.display_name}"${rec.warnings?.length ? " — " + rec.warnings[0] : ""}`);
    } catch (e) {
      setVdMsg(`save failed: ${(e as Error).message}`);
    }
  }, [workspace, loadVirtuals, schemaPolicy]);

  const openVirtual = useCallback((v: VirtualDatasetInfo) => {
    setSelected(null); setResults(null); setError(""); setVdMsg("");
    setWorkspace([{ virtual_id: v.id, alias: "data", name: v.display_name }]);
  }, []);

  const removeVirtual = useCallback(async (id: string) => {
    await deleteVirtualDataset(id).catch(() => {});
    loadVirtuals();
  }, [loadVirtuals]);

  const select = useCallback((ds: DatasetInfo) => {
    setSelected(ds);
    setSchema([]);
    setMeta(null);
    setResults(null);
    setError("");
    getSchema(ds.dataset_id).then((s) => setSchema(s.schema)).catch((e) => setError(e.message));
    getMetadata(ds.dataset_id).then(setMeta).catch(() => setMeta(null));
  }, []);

  const addToWorkspace = useCallback((ds: DatasetInfo) => {
    setWorkspace((ws) => {
      if (ws.some((e) => e.dataset_id === ds.dataset_id)) return ws;
      const alias = aliasFor(ds.name, new Set(ws.map((e) => e.alias)));
      return [...ws, { dataset_id: ds.dataset_id, alias, name: ds.name }];
    });
    setResults(null);
    setError("");
  }, []);

  const removeFromWorkspace = useCallback((id: string) => {
    setWorkspace((ws) => ws.filter((e) => keyOf(e) !== id));
    setHints([]);
  }, []);

  const setAlias = useCallback((id: string, alias: string) => {
    setWorkspace((ws) => ws.map((e) => (keyOf(e) === id ? { ...e, alias } : e)));
  }, []);

  // fetch join hints + starter SQL whenever the workspace set changes (aliases
  // valid and >=2 datasets). Debounced so alias typing doesn't spam the server.
  useEffect(() => {
    if (workspace.length < 2 || workspace.some((e) => e.virtual_id)) { setHints([]); setHintsMsg(""); return; }
    const entries = workspace.map(({ dataset_id, alias }) => ({ dataset_id, alias }));
    // HEL-150: don't silently drop hints on an invalid/duplicate alias — say why.
    const bad = entries.find((e) => !/^[a-z][a-z0-9_]{0,29}$/.test(e.alias));
    if (bad) {
      setHints([]);
      setHintsMsg(`join hints unavailable — alias "${bad.alias || "(empty)"}" is invalid `
        + `(lowercase letter, then letters/digits/underscore)`);
      return;
    }
    const dup = entries.map((e) => e.alias).find((a, i, arr) => arr.indexOf(a) !== i);
    if (dup) {
      setHints([]);
      setHintsMsg(`join hints unavailable — alias "${dup}" is used by more than one dataset`);
      return;
    }
    setHintsMsg("");
    const t = window.setTimeout(() => {
      workspaceHints(entries)
        .then((h) => { setHints(h.hints); if (sql === STARTER_SQL || !sql.trim()) setSql(h.starter_sql); })
        .catch((e) => setError(e.message));
    }, 300);
    return () => window.clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspace]);

  const stopPolling = () => {
    if (pollRef.current != null) window.clearTimeout(pollRef.current);
    if (tickRef.current != null) window.clearInterval(tickRef.current);
    pollRef.current = null;
    tickRef.current = null;
  };

  const run = useCallback(async () => {
    if (running || (!wsMode && !selected)) return;
    setRunning(true);
    setError("");
    setResults(null);
    setElapsedMs(0);
    startRef.current = Date.now();
    // lightweight ticker so the elapsed readout is smooth regardless of poll cadence
    tickRef.current = window.setInterval(() => setElapsedMs(Date.now() - startRef.current), 250);
    try {
      const { query_id } = wsMode
        ? await submitWorkspaceQuery(
            workspace.map((e) => e.virtual_id
              ? { virtual_id: e.virtual_id, alias: e.alias }
              : { dataset_id: e.dataset_id!, alias: e.alias }), sql)
        : await submitQuery(selected!.dataset_id, sql);
      setQueryId(query_id);
      // HEL-150: recursive poll that BACKS OFF after 10s (500ms → 2s) so a long
      // query doesn't hammer the status endpoint indefinitely at 500ms.
      const poll = async () => {
        try {
          const st = await queryStatus(query_id);
          if (st.status === "done") {
            stopPolling();
            setResults(await queryResults(query_id));
            setRunning(false);
          } else if (st.status === "error" || st.status === "cancelled") {
            stopPolling();
            setError(st.status === "error" ? st.error || "query failed" : "cancelled");
            setRunning(false);
          } else {
            const delay = Date.now() - startRef.current < 10_000 ? 500 : 2_000;
            pollRef.current = window.setTimeout(poll, delay);
          }
        } catch (e) {
          stopPolling();
          setError((e as Error).message);
          setRunning(false);
        }
      };
      pollRef.current = window.setTimeout(poll, 500);
    } catch (e) {
      stopPolling();
      setError((e as Error).message);
      setRunning(false);
    }
  }, [selected, sql, running, wsMode, workspace]);

  const cancel = useCallback(() => {
    if (queryId) cancelQuery(queryId).catch(() => undefined);
  }, [queryId]);

  useEffect(() => stopPolling, []);

  const columnDefs = useMemo<ColDef<unknown[]>[]>(
    () => (results?.columns || []).map((c, i) => ({
      headerName: c,
      valueGetter: (p) => (p.data ? p.data[i] : undefined),
      sortable: true,
      resizable: true,
    })),
    [results],
  );

  const shown = datasets.filter((d) => d.name.toLowerCase().includes(filter.toLowerCase()));

  return (
    <div style={{ display: "flex", height: "100vh", fontFamily: "system-ui, sans-serif" }}>
      {/* dataset browser */}
      <aside style={{ width: 320, borderRight: "1px solid #ddd", display: "flex",
                      flexDirection: "column", minWidth: 240 }}>
        <div style={{ padding: "10px 12px", borderBottom: "1px solid #eee" }}>
          <b>QuerySkiff</b> <span style={{ color: "#888", fontSize: 12 }}>parquet SQL viewer</span>
          <input
            placeholder="filter datasets…"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            style={{ width: "100%", marginTop: 8, padding: 6, boxSizing: "border-box" }}
          />
        </div>
        <div style={{ overflowY: "auto", flex: 1 }}>
          {dsError && <div style={{ color: "#b91c1c", padding: 12, fontSize: 13 }}>{dsError}</div>}
          {shown.map((d) => {
            const inWs = workspace.some((e) => e.dataset_id === d.dataset_id);
            return (
              <div
                key={d.dataset_id}
                style={{
                  padding: "8px 12px", fontSize: 13, display: "flex", gap: 6,
                  alignItems: "flex-start",
                  background: selected?.dataset_id === d.dataset_id ? "#eef2ff" : undefined,
                  borderBottom: "1px solid #f5f5f5",
                }}
              >
                <div style={{ flex: 1, cursor: "pointer", minWidth: 0 }} onClick={() => select(d)}>
                  <div style={{ wordBreak: "break-all" }}>
                    {d.kind === "folder" ? "📁" : "📄"} {d.name}
                  </div>
                  <div style={{ color: "#999", fontSize: 11 }}>
                    {fmtBytes(d.size)} {d.modified ? `· ${d.modified.slice(0, 16)}` : ""}
                  </div>
                </div>
                <button
                  title={inWs ? "in join workspace" : "add to join workspace"}
                  onClick={() => (inWs ? removeFromWorkspace(d.dataset_id) : addToWorkspace(d))}
                  style={{ border: "1px solid #ddd", borderRadius: 4, cursor: "pointer",
                           background: inWs ? "#4f46e5" : "#fff", color: inWs ? "#fff" : "#4f46e5",
                           width: 24, height: 24, lineHeight: 1, fontSize: 15, flexShrink: 0 }}
                >
                  {inWs ? "✓" : "+"}
                </button>
              </div>
            );
          })}
          {!shown.length && !dsError && (
            <div style={{ color: "#888", padding: 12, fontSize: 13 }}>no parquet datasets found</div>
          )}
        </div>
        {selected && (
          <div style={{ borderTop: "1px solid #ddd", maxHeight: "40%", overflowY: "auto",
                        padding: "8px 12px", fontSize: 12 }}>
            <b>schema</b> {meta && (meta as { size?: number }).size != null &&
              <span style={{ color: "#888" }}>· {fmtBytes((meta as { size: number }).size)}</span>}
            <table style={{ width: "100%", marginTop: 6, borderCollapse: "collapse" }}>
              <tbody>
                {schema.map((c) => (
                  <tr key={c.column_name}>
                    <td style={{ padding: "1px 4px 1px 0" }}>{c.column_name}</td>
                    <td style={{ color: "#6b21a8" }}>{c.column_type}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      {virtuals.length > 0 && (
        <div style={{ borderTop: "1px solid #eee", padding: "8px 10px" }}>
          <div style={{ fontSize: 12, fontWeight: 600, color: "#555", marginBottom: 4 }}>
            saved datasets
          </div>
          {virtuals.map((v) => (
            <div key={v.id} style={{ display: "flex", alignItems: "center", gap: 4,
                                     fontSize: 12, padding: "2px 0" }}>
              <button onClick={() => openVirtual(v)} title="query this saved dataset"
                      style={{ flex: 1, textAlign: "left", border: "none", background: "none",
                               cursor: "pointer", color: "#4338ca", overflow: "hidden",
                               textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                🗂 {v.display_name} <span style={{ color: "#999" }}>({v.member_count})</span>
              </button>
              <button onClick={() => removeVirtual(v.id)} title="delete"
                      style={{ border: "none", background: "none", cursor: "pointer",
                               color: "#dc2626", fontSize: 15, lineHeight: 1 }}>×</button>
            </div>
          ))}
        </div>
      )}
      {vdMsg && <div style={{ fontSize: 11, color: "#555", padding: "0 10px 6px" }}>{vdMsg}</div>}
      </aside>

      {/* editor + results */}
      <main style={{ flex: 1, display: "flex", flexDirection: "column", minWidth: 0 }}>
        {wsMode && (
          <div style={{ borderBottom: "1px solid #ddd", padding: "8px 12px", background: "#fafafe" }}>
            <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
              <b style={{ fontSize: 13 }}>join workspace</b>
              {workspace.map((e) => {
                const badAlias = !/^[a-z][a-z0-9_]{0,29}$/.test(e.alias);
                const dup = workspace.filter((x) => x.alias === e.alias).length > 1;
                return (
                  <span key={keyOf(e)}
                        style={{ display: "inline-flex", alignItems: "center", gap: 4,
                                 border: `1px solid ${badAlias || dup ? "#dc2626" : "#c7d2fe"}`,
                                 borderRadius: 6, padding: "2px 4px 2px 8px", fontSize: 12,
                                 background: "#fff" }}>
                    <input value={e.alias} onChange={(ev) => setAlias(keyOf(e), ev.target.value)}
                           data-testid="ws-alias"
                           style={{ border: "none", outline: "none", width: 90, fontSize: 12,
                                    fontFamily: "monospace", color: "#4338ca" }} />
                    <span style={{ color: "#999" }}>= {e.name}</span>
                    <button onClick={() => removeFromWorkspace(keyOf(e))}
                            style={{ border: "none", background: "none", cursor: "pointer",
                                     color: "#999", fontSize: 14 }}>×</button>
                  </span>
                );
              })}
              <select value={schemaPolicy} data-testid="schema-policy"
                      onChange={(e) => setSchemaPolicy(e.target.value as "STRICT" | "UNION_BY_NAME")}
                      title={schemaPolicy === "STRICT"
                        ? "STRICT: every file's schema must match exactly"
                        : "UNION BY NAME: columns merged by name; missing ones become NULL"}
                      style={{ fontSize: 11, border: "1px solid #c7d2fe", borderRadius: 6,
                               padding: "2px 4px", background: "#fff", color: "#4338ca" }}>
                <option value="UNION_BY_NAME">union by name</option>
                <option value="STRICT">strict schema</option>
              </select>
              <button onClick={saveWorkspaceAsVirtual}
                      disabled={workspace.some((e) => e.virtual_id)}
                      title={workspace.some((e) => e.virtual_id) ? "already a saved dataset" : "save this selection"}
                      style={{ padding: "2px 8px", cursor: "pointer", fontSize: 12,
                               border: "1px solid #c7d2fe", borderRadius: 6, background: "#eef2ff" }}>
                💾 save as dataset
              </button>
              <button onClick={() => { setWorkspace([]); setHints([]); }}
                      style={{ marginLeft: "auto", fontSize: 12, cursor: "pointer",
                               border: "1px solid #ddd", borderRadius: 4, padding: "2px 8px" }}>
                clear
              </button>
            </div>
            {hintsMsg && (
              <div style={{ marginTop: 6, fontSize: 12, color: "#b45309" }}>{hintsMsg}</div>
            )}
            {hints.length > 0 && (
              <div style={{ marginTop: 6, fontSize: 12, color: "#555" }}>
                shared columns:{" "}
                {hints.map((h) => (
                  <span key={h.column} title={h.note || "compatible"}
                        style={{ marginRight: 8,
                                 color: h.compatible ? "#166534" : "#b45309" }}>
                    {h.column}{h.compatible ? "" : " ⚠"}
                  </span>
                ))}
              </div>
            )}
          </div>
        )}
        <div style={{ height: 180, borderBottom: "1px solid #ddd" }}>
          <Editor
            language="sql"
            value={sql}
            onChange={(v) => setSql(v ?? "")}
            options={{ minimap: { enabled: false }, fontSize: 13, lineNumbers: "on",
                       scrollBeyondLastLine: false, automaticLayout: true }}
          />
        </div>
        <div style={{ padding: "8px 12px", display: "flex", gap: 8, alignItems: "center",
                      borderBottom: "1px solid #eee" }}>
          <button onClick={run} disabled={running || (!wsMode && !selected)}
                  style={{ padding: "6px 18px", cursor: "pointer" }}>
            {running ? `running… ${(elapsedMs / 1000).toFixed(1)}s` : "Run"}
          </button>
          {running && (
            <button onClick={cancel} style={{ padding: "6px 12px", cursor: "pointer" }}>
              Cancel
            </button>
          )}
          {wsMode && <span style={{ color: "#555", fontSize: 13 }}>
            join workspace: {workspace.length} datasets — query each by its alias</span>}
          {!wsMode && !selected && <span style={{ color: "#888", fontSize: 13 }}>select a dataset — it becomes the table <code>data</code> · <b>＋</b> two or more to join</span>}
          {!wsMode && selected && <span style={{ color: "#555", fontSize: 13 }}>table <code>data</code> = {selected.name}</span>}
          {results && (
            <span style={{ marginLeft: "auto", color: "#555", fontSize: 13 }}>
              {results.row_count} rows{results.truncated ? " (truncated)" : ""}
            </span>
          )}
        </div>
        {error && <div style={{ color: "#b91c1c", padding: "8px 12px", fontSize: 13 }}>{error}</div>}
        <div className="ag-theme-quartz" style={{ flex: 1 }}>
          <AgGridReact
            rowData={results?.rows || []}
            columnDefs={columnDefs}
            suppressFieldDotNotation
          />
        </div>
      </main>
    </div>
  );
}
