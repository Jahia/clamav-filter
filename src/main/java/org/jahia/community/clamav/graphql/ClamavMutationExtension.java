package org.jahia.community.clamav.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.community.clamav.ClamavConstants;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLProvider;
import org.jahia.modules.graphql.provider.dxm.security.GraphQLRequiresPermission;
import org.jahia.osgi.BundleUtils;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Dictionary;
import java.util.Hashtable;

@GraphQLTypeExtension(DXGraphQLProvider.Mutation.class)
@GraphQLName("ClamavMutations")
@GraphQLDescription("ClamAV Filter mutations")
public class ClamavMutationExtension {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClamavMutationExtension.class);

    private ClamavMutationExtension() {
    }

    @GraphQLField
    @GraphQLName("clamavSaveSettings")
    @GraphQLDescription("Saves the ClamAV connection settings to the OSGi configuration file and applies them immediately")
    @GraphQLRequiresPermission("admin")
    public static Boolean saveSettings(
            @GraphQLName("host") @GraphQLDescription("ClamAV daemon hostname or IP address") String host,
            @GraphQLName("port") @GraphQLDescription("ClamAV daemon port number") Integer port,
            @GraphQLName("connectionTimeout") @GraphQLDescription("Connection timeout in milliseconds") Integer connectionTimeout,
            @GraphQLName("readTimeout") @GraphQLDescription("Read/response timeout in milliseconds") Integer readTimeout) {
        try {
            final ConfigurationAdmin configAdmin = BundleUtils.getOsgiService(ConfigurationAdmin.class, null);
            if (configAdmin == null) {
                LOGGER.error("ConfigurationAdmin service is not available");
                return Boolean.FALSE;
            }
            final Configuration config = configAdmin.getConfiguration("org.jahia.community.clamav", null);
            Dictionary<String, Object> props = config.getProperties();
            if (props == null) {
                props = new Hashtable<>();
                props.put("host", ClamavConstants.DEFAULT_HOST);
                props.put("port", String.valueOf(ClamavConstants.DEFAULT_PORT));
                props.put("connection_timeout", String.valueOf(ClamavConstants.DEFAULT_CONNECTION_TIMEOUT));
                props.put("read_timeout", String.valueOf(ClamavConstants.DEFAULT_READ_TIMEOUT));
            }
            if (host != null) {
                props.put("host", host);
            }
            if (port != null) {
                props.put("port", String.valueOf(port));
            }
            if (connectionTimeout != null && connectionTimeout > 0) {
                props.put("connection_timeout", String.valueOf(connectionTimeout));
            }
            if (readTimeout != null && readTimeout > 0) {
                props.put("read_timeout", String.valueOf(readTimeout));
            }
            config.update(props);
            return Boolean.TRUE;
        } catch (IOException e) {
            LOGGER.error("Error saving ClamAV settings to OSGi configuration", e);
            return Boolean.FALSE;
        }
    }
}
