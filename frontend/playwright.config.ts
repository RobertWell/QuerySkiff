import { defineConfig } from "@playwright/test";

// HEL-95: browser e2e for the REAL built SPA bundle. `vite preview` serves the
// production build at /queryskiff/ (the gateway path); the backend API is
// mocked per-test with page.route so the suite is hermetic (no MinIO/DuckDB)
// and runs on a bare CI runner. Backend behavior itself is covered by the JVM
// suite + the dual-target contract suite.
export default defineConfig({
  testDir: "./e2e",
  timeout: 30_000,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? [["list"], ["junit", { outputFile: "e2e-results.xml" }]] : "list",
  use: {
    baseURL: "http://localhost:4173",
    trace: "retain-on-failure",
  },
  webServer: {
    command: "npx vite preview --port 4173 --strictPort",
    url: "http://localhost:4173/queryskiff/",
    reuseExistingServer: !process.env.CI,
    timeout: 30_000,
  },
});
