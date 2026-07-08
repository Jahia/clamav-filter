package org.jahia.community.clamav.filters;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.commons.CommonsMultipartResolver;

@Component(immediate = true, service = AbstractServletFilter.class)
public class ClamavFilter extends AbstractServletFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClamavFilter.class);
    @SuppressWarnings("java:S1075")
    private static final String FORMS_UPLOAD_PATH = "/modules/forms/live/fileupload";

    // volatile: written by the OSGi DS bind/unbind thread, read by concurrent servlet request
    // threads in doFilter. The default STATIC reference policy publishes the value before
    // activation, but volatile makes the cross-thread visibility explicit and JMM-safe.
    // S3077 (suppressed): the field holds an immutable service handle that is only ever reassigned,
    // never mutated through the reference, so a volatile reference is the correct, sufficient guard.
    @SuppressWarnings("java:S3077")
    private volatile ClamavService clamavService;

    @Reference(service = ClamavService.class, unbind = "unsetClamavService")
    public void setClamavService(ClamavService clamavService) {
        this.clamavService = clamavService;
    }

    public void unsetClamavService(ClamavService clamavService) {
        // Clear only if the unbound service is the one currently held (DS unbind contract).
        if (this.clamavService == clamavService) {
            this.clamavService = null;
        }
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
        // SEC-141: scan raw (non-multipart) binary upload channels too, not just multipart and the one
        // Forms octet-stream path. Any application/octet-stream body and any PUT body (WebDAV / JCR-REST
        // binary writes) is buffered and scanned via the same fail-closed path, closing the coverage gap
        // where malware delivered over a non-multipart channel entered the repository unscanned.
        final boolean rawBinary = !multipart && isRawBinaryUpload(httpRequest);
        if (!multipart && !rawBinary) {
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
                    LOGGER.error("Unexpected scan outcome: {}", outcome);
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

    /**
     * True for a non-multipart request whose body should be scanned as a raw binary upload: any
     * {@code application/octet-stream} body, or any {@code PUT} carrying a body (WebDAV / JCR-REST binary
     * writes). This generalizes the former Forms-only octet-stream handling to close the SEC-141 gap.
     * Visible for testing.
     */
    static boolean isRawBinaryUpload(HttpServletRequest req) {
        final String contentType = req.getContentType();
        if (contentType != null && contentType.startsWith(MediaType.APPLICATION_OCTET_STREAM_VALUE)) {
            return true;
        }
        return "PUT".equalsIgnoreCase(req.getMethod()) && req.getContentLengthLong() != 0;
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
        if (response instanceof HttpServletResponse httpResponse) {
            httpResponse.sendError(statusCode);
        }
    }

    private ScanOutcome scanMultipart(MultiReadHttpServletRequest wrapped) throws IOException {
        // Parse the parts with a Commons resolver, which reads them from the buffered body
        // independently of any servlet-level multipart configuration. Iterating Spring's parsed
        // MultipartFile map (instead of the Servlet 3.0 getParts() API) avoids an
        // IllegalStateException on endpoints whose servlet has no multipart config registered
        // (e.g. /modules/api/provisioning) while still scanning every uploaded file.
        // A fresh resolver per request: CommonsMultipartResolver / Apache Commons FileUpload are
        // not documented as thread-safe, and this filter is a singleton serving concurrent requests.
        // getMultiFileMap() (not getFileMap()) preserves EVERY part, including multiple files posted
        // under the same field name — getFileMap() collapses those to one, leaving the others
        // unscanned but replayed downstream (an AV bypass).
        final MultipartHttpServletRequest resolved = new CommonsMultipartResolver().resolveMultipart(wrapped);
        final ClamavService service = clamavService;
        if (service == null || !service.ping()) {
            return ScanOutcome.SCANNER_UNAVAILABLE;
        }
        // Open each file's stream lazily inside its own try-with-resources: opening all of them
        // up front would leak the already-opened ones if a later getInputStream() failed.
        for (MultipartFile file : collectFiles(resolved)) {
            try (InputStream in = file.getInputStream()) {
                final ScanOutcome outcome = classify(service.scan(in));
                if (outcome != ScanOutcome.CLEAN) {
                    return outcome;
                }
            }
        }
        return ScanOutcome.CLEAN;
    }

    /**
     * Flattens every uploaded file from the resolved multipart request. Uses {@code getMultiFileMap()}
     * rather than {@code getFileMap()}: the latter is keyed by field name and collapses multiple files
     * posted under the same field name to a single entry, which would leave the others unscanned but
     * replayed downstream (an AV bypass). Visible for testing.
     */
    static List<MultipartFile> collectFiles(MultipartHttpServletRequest resolved) {
        final List<MultipartFile> files = new ArrayList<>();
        resolved.getMultiFileMap().values().forEach(files::addAll);
        return files;
    }

    private ScanOutcome scanOctetStream(MultiReadHttpServletRequest wrapped) throws IOException {
        LOGGER.debug("Forms upload scan");
        final ClamavService service = clamavService;
        if (service == null || !service.ping()) {
            return ScanOutcome.SCANNER_UNAVAILABLE;
        }
        try (InputStream in = wrapped.getInputStream()) {
            return classify(service.scan(in));
        }
    }

    /**
     * Maps a scan {@link Result} to an outcome. Fail-closed: an infected part is {@code INFECTED}
     * and a scanner {@code Status.ERROR} is treated as unavailable (503); anything else is clean.
     */
    private static ScanOutcome classify(Result scanResult) {
        if (Status.FAILED.equals(scanResult.getStatus())) {
            return ScanOutcome.INFECTED;
        }
        if (Status.ERROR.equals(scanResult.getStatus())) {
            return ScanOutcome.SCANNER_UNAVAILABLE;
        }
        return ScanOutcome.CLEAN;
    }

    private enum ScanOutcome {
        CLEAN, INFECTED, SCANNER_UNAVAILABLE
    }
}
