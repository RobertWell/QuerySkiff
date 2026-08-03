import { describe, it, expect, vi, beforeEach } from "vitest";
import * as api from "./api";

// A minimal Response stand-in — fetch is mocked, so we control ok/status/json.
function res(body: unknown, ok = true, status = 200): Response {
  return {
    ok,
    status,
    json: async () => body,
  } as unknown as Response;
}

const fetchMock = vi.fn();
beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal("fetch", fetchMock);
});

const lastCall = () => fetchMock.mock.calls[fetchMock.mock.calls.length - 1];

describe("GET endpoints hit the gateway base path", () => {
  it("listDatasets", async () => {
    fetchMock.mockResolvedValue(res({ datasets: [{ dataset_id: "d1" }] }));
    const out = await api.listDatasets();
    expect(out.datasets[0].dataset_id).toBe("d1");
    expect(lastCall()[0]).toBe("/queryskiff/api/datasets");
  });

  it("getSchema / getMetadata substitute the id", async () => {
    fetchMock.mockResolvedValue(res({ schema: [] }));
    await api.getSchema("abc");
    expect(lastCall()[0]).toBe("/queryskiff/api/datasets/abc/schema");
    fetchMock.mockResolvedValue(res({ size: 10 }));
    await api.getMetadata("abc");
    expect(lastCall()[0]).toBe("/queryskiff/api/datasets/abc/metadata");
  });

  it("listVirtualDatasets", async () => {
    fetchMock.mockResolvedValue(res({ virtual_datasets: [] }));
    await api.listVirtualDatasets();
    expect(lastCall()[0]).toBe("/queryskiff/api/virtual-datasets");
  });

  it("queryStatus / queryResults", async () => {
    fetchMock.mockResolvedValue(res({ query_id: "q", status: "done" }));
    await api.queryStatus("q");
    expect(lastCall()[0]).toBe("/queryskiff/api/queries/q");
    fetchMock.mockResolvedValue(res({ columns: [], rows: [] }));
    await api.queryResults("q");
    expect(lastCall()[0]).toBe("/queryskiff/api/queries/q/results");
  });
});

describe("mutating endpoints send the right method + body", () => {
  it("submitQuery POSTs dataset_id + sql", async () => {
    fetchMock.mockResolvedValue(res({ query_id: "q", status: "pending" }));
    await api.submitQuery("d1", "SELECT 1");
    const [url, init] = lastCall();
    expect(url).toBe("/queryskiff/api/queries");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toEqual({ dataset_id: "d1", sql: "SELECT 1" });
  });

  it("submitWorkspaceQuery POSTs datasets + sql", async () => {
    fetchMock.mockResolvedValue(res({ query_id: "q", status: "pending" }));
    const ds = [{ dataset_id: "d1", alias: "a" }];
    await api.submitWorkspaceQuery(ds, "SELECT * FROM a");
    const [url, init] = lastCall();
    expect(url).toBe("/queryskiff/api/queries");
    expect(JSON.parse(init.body)).toEqual({ datasets: ds, sql: "SELECT * FROM a" });
  });

  it("workspaceHints POSTs the datasets", async () => {
    fetchMock.mockResolvedValue(res({ hints: [], starter_sql: "", schemas: {} }));
    await api.workspaceHints([{ dataset_id: "d1", alias: "a" }]);
    const [url, init] = lastCall();
    expect(url).toBe("/queryskiff/api/workspace/hints");
    expect(init.method).toBe("POST");
  });

  it("saveVirtualDataset POSTs name + ids + policy", async () => {
    fetchMock.mockResolvedValue(res({ id: "v1", display_name: "x" }));
    await api.saveVirtualDataset("x", ["d1", "d2"], "UNION_BY_NAME");
    const [url, init] = lastCall();
    expect(url).toBe("/queryskiff/api/virtual-datasets");
    expect(JSON.parse(init.body)).toEqual({
      display_name: "x", dataset_ids: ["d1", "d2"], schema_policy: "UNION_BY_NAME",
    });
  });

  it("deleteVirtualDataset / cancelQuery DELETE", async () => {
    fetchMock.mockResolvedValue(res({ deleted: true }));
    await api.deleteVirtualDataset("v1");
    expect(lastCall()[0]).toBe("/queryskiff/api/virtual-datasets/v1");
    expect(lastCall()[1].method).toBe("DELETE");
    fetchMock.mockResolvedValue(res({ cancelled: true }));
    await api.cancelQuery("q1");
    expect(lastCall()[0]).toBe("/queryskiff/api/queries/q1");
    expect(lastCall()[1].method).toBe("DELETE");
  });
});

describe("error helper j()", () => {
  it("prefers body.detail on a non-ok response", async () => {
    fetchMock.mockResolvedValue(res({ detail: "boom" }, false, 400));
    await expect(api.listDatasets()).rejects.toThrow("boom");
  });

  it("falls back to body.error", async () => {
    fetchMock.mockResolvedValue(res({ error: "nope" }, false, 500));
    await expect(api.listDatasets()).rejects.toThrow("nope");
  });

  it("falls back to the status when the body isn't JSON", async () => {
    fetchMock.mockResolvedValue({
      ok: false, status: 503, json: async () => { throw new Error("not json"); },
    } as unknown as Response);
    await expect(api.listDatasets()).rejects.toThrow("503");
  });
});
