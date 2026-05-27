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
        // Scan every multipart upload. The scan must NOT be conditional on a client-supplied value
        // (e.g. a request parameter): a guard an attacker can toggle is not a guard. The request is
        // wrapped so the buffered bytes are replayed downstream, so scanning here is safe to do for
        // all multipart uploads, including Spring Webflow ones.
        final boolean multipart = ServletFileUpload.isMultipartContent(httpRequest);
        final boolean formsUpload = !multipart && isFormsOctetStreamUpload(httpRequest);
        if (!multipart && !formsUpload) {
            chain.doFilter(request, response);
            return;
        }

        // Defense-in-depth: when the client honestly declares an oversize body, reject it before a
        // single byte is buffered into the heap. The streaming cap in MultiReadHttpServletRequest
        // still applies to chunked or under-declared bodies.
        if (exceedsScanLimit(httpRequest.getContentLengthLong())) {
            LOGGER.warn("Upload rejected: declared Content-Length exceeds the {}-byte scan limit",
                    ClamavConstants.DEFAULT_MAX_SCAN_BYTES);
            sendError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
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
        return isFormsOctetStreamUpload(req.getContentType(), req.getRequestURI());
    }

    /** True for the Jahia Forms octet-stream upload endpoint. Visible for testing. */
    static boolean isFormsOctetStreamUpload(String contentType, String uri) {
        return contentType != null && contentType.startsWith(MediaType.APPLICATION_OCTET_STREAM_VALUE)
                && uri != null && uri.startsWith(FORMS_UPLOAD_PATH);
    }

    /**
     * True when a declared body length exceeds the scan limit. A negative length (unknown /
     * chunked) returns {@code false} so those requests fall through to the streaming cap.
     * Visible for testing.
     */
    static boolean exceedsScanLimit(long contentLength) {
        return contentLength > ClamavConstants.DEFAULT_MAX_SCAN_BYTES;
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
