import Editor from "@monaco-editor/react";
import type { ColDef } from "ag-grid-community";
import { AgGridReact } from "ag-grid-react";
import "ag-grid-community/styles/ag-grid.css";
import "ag-grid-community/styles/ag-theme-quartz.css";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  DatasetInfo, QueryResults, SchemaCol,
  cancelQuery, getMetadata, getSchema, listDatasets, queryResults, queryStatus, submitQuery,
} from "./api";

const STARTER_SQL = "SELECT *\nFROM data\nLIMIT 500;";

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
  const pollRef = useRef<number | null>(null);

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

  const stopPolling = () => {
    if (pollRef.current != null) window.clearInterval(pollRef.current);
    pollRef.current = null;
  };

  const run = useCallback(async () => {
    if (!selected || running) return;
    setRunning(true);
    setError("");
    setResults(null);
    try {
      const { query_id } = await submitQuery(selected.dataset_id, sql);
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
  }, [selected, sql, running]);

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
          {shown.map((d) => (
            <div
              key={d.dataset_id}
              onClick={() => select(d)}
              style={{
                padding: "8px 12px", cursor: "pointer", fontSize: 13,
                background: selected?.dataset_id === d.dataset_id ? "#eef2ff" : undefined,
                borderBottom: "1px solid #f5f5f5",
              }}
            >
              <div style={{ wordBreak: "break-all" }}>
                {d.kind === "folder" ? "📁" : "📄"} {d.name}
              </div>
              <div style={{ color: "#999", fontSize: 11 }}>
                {fmtBytes(d.size)} {d.modified ? `· ${d.modified.slice(0, 16)}` : ""}
              </div>
            </div>
          ))}
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
          <button onClick={run} disabled={!selected || running}
                  style={{ padding: "6px 18px", cursor: "pointer" }}>
            {running ? "running…" : "Run"}
          </button>
          {running && (
            <button onClick={cancel} style={{ padding: "6px 12px", cursor: "pointer" }}>
              Cancel
            </button>
          )}
          {!selected && <span style={{ color: "#888", fontSize: 13 }}>select a dataset — it becomes the table <code>data</code></span>}
          {selected && <span style={{ color: "#555", fontSize: 13 }}>table <code>data</code> = {selected.name}</span>}
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
