# Next-Gen Control Plane V2 Architecture

## Overview
V2 introduces a complete desktop application with SQLite persistence, glassmorphism UI, and secure node-server joining with bidirectional gRPC streaming.

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           V2 DESKTOP APPLICATION                            │
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────────────┐  │
│  │  Server Mode    │    │   Node Mode     │    │   Registration Flow     │  │
│  │  ┌───────────┐  │    │  ┌───────────┐  │    │  ┌─────────────────┐   │  │
│  │  │Dashboard  │  │    │  │Dashboard  │  │    │  │ Server/Node     │   │  │
│  │  │ - Nodes   │  │    │  │ - Server  │  │    │  │ Registration    │   │  │
│  │  │ - Metrics │  │    │  │ - Status  │  │    │  │                 │   │  │
│  │  │ - Approvals│  │    │  │ - Join    │  │    │  │ TLS Certificate │   │  │
│  │  └─────┬─────┘  │    │  └─────┬─────┘  │    │  │ Generation      │   │  │
│  └────────┼─────────┘    └────────┼─────────┘    │  │ Connection Token│   │  │
│           │                       │              │  └─────────────────┘   │  │
│           └───────────────────────┘              └─────────────────────────┘  │
│                          │                                                   │
│                   ┌──────▼──────┐                                            │
│                   │   SQLite    │                                            │
│                   │  Database   │  ~/.nextgen-cp-v2/cluster.db               │
│                   │  (Hibernate)│                                            │
│                   └──────┬──────┘                                            │
│                          │                                                   │
└──────────────────────────┼───────────────────────────────────────────────────┘
                           │
          ┌────────────────┼────────────────┐
          │                │                │
     ┌────▼────┐    ┌─────▼─────┐    ┌─────▼─────┐
     │  gRPC   │    │  gRPC     │    │   gRPC    │
     │ Server  │    │  Node     │    │  Stream   │
     │ Service │    │  Agent    │    │ (Bi-dir)  │
     └────┬────┘    └─────┬─────┘    └─────┬─────┘
          │               │                │
          └───────────────┴────────────────┘
                    gRPC Protocol
```

## Core Components

### 1. Desktop Application (V2)
- **Entry Point**: `com.nextgen.desktop.v2.DesktopAppV2`
- **UI Framework**: JavaFX with glassmorphism styling
- **Database**: SQLite via Hibernate ORM
- **Security**: Self-signed TLS certificates, connection tokens

### 2. Database Layer
**Entities:**
- `ServerEntity` - Server registration data
- `NodeEntity` - Node registration data
- `ClusterMembershipEntity` - Node-Server relationships
- `JoinRequestEntity` - Pending join approvals

**Repositories:**
- CRUD operations with Hibernate
- Query methods for status filtering
- Transaction support

### 3. gRPC Services
**ClusterManager Service:**
- `RequestJoin` - Node requests to join server
- `RespondToJoin` - Server approves/rejects request
- `EstablishStream` - Bidirectional streaming for heartbeats/commands
- `GetPendingJoinRequests` - Server views pending requests
- `GetClusterNodes` - Server views cluster members
- `SendCommand` - Server sends commands to nodes

### 4. Security Model
- **TLS Certificates**: Self-signed RSA-2048 certificates
- **Connection Tokens**: 128-bit secure random tokens
- **Approval Workflow**: Manual approval for node joining

## Data Flow

### Server Registration Flow:
1. User enters server name and gRPC port
2. System specs auto-detected (CPU, memory, OS)
3. TLS certificate generated
4. Connection token generated
5. Server saved to SQLite database
6. Dashboard launched with server view

### Node Registration Flow:
1. User enters node name
2. System specs auto-detected
3. TLS certificate generated
4. Node saved to database
5. Node dashboard launched with server discovery

### Join Request Flow:
1. Node enters server connection token
2. Node sends `RequestJoin` gRPC call
3. Server stores pending join request
4. Server admin sees request in dashboard
5. Admin approves/rejects via `RespondToJoin`
6. On approval, `ClusterMembership` created
7. Bidirectional stream established
8. Node sends heartbeats, server sends commands

## Technology Stack (V2)
- **Java 21** - Modern language features
- **JavaFX 21** - Desktop GUI
- **SQLite + Hibernate 6.4** - Persistence
- **gRPC 1.68** - Communication
- **Prometheus** - Metrics
- **Maven** - Build tool

## File Locations
- **Database**: `~/.nextgen-cp-v2/cluster.db`
- **TLS Certs**: `~/.nextgen-cp-v2/certs/`
- **Config**: In-database, no external files

## Key Features
- **100% Real Data**: All metrics from actual OS readings
- **SQLite Persistence**: Data survives application restart
- **Secure Joining**: Token-based with manual approval
- **Real-time Streaming**: Bidirectional gRPC for live updates
- **Glassmorphism UI**: Modern translucent design
- **No Docker Required**: Runs directly on host OS

## Performance Characteristics
- **UI Response**: <100ms for local operations
- **Database**: Sub-millisecond queries (local SQLite)
- **gRPC Latency**: <5ms for in-process, <50ms for local network
- **Heartbeat Interval**: Configurable (default 2s)
- Terminal-like monitor must show live node health.
