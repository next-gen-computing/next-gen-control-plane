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

Run the module(s) your change actually touches — see
[DEVELOPMENT.md's Testing section](DEVELOPMENT.md#-testing) for the exact commands for each of the four
modules (`java-control-plane`, `desktop-ui`, `cli`, `python-predictor`) and what CI itself runs. In short:

```bash
# Whichever Java module(s) you touched
cd java-control-plane   # or desktop-ui, or cli
mvn clean test

# Python
cd python-predictor
pytest tests/ -v

# Server-side smoke test (only meaningful for control-plane/predictor changes)
docker compose up --build -d
sleep 15
python scripts/integration-test.py
docker compose down
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

This project does **not** use Conventional Commits (`feat:`/`fix:`/`docs:` prefixes) — check `git log`
and you won't find any. The real, established convention is a plain, descriptive, imperative-mood
subject line that says what changed and, critically, **why** — the reasoning is what a future reader
actually needs, since the diff itself already shows *what* changed:

```
Fix TaskExecutionService.shutdown() to actually wait for in-flight work

executor.shutdown() alone stops new submissions but doesn't block until an
already-running background history write finishes. Caught by a real,
reproducible test failure (a Windows DirectoryNotEmptyException from JUnit's
@TempDir cleanup racing an in-flight write) while re-running the full
desktop-ui suite — not a flaky test, a genuine shutdown-ordering bug.

Now uses the same bounded awaitTermination(5s) + shutdownNow() fallback
idiom DockerResourcesMonitoringService.stopMonitoring() already uses
elsewhere in this codebase.
```

The subject line names the real thing that changed (a class, a behavior, a bug) — not a category label.
The body explains the *why*: what was broken, how it was found (a real test failure, a live run, a
CVE advisory — not "seemed like a good idea"), and what the fix actually does. Look through recent
`git log` output before your first commit here; matching the existing tone matters more than any fixed
template.

### Examples

```bash
# Good — real, specific, explains why
Fix a stale README callout claiming nx image/volume/network writes and bounded log replay aren't built
Pin xgboost to a version CI's own resolver can actually see
Fix TaskExecutionService.shutdown() to actually wait for in-flight work

# Bad — vague, no reasoning, or a category label standing in for a real description
updated code
fix
added some stuff
feat: add feature
```

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

Contributors are credited in commit history and mentioned in release notes (`CHANGELOG.md`) — there is
no separate `CONTRIBUTORS.md` file in this repository to be listed in.

Thank you for contributing! 🎉

---

**Maintainers:** Team Next-Gen  
**Last Updated:** September 2026
