# ── Stage 1: Build ──────────────────────────────
FROM eclipse-temurin:21-jdk AS builder

# Install Maven
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

# Copy proto definitions (context is project root)
COPY proto/ /workspace/proto/

# Copy parent POM
COPY pom.xml /workspace/pom.xml

# Copy Maven project
COPY java-control-plane/pom.xml /workspace/java-control-plane/pom.xml
COPY java-control-plane/src/    /workspace/java-control-plane/src/

WORKDIR /workspace/java-control-plane

# Build fat JAR (skip tests for faster builds)
RUN mvn package -DskipTests -q

# ── Stage 2: Runtime ───────────────────────────
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=builder /workspace/java-control-plane/target/control-plane-1.0-SNAPSHOT.jar /app/control-plane.jar

# Default env vars (overridden by docker-compose)
ENV ROLE=server
ENV NODE_ID=default
ENV CONTROL_PLANE_HOST=control-plane
ENV PREDICTOR_HOST=predictor

EXPOSE 50051 9090 8080

ENTRYPOINT ["java", "-jar", "/app/control-plane.jar"]
