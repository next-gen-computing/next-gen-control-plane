import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Next-Gen Control Plane",
  description: "Distributed cluster management — real-time node monitoring, task orchestration, and job scheduling.",
  keywords: ["cluster", "distributed", "control plane", "monitoring", "orchestration"],
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <head>
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="anonymous" />
      </head>
      <body>{children}</body>
    </html>
  );
}
