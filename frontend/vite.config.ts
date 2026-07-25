import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// Served under the gateway path — assets and router base MUST be /dataraft/
export default defineConfig({
  base: "/dataraft/",
  plugins: [react()],
  build: {
    outDir: "../backend/dataraft/static",
    emptyOutDir: true,
  },
  server: {
    proxy: {
      "/dataraft/api": "http://localhost:5400",
    },
  },
});
