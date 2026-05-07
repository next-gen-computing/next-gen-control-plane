You are a Principal JavaFX Engineer. Fix and completely upgrade the desktop-ui module in this repo: https://github.com/next-gen-computing/next-gen-control-plane

CURRENT PROBLEMS:
- CPU, Memory, and Heartbeat are not updating in real-time.
- Connection validation is broken (accepts wrong server IP).
- UI is not modern and not user-friendly.
- No clear separation between Control Plane (Server) and Nodes.

NEW ARCHITECTURE REQUIRED (Must Follow Exactly):

### 1. Hamburger Menu (Top Left)
- Use 3 horizontal lines icon that opens a side menu with these tabs:
  - Dashboard (Overview)
  - Servers / Control Planes
  - My Node (if running as node)
  - Task Submission
  - Logs & Monitoring
  - Settings

### 2. Servers Dashboard (Control Plane View)
- List of available / connected Control Planes (Servers)
- "Add Server" button → IP + Port + Name
- For each server:
  - Status (Connected / Disconnected)
  - Number of connected nodes
  - Button to "Connect" → sends connection request
- Server Owner View (when connected as server):
  - List of pending connection requests from nodes (with Accept / Reject buttons)
  - Once accepted, node appears in "Connected Nodes" section
  - Show all connected nodes with:
    - Name, Status, Real CPU%, Real Memory%, Last Heartbeat
    - Small live graph (JavaFX LineChart) for CPU & Memory

### 3. Nodes Dashboard (Node View)
- Shows ONLY this node's information:
  - Node Name, Status
  - Large real-time CPU % (progress bar + number)
  - Large real-time Memory % (progress bar + number)
  - Last Heartbeat time + latency
  - Connection status to current server

### 4. General Requirements
- All data MUST be REAL (from gRPC). No random or fake values ever.
- Auto-refresh every 2 seconds for all live data.
- Use modern dark theme with clean cards, icons (use FontAwesome or Material Icons via library), and smooth updates.
- Proper gRPC connection with retry logic and clear error messages.
- When wrong server IP is entered, it should show proper error ("Connection Failed - Server not reachable").
- Use Platform.runLater for all UI updates from background threads.

Tech Stack:
- Java 21 + JavaFX
- Existing proto definitions
- Clean architecture (separate services for gRPC, models, controllers)

TASK:
Completely rebuild / replace the desktop-ui code to match the above specification exactly. 
Focus on real-time updates, proper connection flow (request → accept), and beautiful modern UI.

Start your response with: "✅ FINAL HIGH-QUALITY UI REBUILD STARTED - v2"
End with complete build and run instructions.