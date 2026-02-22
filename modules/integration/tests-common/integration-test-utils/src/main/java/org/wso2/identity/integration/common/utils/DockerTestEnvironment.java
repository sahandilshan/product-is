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

import org.apache.commons.httpclient.ConnectTimeoutException;
import org.apache.commons.httpclient.params.HttpConnectionParams;
import org.apache.commons.httpclient.protocol.Protocol;
import org.apache.commons.httpclient.protocol.ProtocolSocketFactory;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.testcontainers.Testcontainers;
import org.wso2.identity.integration.common.container.WSO2ISContainer;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Lazy-init utility that auto-detects and bootstraps Docker-based integration test mode.
 * <p>
 * This class makes Docker mode <b>self-configuring</b> so that right-clicking any test in
 * IntelliJ works without needing the {@code .run/} template to inject VM options like
 * {@code -Dtest.docker.mode=true}. It auto-resolves paths by walking up from the working
 * directory and performs the full Docker initialization sequence.
 * <p>
 * The class is idempotent — safe to call from both {@code DockerContainerListener} (Maven)
 * and {@code ISIntegrationTest.init()} (IntelliJ).
 */
public class DockerTestEnvironment {

    private static final Log LOG = LogFactory.getLog(DockerTestEnvironment.class);

    private static final String DOCKER_MODE_PROPERTY = "test.docker.mode";
    private static final String CARBON_ZIP_PROPERTY = "carbon.zip";
    private static final String DOCKERFILE_PROPERTY = "docker.dockerfile";
    private static final String FRAMEWORK_RESOURCE_LOCATION_PROPERTY = "framework.resource.location";
    private static final String SERVER_LIST_PROPERTY = "server.list";

    private static volatile boolean initialized = false;
    private static volatile Path carbonHomeMirror;

    private DockerTestEnvironment() {

    }

    /**
     * Determines whether Docker mode should be used.
     * <p>
     * Returns {@code true} if:
     * <ul>
     *   <li>{@code test.docker.mode=true} is explicitly set, OR</li>
     *   <li>{@code framework.resource.location} is NOT set (i.e., not running in legacy Maven
     *       mode where surefire sets it) AND {@code carbon.zip} can be found at the conventional
     *       project path</li>
     * </ul>
     *
     * @return true if Docker mode should be used
     */
    public static boolean shouldUseDockerMode() {

        if (Boolean.getBoolean(DOCKER_MODE_PROPERTY)) {
            return true;
        }

        // If framework.resource.location is set, we're in legacy Maven mode — don't auto-detect.
        String frameworkResourceLocation = System.getProperty(FRAMEWORK_RESOURCE_LOCATION_PROPERTY);
        if (frameworkResourceLocation != null && !frameworkResourceLocation.isEmpty()) {
            return false;
        }

        // Auto-detect: check if carbon.zip is already set or can be found.
        String carbonZip = System.getProperty(CARBON_ZIP_PROPERTY);
        if (carbonZip != null && !carbonZip.isEmpty() && new File(carbonZip).exists()) {
            return true;
        }

        // Try to find carbon.zip at the conventional path.
        Path projectRoot = findProjectRoot();
        if (projectRoot != null) {
            Path distributionTarget = projectRoot.resolve("modules/distribution/target");
            if (Files.isDirectory(distributionTarget)) {
                try {
                    Path zipPath = findCarbonZip(distributionTarget);
                    return zipPath != null;
                } catch (IOException e) {
                    LOG.debug("Error searching for carbon.zip: " + e.getMessage());
                }
            }
        }

        return false;
    }

    /**
     * Ensures the Docker test environment is fully initialized. This method is synchronized
     * and idempotent — multiple calls are safe and only the first call performs initialization.
     * <p>
     * Initialization sequence:
     * <ol>
     *   <li>Auto-resolve paths (carbon.zip, Dockerfile, framework.resource.location)</li>
     *   <li>Set {@code test.docker.mode=true}</li>
     *   <li>Configure Docker socket for non-default runtimes</li>
     *   <li>Expose host ports for mock servers</li>
     *   <li>Start WSO2 IS container</li>
     *   <li>Extract carbon.home and ensure critical files</li>
     *   <li>Initialize ISServerConfiguration</li>
     *   <li>Set carbon.home and truststore system properties</li>
     *   <li>Configure trust-all SSL</li>
     *   <li>Verify REST API readiness</li>
     * </ol>
     */
    public static synchronized void ensureInitialized() {

        if (initialized) {
            return;
        }

        LOG.info("=== DockerTestEnvironment: Auto-initializing Docker mode ===");
        int forkNumber = Integer.getInteger("surefire.forkNumber", 1);
        LOG.info("  Fork number: " + forkNumber + " (port will be " + (9853 + forkNumber - 1) + ")");

        // 1. Auto-resolve paths.
        autoResolvePaths();

        // 2. Set Docker mode flag.
        System.setProperty(DOCKER_MODE_PROPERTY, "true");

        // 3. Configure Docker socket.
        configureDockerEnvironment();

        // 4. Expose host ports so the IS container can reach mock servers on the host.
        LOG.info("Starting WSO2 IS container...");
        Testcontainers.exposeHostPorts(
                8490,   // Embedded Tomcat (travelocity, playground2)
                8587,   // ServiceExtensionMockServer (action webhook callbacks)
                8091,   // MockApplicationServer (OAuth2/OIDC client callbacks)
                8089,   // MockOIDCIdentityProvider (federated OIDC IdP)
                8090,   // MockSMSProvider
                8093,   // MockOAuth2TokenServer
                3025,   // GreenMail SMTP
                3999    // MockCustomAuthenticatorService
        );

        // 5. Start the container.
        WSO2ISContainer container = WSO2ISContainer.getDefaultInstance();

        // 6. Create temp directory and extract carbon.home.
        try {
            carbonHomeMirror = Files.createTempDirectory("wso2is-carbon-home-");
            container.extractCarbonHome(carbonHomeMirror.toString());
        } catch (IOException e) {
            LOG.warn("Partial carbon.home extraction (some dirs may not exist): " + e.getMessage());
        }

        // 7. Ensure critical files exist.
        ensureCriticalFilesExtracted(container);

        // 8. Initialize ISServerConfiguration.
        ISServerConfiguration.initialize(
                container.getBaseUrl(),
                container.getMappedHttpsPort(),
                container.getHost(),
                carbonHomeMirror != null ? carbonHomeMirror.toString() : ""
        );

        // 9. Set carbon.home so existing code that uses Utils.getResidentCarbonHome() works.
        if (carbonHomeMirror != null) {
            System.setProperty("carbon.home", carbonHomeMirror.toString());
        }

        // 10. Set truststore system properties.
        if (carbonHomeMirror != null) {
            File securityDir = new File(carbonHomeMirror.toString(), "repository/resources/security");
            File trustStoreFile = new File(securityDir, "client-truststore.p12");
            if (!trustStoreFile.exists()) {
                trustStoreFile = new File(securityDir, "wso2carbon.p12");
            }
            if (trustStoreFile.exists()) {
                System.setProperty("javax.net.ssl.trustStore", trustStoreFile.getAbsolutePath());
                System.setProperty("javax.net.ssl.trustStorePassword", "wso2carbon");
                System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
                LOG.info("  TrustStore:       " + trustStoreFile.getAbsolutePath());
            }
        }

        // 11. Configure trust-all SSL across all layers.
        configureTrustAllSSL();

        // 12. Verify REST API readiness and cache session cookie.
        loginAndCacheSessionCookie(container);

        // 13. Provision test users and tenants (same users as automation.xml).
        //     This ensures test users exist regardless of whether the test is launched
        //     from Maven (with DockerContainerListener) or IntelliJ (without it).
        provisionTestUsersAndTenants();

        initialized = true;

        LOG.info("WSO2 IS container ready.");
        LOG.info("  Base URL:         " + container.getBaseUrl());
        LOG.info("  HTTPS Port:       " + container.getMappedHttpsPort());
        LOG.info("  Carbon Home Mirror: " + carbonHomeMirror);

        if (Boolean.getBoolean("test.docker.debug")) {
            LOG.info("  Debug Port:       " + container.getMappedDebugPort());
            LOG.info("  Attach IntelliJ remote debug to localhost:" + container.getMappedDebugPort());
        }
    }

    /**
     * Shuts down the Docker test environment: stops the container and cleans up temp directories.
     */
    public static synchronized void shutdown() {

        if (!initialized) {
            return;
        }

        LOG.info("Stopping WSO2 IS container...");
        try {
            WSO2ISContainer.stopDefaultInstance();
        } catch (Exception e) {
            LOG.error("Error stopping container", e);
        }

        if (carbonHomeMirror != null) {
            try {
                deleteDirectory(carbonHomeMirror);
                LOG.info("Cleaned up carbon home mirror: " + carbonHomeMirror);
            } catch (IOException e) {
                LOG.warn("Failed to clean up temp dir: " + carbonHomeMirror, e);
            }
        }

        initialized = false;
    }

    /**
     * Returns whether the Docker test environment has been initialized.
     *
     * @return true if initialized
     */
    public static boolean isInitialized() {

        return initialized;
    }

    /**
     * Returns the carbon home mirror path, or null if not yet initialized.
     *
     * @return the carbon home mirror path
     */
    public static Path getCarbonHomeMirror() {

        return carbonHomeMirror;
    }

    /**
     * Auto-resolves system properties needed for Docker mode by walking up from the current
     * working directory to find the project root.
     * <p>
     * Sets the following system properties if not already set:
     * <ul>
     *   <li>{@code carbon.zip} — path to the distribution ZIP</li>
     *   <li>{@code docker.dockerfile} — path to the Dockerfile</li>
     *   <li>{@code framework.resource.location} — path to test resources</li>
     *   <li>{@code server.list} — set to "IS"</li>
     * </ul>
     */
    static void autoResolvePaths() {

        Path projectRoot = findProjectRoot();
        if (projectRoot == null) {
            LOG.warn("Could not find project root (no 'modules/distribution' directory found "
                    + "walking up from CWD). System properties must be set manually.");
            return;
        }

        LOG.info("Auto-resolved project root: " + projectRoot);

        // carbon.zip
        if (System.getProperty(CARBON_ZIP_PROPERTY) == null
                || System.getProperty(CARBON_ZIP_PROPERTY).isEmpty()) {
            Path distributionTarget = projectRoot.resolve("modules/distribution/target");
            if (Files.isDirectory(distributionTarget)) {
                try {
                    Path zipPath = findCarbonZip(distributionTarget);
                    if (zipPath != null) {
                        System.setProperty(CARBON_ZIP_PROPERTY, zipPath.toString());
                        LOG.info("  carbon.zip = " + zipPath);
                    }
                } catch (IOException e) {
                    LOG.warn("Error searching for carbon.zip in " + distributionTarget + ": "
                            + e.getMessage());
                }
            }
        }

        // docker.dockerfile
        if (System.getProperty(DOCKERFILE_PROPERTY) == null
                || System.getProperty(DOCKERFILE_PROPERTY).isEmpty()) {
            Path dockerfile = projectRoot.resolve(
                    "modules/distribution/src/main/docker/Dockerfile");
            if (Files.exists(dockerfile)) {
                System.setProperty(DOCKERFILE_PROPERTY, dockerfile.toString());
                LOG.info("  docker.dockerfile = " + dockerfile);
            }
        }

        // framework.resource.location
        if (System.getProperty(FRAMEWORK_RESOURCE_LOCATION_PROPERTY) == null
                || System.getProperty(FRAMEWORK_RESOURCE_LOCATION_PROPERTY).isEmpty()) {
            Path resourceLocation = projectRoot.resolve(
                    "modules/integration/tests-integration/tests-backend/src/test/resources");
            if (Files.isDirectory(resourceLocation)) {
                // Ensure trailing separator for compatibility with FrameworkPathUtil expectations.
                System.setProperty(FRAMEWORK_RESOURCE_LOCATION_PROPERTY,
                        resourceLocation.toString() + File.separator);
                LOG.info("  framework.resource.location = "
                        + System.getProperty(FRAMEWORK_RESOURCE_LOCATION_PROPERTY));
            }
        }

        // server.list
        if (System.getProperty(SERVER_LIST_PROPERTY) == null
                || System.getProperty(SERVER_LIST_PROPERTY).isEmpty()) {
            System.setProperty(SERVER_LIST_PROPERTY, "IS");
            LOG.info("  server.list = IS");
        }
    }

    /**
     * Finds the project root by walking up from the current working directory, looking for
     * a directory that contains {@code modules/distribution}.
     *
     * @return the project root path, or null if not found
     */
    private static Path findProjectRoot() {

        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();

        // Walk up at most 10 levels.
        for (int i = 0; i < 10 && current != null; i++) {
            if (Files.isDirectory(current.resolve("modules/distribution"))) {
                return current;
            }
            current = current.getParent();
        }

        return null;
    }

    /**
     * Finds the wso2is-*.zip file in the given directory.
     *
     * @param distributionTarget the directory to search in
     * @return the path to the ZIP file, or null if not found
     */
    private static Path findCarbonZip(Path distributionTarget) throws IOException {

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                distributionTarget, "wso2is-*.zip")) {
            for (Path entry : stream) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Auto-detects Docker socket location for non-default Docker runtimes and configures
     * Testcontainers accordingly.
     * <p>
     * This method handles two concerns:
     * <ol>
     *   <li><b>Docker host discovery</b> — finding the Docker socket (env var, system property,
     *       or auto-detection from known paths). Writes {@code docker.host} to
     *       {@code ~/.testcontainers.properties} for Testcontainers to read.</li>
     *   <li><b>Ryuk disabling for VM-based runtimes</b> — Ryuk (Testcontainers' cleanup container)
     *       bind-mounts the Docker socket. For VM-based runtimes (Rancher Desktop, Colima), the
     *       host socket path (e.g., {@code ~/.rd/docker.sock}) does not exist inside the VM, causing
     *       Ryuk to fail. Since Testcontainers 1.20.x reads {@code TESTCONTAINERS_RYUK_DISABLED}
     *       and {@code TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE} only from {@code System.getenv()}
     *       (not from properties files), we use reflection to pre-set the {@code ResourceReaper}
     *       singleton to {@code JVMHookResourceReaper}, bypassing Ryuk entirely.</li>
     * </ol>
     * <p>
     * Must be called BEFORE any Testcontainers API call.
     */
    static void configureDockerEnvironment() {

        String resolvedDockerHost = null;
        boolean isVmBasedRuntime = false;

        // 1. Check if DOCKER_HOST is already set (env var or system property).
        String dockerHost = System.getenv("DOCKER_HOST");
        if (dockerHost != null && !dockerHost.isEmpty()) {
            LOG.info("DOCKER_HOST (env): " + dockerHost);
            resolvedDockerHost = dockerHost;
        }
        if (resolvedDockerHost == null) {
            dockerHost = System.getProperty("DOCKER_HOST");
            if (dockerHost != null && !dockerHost.isEmpty()) {
                LOG.info("DOCKER_HOST (system property): " + dockerHost);
                resolvedDockerHost = dockerHost;
            }
        }

        // 2. If not set, check default socket.
        if (resolvedDockerHost == null) {
            File defaultSocket = new File("/var/run/docker.sock");
            if (defaultSocket.exists()) {
                LOG.info("Docker socket found at default path: /var/run/docker.sock");
                // Default path — Ryuk can mount /var/run/docker.sock directly. No fix needed.
                return;
            }
        }

        // 3. Known VM-based runtime sockets.
        String homeDir = System.getProperty("user.home");
        String[][] knownSockets = {
                {homeDir + "/.rd/docker.sock", "Rancher Desktop"},
                {homeDir + "/.colima/default/docker.sock", "Colima"},
                {homeDir + "/.docker/run/docker.sock", "Docker Desktop (alternative)"},
        };

        // 4. If docker host not yet resolved, auto-detect from known socket paths.
        if (resolvedDockerHost == null) {
            for (String[] entry : knownSockets) {
                if (new File(entry[0]).exists()) {
                    resolvedDockerHost = "unix://" + entry[0];
                    isVmBasedRuntime = true;
                    LOG.info("Auto-detected " + entry[1] + " Docker socket: " + entry[0]);
                    break;
                }
            }
        }

        if (resolvedDockerHost == null) {
            LOG.error("=== Docker socket not found ===");
            LOG.error("Testcontainers could not find a Docker socket at any known location:");
            LOG.error("  /var/run/docker.sock (Docker Desktop default)");
            for (String[] entry : knownSockets) {
                LOG.error("  " + entry[0] + " (" + entry[1] + ")");
            }
            LOG.error("");
            LOG.error("To fix, set the DOCKER_HOST environment variable:");
            LOG.error("  Rancher Desktop:  export DOCKER_HOST=unix://" + homeDir + "/.rd/docker.sock");
            LOG.error("  Colima:           export DOCKER_HOST=unix://" + homeDir
                    + "/.colima/default/docker.sock");
            LOG.error("  Docker Desktop:   export DOCKER_HOST=unix:///var/run/docker.sock");
            return;
        }

        // 5. Determine if the already-resolved docker host points to a VM-based runtime.
        if (!isVmBasedRuntime) {
            for (String[] entry : knownSockets) {
                if (resolvedDockerHost.contains(entry[0])) {
                    isVmBasedRuntime = true;
                    break;
                }
            }
        }

        // 6. Write docker.host to ~/.testcontainers.properties.
        setDockerHost(resolvedDockerHost);
        LOG.info("Configured Testcontainers — docker.host=" + resolvedDockerHost);

        // 7. For VM-based runtimes, disable Ryuk because it cannot bind-mount the host
        //    Docker socket path (e.g., ~/.rd/docker.sock) inside the VM.
        //    Testcontainers 1.20.x reads TESTCONTAINERS_RYUK_DISABLED only from System.getenv()
        //    (not from properties files), so we use reflection to pre-set the ResourceReaper
        //    singleton to JVMHookResourceReaper before any Testcontainers class initializes Ryuk.
        if (isVmBasedRuntime) {
            disableRyukForVmRuntime();
        }
    }

    /**
     * Disables Ryuk by pre-setting the {@code ResourceReaper} singleton to
     * {@code JVMHookResourceReaper} via reflection.
     * <p>
     * Ryuk fails on VM-based Docker runtimes (Rancher Desktop, Colima) because it tries to
     * bind-mount the host Docker socket path (e.g., {@code ~/.rd/docker.sock}) inside a container,
     * but that path doesn't exist inside the VM. The in-VM path is {@code /var/run/docker.sock},
     * but Testcontainers 1.20.x only reads {@code TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE} from
     * {@code System.getenv()}, which cannot be reliably set from Java.
     * <p>
     * This method must be called BEFORE any Testcontainers class triggers
     * {@code ResourceReaper.instance()}.
     */
    private static void disableRyukForVmRuntime() {

        try {
            // Create a JVMHookResourceReaper instance (the non-Ryuk alternative).
            // JVMHookResourceReaper uses a JVM shutdown hook for cleanup instead of a
            // separate Ryuk container, avoiding the Docker socket mount issue entirely.
            Class<?> jvmReaperClass = Class.forName(
                    "org.testcontainers.utility.JVMHookResourceReaper");
            Constructor<?> constructor = jvmReaperClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object jvmReaper = constructor.newInstance();

            // Pre-set the ResourceReaper singleton before any Testcontainers code calls
            // ResourceReaper.instance() (which would try to create RyukResourceReaper).
            Class<?> reaperClass = Class.forName("org.testcontainers.utility.ResourceReaper");
            Field instanceField = reaperClass.getDeclaredField("instance");
            instanceField.setAccessible(true);
            instanceField.set(null, jvmReaper);

            LOG.info("Disabled Ryuk for VM-based Docker runtime "
                    + "(using JVMHookResourceReaper instead). "
                    + "Container cleanup will use JVM shutdown hooks.");
        } catch (Exception e) {
            LOG.warn("Could not disable Ryuk via reflection: " + e.getMessage()
                    + ". If Ryuk fails to start, set the environment variable: "
                    + "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock");
        }
    }

    /**
     * Configures the Docker host for Testcontainers by writing to {@code ~/.testcontainers.properties}.
     * <p>
     * Testcontainers 1.20.x reads {@code DOCKER_HOST} from {@code System.getenv("DOCKER_HOST")},
     * which cannot be reliably modified at runtime in modern JDKs. However, Testcontainers also
     * reads {@code docker.host} from {@code ~/.testcontainers.properties} during class initialization.
     * <p>
     * This method must be called BEFORE any Testcontainers class is loaded (i.e., before
     * {@code Testcontainers.exposeHostPorts()} or {@code WSO2ISContainer.getDefaultInstance()}).
     *
     * @param dockerHostValue the DOCKER_HOST value (e.g., "unix:///path/to/docker.sock")
     */
    private static void setDockerHost(String dockerHostValue) {

        // Also set the system property (some code may check it).
        System.setProperty("DOCKER_HOST", dockerHostValue);

        // Write docker.host to ~/.testcontainers.properties so Testcontainers picks it up
        // during its TestcontainersConfiguration class initialization.
        Path propsFile = Paths.get(System.getProperty("user.home"), ".testcontainers.properties");
        Properties props = new Properties();

        // Read existing properties to preserve user settings.
        if (Files.exists(propsFile)) {
            try (InputStream in = Files.newInputStream(propsFile)) {
                props.load(in);
            } catch (IOException e) {
                LOG.warn("Could not read existing " + propsFile + ": " + e.getMessage());
            }
        }

        props.setProperty("docker.host", dockerHostValue);

        try (OutputStream out = Files.newOutputStream(propsFile)) {
            props.store(out, "Auto-configured by DockerTestEnvironment");
            LOG.info("Wrote docker.host=" + dockerHostValue + " to " + propsFile);
        } catch (IOException e) {
            LOG.warn("Could not write to " + propsFile + ": " + e.getMessage()
                    + ". Testcontainers may not find the Docker socket. "
                    + "Set DOCKER_HOST as an OS environment variable instead.");
        }
    }

    /**
     * Configures a trust-all SSL context across all layers used by the test framework.
     * <p>
     * Layers:
     * <ol>
     *   <li>JVM default SSLContext</li>
     *   <li>HttpsURLConnection defaults (SSLSocketFactory + HostnameVerifier)</li>
     *   <li>commons-httpclient 3.x Protocol registry (used by Axis2)</li>
     *   <li>RestAssured (via reflection to avoid hard dependency)</li>
     * </ol>
     */
    static void configureTrustAllSSL() {

        try {
            TrustManager[] trustAllManagers = new TrustManager[]{
                    new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {

                        }

                        public void checkServerTrusted(X509Certificate[] chain, String authType) {

                        }

                        public X509Certificate[] getAcceptedIssuers() {

                            return new X509Certificate[0];
                        }
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllManagers, new java.security.SecureRandom());

            // Layer 1: JVM default SSLContext.
            SSLContext.setDefault(sslContext);

            // Layer 2: HttpsURLConnection defaults.
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);

            // Layer 3: commons-httpclient 3.x Protocol registry.
            final SSLSocketFactory trustAllFactory = sslContext.getSocketFactory();
            Protocol trustAllProtocol = new Protocol("https", new ProtocolSocketFactory() {
                public Socket createSocket(String host, int port, InetAddress localAddress,
                                           int localPort) throws IOException {

                    return trustAllFactory.createSocket(host, port, localAddress, localPort);
                }

                public Socket createSocket(String host, int port, InetAddress localAddress,
                                           int localPort, HttpConnectionParams params)
                        throws IOException, ConnectTimeoutException {

                    int timeout = params.getConnectionTimeout();
                    if (timeout == 0) {
                        return trustAllFactory.createSocket(host, port, localAddress, localPort);
                    }
                    Socket socket = trustAllFactory.createSocket();
                    socket.bind(new java.net.InetSocketAddress(localAddress, localPort));
                    socket.connect(new java.net.InetSocketAddress(host, port), timeout);
                    return socket;
                }

                public Socket createSocket(String host, int port) throws IOException {

                    return trustAllFactory.createSocket(host, port);
                }
            }, 443);
            Protocol.registerProtocol("https", trustAllProtocol);

            // Layer 4: RestAssured (via reflection to avoid hard dependency in this module).
            try {
                Class<?> restAssuredClass = Class.forName("io.restassured.RestAssured");
                Method relaxMethod = restAssuredClass.getMethod("useRelaxedHTTPSValidation");
                relaxMethod.invoke(null);
                LOG.info("  SSL configured: trust-all for JVM, HttpsURLConnection, "
                        + "commons-httpclient, and RestAssured.");
            } catch (ClassNotFoundException e) {
                LOG.info("  SSL configured: trust-all for JVM, HttpsURLConnection, "
                        + "and commons-httpclient (RestAssured not on classpath).");
            } catch (Exception e) {
                LOG.warn("  SSL configured: trust-all for JVM, HttpsURLConnection, "
                        + "and commons-httpclient. RestAssured configuration failed: "
                        + e.getMessage());
            }
        } catch (Exception e) {
            LOG.error("Failed to configure trust-all SSL: " + e.getMessage(), e);
        }
    }

    /**
     * Verifies server readiness via REST API and caches credentials.
     * <p>
     * WSO2 IS 7.x does not deploy SOAP admin services. This method verifies the REST API
     * is responsive using Basic auth and sets a placeholder session cookie.
     *
     * @param container the running WSO2 IS container
     */
    static void loginAndCacheSessionCookie(WSO2ISContainer container) {

        ISServerConfiguration config = ISServerConfiguration.getInstance();
        int maxRetries = 15;
        long retryDelayMs = 3000;
        Exception lastException = null;

        LOG.info("Verifying REST API readiness (Basic auth)...");

        String basicAuth = Base64.getEncoder().encodeToString(
                (config.getAdminUsername() + ":" + config.getAdminPassword())
                        .getBytes(StandardCharsets.UTF_8));

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                URL url = new URL(config.getBaseUrl() + "/api/server/v1/configs");
                HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Basic " + basicAuth);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                int responseCode = conn.getResponseCode();
                conn.disconnect();
                if (responseCode == 200) {
                    config.setSessionCookie("docker-rest-mode");
                    LOG.info("REST API ready on attempt " + attempt + " (HTTP " + responseCode
                            + "). Session cookie set to placeholder (SOAP admin services "
                            + "not available in WSO2 IS 7.x — REST tests use Basic auth).");
                    return;
                }
                LOG.info("REST API attempt " + attempt + "/" + maxRetries
                        + " returned HTTP " + responseCode + ". Retrying...");
            } catch (Exception e) {
                lastException = e;
                if (attempt % 5 == 0 || attempt == 1) {
                    LOG.info("REST API attempt " + attempt + "/" + maxRetries
                            + " failed: " + e.getMessage());
                }
            }
            if (attempt < maxRetries) {
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for REST readiness", ie);
                }
            }
        }

        throw new RuntimeException(
                "REST API not available after " + maxRetries + " attempts ("
                        + (maxRetries * retryDelayMs / 1000) + "s). "
                        + "Check container logs for details.",
                lastException);
    }

    /**
     * Ensures critical files are present in the carbon home mirror.
     * The tar-based directory extraction can silently miss files.
     */
    static void ensureCriticalFilesExtracted(WSO2ISContainer container) {

        if (carbonHomeMirror == null) {
            return;
        }

        String[] criticalFiles = {
                "repository/conf/carbon.xml",
                "repository/conf/deployment.toml"
        };

        for (String relativePath : criticalFiles) {
            Path localFile = carbonHomeMirror.resolve(relativePath);
            if (!Files.exists(localFile)) {
                String containerPath = "/home/wso2carbon/wso2is/" + relativePath;
                try {
                    Files.createDirectories(localFile.getParent());
                    container.copyFileFromContainer(containerPath, localFile.toString());
                    LOG.info("Extracted missing critical file: " + relativePath);
                } catch (Exception e) {
                    LOG.warn("Could not extract critical file " + relativePath + ": "
                            + e.getMessage());
                }
            }
        }
    }

    /**
     * Provisions test users and tenants using REST APIs.
     * Replicates what {@code UserPopulateExtension} does via SOAP admin services.
     * Users and tenants are defined in automation.xml {@code <userManagement>}.
     * <p>
     */
    static void provisionTestUsersAndTenants() {

        LOG.info("Provisioning test users and tenants...");

        ISServerConfiguration config = ISServerConfiguration.getInstance();
        String baseUrl = config.getBaseUrl();
        String adminAuth = Base64.getEncoder().encodeToString(
                (config.getAdminUsername() + ":" + config.getAdminPassword())
                        .getBytes(StandardCharsets.UTF_8));

        // 1. Create super-tenant users (from automation.xml <superTenant><users>).
        String[][] superTenantUsers = {
                {"testuser11", "Wso2_test11"},
                {"testuser21", "Wso2_test21"},
                {"Registry580UN", "Wso2_test580UN"},
                {"deniedUser", "Wso2_test"}
        };
        List<String> superTenantUserIds = new ArrayList<>();
        for (String[] user : superTenantUsers) {
            String userId = createUserViaSCIM2(baseUrl + "/scim2/Users", adminAuth, user[0],
                    user[1]);
            if (userId != null) {
                superTenantUserIds.add(userId);
            }
        }

        // 2. Assign admin group to super-tenant users (for group-based logic).
        assignUsersToAdminGroup(baseUrl, adminAuth, superTenantUserIds);

        // 3. Assign Administrator role to super-tenant users (for REST API authorization).
        assignUsersToAdminRole(baseUrl, adminAuth, superTenantUserIds);

        // 4. Create tenant wso2.com (from automation.xml <tenants>).
        createTenant(baseUrl, adminAuth, "wso2.com", "admin", "admin");

        // 5. Wait for tenant to be fully activated before creating tenant users.
        waitForTenantActivation(baseUrl, adminAuth, "wso2.com");

        // 6. Create tenant users (from automation.xml <tenants><tenant domain="wso2.com"><users>).
        String tenantAuth = Base64.getEncoder().encodeToString(
                "admin@wso2.com:admin".getBytes(StandardCharsets.UTF_8));
        String[][] tenantUsers = {
                {"testuser11", "Wso2_test11"},
                {"testuser21", "Wso2_test21"}
        };
        List<String> tenantUserIds = new ArrayList<>();
        for (String[] user : tenantUsers) {
            String userId = createUserViaSCIM2(baseUrl + "/t/wso2.com/scim2/Users", tenantAuth,
                    user[0], user[1]);
            if (userId != null) {
                tenantUserIds.add(userId);
            }
        }

        // 7. Assign admin group to tenant users.
        assignUsersToAdminGroup(baseUrl + "/t/wso2.com", tenantAuth, tenantUserIds);

        // 8. Assign Administrator role to tenant users.
        assignUsersToAdminRole(baseUrl + "/t/wso2.com", tenantAuth, tenantUserIds);

        LOG.info("Test user and tenant provisioning completed.");
    }

    /**
     * Creates a user via SCIM2 REST API.
     *
     * @return the SCIM2 user ID, or null if creation failed
     */
    private static String createUserViaSCIM2(String scimEndpoint, String basicAuth,
                                             String userName, String password) {

        try {
            URL url = new URL(scimEndpoint);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Basic " + basicAuth);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            String body = "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"],"
                    + "\"userName\":\"" + userName + "\","
                    + "\"password\":\"" + password + "\","
                    + "\"name\":{\"givenName\":\"" + userName + "\",\"familyName\":\""
                    + userName + "\"}}";

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            String responseBody = readResponseBody(conn);
            conn.disconnect();

            if (responseCode == 201) {
                String userId = extractJsonValue(responseBody, "id");
                LOG.info("  Created user: " + userName + " (id=" + userId + ")");
                return userId;
            } else if (responseCode == 409) {
                LOG.info("  User already exists: " + userName + ". Looking up ID...");
                return getUserIdByName(scimEndpoint.replace("/scim2/Users", ""), basicAuth,
                        userName);
            } else {
                LOG.warn("  Failed to create user " + userName + ": HTTP " + responseCode
                        + " — " + responseBody);
            }
        } catch (Exception e) {
            LOG.warn("  Failed to create user " + userName + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Looks up a user's SCIM2 ID by username.
     */
    private static String getUserIdByName(String baseUrl, String basicAuth, String userName) {

        try {
            String filterUrl = baseUrl + "/scim2/Users?filter=userName+eq+" + userName;
            URL url = new URL(filterUrl);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Basic " + basicAuth);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int responseCode = conn.getResponseCode();
            String responseBody = readResponseBody(conn);
            conn.disconnect();

            if (responseCode == 200) {
                return extractJsonValue(responseBody, "id");
            }
        } catch (Exception e) {
            LOG.warn("  Failed to look up user " + userName + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Assigns users to the admin group via SCIM2 Groups PATCH.
     */
    private static void assignUsersToAdminGroup(String baseUrl, String basicAuth,
                                                List<String> userIds) {

        if (userIds.isEmpty()) {
            return;
        }

        String adminGroupId = getGroupIdByName(baseUrl, basicAuth, "admin");
        if (adminGroupId == null) {
            LOG.warn("  Admin group not found. Users will not have admin permissions.");
            return;
        }

        StringBuilder members = new StringBuilder();
        for (int i = 0; i < userIds.size(); i++) {
            if (i > 0) {
                members.append(",");
            }
            members.append("{\"value\":\"").append(userIds.get(i)).append("\"}");
        }

        // WSO2 IS 7.x SCIM2 expects the path-less format for group member operations:
        // the "members" array is nested inside the "value" object, not via "path".
        String patchBody = "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                + "\"Operations\":[{\"op\":\"add\","
                + "\"value\":{\"members\":[" + members + "]}}]}";

        try {
            HttpClient httpClient = HttpClient.newBuilder()
                    .sslContext(SSLContext.getDefault())
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/scim2/Groups/" + adminGroupId))
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(patchBody))
                    .header("Authorization", "Basic " + basicAuth)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                LOG.info("  Assigned " + userIds.size() + " users to admin group.");
            } else {
                LOG.warn("  Failed to assign users to admin group: HTTP " + response.statusCode()
                        + " — " + response.body());
            }
        } catch (Exception e) {
            LOG.warn("  Failed to assign users to admin group: " + e.getMessage());
        }
    }

    /**
     * Assigns users to the Administrator role via SCIM2 v2 Roles API.
     */
    private static void assignUsersToAdminRole(String baseUrl, String basicAuth,
                                               List<String> userIds) {

        if (userIds.isEmpty()) {
            return;
        }

        String adminRoleId = getRoleIdByName(baseUrl, basicAuth, "Administrator");
        if (adminRoleId == null) {
            adminRoleId = getRoleIdByName(baseUrl, basicAuth, "admin");
        }
        if (adminRoleId == null) {
            LOG.warn("  Administrator role not found via SCIM2 v2 Roles API. "
                    + "Users may lack REST API permissions (403 on management APIs).");
            return;
        }

        StringBuilder users = new StringBuilder();
        for (int i = 0; i < userIds.size(); i++) {
            if (i > 0) {
                users.append(",");
            }
            users.append("{\"value\":\"").append(userIds.get(i)).append("\"}");
        }

        String patchBody = "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                + "\"Operations\":[{\"op\":\"add\",\"path\":\"users\","
                + "\"value\":[" + users + "]}]}";

        try {
            HttpClient httpClient = HttpClient.newBuilder()
                    .sslContext(SSLContext.getDefault())
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/scim2/v2/Roles/" + adminRoleId))
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(patchBody))
                    .header("Authorization", "Basic " + basicAuth)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                LOG.info("  Assigned " + userIds.size()
                        + " users to Administrator role (SCIM2 v2).");
            } else {
                LOG.warn("  Failed to assign users to Administrator role: HTTP "
                        + response.statusCode() + " — " + response.body());
            }
        } catch (Exception e) {
            LOG.warn("  Failed to assign users to Administrator role: " + e.getMessage());
        }
    }

    /**
     * Gets a SCIM2 v2 Role ID by display name.
     */
    private static String getRoleIdByName(String baseUrl, String basicAuth, String roleName) {

        try {
            String filterUrl = baseUrl + "/scim2/v2/Roles?filter=displayName+eq+" + roleName;
            URL url = new URL(filterUrl);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Basic " + basicAuth);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int responseCode = conn.getResponseCode();
            String responseBody = readResponseBody(conn);
            conn.disconnect();

            if (responseCode == 200) {
                int resourcesIdx = responseBody.indexOf("\"Resources\"");
                if (resourcesIdx >= 0) {
                    String fromResources = responseBody.substring(resourcesIdx);
                    String roleId = extractJsonValue(fromResources, "id");
                    if (roleId != null) {
                        LOG.info("  Found role '" + roleName + "' with id=" + roleId);
                    }
                    return roleId;
                }
            }
        } catch (Exception e) {
            LOG.warn("  Failed to look up role " + roleName + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Gets a SCIM2 group ID by display name.
     */
    private static String getGroupIdByName(String baseUrl, String basicAuth, String groupName) {

        try {
            String filterUrl = baseUrl + "/scim2/Groups?filter=displayName+eq+" + groupName;
            URL url = new URL(filterUrl);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Basic " + basicAuth);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int responseCode = conn.getResponseCode();
            String responseBody = readResponseBody(conn);
            conn.disconnect();

            if (responseCode == 200) {
                int resourcesIdx = responseBody.indexOf("\"Resources\"");
                if (resourcesIdx >= 0) {
                    String fromResources = responseBody.substring(resourcesIdx);
                    return extractJsonValue(fromResources, "id");
                }
            }
        } catch (Exception e) {
            LOG.warn("  Failed to look up group " + groupName + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Creates a tenant via the Tenant Management REST API.
     */
    private static void createTenant(String baseUrl, String basicAuth,
                                     String domain, String adminUser, String adminPassword) {

        try {
            URL url = new URL(baseUrl + "/api/server/v1/tenants");
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Basic " + basicAuth);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            String body = "{\"domain\":\"" + domain + "\","
                    + "\"owners\":[{\"username\":\"" + adminUser + "\","
                    + "\"password\":\"" + adminPassword + "\","
                    + "\"email\":\"" + adminUser + "@" + domain + "\","
                    + "\"firstname\":\"" + adminUser + "\","
                    + "\"lastname\":\"" + adminUser + "\","
                    + "\"provisioningMethod\":\"inline-password\"}]}";

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            conn.disconnect();

            if (responseCode == 201) {
                LOG.info("  Created tenant: " + domain);
            } else if (responseCode == 409) {
                LOG.info("  Tenant already exists: " + domain);
            } else {
                LOG.warn("  Failed to create tenant " + domain + ": HTTP " + responseCode);
            }
        } catch (Exception e) {
            LOG.warn("  Failed to create tenant " + domain + ": " + e.getMessage());
        }
    }

    /**
     * Waits for a tenant to be fully activated by polling the tenant API.
     */
    private static void waitForTenantActivation(String baseUrl, String basicAuth, String domain) {

        LOG.info("  Waiting for tenant " + domain + " to activate...");
        for (int i = 0; i < 10; i++) {
            try {
                URL url = new URL(baseUrl + "/t/" + domain + "/scim2/Users?count=1");
                HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                String tenantAuth = Base64.getEncoder().encodeToString(
                        ("admin@" + domain + ":admin").getBytes(StandardCharsets.UTF_8));
                conn.setRequestProperty("Authorization", "Basic " + tenantAuth);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                int responseCode = conn.getResponseCode();
                conn.disconnect();

                if (responseCode == 200) {
                    LOG.info("  Tenant " + domain + " is active.");
                    return;
                }
            } catch (Exception e) {
                // Tenant not ready yet.
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        LOG.warn("  Tenant " + domain + " activation check timed out. Proceeding anyway.");
    }

    /**
     * Reads the response body from an HttpsURLConnection.
     */
    private static String readResponseBody(HttpsURLConnection conn) {

        try {
            InputStream is = conn.getResponseCode() < 400
                    ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) {
                return "";
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                return sb.toString();
            }
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Extracts the first occurrence of a JSON string value for the given key.
     * Simple pattern-based extraction — avoids adding a JSON library dependency.
     */
    private static String extractJsonValue(String json, String key) {

        String regex = "\"" + key + "\"\\s*:\\s*\"([^\"]+)\"";
        Matcher m = Pattern.compile(regex).matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private static void deleteDirectory(Path dir) throws IOException {

        if (Files.exists(dir)) {
            try (Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                LOG.warn("Could not delete: " + path);
                            }
                        });
            }
        }
    }
}
