import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// Served under the gateway path — assets and router base MUST be /queryskiff/
export default defineConfig({
  base: "/queryskiff/",
  plugins: [react()],
  build: {
    // HEL-149: the JVM backend serves the SPA from Quarkus' static resources
    // root. This used to point at ../backend/queryskiff/static — the RETIRED
    // Python tree — so a local `vite build` wrote where nothing is served.
    // (backend-jvm/Dockerfile overrides --outDir, so images were unaffected;
    // only local builds silently went nowhere.)
    outDir: "../backend-jvm/src/main/resources/META-INF/resources/queryskiff",
    emptyOutDir: true,
  },
  server: {
    proxy: {
      "/queryskiff/api": "http://localhost:5400",
    },
  },
});
