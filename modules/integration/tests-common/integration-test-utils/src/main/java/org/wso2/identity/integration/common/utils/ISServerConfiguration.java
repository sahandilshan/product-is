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

/**
 * Central singleton holding resolved server URLs and configuration for Docker-based integration tests.
 * <p>
 * In Docker mode, this is populated from {@code WSO2ISContainer} at startup.
 * In legacy mode, this class is unused — existing AutomationContext-based URL resolution applies.
 * <p>
 * Usage:
 * <pre>
 *     if (ISServerConfiguration.isDockerMode()) {
 *         String url = ISServerConfiguration.getInstance().getBaseUrl();
 *     }
 * </pre>
 */
public class ISServerConfiguration {

    private static final Log LOG = LogFactory.getLog(ISServerConfiguration.class);

    private static final String DOCKER_MODE_PROPERTY = "test.docker.mode";
    private static final String DEFAULT_ADMIN_USERNAME = "admin";
    private static final String DEFAULT_ADMIN_PASSWORD = "admin";

    private static volatile ISServerConfiguration instance;

    private volatile String baseUrl;
    private volatile int httpsPort;
    private volatile String hostname;
    private volatile String carbonHomeMirror;
    private volatile String sessionCookie;

    private ISServerConfiguration() {

    }

    /**
     * Checks whether Docker mode is active.
     *
     * @return true if running in Docker-based integration test mode
     */
    public static boolean isDockerMode() {

        return Boolean.getBoolean(DOCKER_MODE_PROPERTY);
    }

    /**
     * Initializes the server configuration from a running Docker container.
     *
     * @param baseUrl          the base HTTPS URL (e.g., https://localhost:32789)
     * @param httpsPort        the mapped HTTPS port on the host
     * @param hostname         the hostname of the container
     * @param carbonHomeMirror the local directory with extracted carbon.home subset
     */
    public static synchronized void initialize(String baseUrl, int httpsPort, String hostname,
                                               String carbonHomeMirror) {

        instance = new ISServerConfiguration();
        instance.baseUrl = baseUrl;
        instance.httpsPort = httpsPort;
        instance.hostname = hostname;
        instance.carbonHomeMirror = carbonHomeMirror;

        LOG.info("ISServerConfiguration initialized — baseUrl: " + baseUrl
                + ", httpsPort: " + httpsPort + ", carbonHomeMirror: " + carbonHomeMirror);
    }

    /**
     * Reconfigures the server configuration (e.g., after a container restart with new config).
     *
     * @param baseUrl          the new base HTTPS URL
     * @param httpsPort        the new mapped HTTPS port
     * @param hostname         the new hostname
     * @param carbonHomeMirror the new carbon.home mirror path
     */
    public static synchronized void reconfigure(String baseUrl, int httpsPort, String hostname,
                                                String carbonHomeMirror) {

        if (instance == null) {
            initialize(baseUrl, httpsPort, hostname, carbonHomeMirror);
            return;
        }

        instance.baseUrl = baseUrl;
        instance.httpsPort = httpsPort;
        instance.hostname = hostname;
        instance.carbonHomeMirror = carbonHomeMirror;
        instance.sessionCookie = null;

        LOG.info("ISServerConfiguration reconfigured — baseUrl: " + baseUrl
                + ", httpsPort: " + httpsPort + " (sessionCookie invalidated)");
    }

    /**
     * Checks whether the server configuration has been initialized.
     *
     * @return true if initialized
     */
    public static synchronized boolean isInitialized() {

        return instance != null;
    }

    /**
     * Gets the singleton instance.
     *
     * @return the ISServerConfiguration instance
     * @throws IllegalStateException if not initialized
     */
    public static synchronized ISServerConfiguration getInstance() {

        if (instance == null) {
            throw new IllegalStateException(
                    "ISServerConfiguration not initialized. "
                            + "Ensure DockerContainerInitializerTestCase runs before tests.");
        }
        return instance;
    }

    /**
     * Gets the base HTTPS URL (e.g., https://localhost:32789).
     */
    public String getBaseUrl() {

        return baseUrl;
    }

    /**
     * Gets the mapped HTTPS port on the host.
     */
    public int getHttpsPort() {

        return httpsPort;
    }

    /**
     * Gets the hostname of the container.
     */
    public String getHostname() {

        return hostname;
    }

    /**
     * Gets the local directory containing the extracted carbon.home subset.
     * This includes swagger definitions, config files, keystores, etc.
     */
    public String getCarbonHomeMirror() {

        return carbonHomeMirror;
    }

    /**
     * Gets the cached session cookie from the initial SOAP login.
     *
     * @return the session cookie, or null if not yet cached or invalidated after a restart
     */
    public String getSessionCookie() {

        return sessionCookie;
    }

    /**
     * Caches the session cookie obtained from a successful SOAP login.
     *
     * @param sessionCookie the session cookie to cache
     */
    public void setSessionCookie(String sessionCookie) {

        this.sessionCookie = sessionCookie;
    }

    /**
     * Gets the backend service URL (e.g., https://localhost:32789/services/).
     */
    public String getBackendUrl() {

        return baseUrl + "/services/";
    }

    /**
     * Gets the server URL without /services/ suffix (e.g., https://localhost:32789/).
     */
    public String getServerUrl() {

        return baseUrl + "/";
    }

    /**
     * Gets the default admin username.
     */
    public String getAdminUsername() {

        return DEFAULT_ADMIN_USERNAME;
    }

    /**
     * Gets the default admin password.
     */
    public String getAdminPassword() {

        return DEFAULT_ADMIN_PASSWORD;
    }
}
