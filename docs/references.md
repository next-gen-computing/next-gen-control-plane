# References and Bibliography

This document outlines the foundational technologies, research papers, and software architecture methodologies that influenced the design, testing, and implementation of the Next-Gen Control Plane v2.

---

## 1. Core Technologies and Frameworks

**[1] Oracle Corporation. (2023). "JavaFX: Rich Client Platform."**
*Available:* https://openjfx.io/
*Application in Project:* The primary UI framework utilized for the Desktop Control Plane and Node dashboards. JavaFX's Scene Graph architecture and hardware-accelerated rendering pipeline were essential for delivering 60 FPS charts without freezing the application.
*Key Insight Applied:* The strict enforcement of `Platform.runLater()` for all UI updates originating from background gRPC threads to prevent `IllegalStateException` and concurrency crashes.

**[2] Google Cloud. (2024). "gRPC: A high-performance, open-source universal RPC framework."**
*Available:* https://grpc.io/docs/
*Application in Project:* Used as the transport layer between the Control Plane Server and Edge Nodes. gRPC was chosen over standard REST/HTTP due to its support for bidirectional streaming, allowing nodes to push telemetry (heartbeats, CPU, Memory) continuously without the overhead of establishing new TCP connections every 2 seconds.

**[3] Google. (2008). "Protocol Buffers: Developer Guide."**
*Available:* https://protobuf.dev/
*Application in Project:* The serialization format for gRPC payloads. By defining `NodeRecord` and `Heartbeat` messages in `.proto` files, the project achieves a significantly smaller network payload size compared to JSON, crucial for IoT environments with constrained bandwidth.

**[4] SQLite Consortium. (2024). "Write-Ahead Logging (WAL)."**
*Available:* https://www.sqlite.org/wal.html
*Application in Project:* SQLite is the embedded database for the Control Plane. Transitioning the database journal mode from `DELETE` to `WAL` resolved concurrency locks during integration testing and enabled non-blocking reads while the gRPC services continuously wrote telemetry data.

**[5] JUnit Team. (2024). "JUnit 5 User Guide."**
*Available:* https://junit.org/junit5/docs/current/user-guide/
*Application in Project:* The primary testing framework. Jupiter's lifecycle annotations (`@BeforeEach`, `@AfterEach`) were critical for setting up and safely tearing down the `InProcessServerBuilder` and database connections.

---

## 2. Distributed Systems Architecture & Research

**[6] M. van Steen and A. S. Tanenbaum. (2017). *Distributed Systems*, 3rd ed. Maarten van Steen.**
*Application in Project:* Fundamental concepts regarding Node registration, consensus, and failure detection. The heartbeat interval pattern implemented in the Node manager was directly influenced by Tanenbaum's principles on partial failure detection in distributed networks.

**[7] J. Dean and L. A. Barroso. (2013). "The Tail at Scale." *Communications of the ACM*, vol. 56, no. 2, pp. 74–80.**
*Application in Project:* Guided the latency-sensitive design of the real-time telemetry dashboard. To avoid UI blocking caused by network tail latency, all gRPC calls are strictly asynchronous, ensuring the UI remains responsive even if a Node drops packets.

**[8] E. Brewer. (2012). "CAP Twelve Years Later: How the 'Rules' Have Changed." *Computer*, vol. 45, no. 2, pp. 22-29.**
*Application in Project:* The architectural decision to prioritize Availability (A) and Partition Tolerance (P) for the edge nodes. If a node loses connection to the Control Plane Server, it continues to gather and queue telemetry data locally until the partition is healed.

---

## 3. Libraries and Tooling

**[9] Mockito Contributors. (2023). "Mockito framework site."**
*Available:* https://site.mockito.org/
*Application in Project:* Used to mock complex database repositories (`ServerRepository`, `ClusterMembershipRepository`) during isolated unit testing of ViewModels.

**[10] Awaitility. (2024). "Testing asynchronous systems."**
*Available:* https://github.com/awaitility/awaitility
*Application in Project:* Essential for solving the asynchronous testing challenge. Awaitility is used to poll the JavaFX thread safely during integration testing to verify that properties (like CPU Progress Bars) update correctly following a gRPC payload.

**[11] Eclipse Foundation. (2024). "JaCoCo - Java Code Coverage Library."**
*Available:* https://www.jacoco.org/jacoco/
*Application in Project:* Integrated into the Maven build lifecycle to generate test coverage reports, enforcing the >80% line coverage threshold required by the CI pipeline.

---

## 4. Academic Application References

**[12] VitaVoice Emergency Response System Research.**
*Internal Project Context:* The Control Plane's real-time telemetry acts as a foundational component for scaling emergency AI dispatch systems. The requirement for zero-latency monitoring is driven by the need to guarantee that no conversational AI agent crashes during an active 911 dispatch without immediate re-routing.
