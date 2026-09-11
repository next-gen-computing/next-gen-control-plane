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

WORKDIR /workspace

# Install parent POM into local repo (skip module validation)
RUN mvn install -N -q

# Build fat JAR from inside the sub-module (parent resolves from local repo)
WORKDIR /workspace/java-control-plane
RUN mvn package -DskipTests -q

# ── Stage 2: Runtime ───────────────────────────
FROM eclipse-temurin:21-jre

WORKDIR /app
# The "-all" classifier is the shaded, runnable jar. The unclassified artifact is the thin library
# jar that desktop-ui depends on, and has no bundled dependencies.
COPY --from=builder /workspace/java-control-plane/target/control-plane-1.0-SNAPSHOT-all.jar /app/control-plane.jar

# Default env vars (overridden by docker-compose)
ENV ROLE=server
ENV NODE_ID=default
ENV CONTROL_PLANE_HOST=control-plane
ENV PREDICTOR_HOST=predictor

# 8085 is the dashboard/API port the server actually binds (DASHBOARD_PORT).
# This previously declared 8080, which nothing has ever listened on.
EXPOSE 50051 9090 8085

ENTRYPOINT ["java", "-jar", "/app/control-plane.jar"]
