# Next-Gen Control Plane — Web Dashboard

Modern, real-time distributed cluster management dashboard built with **Next.js 16 (App Router)**, **React 19**, **TypeScript**, and **Tailwind CSS v4**.

![Next.js](https://img.shields.io/badge/Next.js-16.4-black?logo=next.js)
![React](https://img.shields.io/badge/React-19.3-61DAFB?logo=react)
![TypeScript](https://img.shields.io/badge/TypeScript-5.0-3178C6?logo=typescript)
![Tailwind CSS](https://img.shields.io/badge/Tailwind_CSS-v4-38B2AC?logo=tailwind-css)
![WebSocket](https://img.shields.io/badge/WebSocket-STOMP-FF6C37)

---

## 🌟 Overview

The `web-ui` module serves as the primary browser-based operator interface for the Next-Gen Control Plane distributed cluster. It connects to the Java backend via Spring Boot REST APIs and real-time STOMP WebSockets, providing live cluster telemetry, health monitoring, job dispatching, and security audits.

## 🚀 Key Features

- **Real-Time Cluster Telemetry**: Live CPU, memory pressure, battery levels, and network RTT streaming from edge nodes via WebSocket/STOMP.
- **Node Management (`/nodes`)**: Inspect edge node capabilities, active heartbeats, degradation risk scores, and mTLS enrollment status.
- **Distributed Job Orchestration (`/jobs`)**: Submit data-parallel compute jobs and Docker Compose stacks, view largest-remainder task partition slices, and monitor progress.
- **Task Lifecycle & Migration (`/tasks`)**: Live tracking of running tasks, sub-second proactive migration triggers, and split-brain fencing status.
- **Security & Audit Logs (`/audit`)**: Real-time event log for X.509 certificate issuance, single-use token enrollment, and node revocation events.

## 🛠️ Tech Stack & Architecture

- **Framework**: Next.js 16 (Turbopack, App Router)
- **UI Library**: React 19 (Server & Client Components)
- **Language**: TypeScript 5
- **Styling**: Tailwind CSS v4 + Radix UI Primitives + Lucide Icons
- **State Management**: Zustand 5
- **Data Fetching & Cache**: TanStack React Query 5
- **Real-Time Streaming**: `@stomp/stompjs` + SockJS client (STOMP over WebSocket)
- **Data Visualization**: Recharts (Dynamic telemetry time-series charts)

## 📦 Getting Started

### Local Development

1. Ensure the Java Control Plane is running (providing REST and WebSocket endpoints on port `8080`).
2. Install dependencies:
   ```bash
   npm install
   ```
3. Run the development server:
   ```bash
   npm run dev
   ```
4. Open [http://localhost:3000](http://localhost:3000) in your browser.

### Docker Production Deployment

This module is containerized with a production-grade multi-stage Docker build:

```bash
docker build -t nextgen-web-ui .
docker run -p 3000:3000 -e NEXT_PUBLIC_API_BASE=http://localhost:8080 nextgen-web-ui
```

Or spin up the full stack using Docker Compose:
```bash
docker compose -f docker-compose.full-stack.yml up -d
```
