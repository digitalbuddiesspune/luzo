import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/** @type {import('next').NextConfig} */
const nextConfig = {
  output: "export",
  trailingSlash: true,
  // Keep Turbopack scoped to this app. Without this, Next may treat the
  // monorepo git root as project root and scan admin/server (and hang).
  turbopack: {
    root: __dirname,
  },
};

export default nextConfig;
