import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [
    react({
      include: /\.(jsx|js)$/, // treat .js files as JSX
    }),
  ],
  build: {
    outDir: "build", // keeps 'build/' so the Dockerfile COPY path stays unchanged
  },
  // Local dev uses the same /api contract as the production SPA. Forward it
  // through the gateway so authentication, routing and rate limiting behave
  // the same way when Vite is running on localhost:5173.
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://localhost:11000",
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: "./src/setupTests.js",
  },
});
