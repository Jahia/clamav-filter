package org.jahia.community.clamav.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import org.jahia.community.clamav.ClamavConstants;
import org.jahia.community.clamav.scan.Result;
import org.jahia.community.clamav.scan.Status;
import org.jahia.community.clamav.service.ClamavConfig;
import org.jahia.community.clamav.service.ClamavService;
import org.jahia.modules.graphql.provider.dxm.security.GraphQLRequiresPermission;
import org.jahia.osgi.BundleUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.util.Base64;

@GraphQLName("ClamavQuery")
@GraphQLDescription("ClamAV queries")
public class ClamavQuery {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClamavQuery.class);
    // Linked to the domain enum so the GraphQL status string can't silently drift from Status.ERROR.
    private static final String STATUS_ERROR = Status.ERROR.name();

    @GraphQLField
    @GraphQLName("settings")
    @GraphQLDescription("Returns the current ClamAV connection settings from the configuration file")
    @GraphQLRequiresPermission("clamavAdmin")
    public GqlClamavSettings settings() {
        final ClamavConfig config = BundleUtils.getOsgiService(ClamavConfig.class, null);
        if (config == null) {
            return GqlClamavSettings.defaults();
        }
        return new GqlClamavSettings(config.getHost(), config.getPort(), config.getConnectionTimeout(), config.getReadTimeout());
    }

    @GraphQLField
    @GraphQLName("ping")
    @GraphQLDescription("Tests the connection to the ClamAV daemon; returns true if the daemon is reachable")
    @GraphQLRequiresPermission("clamavAdmin")
    public Boolean ping() {
        final ClamavService service = BundleUtils.getOsgiService(ClamavService.class, null);
        if (service == null) {
            return Boolean.FALSE;
        }
        return service.ping();
    }

    @GraphQLField
    @GraphQLName("scanTest")
    @GraphQLDescription("Scans a base64-encoded file against the ClamAV daemon and returns the scan result")
    @GraphQLRequiresPermission("clamavAdmin")
    public GqlScanResult scanTest(
            @GraphQLName("content") @GraphQLDescription("Base64-encoded file content to scan") String content) {
        if (content == null || content.isEmpty()) {
            return new GqlScanResult(STATUS_ERROR, null);
        }
        if (content.length() > ClamavConstants.MAX_BASE64_INPUT_CHARS) {
            LOGGER.warn("Rejecting clamavScanTest: content exceeds {} chars", ClamavConstants.MAX_BASE64_INPUT_CHARS);
            return new GqlScanResult(STATUS_ERROR, null);
        }
        final ClamavService service = BundleUtils.getOsgiService(ClamavService.class, null);
        if (service == null) {
            return new GqlScanResult("CONNECTION_FAILED", null);
        }
        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(content);
        } catch (IllegalArgumentException e) {
            // Predictable bad-input case (malformed base64) — log at warn, not error.
            LOGGER.warn("Rejecting clamavScanTest: content is not valid base64");
            return new GqlScanResult(STATUS_ERROR, null);
        }
        try {
            final Result result = service.scan(new ByteArrayInputStream(bytes));
            return new GqlScanResult(result.getStatus().name(), result.getSignature());
        } catch (RuntimeException e) {
            LOGGER.error("Error scanning file via test endpoint", e);
            return new GqlScanResult(STATUS_ERROR, null);
        }
    }

    @GraphQLName("ClamavSettings")
    @GraphQLDescription("ClamAV daemon connection settings")
    public static class GqlClamavSettings {

        private final String host;
        private final int port;
        private final int connectionTimeout;
        private final int readTimeout;

        public GqlClamavSettings(String host, int port, int connectionTimeout, int readTimeout) {
            this.host = host;
            this.port = port;
            this.connectionTimeout = connectionTimeout;
            this.readTimeout = readTimeout;
        }

        public static GqlClamavSettings defaults() {
            return new GqlClamavSettings(
                    ClamavConstants.DEFAULT_HOST,
                    ClamavConstants.DEFAULT_PORT,
                    ClamavConstants.DEFAULT_CONNECTION_TIMEOUT,
                    ClamavConstants.DEFAULT_READ_TIMEOUT
            );
        }

        @GraphQLField
        @GraphQLName("host")
        @GraphQLDescription("ClamAV daemon hostname or IP address")
        public String getHost() {
            return host;
        }

        @GraphQLField
        @GraphQLName("port")
        @GraphQLDescription("ClamAV daemon port number")
        public int getPort() {
            return port;
        }

        @GraphQLField
        @GraphQLName("connectionTimeout")
        @GraphQLDescription("Connection timeout in milliseconds")
        public int getConnectionTimeout() {
            return connectionTimeout;
        }

        @GraphQLField
        @GraphQLName("readTimeout")
        @GraphQLDescription("Read/response timeout in milliseconds")
        public int getReadTimeout() {
            return readTimeout;
        }
    }

    @GraphQLName("ClamavScanResult")
    @GraphQLDescription("Result of a ClamAV file scan")
    public static class GqlScanResult {

        private final String status;
        private final String signature;

        public GqlScanResult(String status, String signature) {
            this.status = status;
            this.signature = signature;
        }

        @GraphQLField
        @GraphQLName("status")
        @GraphQLDescription("Scan outcome: PASSED, FAILED, ERROR, or CONNECTION_FAILED")
        public String getStatus() {
            return status;
        }

        @GraphQLField
        @GraphQLName("signature")
        @GraphQLDescription("Virus signature name when status is FAILED, null otherwise")
        public String getSignature() {
            return signature;
        }
    }
}
