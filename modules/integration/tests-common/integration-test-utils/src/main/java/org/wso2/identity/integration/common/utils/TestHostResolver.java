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

/**
 * Utility to resolve the correct hostname for mock servers (WireMock, Tomcat, etc.)
 * that the IS server needs to call back to.
 * <p>
 * In Docker mode, the IS runs inside a container and cannot reach {@code localhost} on the host.
 * Instead, it must use {@code host.testcontainers.internal} which Testcontainers maps to the
 * host machine.
 * <p>
 * In legacy mode, the IS runs as a local process on the same machine, so {@code localhost} works.
 * <p>
 * Usage:
 * <pre>
 *     // Instead of hardcoding "localhost":
 *     String callbackUrl = "http://" + TestHostResolver.getHostname() + ":8490/playground2/oauth2client";
 * </pre>
 */
public class TestHostResolver {

    private static final String DOCKER_HOST = "host.testcontainers.internal";
    private static final String LOCAL_HOST = "localhost";

    private TestHostResolver() {

    }

    /**
     * Returns the hostname that the IS server can use to reach mock servers on the host machine.
     *
     * @return {@code "host.testcontainers.internal"} in Docker mode, {@code "localhost"} in legacy mode
     */
    public static String getHostname() {

        return ISServerConfiguration.isDockerMode() ? DOCKER_HOST : LOCAL_HOST;
    }

    /**
     * Replaces {@code localhost} in the given URL with the appropriate hostname for the current mode.
     * Useful for converting hardcoded callback URLs to Docker-compatible ones.
     *
     * @param url the URL containing "localhost"
     * @return the URL with localhost replaced if in Docker mode, or unchanged in legacy mode
     */
    public static String resolveUrl(String url) {

        if (ISServerConfiguration.isDockerMode() && url != null) {
            // Use regex to replace "localhost" only when it appears as a hostname
            // (after "://" or at string start), not as a substring of another word.
            return url.replaceAll("(?<=://)localhost(?=[:/]|$)", DOCKER_HOST);
        }
        return url;
    }
}
