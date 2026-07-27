// QuerySkiff API client — everything under the gateway base path.
const API = "/queryskiff/api";

export interface DatasetInfo {
  dataset_id: string;
  name: string;
  bucket: string;
  kind: "file" | "folder";
  size: number | null;
  modified: string | null;
  parts?: number;
}

export interface SchemaCol {
  column_name: string;
  column_type: string;
  [k: string]: unknown;
}

export interface QueryStatus {
  query_id: string;
  status: "pending" | "running" | "done" | "error" | "cancelled";
  error: string | null;
  row_count: number;
  truncated: boolean;
}

export interface QueryResults extends QueryStatus {
  columns: string[];
  rows: unknown[][];
}

async function j<T>(resp: Response): Promise<T> {
  if (!resp.ok) {
    let detail = `${resp.status}`;
    try {
      const body = await resp.json();
      detail = body.detail || body.error || detail;
    } catch {
      /* keep status */
    }
    throw new Error(detail);
  }
  return resp.json() as Promise<T>;
}

export const listDatasets = () =>
  fetch(`${API}/datasets`).then((r) => j<{ datasets: DatasetInfo[] }>(r));

export const getSchema = (id: string) =>
  fetch(`${API}/datasets/${id}/schema`).then((r) => j<{ schema: SchemaCol[] }>(r));

export const getMetadata = (id: string) =>
  fetch(`${API}/datasets/${id}/metadata`).then((r) => j<Record<string, unknown>>(r));

export const submitQuery = (dataset_id: string, sql: string) =>
  fetch(`${API}/queries`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ dataset_id, sql }),
  }).then((r) => j<{ query_id: string; status: string }>(r));

export const queryStatus = (id: string) =>
  fetch(`${API}/queries/${id}`).then((r) => j<QueryStatus>(r));

export const queryResults = (id: string) =>
  fetch(`${API}/queries/${id}/results`).then((r) => j<QueryResults>(r));

export const cancelQuery = (id: string) =>
  fetch(`${API}/queries/${id}`, { method: "DELETE" }).then((r) => j<{ cancelled: boolean }>(r));
