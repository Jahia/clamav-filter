package org.jahia.community.clamav.service;

import java.util.Dictionary;
import org.osgi.framework.Constants;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedServiceFactory;
import org.osgi.service.component.annotations.Component;

@Component(immediate = true, service = {ClamavConfig.class, ManagedServiceFactory.class}, property = Constants.SERVICE_PID + "=org.jahia.community.clamav")
public class ClamavConfig implements ManagedServiceFactory {

    private String host;
    private Integer port;

    @Override
    public String getName() {
        return "org.jahia.community.clamav";
    }

    @Override
    public void updated(String pid, Dictionary<String, ?> dictionary) throws ConfigurationException {
        host = (String) dictionary.get("host");
        port = Integer.valueOf((String) dictionary.get("port"));
    }

    @Override
    public void deleted(String pid) {
        host = null;
        port = null;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

}
