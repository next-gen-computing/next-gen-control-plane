# Contributing to Next-Gen Control Plane

First off, thank you for considering contributing! It's people like you that make this project a great tool.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [How Can I Contribute?](#how-can-i-contribute)
- [Development Workflow](#development-workflow)
- [Style Guidelines](#style-guidelines)
- [Commit Messages](#commit-messages)
- [Pull Request Process](#pull-request-process)
- [Testing Requirements](#testing-requirements)

## Code of Conduct

This project and everyone participating in it is governed by our commitment to:
- Being respectful and inclusive
- Focusing on constructive feedback
- Prioritizing learning and collaboration

## Getting Started

1. **Fork the repository** on GitHub
2. **Clone your fork** locally
   ```bash
   git clone https://github.com/YOUR_USERNAME/next-gen-control-plane.git
   cd next-gen-control-plane
   ```
3. **Install prerequisites** (see [DEVELOPMENT.md](DEVELOPMENT.md))
4. **Create a branch** for your changes
   ```bash
   git checkout -b feature/your-feature-name
   ```

## How Can I Contribute?

### Reporting Bugs

Before creating a bug report:
- Check [existing issues](../../issues) to avoid duplicates
- Use the latest version
- Collect information about the bug (logs, environment)

**Good bug reports include:**
- Clear title and description
- Steps to reproduce
- Expected vs actual behavior
- Environment details (OS, Java version, Docker version)
- Relevant log output

### Suggesting Enhancements

- Use a clear, descriptive title
- Explain why this enhancement would be useful
- Provide examples of how it would work
- List any potential drawbacks

### Pull Requests

1. Update README.md if user-facing changes
2. Update DEVELOPMENT.md if dev-facing changes
3. Add/update tests for any new functionality
4. Ensure all tests pass
5. Update CHANGELOG.md under [Unreleased]
6. Follow style guidelines below

## Development Workflow

### 1. Setup Development Environment

```bash
# Start all services
docker compose up --build

# Or for local development (see DEVELOPMENT.md)
cd java-control-plane && mvn clean package -DskipTests
```

### 2. Make Changes

- Write code following style guidelines
- Add tests for new functionality
- Run tests locally before pushing

### 3. Test Your Changes

```bash
# Java tests
cd java-control-plane
mvn clean test

# Integration test
docker compose up --build -d
sleep 15
python scripts/integration-test.py
```

### 4. Commit and Push

```bash
git add .
git commit -m "feat: add your feature description"
git push origin feature/your-feature-name
```

### 5. Create Pull Request

- Fill in the PR template
- Link related issues
- Request review from maintainers

## Style Guidelines

### Java

- **Java 21** features are encouraged (switch expressions, var, records)
- **No `System.out.println()`** — use SLF4J logging
- **Real OS readings only** — never fake or random data in production code
- **Thread-safe by default** — use volatile, AtomicInteger, ConcurrentHashMap
- **Maximum line length:** 120 characters
- **Indentation:** 4 spaces (no tabs)

**Good Example:**
```java
public class NodeRecord {
    private final String nodeId;
    private volatile float cpuUsage;
    
    public void setCpuUsage(float cpuUsage) {
        this.cpuUsage = cpuUsage;
    }
}
```

**Bad Example:**
```java
public class BadCode {
    // Don't use println
    System.out.println("Debug: " + value);
    
    // Don't use random for metrics
    float cpu = (float) (Math.random() * 100);
}
```

### Python

- **Type hints** where possible
- **f-strings** for string formatting
- **logging module** (not print)
- Follow **PEP 8**
- **Maximum line length:** 100 characters

**Good Example:**
```python
import logging

LOG = logging.getLogger(__name__)

def get_prediction(node_id: str, cpu: float) -> dict:
    LOG.info(f"Processing node {node_id}")
    return {"load": cpu * 0.5}
```

### Documentation

- Use clear, concise language
- Include code examples
- Update table of contents if adding sections
- Check spelling and grammar

## Commit Messages

We follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <subject>

<body>

<footer>
```

### Types

- **feat:** New feature
- **fix:** Bug fix
- **docs:** Documentation only
- **style:** Code style (formatting, semicolons, etc.)
- **refactor:** Code change that neither fixes a bug nor adds a feature
- **perf:** Performance improvement
- **test:** Adding or correcting tests
- **chore:** Build process or auxiliary tool changes

### Examples

```bash
# Good commits
feat: add health check endpoints
fix: resolve heartbeat timeout edge case
docs: update API documentation with examples
refactor: simplify heartbeat monitor logic
test: add NodeRecord unit tests for thread safety
perf: optimize node registry lookups

# Bad commits
updated code                    # Too vague
fix                           # No description
added some stuff              # Unclear
bugfix                       # Not conventional format
```

### Scope

Optional but helpful:
- `controlplane` — Control Plane server
- `agent` — Node Agent
- `predictor` — Python predictor service
- `docs` — Documentation
- `tests` — Test suite

**Example:** `feat(controlplane): add node health status endpoint`

## Pull Request Process

### Before Submitting

- [ ] Tests pass locally
- [ ] Code follows style guidelines
- [ ] Documentation updated (if needed)
- [ ] CHANGELOG.md updated under [Unreleased]
- [ ] Commit messages follow conventions
- [ ] Branch is up to date with main

### PR Description Template

```markdown
## What
Brief description of changes

## Why
Explanation of why these changes are needed

## How
Technical details of implementation

## Testing
- [ ] Unit tests added/updated
- [ ] Integration tests pass
- [ ] Manual testing completed

## Screenshots (if UI changes)
Before/After screenshots

## Related Issues
Fixes #123, Relates to #456
```

### Review Process

1. **Automated checks** must pass (CI/CD, tests)
2. **Code review** by at least one maintainer
3. **Approval** required before merge
4. **Squash merge** preferred for clean history

## Testing Requirements

### Unit Tests

- Required for all new public methods
- Use descriptive test names
- Follow AAA pattern (Arrange, Act, Assert)

```java
@Test
void testNodeMarkedDeadAfterTimeout() {
    // Arrange
    NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1");
    record.setLastHeartbeatMillis(System.currentTimeMillis() - 10_000);
    
    // Act
    monitor.checkHeartbeats();
    
    // Assert
    assertEquals("SUSPECTED_DEAD", record.getStatus());
}
```

### Coverage Requirements

- **Minimum:** 60% instruction coverage
- **Target:** 80%+ for all components
- **CI will fail** if coverage drops below threshold

### Integration Tests

- Required for feature-level changes
- Must pass before PR can be merged
- Use `scripts/integration-test.py` as template

## Questions?

- Open an issue for questions
- Check existing documentation first
- Join discussions in pull requests

## Recognition

Contributors will be:
- Listed in CONTRIBUTORS.md
- Mentioned in release notes
- Credited in commit history

Thank you for contributing! 🎉

---

**Maintainers:** Team Next-Gen  
**Last Updated:** August 2026
