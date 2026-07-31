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
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: "./src/setupTests.js",
  },
});
