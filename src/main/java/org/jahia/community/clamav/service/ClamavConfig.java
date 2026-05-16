package org.jahia.community.clamav.service;

import java.util.Dictionary;
import org.jahia.community.clamav.ClamavConstants;
import org.osgi.framework.Constants;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.osgi.service.component.annotations.Component;

@Component(immediate = true, service = {ClamavConfig.class, ManagedService.class}, property = Constants.SERVICE_PID + "=org.jahia.community.clamav")
public class ClamavConfig implements ManagedService {

    private volatile String host = ClamavConstants.DEFAULT_HOST;
    private volatile int port = ClamavConstants.DEFAULT_PORT;
    private volatile int connectionTimeout = ClamavConstants.DEFAULT_CONNECTION_TIMEOUT;
    private volatile int readTimeout = ClamavConstants.DEFAULT_READ_TIMEOUT;

    @Override
    public void updated(Dictionary<String, ?> dictionary) throws ConfigurationException {
        if (dictionary == null) {
            return;
        }
        final String newHost = parseString(dictionary, "host", host);
        final int newPort = parseInt(dictionary, "port", port);
        final int newConn = parseInt(dictionary, "connection_timeout", connectionTimeout);
        final int newRead = parseInt(dictionary, "read_timeout", readTimeout);
        if (newPort < ClamavConstants.MIN_PORT || newPort > ClamavConstants.MAX_PORT) {
            throw new ConfigurationException("port", "out of range");
        }
        if (newConn <= 0 || newConn > ClamavConstants.MAX_TIMEOUT_MS
                || newRead <= 0 || newRead > ClamavConstants.MAX_TIMEOUT_MS) {
            throw new ConfigurationException("timeout", "out of range");
        }
        this.host = newHost;
        this.port = newPort;
        this.connectionTimeout = newConn;
        this.readTimeout = newRead;
    }

    private static String parseString(Dictionary<String, ?> d, String key, String fallback) {
        final Object v = d.get(key);
        return (v == null) ? fallback : v.toString();
    }

    private static int parseInt(Dictionary<String, ?> d, String key, int fallback) throws ConfigurationException {
        final Object v = d.get(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            throw new ConfigurationException(key, "not an integer", e);
        }
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    public int getReadTimeout() {
        return readTimeout;
    }
}
