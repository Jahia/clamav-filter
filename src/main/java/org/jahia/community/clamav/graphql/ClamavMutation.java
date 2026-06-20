package org.jahia.community.clamav.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import org.jahia.community.clamav.ClamavConstants;
import org.jahia.community.clamav.ClamavValidation;
import org.jahia.modules.graphql.provider.dxm.security.GraphQLRequiresPermission;
import org.jahia.osgi.BundleUtils;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Dictionary;
import java.util.Hashtable;

@GraphQLName("ClamavMutation")
@GraphQLDescription("ClamAV mutations")
public class ClamavMutation {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClamavMutation.class);
    private static final String PID = "org.jahia.community.clamav";

    @GraphQLField
    @GraphQLName("saveSettings")
    @GraphQLDescription("Saves the ClamAV connection settings to the OSGi configuration file and applies them immediately")
    @GraphQLRequiresPermission("clamavAdmin")
    public Boolean saveSettings(
            @GraphQLName("host") @GraphQLDescription("ClamAV daemon hostname or IP address") String host,
            @GraphQLName("port") @GraphQLDescription("ClamAV daemon port number") Integer port,
            @GraphQLName("connectionTimeout") @GraphQLDescription("Connection timeout in milliseconds") Integer connectionTimeout,
            @GraphQLName("readTimeout") @GraphQLDescription("Read/response timeout in milliseconds") Integer readTimeout) {
        if (!validateInputs(host, port, connectionTimeout, readTimeout)) {
            return Boolean.FALSE;
        }
        try {
            final ConfigurationAdmin configAdmin = BundleUtils.getOsgiService(ConfigurationAdmin.class, null);
            if (configAdmin == null) {
                LOGGER.error("ConfigurationAdmin service is not available");
                return Boolean.FALSE;
            }
            final Configuration config = configAdmin.getConfiguration(PID, null);
            final Dictionary<String, Object> props = currentOrDefaults(config.getProperties());
            applyUpdates(props, host, port, connectionTimeout, readTimeout);
            config.update(props);
            return Boolean.TRUE;
        } catch (IOException e) {
            LOGGER.error("Error saving ClamAV settings to OSGi configuration", e);
            return Boolean.FALSE;
        }
    }

    private static boolean validateInputs(String host, Integer port, Integer connTimeout, Integer readTimeout) {
        if (host != null && !isValidHost(host)) {
            LOGGER.warn("Rejecting clamavSaveSettings: invalid host");
            return false;
        }
        if (port != null && (port < ClamavConstants.MIN_PORT || port > ClamavConstants.MAX_PORT)) {
            LOGGER.warn("Rejecting clamavSaveSettings: port out of range");
            return false;
        }
        if (!isValidTimeout(connTimeout) || !isValidTimeout(readTimeout)) {
            LOGGER.warn("Rejecting clamavSaveSettings: timeout out of range");
            return false;
        }
        return true;
    }

    private static boolean isValidTimeout(Integer t) {
        return t == null || (t > 0 && t <= ClamavConstants.MAX_TIMEOUT_MS);
    }

    @SuppressWarnings("java:S1149") // OSGi ConfigurationAdmin requires a Dictionary; Hashtable is the standard impl
    private static Dictionary<String, Object> currentOrDefaults(Dictionary<String, Object> existing) {
        if (existing != null) {
            return existing;
        }
        final Dictionary<String, Object> props = new Hashtable<>();
        props.put("host", ClamavConstants.DEFAULT_HOST);
        props.put("port", String.valueOf(ClamavConstants.DEFAULT_PORT));
        props.put("connection_timeout", String.valueOf(ClamavConstants.DEFAULT_CONNECTION_TIMEOUT));
        props.put("read_timeout", String.valueOf(ClamavConstants.DEFAULT_READ_TIMEOUT));
        return props;
    }

    private static void applyUpdates(Dictionary<String, Object> props, String host, Integer port, Integer connTimeout, Integer readTimeout) {
        if (host != null) {
            props.put("host", host);
        }
        if (port != null) {
            props.put("port", String.valueOf(port));
        }
        if (connTimeout != null) {
            props.put("connection_timeout", String.valueOf(connTimeout));
        }
        if (readTimeout != null) {
            props.put("read_timeout", String.valueOf(readTimeout));
        }
    }

    /** Delegates to {@link ClamavValidation#isValidHost(String)}. Visible for testing. */
    static boolean isValidHost(String host) {
        return ClamavValidation.isValidHost(host);
    }
}
