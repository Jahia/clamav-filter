package org.jahia.community.clamav.filters;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

    private static final String JSON_SUFFIX = "+json";

    /**
     * The only methods assumed to carry no body when the body length is unknown. Deliberately tiny:
     * this is NOT a "which uploads do we scan" allow-list (SEC-418 was exactly that mistake), it only
     * keeps the read-only traffic that makes up the bulk of the requests out of the scanner. Anything
     * that slips through anyway is caught by the empty-body check in {@link #scanBody}.
     */
    private static final Set<String> BODILESS_METHODS = Set.of("GET", "HEAD");

    /**
     * The only media types exempt from scanning: Jahia's own structured-API traffic, which carries
     * parsed request data rather than a file. Keep this list short and justified — every entry is a
     * hole in the antivirus, and anything NOT listed here is scanned. Notably absent on purpose:
     * {@code text/*}, {@code application/xml} and every image/document type, all of which can carry
     * a file payload. GraphQL *file* uploads use multipart and are scanned on the multipart path.
     */
    private static final Set<String> SKIPPED_MEDIA_TYPES = Set.of(
            MediaType.APPLICATION_JSON_VALUE,
            MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            "application/graphql");

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
        // SEC-418: the gate is DENY-BY-DEFAULT. Every request carrying a body is buffered and scanned
        // through the same fail-closed path unless its media type is on SKIPPED_MEDIA_TYPES. The former
        // SEC-141 predicate was an allow-list of exactly two shapes (an application/octet-stream body,
        // or a PUT with a body) guarding a pass-through branch, so a PATCH, a case-variant
        // `Application/OCTET-STREAM` and a POST declaring any other type all reached the repository
        // unscanned. Do NOT turn this back into an allow-list of shapes: a scanner must scan what it
        // does not recognise, not wave it through.
        if (!multipart && !shouldScan(httpRequest)) {
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
            final ScanOutcome outcome = multipart ? scanMultipart(wrapped) : scanBody(wrapped);
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
     * True when a non-multipart request must be scanned: it carries a body and its media type is not
     * explicitly exempt. Deliberately independent of the HTTP method — SEC-418 showed that keying on
     * the verb left {@code PATCH} and {@code DELETE} bodies unscanned. Visible for testing.
     */
    static boolean shouldScan(HttpServletRequest req) {
        return mayHaveBody(req) && !isSkippableMediaType(req.getContentType());
    }

    /**
     * True when the request may carry an entity body. A declared {@code Content-Length} settles it
     * either way. An UNKNOWN length ({@code -1}) is assumed to be a body unless the method has no body
     * semantics: that covers HTTP/1.1 chunked uploads and, critically, HTTP/2 streamed bodies, which
     * carry no {@code Transfer-Encoding} header at all because RFC 9113 &sect;8.2.2 forbids it. Keying
     * on that header instead would let every h2 upload through unscanned. A null method is treated as
     * body-bearing — unknown means scan. Visible for testing.
     */
    static boolean mayHaveBody(HttpServletRequest req) {
        final long declaredLength = req.getContentLengthLong();
        if (declaredLength > 0) {
            return true;
        }
        if (declaredLength == 0) {
            return false;
        }
        final String method = req.getMethod();
        // Set.of() rejects a null probe with an NPE, so the null case is settled first (and safely).
        return method == null || !BODILESS_METHODS.contains(method);
    }

    /**
     * True for a media type on {@link #SKIPPED_MEDIA_TYPES}, or any structured {@code +json} syntax.
     * The comparison is case-insensitive and ignores parameters, because media types are
     * case-insensitive per RFC 7231 &sect;3.1.1.1 — SEC-418 bypassed the old case-sensitive
     * {@code startsWith} check with nothing more than {@code Application/OCTET-STREAM}. An absent or
     * unrecognised Content-Type is NOT skippable: unknown means scan. Visible for testing.
     */
    static boolean isSkippableMediaType(String contentType) {
        final String mediaType = baseMediaType(contentType);
        return SKIPPED_MEDIA_TYPES.contains(mediaType) || mediaType.endsWith(JSON_SUFFIX);
    }

    /**
     * The lower-cased {@code type/subtype} of a Content-Type header with any parameters
     * ({@code ; charset=...}) stripped, or an empty string when none is declared. Lower-casing uses
     * {@link Locale#ROOT} so the decision cannot change with the JVM's default locale.
     */
    private static String baseMediaType(String contentType) {
        if (contentType == null) {
            return "";
        }
        final int parameterStart = contentType.indexOf(';');
        final String typeAndSubtype = parameterStart < 0 ? contentType : contentType.substring(0, parameterStart);
        return typeAndSubtype.trim().toLowerCase(Locale.ROOT);
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

    private ScanOutcome scanBody(MultiReadHttpServletRequest wrapped) throws IOException {
        // Buffer first and settle emptiness before consulting the scanner: a request whose length was
        // unknown may turn out to carry no body at all (an OPTIONS preflight, a bodyless POST). There is
        // nothing to scan, so it must not cost a daemon round-trip — nor a fail-closed 503 when the
        // daemon is down.
        if (wrapped.bufferedLength() == 0) {
            LOGGER.debug("Request body is empty - nothing to scan");
            return ScanOutcome.CLEAN;
        }
        LOGGER.debug("Scanning non-multipart request body");
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
