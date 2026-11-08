/** @type {import('next').NextConfig} */
const nextConfig = {
  // Allow the app to be embedded inside the JavaFX WebView at a local port
  // while the REST API is on 8080 (Spring Boot)
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination: `${process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080"}/api/:path*`,
      },
    ];
  },
  // Standalone output for Docker deployment
  output: "standalone",
};

module.exports = nextConfig;
