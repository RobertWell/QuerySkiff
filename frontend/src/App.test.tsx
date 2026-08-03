import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";

// --- mock the heavy editor/grid so jsdom can mount the app ---------------------
vi.mock("@monaco-editor/react", () => ({
  default: ({ value, onChange }: { value: string; onChange: (v: string) => void }) => (
    <textarea data-testid="sql" value={value} onChange={(e) => onChange(e.target.value)} />
  ),
}));
vi.mock("ag-grid-react", () => ({
  AgGridReact: ({ rowData }: { rowData: unknown[] }) => (
    <div data-testid="grid" data-rows={rowData.length} />
  ),
}));

// --- mock the API layer -------------------------------------------------------
const a = vi.hoisted(() => ({
  listDatasets: vi.fn(),
  listVirtualDatasets: vi.fn(),
  getSchema: vi.fn(),
  getMetadata: vi.fn(),
  workspaceHints: vi.fn(),
  submitQuery: vi.fn(),
  submitWorkspaceQuery: vi.fn(),
  queryStatus: vi.fn(),
  queryResults: vi.fn(),
  cancelQuery: vi.fn(),
  saveVirtualDataset: vi.fn(),
  deleteVirtualDataset: vi.fn(),
}));
vi.mock("./api", () => a);

import App, { aliasFor, fmtBytes, keyOf } from "./App";

const DS = [
  { dataset_id: "d1", name: "Sales 2026", bucket: "b", kind: "file", size: 2048, modified: "2026-08-01T10:00:00Z" },
  { dataset_id: "d2", name: "Sales 2025", bucket: "b", kind: "folder", size: null, modified: null },
];

beforeEach(() => {
  Object.values(a).forEach((f) => f.mockReset());
  a.listDatasets.mockResolvedValue({ datasets: DS });
  a.listVirtualDatasets.mockResolvedValue({ virtual_datasets: [] });
  a.getSchema.mockResolvedValue({ schema: [{ column_name: "amount", column_type: "DOUBLE" }] });
  a.getMetadata.mockResolvedValue({ size: 2048 });
  a.workspaceHints.mockResolvedValue({ hints: [{ column: "id", aliases: [], compatible: true, note: null }], starter_sql: "SELECT 1", schemas: {} });
});

describe("pure helpers", () => {
  it("fmtBytes scales units and handles null", () => {
    expect(fmtBytes(null)).toBe("");
    expect(fmtBytes(512)).toBe("512 B");
    expect(fmtBytes(2048)).toBe("2.0 KB");
    expect(fmtBytes(5 * (1 << 20))).toBe("5.0 MB");
    expect(fmtBytes(3 * (1 << 30))).toBe("3.0 GB");
  });

  it("aliasFor sanitizes, truncates, and de-dupes", () => {
    expect(aliasFor("Sales 2026", new Set())).toBe("sales_2026");
    expect(aliasFor("123 leading digits", new Set())).toBe("leading_digits");
    expect(aliasFor("!!!", new Set())).toBe("t");
    expect(aliasFor("Sales 2026", new Set(["sales_2026"]))).toBe("sales_2026_1");
  });

  it("keyOf prefers dataset_id then virtual_id", () => {
    expect(keyOf({ dataset_id: "d" })).toBe("d");
    expect(keyOf({ virtual_id: "v" })).toBe("v");
    expect(keyOf({})).toBe("");
  });
});

describe("dataset browser", () => {
  it("lists datasets and filters them", async () => {
    render(<App />);
    expect(await screen.findByText("📄 Sales 2026")).toBeInTheDocument();
    expect(screen.getByText("📁 Sales 2025")).toBeInTheDocument();
    fireEvent.change(screen.getByPlaceholderText("filter datasets…"), { target: { value: "2026" } });
    expect(screen.queryByText("📁 Sales 2025")).not.toBeInTheDocument();
    expect(screen.getByText("📄 Sales 2026")).toBeInTheDocument();
  });

  it("shows a dataset error when listing fails", async () => {
    a.listDatasets.mockRejectedValue(new Error("s3 down"));
    render(<App />);
    expect(await screen.findByText(/could not list datasets: s3 down/)).toBeInTheDocument();
  });

  it("selecting a dataset loads its schema", async () => {
    render(<App />);
    fireEvent.click(await screen.findByText("📄 Sales 2026"));
    expect(await screen.findByText("amount")).toBeInTheDocument();
    expect(screen.getByText("DOUBLE")).toBeInTheDocument();
    expect(a.getSchema).toHaveBeenCalledWith("d1");
  });
});

describe("join workspace", () => {
  it("adds two datasets, derives aliases, and fetches hints", async () => {
    render(<App />);
    await screen.findByText("📄 Sales 2026");
    const addBtns = screen.getAllByTitle("add to join workspace");
    fireEvent.click(addBtns[0]);
    fireEvent.click(addBtns[1]);
    expect(await screen.findByText("join workspace")).toBeInTheDocument();
    // two alias inputs, deduped
    const aliases = screen.getAllByTestId("ws-alias") as HTMLInputElement[];
    expect(aliases.map((i) => i.value)).toEqual(["sales_2026", "sales_2025"]);
    await waitFor(() => expect(a.workspaceHints).toHaveBeenCalled());
    expect(await screen.findByText("id")).toBeInTheDocument();
  });

  it("explains why hints are blocked on an invalid alias", async () => {
    render(<App />);
    await screen.findByText("📄 Sales 2026");
    const addBtns = screen.getAllByTitle("add to join workspace");
    fireEvent.click(addBtns[0]);
    fireEvent.click(addBtns[1]);
    const aliases = await screen.findAllByTestId("ws-alias");
    fireEvent.change(aliases[0], { target: { value: "1bad" } });
    expect(await screen.findByText(/join hints unavailable — alias "1bad" is invalid/)).toBeInTheDocument();
  });

  it("flags a duplicate alias", async () => {
    render(<App />);
    await screen.findByText("📄 Sales 2026");
    const addBtns = screen.getAllByTitle("add to join workspace");
    fireEvent.click(addBtns[0]);
    fireEvent.click(addBtns[1]);
    const aliases = await screen.findAllByTestId("ws-alias");
    fireEvent.change(aliases[1], { target: { value: "sales_2026" } });
    expect(await screen.findByText(/is used by more than one dataset/)).toBeInTheDocument();
  });
});

describe("virtual datasets", () => {
  it("saves the workspace selection as a virtual dataset", async () => {
    vi.spyOn(window, "prompt").mockReturnValue("my dataset");
    a.saveVirtualDataset.mockResolvedValue({ id: "v1", display_name: "my dataset", warnings: [] });
    render(<App />);
    await screen.findByText("📄 Sales 2026");
    fireEvent.click(screen.getAllByTitle("add to join workspace")[0]);
    fireEvent.click(await screen.findByText("💾 save as dataset"));
    await waitFor(() => expect(a.saveVirtualDataset).toHaveBeenCalledWith("my dataset", ["d1"], "UNION_BY_NAME"));
    expect(await screen.findByText(/saved "my dataset"/)).toBeInTheDocument();
  });

  it("saves with STRICT when the schema-policy toggle is switched", async () => {
    vi.spyOn(window, "prompt").mockReturnValue("strict set");
    a.saveVirtualDataset.mockResolvedValue({ id: "v2", display_name: "strict set", warnings: [] });
    render(<App />);
    await screen.findByText("📄 Sales 2026");
    fireEvent.click(screen.getAllByTitle("add to join workspace")[0]);
    fireEvent.change(await screen.findByTestId("schema-policy"), { target: { value: "STRICT" } });
    fireEvent.click(screen.getByText("💾 save as dataset"));
    await waitFor(() => expect(a.saveVirtualDataset).toHaveBeenCalledWith("strict set", ["d1"], "STRICT"));
  });

  it("surfaces the backend's save warning in the sidebar message", async () => {
    vi.spyOn(window, "prompt").mockReturnValue("big set");
    a.saveVirtualDataset.mockResolvedValue({
      id: "v3", display_name: "big set",
      warnings: ["selection has 900 files — consider promoting to a managed table"],
    });
    render(<App />);
    await screen.findByText("📄 Sales 2026");
    fireEvent.click(screen.getAllByTitle("add to join workspace")[0]);
    fireEvent.click(await screen.findByText("💾 save as dataset"));
    expect(await screen.findByText(/saved "big set" — selection has 900 files/)).toBeInTheDocument();
  });

  it("browses and opens a saved virtual dataset", async () => {
    a.listVirtualDatasets.mockResolvedValue({ virtual_datasets: [
      { id: "v9", display_name: "quarter mix", member_count: 3, schema_policy: "UNION_BY_NAME", mode: "m", promoted: false, owner: null, created_at: null, expires_at: null },
    ] });
    render(<App />);
    const open = await screen.findByTitle("query this saved dataset");
    fireEvent.click(open);
    // opening a virtual puts us in workspace mode with alias "data"
    const alias = await screen.findByTestId("ws-alias") as HTMLInputElement;
    expect(alias.value).toBe("data");
  });

  it("deletes a saved virtual dataset", async () => {
    a.listVirtualDatasets.mockResolvedValue({ virtual_datasets: [
      { id: "v9", display_name: "quarter mix", member_count: 3, schema_policy: "UNION_BY_NAME", mode: "m", promoted: false, owner: null, created_at: null, expires_at: null },
    ] });
    a.deleteVirtualDataset.mockResolvedValue({ deleted: true });
    render(<App />);
    fireEvent.click(await screen.findByTitle("delete"));
    await waitFor(() => expect(a.deleteVirtualDataset).toHaveBeenCalledWith("v9"));
  });
});

describe("run query lifecycle", () => {
  it("runs a single-dataset query and shows the row count", async () => {
    a.submitQuery.mockResolvedValue({ query_id: "q1", status: "pending" });
    a.queryStatus.mockResolvedValue({ query_id: "q1", status: "done", error: null, row_count: 42, truncated: false });
    a.queryResults.mockResolvedValue({ query_id: "q1", status: "done", error: null, row_count: 42, truncated: false, columns: ["amount"], rows: [[1], [2]] });
    render(<App />);
    fireEvent.click(await screen.findByText("📄 Sales 2026"));
    fireEvent.click(screen.getByText("Run"));
    await waitFor(() => expect(a.submitQuery).toHaveBeenCalledWith("d1", expect.any(String)));
    expect(await screen.findByText("42 rows")).toBeInTheDocument();
    expect((screen.getByTestId("grid")).getAttribute("data-rows")).toBe("2");
  });

  it("surfaces a query error from the status poll", async () => {
    a.submitQuery.mockResolvedValue({ query_id: "q2", status: "pending" });
    a.queryStatus.mockResolvedValue({ query_id: "q2", status: "error", error: "syntax error", row_count: 0, truncated: false });
    render(<App />);
    fireEvent.click(await screen.findByText("📄 Sales 2026"));
    fireEvent.click(screen.getByText("Run"));
    expect(await screen.findByText("syntax error")).toBeInTheDocument();
  });
});
