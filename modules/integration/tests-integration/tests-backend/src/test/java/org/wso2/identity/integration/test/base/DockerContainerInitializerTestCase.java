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

package org.wso2.identity.integration.test.base;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.testng.Assert;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Test;
import org.wso2.identity.integration.common.utils.ISServerConfiguration;

/**
 * Initializer test case for Docker-based integration tests.
 * <p>
 * The actual container startup is handled by {@code DockerContainerListener.onExecutionStart()}
 * which runs before TestNG instantiates test classes (important for {@code @Factory} constructors).
 * <p>
 * This class validates that the container is ready and logs configuration details.
 * It must be the first class in testng-docker.xml.
 */
public class DockerContainerInitializerTestCase {

    private static final Log LOG = LogFactory.getLog(DockerContainerInitializerTestCase.class);

    @BeforeSuite(alwaysRun = true)
    public void validateContainerReady() throws Exception {

        if (!ISServerConfiguration.isDockerMode()) {
            LOG.info("Not in Docker mode — skipping Docker container validation.");
            return;
        }

        ISServerConfiguration config = ISServerConfiguration.getInstance();
        LOG.info("=== Docker Container Validation ===");
        LOG.info("  Base URL:          " + config.getBaseUrl());
        LOG.info("  HTTPS Port:        " + config.getHttpsPort());
        LOG.info("  Carbon Home Mirror: " + config.getCarbonHomeMirror());
        LOG.info("Docker container is ready for tests.");
    }

    @Test(alwaysRun = true, groups = "wso2.is",
            description = "Verify Docker container is initialized and REST API is reachable")
    public void testContainerInitialized() throws Exception {

        if (!ISServerConfiguration.isDockerMode()) {
            LOG.info("Not in Docker mode — skipping container initialization test.");
            return;
        }

        ISServerConfiguration config = ISServerConfiguration.getInstance();
        Assert.assertNotNull(config.getBaseUrl(), "Base URL should not be null");
        Assert.assertTrue(config.getHttpsPort() > 0, "HTTPS port should be positive");
        Assert.assertNotNull(config.getSessionCookie(), "Session cookie should be set");

        // Verify REST API is reachable.
        String basicAuth = java.util.Base64.getEncoder().encodeToString(
                (config.getAdminUsername() + ":" + config.getAdminPassword()).getBytes());
        java.net.URL url = new java.net.URL(config.getBaseUrl() + "/api/server/v1/configs");
        javax.net.ssl.HttpsURLConnection conn =
                (javax.net.ssl.HttpsURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Basic " + basicAuth);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        int responseCode = conn.getResponseCode();
        conn.disconnect();

        Assert.assertEquals(responseCode, 200,
                "REST API should return 200. Got: " + responseCode);
        LOG.info("Docker container initialization test passed. REST API returned HTTP 200.");
    }
}
