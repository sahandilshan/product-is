# WSO2 Identity Server Integration Tests

This project contains the integration test suite for WSO2 Identity Server. 
The integration tests support two execution modes:

1. **Legacy (TF) mode** (default): Uses Carbon Test Automation Framework (TAF) to spawn a WSO2 IS process from an extracted ZIP.
2. **Docker mode** (`-DdockerTests`): Uses [Testcontainers](https://www.testcontainers.org/) to run WSO2 IS in a Docker container.

Both modes run the **same test classes** — no duplication. Base classes (`ISIntegrationTest`, `RESTTestBase`, etc.) detect the mode at runtime and configure URLs, ports, and authentication accordingly.

---

## 🚀 Docker Mode Setup & Usage

Docker mode builds a Docker image **locally from your `wso2is-{version}.zip`**, ensuring it always reflects your latest code changes.

### Advantages
- **10-20x faster startup**: Cached Docker image starts in ~30-60s vs ~10-20 min for TF mode.
- **Fast re-runs**: Re-running the same test takes ~2-5s (container already running).
- **Standard debugging**: Server-side debugging via exposed port `5005`.

### Prerequisites

#### 1. Build the product distribution ZIP
```bash
mvn clean install --batch-mode -Dmaven.test.skip=true
```
This creates `modules/distribution/target/wso2is-<version>.zip`.

#### 2. Docker runtime
A Docker runtime must be installed and running. The test framework auto-detects socket locations for:
- Docker Desktop (`/var/run/docker.sock`)
- Rancher Desktop (`~/.rd/docker.sock`)
- Colima (`~/.colima/default/docker.sock`)

*If using a different runtime, set `export DOCKER_HOST=unix:///path/to/your/docker.sock`.*

#### 3. Java 21
Docker mode tests require JDK 21 `eclipse-temurin:21-jre-jammy` as the base image.

### Running with Maven

Run all tests in `testng-docker.xml` against a single WSO2 IS container on port `9853`.

```bash
cd modules/integration
mvn clean install -DdockerTests
```

### First Run vs Subsequent Runs

The framework caches two Docker images:
- **Base image** (`wso2is-integration-test:wso2is-<version>`) — built from ZIP
- **Initialized image** (`...-initialized`) — base image + provisioned users/tenants

| | First run | Subsequent runs |
|---|-----------|-----------------|
| Docker image build | ~2-3 min | Skipped |
| User/tenant provisioning | ~10-15s | Skipped |
| IS server startup | ~30-60s | ~30-60s |

*To force a clean start after code changes, delete the cached images:*
```bash
docker rmi wso2is-integration-test:wso2is-7.3.0-m1-SNAPSHOT-initialized
docker rmi wso2is-integration-test:wso2is-7.3.0-m1-SNAPSHOT
```

### Debugging (Docker Mode)

#### Debug test code
Set breakpoints and run your test client code from your IDE.

#### Debug IS server code
1. Run tests with debug enabled:
   ```bash
   mvn clean install -DdockerTests -Dtest.docker.debug=true
   ```
2. Port `5005` is exposed. The mapped host port is logged at startup (e.g., `Debug Port: 52341`).
3. Create a Remote JVM Debug configuration pointing to `localhost:<mapped-port>`, and attach.

#### View container logs
Stream logs inline during test execution:
```bash
mvn clean install -DdockerTests -Dtest.docker.logs=true
```
Or check Docker directly:
```bash
docker logs <container-id>
```

---

## 🏛 Legacy (TF) Mode

Legacy mode extracts the IS distribution ZIP and starts the server as a local OS process. Tests requiring Tomcat (SAML/SSO with travelocity) or LDAP currently need this mode.

```bash
# Must build product first
mvn clean install -Dmaven.test.skip=true

# Run integration tests (default mode)
cd modules/integration
mvn clean install
```
No additional setup is needed.

---

## 🚧 Future Improvements (Docker Mode Only)

The following execution methods are **experimental, are not fully tested, and have known blockers**. 
*Note: The legacy Carbon TAF (TF) mode architecture fundamentally relies on static ports, single-instance assumptions, and strictly managed lifecycle listeners, meaning these features will **never** be realistically supported in TF mode. Docker mode is the only viable path forward for them.*

### 1. Run from IntelliJ IDEA (Experimental)
The ability to right-click and run individual test classes directly in IntelliJ is highly desired but currently fails to apply lifecycle listeners smoothly in all scenarios.
- **Current Blocker:** IntelliJ does not always parse or apply the required `DockerContainerListener` or system properties consistently unless a specific run configuration template is strictly followed.
- **Future Goal:** Full IDE integration where any test class can be run directly via the IDE gutter without complex template setup.

### 2. Parallel Execution (Experimental)
Executing tests in parallel using multiple Docker containers concurrently (`-DdockerTestParallel=N`) has been prototyped but is not stable.
- **Current Blocker:** Mock server port conflicts. Several tests start local mock servers (e.g., embedded Tomcat on 8490, `ServiceExtensionMockServer` on 8587) that bind to fixed host ports. When multiple surefire forks run concurrently, these mock servers clash. Test execution order is also not guaranteed, breaking dependent tests.
- **Future Goal:** Implement dynamic, fork-aware port allocation for all mock servers based on `surefire.forkNumber`.

---

## ⚙️ Docker Architecture Summary
- **Testcontainers Wrapper:** `WSO2ISContainer.java` handles image builds, lifecycle, and port mapping.
- **Configurations:** `DockerTestEnvironment.java` bootstraps the environment (SSL, users), and `ISServerConfiguration.java` detects the runtime mode to adjust URLs dynamically.
- **TestNG Suite:** `testng-docker.xml` is the entry point for sequential Docker tests.
