# Testing Guide

## Overview

This project uses **JUnit 5 (Jupiter)**, **Mockito**, and **Awaitility** for testing. Tests are organized into three categories:

1. **Unit Tests** - Fast, isolated tests for individual components
2. **Integration Tests** - Tests that verify component interactions
3. **End-to-End Tests** - Full workflow tests

## Test Structure

```
src/test/java/com/nextgen/
├── controlplane/              # V1 Control Plane tests
│   ├── ControlPlaneServiceImplTest.java
│   ├── DashboardApiHandlerTest.java
│   ├── HeartbeatMonitorTest.java
│   └── NodeRecordTest.java
├── desktop/                     # V1 Desktop tests
│   ├── service/
│   │   └── ErrorHandlerTest.java
│   └── viewmodel/
│       └── ModeSelectionViewModelTest.java
└── desktop/v2/                  # V2 Desktop tests
    ├── db/
    │   ├── DatabaseManagerTest.java
    │   └── entities/
    │       ├── ServerEntityTest.java
    │       ├── NodeEntityTest.java
    │       ├── ClusterMembershipEntityTest.java
    │       └── JoinRequestEntityTest.java
    ├── db/repositories/
    │   ├── ServerRepositoryTest.java
    │   ├── NodeRepositoryTest.java
    │   ├── ClusterMembershipRepositoryTest.java
    │   └── JoinRequestRepositoryTest.java
    ├── service/
    │   └── RegistrationServiceTest.java
    ├── util/
    │   ├── SystemSpecDetectorTest.java
    │   └── TlsCertificateGeneratorTest.java
    ├── grpc/
    │   └── ClusterManagerServiceImplTest.java
    └── integration/
        ├── V2EndToEndIntegrationTest.java
        ├── gRPCIntegrationTest.java
        └── DatabaseIntegrationTest.java
```

## Running Tests

### Run All Tests
```bash
cd java-control-plane
mvn test
```

### Run Specific Test Class
```bash
mvn test -Dtest=RegistrationServiceTest
```

### Run Tests with Coverage
```bash
mvn clean test jacoco:report
# Report: target/site/jacoco/index.html
```

### Run V2 Tests Only
```bash
mvn test -Dtest="**/v2/**/*Test"
```

## Test Categories

### 1. Entity Tests
Test entity getters/setters, status transitions, and validation.

```java
@Test
void testStatusTransitions() {
    ServerEntity server = new ServerEntity();
    server.setStatus(ServerEntity.ServerStatus.INACTIVE);
    
    server.setStatus(ServerEntity.ServerStatus.ACTIVE);
    assertEquals(ServerEntity.ServerStatus.ACTIVE, server.getStatus());
}
```

### 2. Repository Tests
Test database operations with actual SQLite database.

```java
@Test
void testSaveAndFindById() {
    ServerEntity server = createTestServer("test-id");
    ServerEntity saved = repository.save(server);
    
    Optional<ServerEntity> found = repository.findById("test-id");
    assertTrue(found.isPresent());
}
```

### 3. Service Tests
Test business logic with mocked dependencies where appropriate.

```java
@Test
void testRegisterServer() {
    ServerEntity server = service.registerServer("TestServer", 50051);
    
    assertNotNull(server.getId());
    assertEquals("TestServer", server.getName());
    assertNotNull(server.getTlsCertificate());
}
```

### 4. Utility Tests
Test helper classes like system spec detection and certificate generation.

```java
@Test
void testDetectSystemSpecs() {
    Map<String, Object> specs = SystemSpecDetector.detectSystemSpecs();
    
    assertTrue(specs.containsKey("cpuCores"));
    assertTrue((Integer) specs.get("cpuCores") > 0);
}
```

### 5. gRPC Tests
Use in-process gRPC server for fast, isolated testing.

```java
@BeforeEach
void setUp() throws IOException {
    String serverName = InProcessServerBuilder.generateName();
    server = InProcessServerBuilder
        .forName(serverName)
        .directExecutor()
        .addService(new ClusterManagerServiceImpl())
        .build()
        .start();
    
    channel = InProcessChannelBuilder
        .forName(serverName)
        .directExecutor()
        .build();
    
    stub = ClusterManagerGrpc.newBlockingStub(channel);
}
```

### 6. Integration Tests
Test complete workflows across multiple components.

```java
@Test
void testFullServerNodeJoinFlow() {
    // 1. Register Server
    ServerEntity server = registrationService.registerServer("IntegrationServer", 50051);
    
    // 2. Register Node
    NodeEntity node = registrationService.registerNode("IntegrationNode");
    
    // 3. Create Join Request
    JoinRequestEntity request = createJoinRequest(server, node);
    
    // 4. Approve Request
    approveRequest(request);
    
    // 5. Verify Membership
    List<ClusterMembershipEntity> memberships = 
        membershipRepo.findByServerId(server.getId());
    assertTrue(memberships.size() > 0);
}
```

## Best Practices

### 1. Use @BeforeEach and @AfterEach
Clean up EntityManager but don't shutdown the singleton DatabaseManager:

```java
@AfterEach
void tearDown() {
    if (em != null && em.isOpen()) {
        em.close();
    }
    // Don't shutdown singleton - it will be reused by other tests
}
```

### 2. Automatic WAL File Cleanup
The V2 application automatically cleans up SQLite WAL (Write-Ahead Logging) files on shutdown to prevent locked file issues. When the application closes, it deletes:
- `~/.nextgen-cp-v2/cluster.db-wal` (WAL file)
- `~/.nextgen-cp-v2/cluster.db-shm` (Shared memory file)

This ensures that the database is in a clean state for the next launch, preventing "file is locked" errors that can occur when the application is terminated abruptly or when multiple instances are run.

### 3. Use In-Process gRPC
Avoid network calls in tests:

```java
// Good: In-process
InProcessServerBuilder.forName("test")

// Bad: Actual network
ServerBuilder.forPort(50051)
```

### 4. Test Real Values
Ensure all tests verify actual OS readings or generated data:

```java
@Test
void testRealOsValuesNotRandom() {
    Map<String, Object> specs1 = SystemSpecDetector.detectSystemSpecs();
    Map<String, Object> specs2 = SystemSpecDetector.detectSystemSpecs();
    
    // Should be consistent (real values don't change)
    assertEquals(specs1.get("cpuCores"), specs2.get("cpuCores"));
}
```

### 5. Parallel Test Safety
Avoid shared mutable state:

```java
// Good: Each test creates own entities
ServerEntity server = new ServerEntity();
server.setId("test-" + System.currentTimeMillis());

// Bad: Static shared state
static ServerEntity sharedServer; // Don't do this
```

## Coverage Requirements

- **Minimum 80%** line coverage for V2 components
- **100%** coverage for critical paths (registration, joining, heartbeats)
- Run `mvn jacoco:report` to generate coverage report

## Common Issues

### Issue: Database Lock on Windows
**Solution**: Ensure proper shutdown between tests:
```java
@AfterEach
void tearDown() {
    DatabaseManager.getInstance().shutdown();
    try {
        Thread.sleep(100); // Give time for file release
    } catch (InterruptedException e) {
        // Ignore
    }
}
```

### Issue: gRPC Channel Not Closed
**Solution**: Always shutdown channels:
```java
@AfterEach
void tearDown() throws Exception {
    channel.shutdownNow();
    channel.awaitTermination(5, TimeUnit.SECONDS);
}
```

### Issue: Test Database Pollution
**Solution**: Use unique IDs:
```java
String uniqueId = "test-" + System.currentTimeMillis() + "-" + UUID.randomUUID();
```

## CI/CD Integration

Tests run automatically on build:
```bash
mvn clean verify
```

This runs:
1. `compile` - Compile all sources
2. `test` - Run all tests
3. `jacoco:report` - Generate coverage
4. `verify` - Check coverage thresholds
