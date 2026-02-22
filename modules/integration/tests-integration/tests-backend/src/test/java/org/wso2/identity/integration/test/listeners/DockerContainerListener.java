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

package org.wso2.identity.integration.test.listeners;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.testng.IExecutionListener;
import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ITestResult;
import org.wso2.identity.integration.common.container.WSO2ISContainer;
import org.wso2.identity.integration.common.utils.DockerTestEnvironment;
import org.wso2.identity.integration.common.utils.ISServerConfiguration;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

/**
 * TestNG execution listener for Docker-based integration test mode.
 * <p>
 * Replaces the Carbon TAF listeners (TestExecutionListener, TestManagerListener) which
 * trigger CarbonServerExtension to start/stop the IS process. In Docker mode, this listener
 * delegates core initialization to {@link DockerTestEnvironment} and handles provisioning
 * of test users and tenants.
 * <p>
 * When running from Maven ({@code -DdockerTests}), this listener is registered in testng-docker.xml.
 * When running from IntelliJ without the template, {@link DockerTestEnvironment} is triggered
 * directly from {@code ISIntegrationTest.init()}.
 */
public class DockerContainerListener implements IExecutionListener, IInvokedMethodListener {

    private static final Log LOG = LogFactory.getLog(DockerContainerListener.class);

    @Override
    public void onExecutionStart() {

        if (!ISServerConfiguration.isDockerMode() && !DockerTestEnvironment.shouldUseDockerMode()) {
            return;
        }

        LOG.info("=== Docker Integration Test Mode (via DockerContainerListener) ===");

        // Delegate all initialization (container start, SSL, provisioning) to DockerTestEnvironment.
        // Provisioning of test users and tenants is now handled inside ensureInitialized(),
        // so it works in both Maven (via this listener) and IntelliJ (via ISIntegrationTest.init()).
        DockerTestEnvironment.ensureInitialized();
    }

    @Override
    public void onExecutionFinish() {

        if (!ISServerConfiguration.isDockerMode() && !DockerTestEnvironment.isInitialized()) {
            return;
        }

        DockerTestEnvironment.shutdown();
    }

    @Override
    public void beforeInvocation(IInvokedMethod method, ITestResult testResult) {

        if (method.isTestMethod()) {
            LOG.info("=================== Running the test method "
                    + testResult.getTestClass().getRealClass().getName() + "."
                    + method.getTestMethod().getMethodName() + " ===================");
        }
    }

    @Override
    public void afterInvocation(IInvokedMethod method, ITestResult testResult) {

        if (method.isTestMethod()) {
            String testName = testResult.getTestClass().getRealClass().getName() + "."
                    + method.getTestMethod().getMethodName();
            switch (testResult.getStatus()) {
                case ITestResult.SUCCESS:
                    LOG.info("=================== On test success " + testName + " ===================");
                    break;
                case ITestResult.FAILURE:
                    LOG.info("=================== On test failure " + testName + " ===================");
                    break;
                case ITestResult.SKIP:
                    LOG.info("=================== On test skip " + testName + " ===================");
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Dumps comprehensive diagnostics when REST API login fails.
     * Searches the full container logs for key startup indicators.
     */
    static void dumpDiagnostics(WSO2ISContainer container, ISServerConfiguration config,
                                int maxRetries, long retryDelayMs) {

        LOG.error("=== DIAGNOSTICS: REST API not available after "
                + maxRetries + " attempts (" + (maxRetries * retryDelayMs / 1000) + "s) ===");

        try {
            String fullLogs = container.getLogs();
            String[] allLines = fullLogs.split("\n");

            LOG.error("Total container log lines: " + allLines.length);

            // 1. Check if Carbon startup completed.
            boolean startupFound = fullLogs.contains("WSO2 Carbon started");
            LOG.error("'WSO2 Carbon started' found in logs: " + startupFound);

            // 2. Find AuthenticationAdmin mentions (excluding our retry errors).
            StringBuilder authAdminLines = new StringBuilder();
            for (String line : allLines) {
                if (line.contains("AuthenticationAdmin")
                        && !line.contains("service cannot be found")) {
                    authAdminLines.append("  ").append(line).append("\n");
                }
            }
            if (authAdminLines.length() > 0) {
                LOG.error("AuthenticationAdmin log entries (excluding retry errors):\n"
                        + authAdminLines);
            } else {
                LOG.error("AuthenticationAdmin: NO mentions found in logs "
                        + "(service may not be in the distribution)");
            }

            // 3. Find ERROR/FATAL lines during startup (exclude retry-generated errors).
            StringBuilder startupErrors = new StringBuilder();
            int errorCount = 0;
            for (String line : allLines) {
                if ((line.contains("] ERROR ") || line.contains("] FATAL "))
                        && !line.contains("AxisEngine")
                        && !line.contains("TilesJspServlet")
                        && !line.contains("service cannot be found")) {
                    startupErrors.append("  ").append(line).append("\n");
                    errorCount++;
                    if (errorCount >= 30) {
                        startupErrors.append("  ... (").append("truncated, more errors exist)\n");
                        break;
                    }
                }
            }
            if (startupErrors.length() > 0) {
                LOG.error("Startup ERROR/FATAL lines (non-retry):\n" + startupErrors);
            } else {
                LOG.error("No startup ERROR/FATAL lines found (excluding retry noise).");
            }

            // 4. Show first 100 lines of logs (startup sequence).
            StringBuilder startupSequence = new StringBuilder();
            int startupLines = Math.min(100, allLines.length);
            for (int i = 0; i < startupLines; i++) {
                startupSequence.append("  ").append(allLines[i]).append("\n");
            }
            LOG.error("=== FIRST " + startupLines + " LINES OF CONTAINER LOGS ===\n"
                    + startupSequence);

        } catch (Exception e) {
            LOG.error("Failed to retrieve container logs: " + e.getMessage());
        }

        // 5. Try HTTP GET to /services/ to list available Axis2 services.
        try {
            URL servicesUrl = new URL(config.getBaseUrl() + "/services/listServices");
            HttpsURLConnection conn = (HttpsURLConnection) servicesUrl.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int responseCode = conn.getResponseCode();
            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && response.length() < 5000) {
                    response.append(line).append("\n");
                }
            }
            LOG.error("=== GET /services/listServices (HTTP " + responseCode + ") ===\n"
                    + response);
        } catch (Exception e) {
            LOG.error("GET /services/listServices failed: " + e.getMessage());
            try {
                URL servicesUrl = new URL(config.getBaseUrl() + "/services/");
                HttpsURLConnection conn = (HttpsURLConnection) servicesUrl.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                int responseCode = conn.getResponseCode();
                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                responseCode < 400 ? conn.getInputStream()
                                        : conn.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null && response.length() < 5000) {
                        response.append(line).append("\n");
                    }
                }
                LOG.error("=== GET /services/ (HTTP " + responseCode + ") ===\n" + response);
            } catch (Exception e2) {
                LOG.error("GET /services/ also failed: " + e2.getMessage());
            }
        }

        LOG.error("=== END DIAGNOSTICS ===");
    }

}
