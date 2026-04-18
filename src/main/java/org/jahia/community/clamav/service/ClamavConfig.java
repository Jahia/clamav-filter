package org.jahia.community.clamav.service;

import java.util.Dictionary;
import org.jahia.community.clamav.ClamavConstants;
import org.osgi.framework.Constants;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.osgi.service.component.annotations.Component;

@Component(immediate = true, service = {ClamavConfig.class, ManagedService.class}, property = Constants.SERVICE_PID + "=org.jahia.community.clamav")
public class ClamavConfig implements ManagedService {

    private String host = ClamavConstants.DEFAULT_HOST;
    private int port = ClamavConstants.DEFAULT_PORT;
    private int connectionTimeout = ClamavConstants.DEFAULT_CONNECTION_TIMEOUT;
    private int readTimeout = ClamavConstants.DEFAULT_READ_TIMEOUT;

    @Override
    public void updated(Dictionary<String, ?> dictionary) throws ConfigurationException {
        if (dictionary == null) {
            return;
        }
        if (dictionary.get("host") != null) {
            host = (String) dictionary.get("host");
        }
        if (dictionary.get("port") != null) {
            port = Integer.parseInt((String) dictionary.get("port"));
        }
        if (dictionary.get("connection_timeout") != null) {
            connectionTimeout = Integer.parseInt((String) dictionary.get("connection_timeout"));
        }
        if (dictionary.get("read_timeout") != null) {
            readTimeout = Integer.parseInt((String) dictionary.get("read_timeout"));
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
