import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// Served under the gateway path — assets and router base MUST be /queryskiff/
export default defineConfig({
  base: "/queryskiff/",
  plugins: [react()],
  build: {
    outDir: "../backend/queryskiff/static",
    emptyOutDir: true,
  },
  server: {
    proxy: {
      "/queryskiff/api": "http://localhost:5400",
    },
  },
});
