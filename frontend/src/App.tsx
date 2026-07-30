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
} from "./api";

const STARTER_SQL = "SELECT *\nFROM data\nLIMIT 500;";

// derive a valid, unique alias (^[a-z][a-z0-9_]{0,29}$) from a dataset name
function aliasFor(name: string, taken: Set<string>): string {
  let base = name.toLowerCase().replace(/[^a-z0-9_]/g, "_").replace(/^[^a-z]+/, "");
  if (!base) base = "t";
  base = base.slice(0, 30);
  let a = base;
  let i = 1;
  while (taken.has(a)) a = `${base}_${i++}`.slice(0, 30);
  return a;
}

function fmtBytes(n: number | null): string {
  if (n == null) return "";
  if (n > 1 << 30) return `${(n / (1 << 30)).toFixed(1)} GB`;
  if (n > 1 << 20) return `${(n / (1 << 20)).toFixed(1)} MB`;
  if (n > 1 << 10) return `${(n / (1 << 10)).toFixed(1)} KB`;
  return `${n} B`;
}

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
  const pollRef = useRef<number | null>(null);
  const wsMode = workspace.length > 0;

  useEffect(() => {
    listDatasets()
      .then((d) => setDatasets(d.datasets))
      .catch((e) => setDsError(`could not list datasets: ${e.message}`));
  }, []);

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
    setWorkspace((ws) => ws.filter((e) => e.dataset_id !== id));
    setHints([]);
  }, []);

  const setAlias = useCallback((id: string, alias: string) => {
    setWorkspace((ws) => ws.map((e) => (e.dataset_id === id ? { ...e, alias } : e)));
  }, []);

  // fetch join hints + starter SQL whenever the workspace set changes (aliases
  // valid and >=2 datasets). Debounced so alias typing doesn't spam the server.
  useEffect(() => {
    if (workspace.length < 2) { setHints([]); return; }
    const entries = workspace.map(({ dataset_id, alias }) => ({ dataset_id, alias }));
    const valid = entries.every((e) => /^[a-z][a-z0-9_]{0,29}$/.test(e.alias)) &&
      new Set(entries.map((e) => e.alias)).size === entries.length;
    if (!valid) return;
    const t = window.setTimeout(() => {
      workspaceHints(entries)
        .then((h) => { setHints(h.hints); if (sql === STARTER_SQL || !sql.trim()) setSql(h.starter_sql); })
        .catch((e) => setError(e.message));
    }, 300);
    return () => window.clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspace]);

  const stopPolling = () => {
    if (pollRef.current != null) window.clearInterval(pollRef.current);
    pollRef.current = null;
  };

  const run = useCallback(async () => {
    if (running || (!wsMode && !selected)) return;
    setRunning(true);
    setError("");
    setResults(null);
    try {
      const { query_id } = wsMode
        ? await submitWorkspaceQuery(
            workspace.map(({ dataset_id, alias }) => ({ dataset_id, alias })), sql)
        : await submitQuery(selected!.dataset_id, sql);
      setQueryId(query_id);
      pollRef.current = window.setInterval(async () => {
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
          }
        } catch (e) {
          stopPolling();
          setError((e as Error).message);
          setRunning(false);
        }
      }, 500);
    } catch (e) {
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
                  <span key={e.dataset_id}
                        style={{ display: "inline-flex", alignItems: "center", gap: 4,
                                 border: `1px solid ${badAlias || dup ? "#dc2626" : "#c7d2fe"}`,
                                 borderRadius: 6, padding: "2px 4px 2px 8px", fontSize: 12,
                                 background: "#fff" }}>
                    <input value={e.alias} onChange={(ev) => setAlias(e.dataset_id, ev.target.value)}
                           style={{ border: "none", outline: "none", width: 90, fontSize: 12,
                                    fontFamily: "monospace", color: "#4338ca" }} />
                    <span style={{ color: "#999" }}>= {e.name}</span>
                    <button onClick={() => removeFromWorkspace(e.dataset_id)}
                            style={{ border: "none", background: "none", cursor: "pointer",
                                     color: "#999", fontSize: 14 }}>×</button>
                  </span>
                );
              })}
              <button onClick={() => { setWorkspace([]); setHints([]); }}
                      style={{ marginLeft: "auto", fontSize: 12, cursor: "pointer",
                               border: "1px solid #ddd", borderRadius: 4, padding: "2px 8px" }}>
                clear
              </button>
            </div>
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
            {running ? "running…" : "Run"}
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
