import { test, expect, Page } from "@playwright/test";

// HEL-95 browser e2e: the REAL production bundle (vite preview) driven through
// real user flows, with the backend mocked at the network layer per test.

const API = "**/queryskiff/api";

const DATASETS = {
  datasets: [
    { dataset_id: "ds_alpha", name: "alpha.parquet", kind: "file", size: 1024, modified: "2026-07-30T10:00:00" },
    { dataset_id: "ds_beta", name: "beta.parquet", kind: "file", size: 2048, modified: "2026-07-31T11:00:00" },
    { dataset_id: "ds_folder", name: "daily/ (3 parts)", kind: "folder", parts: 3, size: null, modified: null },
  ],
};

const VIRTUALS = {
  virtual_datasets: [
    { id: "v_aabbccddeeff001122", display_name: "july pair", mode: "VIRTUAL", owner: null,
      member_count: 2, dataset_ids: ["ds_alpha", "ds_beta"], schema_policy: "UNION_BY_NAME",
      engine_preference: "duckdb", created_at: "2026-07-31T00:00:00Z",
      updated_at: "2026-07-31T00:00:00Z", expires_at: null, promoted: false, warnings: [] },
  ],
};

async function mockApi(page: Page, overrides: Record<string, unknown> = {}) {
  await page.route(`${API}/datasets`, (r) =>
    r.fulfill({ json: (overrides.datasets as object) ?? DATASETS }));
  await page.route(`${API}/virtual-datasets`, (r) => {
    if (r.request().method() === "GET")
      return r.fulfill({ json: (overrides.virtuals as object) ?? VIRTUALS });
    return r.fallback();
  });
  await page.route(`${API}/datasets/*/schema`, (r) =>
    r.fulfill({ json: { schema: [
      { column_name: "id", column_type: "BIGINT" },
      { column_name: "price", column_type: "DOUBLE" },
    ] } }));
  await page.route(`${API}/datasets/*/metadata`, (r) =>
    r.fulfill({ json: { kind: "file", name: "alpha", size: 1024 } }));
  await page.route(`${API}/workspace/hints`, (r) =>
    r.fulfill({ json: { hints: [{ column: "id", compatible: true, kind: "numeric", note: "" }],
                        starter_sql: "SELECT * FROM alpha JOIN beta USING (id) LIMIT 100" } }));
}

test("SPA boots and lists datasets and saved virtual datasets", async ({ page }) => {
  await mockApi(page);
  await page.goto("/queryskiff/");
  await expect(page.getByText("QuerySkiff", { exact: true })).toBeVisible();
  await expect(page.getByText("alpha.parquet")).toBeVisible();
  await expect(page.getByText("beta.parquet")).toBeVisible();
  await expect(page.getByText("📁 daily/ (3 parts)")).toBeVisible();
  // HEL-121 saved-dataset browsing
  await expect(page.getByText("saved datasets")).toBeVisible();
  await expect(page.getByText("july pair")).toBeVisible();
});

test("dataset filter narrows the list", async ({ page }) => {
  await mockApi(page);
  await page.goto("/queryskiff/");
  await page.getByPlaceholder("filter datasets…").fill("beta");
  await expect(page.getByText("beta.parquet")).toBeVisible();
  await expect(page.getByText("alpha.parquet")).not.toBeVisible();
});

test("selecting a dataset shows its schema", async ({ page }) => {
  await mockApi(page);
  await page.goto("/queryskiff/");
  await page.getByText("alpha.parquet").click();
  await expect(page.getByText("schema")).toBeVisible();
  await expect(page.getByRole("cell", { name: "price" })).toBeVisible();
  await expect(page.getByRole("cell", { name: "DOUBLE" })).toBeVisible();
});

test("workspace join flow: add two datasets, see hints, save as virtual dataset", async ({ page }) => {
  await mockApi(page);
  const saves: unknown[] = [];
  await page.route(`${API}/virtual-datasets`, (r) => {
    if (r.request().method() === "POST") {
      saves.push(r.request().postDataJSON());
      return r.fulfill({ json: { id: "v_ff0011223344556677", display_name: "my pair",
        mode: "VIRTUAL", member_count: 2, dataset_ids: ["ds_alpha", "ds_beta"],
        schema_policy: "UNION_BY_NAME", engine_preference: "duckdb",
        created_at: "2026-08-01T00:00:00Z", updated_at: "2026-08-01T00:00:00Z",
        expires_at: null, promoted: false, warnings: [] } });
    }
    return r.fulfill({ json: VIRTUALS });
  });
  await page.goto("/queryskiff/");

  await page.getByTitle("add to join workspace").first().click();
  await page.getByTitle("add to join workspace").first().click();  // next remaining
  await expect(page.getByText("join workspace", { exact: true })).toBeVisible();
  await expect(page.getByText("shared columns:")).toBeVisible();   // hints arrived

  page.on("dialog", (d) => d.accept("my pair"));                   // window.prompt
  await page.getByText("💾 save as dataset").click();
  await expect.poll(() => saves.length).toBe(1);
  const body = saves[0] as { dataset_ids: string[]; display_name: string };
  expect(body.dataset_ids).toEqual(["ds_alpha", "ds_beta"]);
  expect(body.display_name).toBe("my pair");
});

test("HEL-150: a running query shows elapsed time with cancel still available", async ({ page }) => {
  await mockApi(page);
  // submit returns a query id; status stays "running" so the poll keeps going.
  await page.route(`${API}/queries`, (r) => {
    if (r.request().method() === "POST") return r.fulfill({ json: { query_id: "q1" } });
    return r.fallback();
  });
  await page.route(`${API}/queries/*`, (r) => {
    if (r.request().method() === "GET") return r.fulfill({ json: { status: "running" } });
    if (r.request().method() === "DELETE") return r.fulfill({ json: { cancelled: true } });
    return r.fallback();
  });
  await page.goto("/queryskiff/");
  await page.getByText("alpha.parquet").click();          // becomes table `data`
  await page.getByRole("button", { name: "Run" }).click();
  // elapsed readout appears and both the running label + Cancel are shown
  await expect(page.getByRole("button", { name: /running… \d+\.\d+s/ })).toBeVisible();
  await expect(page.getByRole("button", { name: "Cancel" })).toBeVisible();
});

test("HEL-150: an invalid workspace alias explains why join hints are blocked", async ({ page }) => {
  await mockApi(page);
  await page.goto("/queryskiff/");
  await page.getByTitle("add to join workspace").first().click();
  await page.getByTitle("add to join workspace").first().click();
  await expect(page.getByText("shared columns:")).toBeVisible();   // hints load with valid aliases
  // break the first alias -> hints must disappear WITH a visible reason, not silently
  await page.getByTestId("ws-alias").first().fill("1bad");
  await expect(page.getByText(/join hints unavailable/)).toBeVisible();
  await expect(page.getByText("shared columns:")).not.toBeVisible();
});

test("deleting a saved virtual dataset issues the DELETE", async ({ page }) => {
  await mockApi(page);
  let deleted = "";
  await page.route(`${API}/virtual-datasets/*`, (r) => {
    if (r.request().method() === "DELETE") {
      deleted = r.request().url();
      return r.fulfill({ json: { deleted: true } });
    }
    return r.fallback();
  });
  await page.goto("/queryskiff/");
  await page.getByTitle("delete").click();
  await expect.poll(() => deleted).toContain("v_aabbccddeeff001122");
});
