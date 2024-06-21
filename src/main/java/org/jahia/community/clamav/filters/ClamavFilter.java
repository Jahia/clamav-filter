package org.jahia.community.clamav.filters;

import java.io.IOException;
import java.io.InputStream;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.jahia.bin.filters.AbstractServletFilter;
import org.jahia.community.clamav.scan.Result;
import org.jahia.community.clamav.scan.Status;
import org.jahia.community.clamav.service.ClamavService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.commons.CommonsMultipartResolver;

@Component(immediate = true, service = AbstractServletFilter.class)
public class ClamavFilter extends AbstractServletFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClamavFilter.class);
    public static final String WEBFLOW_TOKEN_PARAM = "webflowToken";
    private final CommonsMultipartResolver multipartResolver = new CommonsMultipartResolver();
    private ClamavService clamavService;

    @Reference(service = ClamavService.class)
    public void setClamavService(ClamavService clamavService) {
        this.clamavService = clamavService;
    }

    public ClamavFilter() {
        setMatchAllUrls(true);
        setOrder(0.5f);
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // Nothing to do
    }

    @Override
    public void destroy() {
        // Nothing to do
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        try {
            boolean isClean = true;
            if (request instanceof HttpServletRequest) {
                HttpServletRequest httpServletRequest = (HttpServletRequest) request;
                // Fileupload other than with Webflow
                if (ServletFileUpload.isMultipartContent(httpServletRequest) && httpServletRequest.getParameter(WEBFLOW_TOKEN_PARAM) == null) {
                    httpServletRequest = multipartResolver.resolveMultipart(new MultiReadHttpServletRequest(httpServletRequest));
                    isClean = checkWithAntivirus(httpServletRequest);
                } // Fileuplod trhough Webflow, ignored for the moment as it's breaking the feature and it's meant to be deprecated at one moment
                else if (ServletFileUpload.isMultipartContent(httpServletRequest) && httpServletRequest.getParameter(WEBFLOW_TOKEN_PARAM) != null) {
                    LOGGER.info("Webdflow upload, ignored");
                } // Fileupload through Forms
                else if (httpServletRequest.getContentType() != null && httpServletRequest.getContentType().startsWith(MediaType.APPLICATION_OCTET_STREAM_VALUE)
                        && httpServletRequest.getRequestURI() != null && httpServletRequest.getRequestURI().startsWith("/modules/forms/live/fileupload")) {
                    final MultiReadHttpServletRequest multiReadHttpServletRequest = new MultiReadHttpServletRequest(httpServletRequest);
                    LOGGER.info("Forms upload");
                    isClean = checkWithAntivirus(multiReadHttpServletRequest.getInputStream());
                }
            }
            if (isClean) {
                chain.doFilter(request, response);
            } else {
                LOGGER.error("Uploaded file is a malware");
                if (response instanceof HttpServletResponse) {
                    ((HttpServletResponse) response).sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                }
            }
        } catch (IOException | ServletException | MultipartException ex) {
            LOGGER.error("Error when filtering request to check for a malware", ex);
        }
    }

    private boolean checkWithAntivirus(HttpServletRequest request) throws IOException, ServletException {
        if (clamavService.ping()) {
            Result scanResult = null;
            for (Part part : request.getParts()) {
                scanResult = clamavService.scan(part.getInputStream());
                if (scanResult.getStatus().equals(Status.FAILED)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean checkWithAntivirus(InputStream inputStream) {
        if (clamavService.ping()) {
            final Result scanResult = clamavService.scan(inputStream);
            if (scanResult.getStatus().equals(Status.FAILED)) {
                return false;
            }
        }
        return true;
    }
}
