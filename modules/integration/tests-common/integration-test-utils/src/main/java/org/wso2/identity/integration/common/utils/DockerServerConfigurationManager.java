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

package org.wso2.identity.integration.common.utils;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.identity.integration.common.container.WSO2ISContainer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Docker-mode replacement for {@code ServerConfigurationManager} from Carbon TAF.
 * <p>
 * In legacy mode, {@code ServerConfigurationManager} operates by copying files into the local
 * carbon.home filesystem and restarting the OS process. In Docker mode, this class performs the
 * equivalent operations using the Testcontainers API:
 * <ul>
 *   <li>{@link #applyConfiguration(File)} — Stops current container, starts new container with
 *       custom deployment.toml, reconfigures ISServerConfiguration</li>
 *   <li>{@link #applyConfigurationWithoutRestart(File, File, boolean)} — Copies a file into the
 *       running container without restart</li>
 *   <li>{@link #restoreToLastConfiguration(boolean)} — Stops current container, restarts default
 *       container (no custom config)</li>
 *   <li>{@link #copyToComponentDropins(File)} — Copies a JAR into the container's dropins folder</li>
 *   <li>{@link #removeFromComponentDropins(String)} — Not supported in Docker mode (requires container restart)</li>
 * </ul>
 * <p>
 * This class mirrors the API of Carbon TAF's ServerConfigurationManager so that test classes can
 * use either one based on the runtime mode.
 */
public class DockerServerConfigurationManager {

    private static final Log LOG = LogFactory.getLog(DockerServerConfigurationManager.class);

    /** Tracks previous temp directories created during restarts for cleanup. */
    private Path previousCarbonHomeMirror;

    /** Backup of the original deployment.toml extracted from the container before any changes. */
    private Path originalDeploymentTomlBackup;

    private static final String CARBON_HOME_PATH = "/home/wso2carbon/wso2is";
    private static final String DEPLOYMENT_TOML_PATH = CARBON_HOME_PATH + "/repository/conf/deployment.toml";
    private static final String DROPINS_PATH = CARBON_HOME_PATH + "/repository/components/dropins/";
    private static final String WEBAPPS_PATH = CARBON_HOME_PATH + "/repository/deployment/server/webapps/";

    /**
     * Applies a new deployment.toml configuration by stopping the current container and
     * starting a new one with the custom configuration.
     * <p>
     * This is equivalent to {@code ServerConfigurationManager.applyConfiguration(srcFile)}
     * in legacy mode.
     *
     * @param newDeploymentToml the new deployment.toml file to apply
     * @throws Exception if the container fails to start with the new configuration
     */
    public void applyConfiguration(File newDeploymentToml) throws Exception {

        LOG.info("Applying new configuration: " + newDeploymentToml.getAbsolutePath());

        WSO2ISContainer container = WSO2ISContainer.getDefaultInstance();

        // Backup the original deployment.toml before the first modification.
        backupOriginalDeploymentToml(container);

        // Copy the new deployment.toml into the running container.
        container.copyFileIntoContainer(newDeploymentToml.getAbsolutePath(), DEPLOYMENT_TOML_PATH);

        // Restart the IS process in-place (no container replacement).
        container.restartServerProcess();

        // Re-extract carbon.home and reconfigure ISServerConfiguration.
        reconfigureAfterRestart(container);

        LOG.info("Configuration applied successfully via in-process restart.");
    }

    /**
     * Applies a new configuration file by stopping the current container and starting a new one.
     * <p>
     * This is equivalent to
     * {@code ServerConfigurationManager.applyConfiguration(srcFile, targetFile, backup, restart)}
     * in legacy mode.
     *
     * @param srcFile       the source configuration file
     * @param targetFile    the target file (used to determine the destination path in the container)
     * @param backupTarget  whether to backup the target (ignored in Docker mode — container is fresh)
     * @param restart       whether to restart. If false, copies file without restart.
     * @throws Exception if the operation fails
     */
    public void applyConfiguration(File srcFile, File targetFile, boolean backupTarget,
                                    boolean restart) throws Exception {

        WSO2ISContainer container = WSO2ISContainer.getDefaultInstance();

        if (!restart) {
            // Copy without restart — just copy the file into the running container.
            applyConfigurationWithoutRestart(srcFile, targetFile, backupTarget);
            return;
        }

        String targetName = targetFile.getName();
        if ("deployment.toml".equals(targetName)) {
            applyConfiguration(srcFile);
        } else {
            // For other config files: copy into the running container
            // and restart the IS process in-place.
            LOG.info("Applying configuration file: " + srcFile.getName()
                    + " -> " + targetFile.getName() + " (with in-process restart)");

            // Backup original if it's the first modification.
            backupOriginalDeploymentToml(container);

            String containerTargetPath = resolveContainerPath(targetFile);
            container.copyFileIntoContainer(srcFile.getAbsolutePath(), containerTargetPath);

            // Restart IS process in-place.
            container.restartServerProcess();
            reconfigureAfterRestart(container);

            LOG.info("Configuration applied with in-process restart. File: " + srcFile.getName());
        }
    }

    /**
     * Copies a configuration file into the running container without restart.
     * <p>
     * This is equivalent to
     * {@code ServerConfigurationManager.applyConfigurationWithoutRestart(srcFile, targetFile, backup)}
     * in legacy mode.
     *
     * @param srcFile      the source file to copy
     * @param targetFile   the target file (used to determine the container path)
     * @param backupTarget whether to backup (ignored in Docker mode)
     * @throws Exception if the copy fails
     */
    public void applyConfigurationWithoutRestart(File srcFile, File targetFile,
                                                 boolean backupTarget) throws Exception {

        LOG.info("Copying configuration without restart: " + srcFile.getName()
                + " -> " + targetFile.getName());

        WSO2ISContainer container = WSO2ISContainer.getDefaultInstance();
        String containerPath = resolveContainerPath(targetFile);
        container.copyFileIntoContainer(srcFile.getAbsolutePath(), containerPath);

        LOG.info("Configuration file copied to container: " + containerPath);
    }

    /**
     * Restores the server to the default configuration by stopping the current container
     * and starting a fresh one without any custom configuration.
     * <p>
     * This is equivalent to {@code ServerConfigurationManager.restoreToLastConfiguration(restart)}
     * in legacy mode.
     *
     * @param restart whether to restart. In Docker mode, a fresh container is always started.
     * @throws Exception if the restore fails
     */
    public void restoreToLastConfiguration(boolean restart) throws Exception {

        LOG.info("Restoring to default configuration (restart=" + restart + ")");

        if (!restart) {
            LOG.info("No restart requested — skipping restore in Docker mode.");
            return;
        }

        WSO2ISContainer container = WSO2ISContainer.getDefaultInstance();

        // Restore the original deployment.toml from backup.
        if (originalDeploymentTomlBackup != null && Files.exists(originalDeploymentTomlBackup)) {
            container.copyFileIntoContainer(
                    originalDeploymentTomlBackup.toAbsolutePath().toString(), DEPLOYMENT_TOML_PATH);
            LOG.info("Restored original deployment.toml from backup.");
        } else {
            LOG.warn("No original deployment.toml backup found — skipping restore.");
        }

        // Restart IS process in-place.
        container.restartServerProcess();
        reconfigureAfterRestart(container);

        LOG.info("Restored to default configuration via in-process restart.");
    }

    /**
     * Restores the server to the default configuration with restart.
     *
     * @throws Exception if the restore fails
     */
    public void restoreToLastConfiguration() throws Exception {

        restoreToLastConfiguration(true);
    }

    /**
     * Restarts the server by stopping the current container and starting a fresh one.
     * <p>
     * Equivalent to {@code ServerConfigurationManager.restartGracefully()} in legacy mode.
     *
     * @throws Exception if the restart fails
     */
    public void restartGracefully() throws Exception {

        LOG.info("Restarting IS server process gracefully...");
        WSO2ISContainer container = WSO2ISContainer.getDefaultInstance();
        container.restartServerProcess();
        reconfigureAfterRestart(container);
        LOG.info("IS server process restarted gracefully.");
    }

    /**
     * Copies a JAR file to the server's component/dropins directory.
     * <p>
     * Equivalent to {@code ServerConfigurationManager.copyToComponentDropins(jarFile)} in legacy mode.
     *
     * @param jarFile the JAR file to copy
     * @throws Exception if the copy fails
     */
    public void copyToComponentDropins(File jarFile) throws Exception {

        WSO2ISContainer container = WSO2ISContainer.getDefaultInstance();
        String containerPath = DROPINS_PATH + jarFile.getName();
        container.copyFileIntoContainer(jarFile.getAbsolutePath(), containerPath);
        LOG.info("Copied JAR to dropins: " + jarFile.getName());
    }

    /**
     * Removes a JAR from the component/dropins directory.
     * <p>
     * Note: In Docker mode, this is a no-op since removing files from a running container
     * is not straightforward. Tests that need this should use a container restart instead.
     *
     * @param jarFileName the JAR filename to remove
     */
    public void removeFromComponentDropins(String jarFileName) {

        LOG.warn("removeFromComponentDropins is not supported in Docker mode. "
                + "Consider using restoreToLastConfiguration() instead. JAR: " + jarFileName);
    }

    /**
     * Copies a file to a specific path inside the server.
     * This is a general-purpose method for deploying JARs, WARs, or config files
     * into the running container.
     *
     * @param localPath          the local file path
     * @param serverRelativePath the path relative to CARBON_HOME inside the container
     * @throws Exception if the copy fails
     */
    public void copyFileToServer(String localPath, String serverRelativePath) throws Exception {

        WSO2ISContainer container = WSO2ISContainer.getDefaultInstance();
        String containerPath = CARBON_HOME_PATH + "/" + serverRelativePath;
        container.copyFileIntoContainer(localPath, containerPath);
        LOG.info("Copied file to server: " + localPath + " -> " + containerPath);
    }

    /**
     * Copies a WAR file to the server's webapps directory.
     *
     * @param warFile the WAR file to deploy
     * @throws Exception if the copy fails
     */
    public void deployWebApp(File warFile) throws Exception {

        WSO2ISContainer container = WSO2ISContainer.getDefaultInstance();
        String containerPath = WEBAPPS_PATH + warFile.getName();
        container.copyFileIntoContainer(warFile.getAbsolutePath(), containerPath);
        LOG.info("Deployed webapp: " + warFile.getName());
    }

    /**
     * Backs up the original deployment.toml from the container before the first modification.
     * This allows {@link #restoreToLastConfiguration(boolean)} to restore the original config.
     * <p>
     * The backup is only taken once — subsequent calls are no-ops.
     *
     * @param container the running WSO2 IS container
     */
    private void backupOriginalDeploymentToml(WSO2ISContainer container) {

        if (originalDeploymentTomlBackup != null) {
            return; // Already backed up.
        }

        try {
            Path backupFile = Files.createTempFile("deployment-toml-backup-", ".toml");
            // Use Testcontainers copyFileFromContainer to extract the file to the host.
            container.copyFileFromContainer(DEPLOYMENT_TOML_PATH, backupFile.toAbsolutePath().toString());
            originalDeploymentTomlBackup = backupFile;
            LOG.info("Backed up original deployment.toml to: " + backupFile);
        } catch (Exception e) {
            LOG.warn("Failed to backup original deployment.toml: " + e.getMessage(), e);
        }
    }

    /**
     * Reconfigures ISServerConfiguration, re-extracts carbon.home, and re-authenticates
     * after a container restart.
     */
    private void reconfigureAfterRestart(WSO2ISContainer container) throws Exception {

        // Clean up the previous temp directory to avoid leaks.
        if (previousCarbonHomeMirror != null && Files.exists(previousCarbonHomeMirror)) {
            try {
                try (Stream<Path> paths = Files.walk(previousCarbonHomeMirror)) {
                    paths.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                            });
                }
                LOG.info("Cleaned up previous carbon home mirror: " + previousCarbonHomeMirror);
            } catch (IOException e) {
                LOG.warn("Failed to clean up previous temp dir: " + previousCarbonHomeMirror, e);
            }
        }

        // Create a new temp directory for the carbon.home mirror.
        Path tempDir = Files.createTempDirectory("wso2is-carbon-home-");
        previousCarbonHomeMirror = tempDir;
        container.extractCarbonHome(tempDir.toString());

        // Reconfigure ISServerConfiguration with new container's details.
        // This also clears the cached session cookie.
        ISServerConfiguration.reconfigure(
                container.getBaseUrl(),
                container.getMappedHttpsPort(),
                container.getHost(),
                tempDir.toString()
        );

        // Update the carbon.home system property.
        System.setProperty("carbon.home", tempDir.toString());

        // Re-login and cache the session cookie for subsequent test classes.
        // SOAP services may take time to redeploy after a container restart.
        loginAndCacheSessionCookie(container);

        LOG.info("ISServerConfiguration reconfigured after restart. "
                + "carbon.home mirror: " + tempDir);
    }

    /**
     * Verifies server readiness via REST API and refreshes the cached session cookie
     * after a container restart.
     *
     * @param container the running WSO2 IS container
     */
    private void loginAndCacheSessionCookie(WSO2ISContainer container) {

        ISServerConfiguration config = ISServerConfiguration.getInstance();
        int maxRetries = 15;
        long retryDelayMs = 3000;
        Exception lastException = null;

        LOG.info("Verifying REST API readiness after restart...");

        String basicAuth = java.util.Base64.getEncoder().encodeToString(
                (config.getAdminUsername() + ":" + config.getAdminPassword()).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                java.net.URL url = new java.net.URL(config.getBaseUrl() + "/api/server/v1/configs");
                javax.net.ssl.HttpsURLConnection conn =
                        (javax.net.ssl.HttpsURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Basic " + basicAuth);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                int responseCode = conn.getResponseCode();
                conn.disconnect();
                if (responseCode == 200) {
                    config.setSessionCookie("docker-rest-mode");
                    LOG.info("REST API ready on attempt " + attempt + " after restart (HTTP "
                            + responseCode + "). Session cookie placeholder set.");
                    return;
                }
                LOG.info("REST API attempt " + attempt + "/" + maxRetries
                        + " after restart returned HTTP " + responseCode);
            } catch (Exception e) {
                lastException = e;
                if (attempt % 5 == 0 || attempt == 1) {
                    LOG.info("REST API attempt " + attempt + "/" + maxRetries
                            + " after restart failed: " + e.getMessage());
                }
            }
            if (attempt < maxRetries) {
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(
                            "Interrupted while waiting for REST readiness after restart", ie);
                }
            }
        }

        LOG.error("REST API not available after restart. Tried " + maxRetries + " attempts.");
        throw new RuntimeException(
                "REST API not available after restart (" + maxRetries + " attempts, "
                        + (maxRetries * retryDelayMs / 1000) + "s).",
                lastException);
    }

    /**
     * Resolves a local target file path to the corresponding container path.
     * <p>
     * Attempts to map the file's path relative to the carbon.home to the container's
     * CARBON_HOME_PATH. Falls back to using the file name with deployment.toml path.
     */
    private String resolveContainerPath(File targetFile) {

        String targetPath = targetFile.getAbsolutePath();

        // Try to extract the relative path from carbon.home.
        String carbonHome = System.getProperty("carbon.home", "");
        if (!carbonHome.isEmpty() && targetPath.startsWith(carbonHome)) {
            String relativePath = targetPath.substring(carbonHome.length());
            return CARBON_HOME_PATH + relativePath;
        }

        // Common config file patterns.
        String fileName = targetFile.getName();
        if ("deployment.toml".equals(fileName)) {
            return DEPLOYMENT_TOML_PATH;
        }

        // Default: put in conf directory.
        LOG.warn("Could not resolve container path for: " + targetPath
                + ". Using conf directory as default.");
        return CARBON_HOME_PATH + "/repository/conf/" + fileName;
    }
}
