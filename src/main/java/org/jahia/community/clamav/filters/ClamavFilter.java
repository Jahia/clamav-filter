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
import org.jahia.community.clamav.ClamavConstants;
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
    @SuppressWarnings("java:S1075")
    private static final String FORMS_UPLOAD_PATH = "/modules/forms/live/fileupload";

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
        if (!(request instanceof HttpServletRequest)) {
            chain.doFilter(request, response);
            return;
        }
        final HttpServletRequest httpRequest = (HttpServletRequest) request;
        final boolean multipart = ServletFileUpload.isMultipartContent(httpRequest)
                && httpRequest.getParameter(WEBFLOW_TOKEN_PARAM) == null;
        final boolean formsUpload = !multipart && isFormsOctetStreamUpload(httpRequest);
        if (!multipart && !formsUpload) {
            chain.doFilter(request, response);
            return;
        }

        if (ServletFileUpload.isMultipartContent(httpRequest)
                && httpRequest.getParameter(WEBFLOW_TOKEN_PARAM) != null) {
            LOGGER.info("Webflow upload, ignored");
            chain.doFilter(request, response);
            return;
        }

        try {
            final MultiReadHttpServletRequest wrapped = new MultiReadHttpServletRequest(httpRequest, ClamavConstants.DEFAULT_MAX_SCAN_BYTES);
            final ScanOutcome outcome = multipart ? scanMultipart(wrapped) : scanOctetStream(wrapped);
            switch (outcome) {
                case CLEAN:
                    chain.doFilter(wrapped, response);
                    return;
                case INFECTED:
                    LOGGER.error("Uploaded file is a malware");
                    sendError(response, HttpServletResponse.SC_FORBIDDEN);
                    return;
                case SCANNER_UNAVAILABLE:
                    LOGGER.error("ClamAV unreachable - rejecting upload (fail-closed)");
                    sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    return;
                default:
                    sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        } catch (MultiReadHttpServletRequest.RequestTooLargeException ex) {
            LOGGER.warn("Upload rejected: {}", ex.getMessage());
            sendError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        } catch (IOException | ServletException | MultipartException ex) {
            LOGGER.error("Error scanning request for malware", ex);
            sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private static boolean isFormsOctetStreamUpload(HttpServletRequest req) {
        final String ct = req.getContentType();
        final String uri = req.getRequestURI();
        return ct != null && ct.startsWith(MediaType.APPLICATION_OCTET_STREAM_VALUE)
                && uri != null && uri.startsWith(FORMS_UPLOAD_PATH);
    }

    private static void sendError(ServletResponse response, int statusCode) throws IOException {
        if (response instanceof HttpServletResponse) {
            ((HttpServletResponse) response).sendError(statusCode);
        }
    }

    private ScanOutcome scanMultipart(MultiReadHttpServletRequest wrapped) throws IOException, ServletException {
        if (clamavService == null || !clamavService.ping()) {
            return ScanOutcome.SCANNER_UNAVAILABLE;
        }
        final HttpServletRequest resolved = multipartResolver.resolveMultipart(wrapped);
        for (Part part : resolved.getParts()) {
            try (InputStream in = part.getInputStream()) {
                final Result scanResult = clamavService.scan(in);
                if (Status.FAILED.equals(scanResult.getStatus())) {
                    return ScanOutcome.INFECTED;
                }
                if (Status.ERROR.equals(scanResult.getStatus())) {
                    return ScanOutcome.SCANNER_UNAVAILABLE;
                }
            }
        }
        return ScanOutcome.CLEAN;
    }

    private ScanOutcome scanOctetStream(MultiReadHttpServletRequest wrapped) throws IOException {
        if (clamavService == null || !clamavService.ping()) {
            return ScanOutcome.SCANNER_UNAVAILABLE;
        }
        LOGGER.info("Forms upload scan");
        try (InputStream in = wrapped.getInputStream()) {
            final Result scanResult = clamavService.scan(in);
            if (Status.FAILED.equals(scanResult.getStatus())) {
                return ScanOutcome.INFECTED;
            }
            if (Status.ERROR.equals(scanResult.getStatus())) {
                return ScanOutcome.SCANNER_UNAVAILABLE;
            }
        }
        return ScanOutcome.CLEAN;
    }

    private enum ScanOutcome {
        CLEAN, INFECTED, SCANNER_UNAVAILABLE
    }
}
