package org.jahia.community.clamav.service;

import java.io.InputStream;
import org.jahia.community.clamav.scan.Result;

public interface ClamavService {

    public boolean ping();

    public Result scan(final InputStream inputStream);

}
