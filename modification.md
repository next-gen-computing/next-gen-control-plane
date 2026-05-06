# Phase-2 Enhanced Desktop UI Requirements

## Goal
Build a **beautiful, modern, extremely user-friendly** JavaFX Desktop Application (10x better than standard Docker Desktop) that works with both Docker nodes and **real physical machines**.

## Core Capabilities
1. **Node Connection**
   - Easy "Add Node" feature: IP Address + Port + Node Name
   - Connect to real physical machines (NodeAgent must run on them)
   - Auto-discovery (optional bonus)

2. **Live Dashboard** (Main Screen)
   - Beautiful cards for each node showing:
     - Node Name / Hostname
     - Status (Healthy / Warning / Offline) with color coding
     - Real CPU Usage % (live)
     - Real Memory Usage % (live)
     - Last Heartbeat time + latency
     - Predicted Load & Failure Probability (from Predictor)
   - Cluster summary: Total Nodes, Healthy Nodes, Overall CPU, Overall Memory

3. **Task Execution (Most Important)**
   - Simple task submission panel
   - User can submit a simple parallelizable task (example: Matrix Multiplication, Prime numbers in range, Image processing stub, or "Sum of large array")
   - System automatically:
     - Splits the task into subtasks
     - Uses Predictive Scheduling to send to best available nodes
     - Aggregates results
   - Show progress of each subtask in real-time

4. **Monitoring & Visualization**
   - Real-time performance graphs (CPU & Memory per node)
   - Live work distribution view (which node is doing what)
   - Heartbeat history
   - Logs panel (clean and searchable)

5. **Design Requirements**
   - Modern, premium look (dark theme by default, light mode toggle)
   - Extremely user-friendly — even non-IT people should use it easily
   - Sidebar navigation
   - Responsive layout
   - Smooth animations and loading states
   - Professional polish (like latest Docker Desktop / Raycast / Linear)

## Strict Rules
- ONLY real data from gRPC (CPU, Memory, Heartbeat from actual OS).
- Never use random, fake, or mock values.
- If no data, show "Connecting...", "N/A", or empty state.
- Must work with real physical nodes (NodeAgent running on them).
- Use existing proto definitions.

Tech Stack: Java 21 + JavaFX + gRPC Client