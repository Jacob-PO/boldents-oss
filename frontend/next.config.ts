import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Standalone output for production (Docker deployment)
  output: 'standalone',

  // Rewrites for API proxy (works in both dev and standalone)
  async rewrites() {
    return [
      {
        source: '/api/:path*',
        destination: `${process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080'}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;
