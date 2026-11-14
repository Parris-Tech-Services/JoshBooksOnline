import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Pin the workspace root: a stray ~/package-lock.json otherwise makes
  // Turbopack treat the whole home directory as the project.
  turbopack: {
    root: __dirname,
  },
};

export default nextConfig;
