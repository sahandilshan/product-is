/*
 * Copyright (c) 2025, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.identity.integration.common.container;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

import com.github.dockerjava.api.exception.NotFoundException;

import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * Testcontainers wrapper for WSO2 Identity Server.
 * <p>
 * Builds a Docker image from the locally built wso2is ZIP distribution and manages the container
 * lifecycle. This is used in Docker-based integration test mode (-DdockerTests) to replace the
 * Carbon TAF process-fork approach.
 * <p>
 * Key features:
 * <ul>
 *   <li>Builds Docker image from local ZIP (not Docker Hub) — always tests latest code</li>
 *   <li>Singleton pattern — one container per test suite, started once</li>
 *   <li>Extracts carbon.home subset to host for swagger definitions, keystores, etc.</li>
 *   <li>Optional debug port (5005) for server-side remote debugging</li>
 *   <li>Host access via {@code host.testcontainers.internal} for WireMock/mock servers</li>
 * </ul>
 */
public class WSO2ISContainer extends GenericContainer<WSO2ISContainer> {

    private static final Log LOG = LogFactory.getLog(WSO2ISContainer.class);

    private static final int HTTPS_PORT = 9443;
    private static final int HTTP_PORT = 9763;
    private static final int DEBUG_PORT = 5005;
    // Fixed host port matching the legacy Carbon TAF port-offset (9443 + 410 = 9853).
    // In parallel mode, each surefire fork gets its own port: fork 1 → 9853, fork 2 → 9854, etc.
    private static final int FIXED_HTTPS_PORT;
    static {
        int forkNumber = Integer.getInteger("surefire.forkNumber", 1);
        FIXED_HTTPS_PORT = 9853 + (forkNumber - 1);
    }

    private static final String CARBON_HOME_PATH = "/home/wso2carbon/wso2is";
    private static final String STARTUP_LOG_MESSAGE = "WSO2 Carbon started";

    private static final String CARBON_ZIP_PROPERTY = "carbon.zip";
    private static final String DOCKERFILE_PROPERTY = "docker.dockerfile";
    private static final String DEBUG_PROPERTY = "test.docker.debug";
    private static final String LOG_OUTPUT_PROPERTY = "test.docker.logs";

    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);

    private static WSO2ISContainer defaultInstance;

    /** Tracks how many times restartServerProcess() has been called on this instance. */
    private int restartCount = 0;

    /**
     * Creates a WSO2ISContainer with default configuration.
     * Builds the Docker image from the locally built ZIP using the project Dockerfile.
     */
    public WSO2ISContainer() {

        this(null);
    }

    /**
     * Creates a WSO2ISContainer with an optional custom deployment.toml.
     *
     * @param customDeploymentToml path to custom deployment.toml file, or null for default config
     */
    public WSO2ISContainer(File customDeploymentToml) {

        super(getOrBuildImage());

        withExposedPorts(HTTPS_PORT, HTTP_PORT);
        // Bind container port 9443 to fixed host port 9853 (9443 + 410 offset).
        // This matches the legacy Carbon TAF port-offset convention so that all
        // hardcoded "localhost:9853" URLs in 49+ test files work without modification.
        addFixedExposedPort(FIXED_HTTPS_PORT, HTTPS_PORT);
        LOG.info("Container will bind HTTPS to host port " + FIXED_HTTPS_PORT
                + " (fork " + Integer.getInteger("surefire.forkNumber", 1) + ")");
        withAccessToHost(true);

        // Enable debug port if requested.
        if (Boolean.getBoolean(DEBUG_PROPERTY)) {
            addExposedPort(DEBUG_PORT);
            withEnv("JAVA_OPTS", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:" + DEBUG_PORT);
            LOG.info("Docker debug mode enabled. Debug port " + DEBUG_PORT + " will be exposed.");
        }

        // Mount custom deployment.toml if provided.
        if (customDeploymentToml != null && customDeploymentToml.exists()) {
            withCopyFileToContainer(
                    MountableFile.forHostPath(customDeploymentToml.getAbsolutePath()),
                    CARBON_HOME_PATH + "/repository/conf/deployment.toml"
            );
            LOG.info("Using custom deployment.toml: " + customDeploymentToml.getAbsolutePath());
        }

        // Override the entrypoint to append test-specific configs to deployment.toml
        // before starting the server:
        //
        // 1. proxyPort: The IS server generates redirect URLs and response bodies with its
        //    internal port 9443, but tests access it via the mapped host port 9853. Setting
        //    proxyPort makes the server use the external port in generated URLs.
        //
        // 2. DPoP header_validity_period: The default validity is 3000 seconds (50 min).
        //    The DPoP expired-proof test creates a proof 3000.001s old — only 1ms past the
        //    boundary. Docker Desktop's LinuxKit VM on macOS has clock drift vs the host,
        //    making the proof appear NOT expired. Reducing to 300s (5 min) gives a robust
        //    margin: the test's 3000s-old proof is 10x the limit, immune to clock drift.
        String log4j2Props = CARBON_HOME_PATH + "/repository/conf/log4j2.properties";
        String guardFile = "/tmp/.is-configured";
        withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint(
                "/bin/sh", "-c",
                // Guard: only append test-specific configs on the first boot.
                // On subsequent Docker restarts (via restartServerProcess()), the guard file
                // persists in the container filesystem, so these appends are skipped.
                "if [ ! -f " + guardFile + " ]; then "
                        // 1. Append test-specific configs to deployment.toml.
                        + "printf '\\n[transport.https.properties]\\nproxyPort = " + FIXED_HTTPS_PORT + "\\n"
                        + "\\n[event.default_listener.dpop.property]\\nheader_validity_period = \"300\"\\n' >> "
                        + CARBON_HOME_PATH + "/repository/conf/deployment.toml"
                        // 2. Suppress noisy TilesJspServlet FATAL errors in log output.
                        + " && sed -i 's/^loggers = /loggers = tiles-suppress, /' " + log4j2Props
                        + " && printf '\\nlogger.tiles-suppress.name = org.wso2.carbon.ui.TilesJspServlet"
                        + "\\nlogger.tiles-suppress.level = OFF\\n' >> " + log4j2Props
                        + " && touch " + guardFile
                        + "; fi"
                        + " && exec " + CARBON_HOME_PATH + "/bin/wso2server.sh"
        ));

        // Wait for IS to be fully started by watching container logs for the Carbon
        // startup completion message. This is more reliable than HttpWaitStrategy on
        // the login page — Tomcat serves login.jsp (HTTP 200) before Carbon/OSGi/Axis2
        // finish initializing, causing SOAP services like AuthenticationAdmin to be
        // unavailable. The "WSO2 Carbon started" message is printed by
        // StartupFinalizerServiceComponent only after all bundles and services are deployed.
        waitingFor(new LogMessageWaitStrategy()
                .withRegEx(".*" + STARTUP_LOG_MESSAGE + ".*\\n")
                .withStartupTimeout(STARTUP_TIMEOUT));

        // Stream container logs (stdout + stderr) to the test output.
        // Enable with: -Dtest.docker.logs=true
        if (Boolean.getBoolean(LOG_OUTPUT_PROPERTY)) {
            withLogConsumer(new Slf4jLogConsumer(
                    LoggerFactory.getLogger("WSO2IS-CONTAINER")).withSeparateOutputStreams());
            LOG.info("Container log streaming enabled (-D" + LOG_OUTPUT_PROPERTY + "=true).");
        }
    }

    /**
     * Returns the singleton default instance. Creates and starts it on first call.
     *
     * @return the default WSO2ISContainer instance
     */
    public static synchronized WSO2ISContainer getDefaultInstance() {

        if (defaultInstance == null) {
            defaultInstance = new WSO2ISContainer();
            defaultInstance.start();
            LOG.info("Default WSO2 IS container started. Base URL: " + defaultInstance.getBaseUrl());
        }
        return defaultInstance;
    }

    /**
     * Replaces the default instance with a new container (e.g., with a different config).
     * Stops the old instance first.
     *
     * @param newInstance the new container to use as default
     */
    public static synchronized void replaceDefaultInstance(WSO2ISContainer newInstance) {

        if (defaultInstance != null && defaultInstance.isRunning()) {
            LOG.info("Stopping previous default container...");
            defaultInstance.stop();
        }
        defaultInstance = newInstance;
        if (!defaultInstance.isRunning()) {
            defaultInstance.start();
        }
        LOG.info("Default WSO2 IS container replaced. New base URL: " + defaultInstance.getBaseUrl());
    }

    /**
     * Stops and clears the default instance.
     */
    public static synchronized void stopDefaultInstance() {

        if (defaultInstance != null) {
            if (defaultInstance.isRunning()) {
                defaultInstance.stop();
            }
            defaultInstance = null;
            LOG.info("Default WSO2 IS container stopped.");
        }
    }

    /**
     * Gets the base HTTPS URL for the running container (e.g., https://localhost:32789).
     *
     * @return the base URL
     */
    public String getBaseUrl() {

        return "https://" + getHost() + ":" + getMappedPort(HTTPS_PORT);
    }

    /**
     * Gets the mapped HTTPS port on the host.
     *
     * @return the mapped HTTPS port
     */
    public int getMappedHttpsPort() {

        return getMappedPort(HTTPS_PORT);
    }

    /**
     * Gets the mapped debug port on the host (only available if debug mode is enabled).
     *
     * @return the mapped debug port
     * @throws IllegalStateException if debug mode is not enabled
     */
    public int getMappedDebugPort() {

        if (!Boolean.getBoolean(DEBUG_PROPERTY)) {
            throw new IllegalStateException("Debug mode is not enabled. Set -D" + DEBUG_PROPERTY + "=true");
        }
        return getMappedPort(DEBUG_PORT);
    }

    /**
     * Extracts carbon.home directories from the container to a local directory.
     * This provides access to swagger definitions, config files, and other resources
     * that tests need from the server filesystem.
     *
     * @param targetDir the local directory to extract to
     * @throws IOException if extraction fails
     */
    public void extractCarbonHome(String targetDir) throws IOException {

        LOG.info("Extracting carbon.home from container to: " + targetDir);

        // Directories to extract from the container.
        String[] directoriesToExtract = {
                "/repository/conf",
                "/repository/resources/security",
                "/repository/deployment/server/webapps/api/WEB-INF/lib",
                "/dbscripts",
                "/repository/deployment/server/servicemetafiles",
                "/repository/logs"
        };

        for (String dir : directoriesToExtract) {
            String containerPath = CARBON_HOME_PATH + dir;
            Path localBase = Paths.get(targetDir);

            try {
                extractDirectoryFromContainer(containerPath, localBase);
                LOG.info("Extracted: " + containerPath + " -> " + localBase);
            } catch (Exception e) {
                // Some directories may not exist in all configurations.
                LOG.warn("Could not extract " + containerPath + ": " + e.getMessage());
            }
        }

        LOG.info("Carbon home extraction completed.");
    }

    /**
     * Extracts a directory from the container to the local filesystem using the Docker tar stream.
     * {@code copyFileFromContainer()} only handles single files — this method handles directories
     * by reading the tar archive returned by the Docker API.
     *
     * @param containerPath absolute path of the directory inside the container
     * @param localBase     local base directory (the container's CARBON_HOME_PATH prefix is
     *                      stripped, so /home/wso2carbon/wso2is/repository/conf/carbon.xml
     *                      becomes localBase/repository/conf/carbon.xml)
     */
    private void extractDirectoryFromContainer(String containerPath, Path localBase)
            throws IOException {

        copyFileFromContainer(containerPath, inputStream -> {
            try (TarArchiveInputStream tarStream = new TarArchiveInputStream(inputStream)) {
                TarArchiveEntry entry;
                while ((entry = tarStream.getNextTarEntry()) != null) {
                    // Entry name is relative to the container path's parent.
                    // E.g., for container path /home/wso2carbon/wso2is/repository/conf,
                    // entry names look like "conf/carbon.xml", "conf/deployment.toml", etc.
                    // We need to reconstruct the full relative path from CARBON_HOME_PATH.
                    String entryName = entry.getName();

                    // Build the local path: strip the CARBON_HOME_PATH prefix from containerPath
                    // to get the relative directory, then combine with the entry name.
                    String relativeDirFromCarbonHome = containerPath.substring(
                            CARBON_HOME_PATH.length());
                    // relativeDirFromCarbonHome is e.g. "/repository/conf"
                    // entryName is e.g. "conf/carbon.xml" or "conf/"
                    // We want: localBase/repository/conf/carbon.xml

                    // The entryName starts with the last segment of containerPath.
                    // Get the parent of the relative dir to combine correctly.
                    String parentDir = relativeDirFromCarbonHome.substring(
                            0, relativeDirFromCarbonHome.lastIndexOf('/'));
                    // parentDir is e.g. "/repository"

                    Path targetPath = localBase.resolve(
                            (parentDir + "/" + entryName).substring(1));  // Remove leading /

                    if (entry.isDirectory()) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.createDirectories(targetPath.getParent());
                        Files.copy(tarStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            return null;
        });
    }

    /**
     * Copies a file into the running container.
     *
     * @param localPath     the local file path
     * @param containerPath the target path inside the container
     */
    public void copyFileIntoContainer(String localPath, String containerPath) {

        copyFileToContainer(MountableFile.forHostPath(localPath), containerPath);
        LOG.info("Copied " + localPath + " -> " + containerPath + " (inside container)");
    }

    /**
     * Restarts the IS server process inside the running container without destroying it.
     * <p>
     * Uses the Docker restart API ({@code docker restart}) which sends SIGTERM to PID 1
     * (the IS process started via {@code exec wso2server.sh}), waits for it to exit, and
     * then re-runs the container's entrypoint. Since the container's filesystem is preserved,
     * any config files copied into it (e.g., a new deployment.toml) will be picked up on restart.
     * <p>
     * This is significantly faster than destroying and recreating the container (~15-20s vs ~40-60s)
     * and preserves port mappings, so no reconfiguration of URLs is needed.
     *
     * @param timeoutSeconds seconds to wait for the server to stop before SIGKILL
     * @throws RuntimeException if the server doesn't start within the startup timeout
     */
    public void restartServerProcess(int timeoutSeconds) {

        restartCount++;
        int expectedOccurrences = restartCount + 1; // +1 for the initial boot
        LOG.info("Restarting IS server process inside container (timeout=" + timeoutSeconds
                + "s, expecting " + expectedOccurrences + " startup messages)...");

        // Use Docker Java client to restart the container.
        DockerClientFactory.instance().client()
                .restartContainerCmd(getContainerId())
                .withTimeout(timeoutSeconds)
                .exec();

        // Wait for the server to be fully started by polling for the startup log message.
        // The LogMessageWaitStrategy used during initial startup only watches logs from
        // container creation. After a restart we need to poll the logs manually.
        long deadlineMs = System.currentTimeMillis() + STARTUP_TIMEOUT.toMillis();
        boolean started = false;

        while (System.currentTimeMillis() < deadlineMs) {
            try {
                String logs = getLogs();
                // Count occurrences of the startup message. After restart N there should be
                // at least N+1 occurrences (one from each boot).
                int count = 0;
                int idx = 0;
                while ((idx = logs.indexOf(STARTUP_LOG_MESSAGE, idx)) != -1) {
                    count++;
                    idx += STARTUP_LOG_MESSAGE.length();
                }
                if (count >= expectedOccurrences) {
                    started = true;
                    break;
                }
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for IS restart", e);
            }
        }

        if (!started) {
            throw new RuntimeException(
                    "IS server did not start after restart within " + STARTUP_TIMEOUT.toSeconds() + "s. "
                            + "Check container logs for errors.");
        }

        LOG.info("IS server process restarted successfully inside container.");
    }

    /**
     * Restarts the IS server process with a default stop timeout of 30 seconds.
     *
     * @throws RuntimeException if the server doesn't start within the startup timeout
     */
    public void restartServerProcess() {

        restartServerProcess(30);
    }

    /**
     * Gets the carbon home path inside the container.
     *
     * @return the carbon home path
     */
    public String getCarbonHomePath() {

        return CARBON_HOME_PATH;
    }

    /**
     * Returns an existing Docker image if one matches the current ZIP version,
     * or builds a new one. The image is tagged with the ZIP filename (e.g.,
     * "wso2is-integration-test:wso2is-7.3.0-m1-SNAPSHOT") so it is reused across
     * test runs and automatically rebuilds when the ZIP changes.
     */
    private static ImageFromDockerfile getOrBuildImage() {

        String carbonZipPath = System.getProperty(CARBON_ZIP_PROPERTY);
        String dockerfilePath = System.getProperty(DOCKERFILE_PROPERTY);

        if (carbonZipPath == null || carbonZipPath.isEmpty()) {
            throw new IllegalStateException(
                    "System property '" + CARBON_ZIP_PROPERTY + "' is not set. "
                            + "Set it to the path of wso2is-{version}.zip "
                            + "(e.g., distribution/target/wso2is-7.2.1-SNAPSHOT.zip)");
        }

        File carbonZip = new File(carbonZipPath);
        if (!carbonZip.exists()) {
            throw new IllegalStateException(
                    "Carbon ZIP not found at: " + carbonZipPath
                            + ". Build the distribution first: mvn clean install -Dmaven.test.skip=true");
        }

        // Deterministic tag from ZIP filename: "wso2is-integration-test:wso2is-7.3.0-m1-SNAPSHOT"
        String zipName = carbonZip.getName().replace(".zip", "");
        String fullImageName = "wso2is-integration-test:" + zipName;

        // Check if base image already exists — skip rebuild if so.
        if (imageExists(fullImageName)) {
            LOG.info("Reusing existing base Docker image: " + fullImageName);
            return new ImageFromDockerfile(fullImageName, false)
                    .withDockerfileFromBuilder(builder -> builder.from(fullImageName).build());
        }

        LOG.info("Building Docker image '" + fullImageName + "' from ZIP: " + carbonZipPath);

        ImageFromDockerfile image = new ImageFromDockerfile(fullImageName, false);

        if (dockerfilePath != null && new File(dockerfilePath).exists()) {
            image.withFileFromFile("Dockerfile", new File(dockerfilePath))
                    .withFileFromFile(carbonZip.getName(), carbonZip)
                    .withBuildArg("WSO2IS_ZIP", carbonZip.getName());
        } else {
            LOG.warn("Dockerfile not found at: " + dockerfilePath + ". Using inline Dockerfile.");
            image.withFileFromFile(carbonZip.getName(), carbonZip)
                    .withDockerfileFromBuilder(builder ->
                            builder.from("eclipse-temurin:21-jre-jammy")
                                    .run("groupadd -g 802 wso2 && useradd -u 802 -g wso2 -m wso2carbon")
                                    .run("apt-get update && apt-get install -y unzip curl "
                                            + "&& rm -rf /var/lib/apt/lists/*")
                                    .copy(carbonZip.getName(), "/tmp/wso2is.zip")
                                    .run("unzip -q /tmp/wso2is.zip -d /home/wso2carbon/ "
                                            + "&& mv /home/wso2carbon/wso2is-* /home/wso2carbon/wso2is "
                                            + "&& rm /tmp/wso2is.zip "
                                            + "&& chown -R wso2carbon:wso2 /home/wso2carbon/wso2is")
                                    .env("CARBON_HOME", CARBON_HOME_PATH)
                                    .user("wso2carbon")
                                    .workDir(CARBON_HOME_PATH)
                                    .expose(HTTPS_PORT, HTTP_PORT, DEBUG_PORT)
                                    .entryPoint("./bin/wso2server.sh")
                                    .build());
        }

        return image;
    }

    /**
     * Checks whether a Docker image with the given name (including tag) already exists locally.
     */
    private static boolean imageExists(String imageName) {

        try {
            DockerClientFactory.instance().client().inspectImageCmd(imageName).exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        }
    }
}
