package org.jahia.community.clamav.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.community.clamav.ClamavConstants;
import org.jahia.community.clamav.service.ClamavConfig;
import org.jahia.community.clamav.service.ClamavService;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLProvider;
import org.jahia.modules.graphql.provider.dxm.security.GraphQLRequiresPermission;
import org.jahia.osgi.BundleUtils;

@GraphQLTypeExtension(DXGraphQLProvider.Query.class)
@GraphQLName("ClamavQueries")
@GraphQLDescription("ClamAV Filter queries")
public class ClamavQueryExtension {

    private ClamavQueryExtension() {
    }

    @GraphQLField
    @GraphQLName("clamavSettings")
    @GraphQLDescription("Returns the current ClamAV connection settings from the configuration file")
    @GraphQLRequiresPermission("admin")
    public static GqlClamavSettings settings() {
        final ClamavConfig config = BundleUtils.getOsgiService(ClamavConfig.class, null);
        if (config == null) {
            return GqlClamavSettings.defaults();
        }
        return new GqlClamavSettings(config.getHost(), config.getPort(), config.getConnectionTimeout(), config.getReadTimeout());
    }

    @GraphQLField
    @GraphQLName("clamavPing")
    @GraphQLDescription("Tests the connection to the ClamAV daemon; returns true if the daemon is reachable")
    @GraphQLRequiresPermission("admin")
    public static Boolean ping() {
        final ClamavService service = BundleUtils.getOsgiService(ClamavService.class, null);
        if (service == null) {
            return Boolean.FALSE;
        }
        return service.ping();
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
}
