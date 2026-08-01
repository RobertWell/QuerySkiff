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

// HEL-112: workspace of {dataset_id, alias} entries joined with ordinary SQL.
// HEL-121: an entry may reference a saved virtual dataset via `virtual_id`
// instead — its members expand under the one alias on the server.
export interface WorkspaceEntry {
  dataset_id?: string;
  virtual_id?: string;
  alias: string;
}

// HEL-121: a saved file-selection (virtual dataset). Browser-safe projection —
// opaque id only, never object paths.
export interface VirtualDatasetInfo {
  id: string;
  display_name: string;
  member_count: number;
  schema_policy: "STRICT" | "UNION_BY_NAME";
  mode: string;
  promoted: boolean;
  owner: string | null;
  created_at: string | null;
  expires_at: string | null;
  warnings?: string[];
}

export const listVirtualDatasets = () =>
  fetch(`${API}/virtual-datasets`).then((r) =>
    j<{ virtual_datasets: VirtualDatasetInfo[] }>(r));

export const saveVirtualDataset = (
  display_name: string,
  dataset_ids: string[],
  schema_policy: "STRICT" | "UNION_BY_NAME",
) =>
  fetch(`${API}/virtual-datasets`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ display_name, dataset_ids, schema_policy }),
  }).then((r) => j<VirtualDatasetInfo>(r));

export const deleteVirtualDataset = (id: string) =>
  fetch(`${API}/virtual-datasets/${id}`, { method: "DELETE" }).then((r) =>
    j<{ deleted: boolean }>(r));

export interface JoinHint {
  column: string;
  aliases: { alias: string; type: string }[];
  compatible: boolean;
  note: string | null;
}

export const submitWorkspaceQuery = (datasets: WorkspaceEntry[], sql: string) =>
  fetch(`${API}/queries`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ datasets, sql }),
  }).then((r) => j<{ query_id: string; status: string }>(r));

export const workspaceHints = (datasets: WorkspaceEntry[]) =>
  fetch(`${API}/workspace/hints`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ datasets }),
  }).then((r) => j<{ hints: JoinHint[]; starter_sql: string;
                     schemas: Record<string, SchemaCol[]> }>(r));

export const queryStatus = (id: string) =>
  fetch(`${API}/queries/${id}`).then((r) => j<QueryStatus>(r));

export const queryResults = (id: string) =>
  fetch(`${API}/queries/${id}/results`).then((r) => j<QueryResults>(r));

export const cancelQuery = (id: string) =>
  fetch(`${API}/queries/${id}`, { method: "DELETE" }).then((r) => j<{ cancelled: boolean }>(r));
