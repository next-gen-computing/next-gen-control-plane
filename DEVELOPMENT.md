
#### File 2: `DEVELOPMENT.md`
```markdown
# Development Setup (Phase-1)

## Prerequisites
- Docker Desktop / Docker Compose
- Java 21
- Python 3.11+
- Maven 3.9+

## Running the Full Cluster (One Command)
```bash
docker compose up --build


## Manual Development

1. Start Control Plane: cd java-control-plane && mvn spring-boot:run (later)
2. Start Node Agents: 3 terminals
3. Start Python Predictor: cd python-predictor && poetry run python -m src.predictor

Commit rules: Use Conventional Commits (feat:, fix:, docs:).
Every change must go through PR.