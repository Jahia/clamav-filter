package org.jahia.community.clamav.filters;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import javax.servlet.FilterChain;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.jahia.community.clamav.ClamavConstants;
import org.jahia.community.clamav.scan.Result;
import org.jahia.community.clamav.scan.Status;
import org.jahia.community.clamav.service.ClamavService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ClamavFilter#doFilter}, the fail-closed core of this module. This is the
 * single most security-critical piece of the module (AGENTS.md: "Scanner unreachable / Status.ERROR
 * is fail-closed (503). Do not change this without a documented threat-model review.") — every test
 * here was written to genuinely fail if that guarantee regressed, not merely to exist.
 *
 * <p>Shared harness: a small multipart-body builder (real bytes parsed by a real
 * {@code CommonsMultipartResolver}, not a mocked multipart request), plain octet-stream/PUT request
 * builders, and helpers to force the streaming-cap / malformed-multipart / stream-IOException error
 * paths. All scenarios funnel through {@link #doFilterCapturing}.
 */
class ClamavFilterDoFilterTest {

    private static final String BOUNDARY = "----ClamavTestBoundary987654321";

    // --- Shared multipart/body builders -------------------------------------------------------

    private static byte[] multipartBody(String fieldName, String filename, byte[] fileContent) {
        final String preamble = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "\r\n";
        final String epilogue = "\r\n--" + BOUNDARY + "--\r\n";
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, preamble);
        out.writeBytes(fileContent);
        writeAscii(out, epilogue);
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
    }

    private static HttpServletRequest multipartRequest(byte[] body) throws IOException {
        final HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getContentType()).thenReturn("multipart/form-data; boundary=" + BOUNDARY);
        when(request.getContentLengthLong()).thenReturn((long) body.length);
        when(request.getContentLength()).thenReturn(body.length);
        when(request.getCharacterEncoding()).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/cms/render/live/en/sites/test/upload");
        when(request.getInputStream()).thenReturn(new ByteArrayServletInputStream(body));
        return request;
    }

    private static HttpServletRequest octetStreamRequest(byte[] body) throws IOException {
        final HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getContentType()).thenReturn("application/octet-stream");
        when(request.getContentLengthLong()).thenReturn((long) body.length);
        when(request.getContentLength()).thenReturn(body.length);
        when(request.getRequestURI()).thenReturn("/modules/forms/live/fileupload");
        when(request.getInputStream()).thenReturn(new ByteArrayServletInputStream(body));
        return request;
    }

    /** A request whose declared Content-Length alone exceeds the scan cap: rejected before any buffering. */
    private static HttpServletRequest declaredOversizeRequest() {
        final HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getContentType()).thenReturn("application/octet-stream");
        when(request.getContentLengthLong()).thenReturn(ClamavConstants.DEFAULT_MAX_SCAN_BYTES + 1);
        when(request.getRequestURI()).thenReturn("/modules/forms/live/fileupload");
        return request;
    }

    /**
     * A request that under-declares its length (unknown/chunked, -1) but whose actual streamed body
     * exceeds the cap, forcing rejection via {@link MultiReadHttpServletRequest.RequestTooLargeException}
     * rather than the up-front declared-length check. Uses a synthetic zero-filled stream so the test
     * doesn't need to materialize a ~100 MB byte array itself.
     */
    private static HttpServletRequest streamingCapExceededRequest() throws IOException {
        final HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getContentType()).thenReturn("application/octet-stream");
        when(request.getContentLengthLong()).thenReturn(-1L);
        when(request.getRequestURI()).thenReturn("/modules/forms/live/fileupload");
        when(request.getInputStream()).thenReturn(new ZeroFilledServletInputStream(ClamavConstants.DEFAULT_MAX_SCAN_BYTES + 4096));
        return request;
    }

    /**
     * A request whose body carries the declared multipart Content-Type and a syntactically-opened
     * part (valid headers, boundary present) but whose stream ends abruptly mid-content, without
     * ever reaching a closing boundary. A preamble with no boundary at all is legal multipart (RFC
     * 2046 treats unrecognized leading bytes as an ignorable preamble) and commons-fileupload
     * silently returns zero parts for it — this instead forces a genuine
     * {@code MalformedStreamException}/{@code FileUploadException} deep in the parser.
     */
    private static HttpServletRequest malformedMultipartRequest() throws IOException {
        final String truncated = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"x.txt\"\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "\r\n"
                + "unterminated part content, stream ends here with no closing boundary";
        final byte[] garbage = truncated.getBytes(StandardCharsets.US_ASCII);
        final HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getContentType()).thenReturn("multipart/form-data; boundary=" + BOUNDARY);
        when(request.getContentLengthLong()).thenReturn((long) garbage.length);
        when(request.getContentLength()).thenReturn(garbage.length);
        when(request.getCharacterEncoding()).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/cms/render/live/en/sites/test/upload");
        when(request.getInputStream()).thenReturn(new ByteArrayServletInputStream(garbage));
        return request;
    }

    /** A request whose stream throws IOException on read, forcing the generic-error catch block. */
    private static HttpServletRequest requestWithThrowingStream() throws IOException {
        final HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getContentType()).thenReturn("application/octet-stream");
        when(request.getContentLengthLong()).thenReturn(10L);
        when(request.getRequestURI()).thenReturn("/modules/forms/live/fileupload");
        when(request.getInputStream()).thenThrow(new IOException("simulated broken connection"));
        return request;
    }

    private static ClamavFilter filterWith(ClamavService service) {
        final ClamavFilter filter = new ClamavFilter();
        if (service != null) {
            filter.setClamavService(service);
        }
        return filter;
    }

    private static ClamavService mockService(boolean pingResult, Result scanResult) {
        final ClamavService service = mock(ClamavService.class);
        when(service.ping()).thenReturn(pingResult);
        if (scanResult != null) {
            when(service.scan(any())).thenReturn(scanResult);
        }
        return service;
    }

    // --- F1 / F3: happy path, wrapped-request forwarding, byte-identity ------------------------

    @Nested
    @DisplayName("clean uploads: forwarded to the chain via the wrapped request")
    class HappyPath {

        @Test
        @DisplayName("multipart: chain.doFilter is called exactly once with the wrapped request; scan() sees the file bytes")
        void multipartCleanFileForwardedWithWrappedRequest() throws Exception {
            final byte[] fileContent = "hello clamav, this is a clean file".getBytes(StandardCharsets.UTF_8);
            final byte[] rawBody = multipartBody("file", "clean.txt", fileContent);
            final HttpServletRequest request = multipartRequest(rawBody);
            final ClamavService service = mockService(true, new Result(Status.PASSED, "stream: OK"));
            final ClamavFilter filter = filterWith(service);
            final HttpServletResponse response = mock(HttpServletResponse.class);
            final FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            final ArgumentCaptor<ServletRequest> forwarded = ArgumentCaptor.forClass(ServletRequest.class);
            verify(chain, times(1)).doFilter(forwarded.capture(), org.mockito.ArgumentMatchers.eq(response));
            assertThat(forwarded.getValue()).isInstanceOf(MultiReadHttpServletRequest.class);

            // F3: the wrapped instance replays the exact original bytes downstream (no TOCTOU gap).
            assertThat(readAll(((MultiReadHttpServletRequest) forwarded.getValue()).getInputStream())).isEqualTo(rawBody);

            // F3: the bytes fed to scan() are exactly the uploaded file's bytes, not the whole multipart envelope.
            final ArgumentCaptor<InputStream> scanned = ArgumentCaptor.forClass(InputStream.class);
            verify(service).scan(scanned.capture());
            assertThat(readAll(scanned.getValue())).isEqualTo(fileContent);

            verify(response, never()).sendError(anyInt());
        }

        @Test
        @DisplayName("octet-stream: chain.doFilter is called exactly once with the wrapped request")
        void octetStreamCleanFileForwardedWithWrappedRequest() throws Exception {
            final byte[] fileContent = "clean forms upload body".getBytes(StandardCharsets.UTF_8);
            final HttpServletRequest request = octetStreamRequest(fileContent);
            final ClamavService service = mockService(true, new Result(Status.PASSED, "stream: OK"));
            final ClamavFilter filter = filterWith(service);
            final HttpServletResponse response = mock(HttpServletResponse.class);
            final FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            final ArgumentCaptor<ServletRequest> forwarded = ArgumentCaptor.forClass(ServletRequest.class);
            verify(chain, times(1)).doFilter(forwarded.capture(), org.mockito.ArgumentMatchers.eq(response));
            assertThat(forwarded.getValue()).isInstanceOf(MultiReadHttpServletRequest.class);
            assertThat(readAll(((MultiReadHttpServletRequest) forwarded.getValue()).getInputStream())).isEqualTo(fileContent);
            verify(service).scan(any());
            verify(response, never()).sendError(anyInt());
        }
    }

    // --- F2: scanning cannot be disabled by a client-supplied parameter/header ------------------

    @Nested
    @DisplayName("scanning cannot be bypassed by a client-supplied parameter or header")
    class NonBypassable {

        @Test
        @DisplayName("an attacker-set parameter/header has zero effect: infected file is still blocked")
        void adversarialParameterDoesNotSkipScanning() throws Exception {
            final byte[] rawBody = multipartBody("file", "evil.txt", "malware-ish content".getBytes(StandardCharsets.UTF_8));
            final HttpServletRequest request = multipartRequest(rawBody);
            // Plausible bypass attempts: neither is read anywhere in ClamavFilter.doFilter().
            when(request.getParameter("webflowToken")).thenReturn("skip");
            when(request.getHeader("X-Skip-Scan")).thenReturn("true");
            final ClamavService service = mockService(true, new Result(Status.FAILED, "stream: X FOUND", "X"));
            final ClamavFilter filter = filterWith(service);
            final HttpServletResponse response = mock(HttpServletResponse.class);
            final FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            // If the bypass had any effect, the file would have passed unscanned (chain called, 200-ish).
            // Instead: scanning happened regardless, and the (attacker-unwanted) infected verdict governs.
            verify(service).scan(any());
            verify(response).sendError(HttpServletResponse.SC_FORBIDDEN);
            verify(chain, never()).doFilter(any(), any());
        }
    }

    // --- F4: oversize rejection, both entry paths -----------------------------------------------

    @Nested
    @DisplayName("oversize uploads are rejected with 413, before or during buffering")
    class OversizeUpload {

        @Test
        @DisplayName("declared Content-Length above the cap is rejected up front, before any scan attempt")
        void declaredLengthOverCapRejectedUpFront() throws Exception {
            final HttpServletRequest request = declaredOversizeRequest();
            final ClamavService service = mock(ClamavService.class);
            final ClamavFilter filter = filterWith(service);
            final HttpServletResponse response = mock(HttpServletResponse.class);
            final FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            verify(response).sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            verify(chain, never()).doFilter(any(), any());
            verify(service, never()).ping();
            verify(service, never()).scan(any());
        }

        @Test
        @DisplayName("an under-declared (unknown-length) body that streams over the cap is rejected via the streaming check")
        void streamingCapExceededRejected() throws Exception {
            final HttpServletRequest request = streamingCapExceededRequest();
            final ClamavService service = mockService(true, new Result(Status.PASSED, "stream: OK"));
            final ClamavFilter filter = filterWith(service);
            final HttpServletResponse response = mock(HttpServletResponse.class);
            final FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            verify(response).sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            verify(chain, never()).doFilter(any(), any());
        }
    }

    // --- F5: infected upload -> 403, log message pinned exactly (and signature is NOT logged) ---

    @Nested
    @DisplayName("infected uploads are rejected with 403 and a signature-free log line")
    class InfectedUpload {

        private ListAppender<ILoggingEvent> appender;
        private ch.qos.logback.classic.Logger logger;

        @BeforeEach
        void attachAppender() {
            logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ClamavFilter.class);
            appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
        }

        @AfterEach
        void detachAppender() {
            logger.detachAppender(appender);
        }

        @Test
        @DisplayName("multipart: 403, chain never called, log message is exactly \"Uploaded file is a malware\" without the signature")
        void infectedMultipartRejected() throws Exception {
            final byte[] rawBody = multipartBody("file", "eicar.txt", "EICAR-ish".getBytes(StandardCharsets.UTF_8));
            final HttpServletRequest request = multipartRequest(rawBody);
            final ClamavService service = mockService(true,
                    new Result(Status.FAILED, "stream: Win.Test.EICAR_HDB-1 FOUND", "Win.Test.EICAR_HDB-1"));
            final ClamavFilter filter = filterWith(service);
            final HttpServletResponse response = mock(HttpServletResponse.class);
            final FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            verify(response).sendError(HttpServletResponse.SC_FORBIDDEN);
            verify(chain, never()).doFilter(any(), any());

            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getFormattedMessage()).isEqualTo("Uploaded file is a malware");
                assertThat(event.getFormattedMessage()).doesNotContain("Win.Test.EICAR_HDB-1");
            });
        }
    }

    // --- F6 / U1: fail-closed when the scanner is unreachable, both entry paths -----------------

    @Nested
    @DisplayName("F6: fail-closed (503) when the scanner is unreachable")
    class FailClosed {

        @Test
        @DisplayName("multipart: service never bound -> 503, scan() never invoked")
        void serviceNeverBoundMultipart() throws Exception {
            final byte[] rawBody = multipartBody("file", "clean.txt", "clean".getBytes(StandardCharsets.UTF_8));
            final HttpServletRequest request = multipartRequest(rawBody);
            final ClamavFilter filter = filterWith(null);
            final HttpServletResponse response = mock(HttpServletResponse.class);
            final FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            verify(response).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            verify(chain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("octet-stream: service never bound -> 503, scan() never invoked")
        void serviceNeverBoundOctetStream() throws Exception {
            final HttpServletRequest request = octetStreamRequest("clean".getBytes(StandardCharsets.UTF_8));
            final ClamavFilter filter = filterWith(null);
            final HttpServletResponse response = mock(HttpServletResponse.class);
            final FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            verify(response).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            verify(chain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("multipart: ping() false -> 503; scan() is never invoked (pins the || short-circuit ordering)")
        void pingFailsMultipart() throws Exception {
            final byte[] rawBody = multipartBody("file", "clean.txt", "clean".getBytes(StandardCharsets.UTF_8));
            final HttpServletRequest request = multipartRequest(rawBody);
            final ClamavService service = mockService(false, null);
            final ClamavFilter filter = filterWith(service);
            final HttpServletResponse response = mock(HttpServletResponse.class);
            final FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            verify(response).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            verify(chain, never()).doFilter(any(), any());
            verify(service, never()).scan(any());
        }

        @Test
        @DisplayName("octet-stream: ping() false -> 503; scan() is never invoked")
        void pingFailsOctetStream() throws Exception {
            final HttpServletRequest request = octetStreamRequest("clean".getBytes(StandardCharsets.UTF_8));
            final ClamavService service = mockService(false, null);
            final ClamavFilter filter = filterWith(service);
            final HttpServletResponse response = mock(HttpServletResponse.class);
            final FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            verify(response).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            verify(chain, never()).doFilter(any(), any());
            verify(service, never()).scan(any());
        }

        @Test
        @DisplayName("multipart: scan() returns Status.ERROR mid-scan -> 503, chain never called")
        void scanReturnsErrorMultipart() throws Exception {
            final byte[] rawBody = multipartBody("file", "clean.txt", "clean".getBytes(StandardCharsets.UTF_8));
            final HttpServletRequest request = multipartRequest(rawBody);
            final ClamavService service = mockService(true,
                    new Result(Status.ERROR, "Impossible to scan inputstream for a malware"));
            final ClamavFilter filter = filterWith(service);
            final HttpServletResponse response = mock(HttpServletResponse.class);
            final FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            verify(response).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            verify(chain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("octet-stream: scan() returns Status.ERROR mid-scan -> 503, chain never called")
        void scanReturnsErrorOctetStream() throws Exception {
            final HttpServletRequest request = octetStreamRequest("clean".getBytes(StandardCharsets.UTF_8));
            final ClamavService service = mockService(true,
                    new Result(Status.ERROR, "Impossible to scan inputstream for a malware"));
            final ClamavFilter filter = filterWith(service);
            final HttpServletResponse response = mock(HttpServletResponse.class);
            final FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            verify(response).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            verify(chain, never()).doFilter(any(), any());
        }
    }

    // --- F7: generic error handling for unexpected failures -------------------------------------

    @Nested
    @DisplayName("F7: unexpected failures during parsing/reading are handled as 500, never forwarded")
    class GenericError {

        private ListAppender<ILoggingEvent> appender;
        private ch.qos.logback.classic.Logger logger;

        @BeforeEach
        void attachAppender() {
            logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ClamavFilter.class);
            appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
        }

        @AfterEach
        void detachAppender() {
            logger.detachAppender(appender);
        }

        @Test
        @DisplayName("a malformed multipart body triggers MultipartException -> 500")
        void malformedMultipartTriggersGenericError() throws Exception {
            final HttpServletRequest request = malformedMultipartRequest();
            final ClamavService service = mockService(true, new Result(Status.PASSED, "stream: OK"));
            final ClamavFilter filter = filterWith(service);
            final HttpServletResponse response = mock(HttpServletResponse.class);
            final FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            verify(response).sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            verify(chain, never()).doFilter(any(), any());
            assertThat(appender.list).anySatisfy(event ->
                    assertThat(event.getFormattedMessage()).isEqualTo("Error scanning request for malware"));
        }

        @Test
        @DisplayName("an IOException reading the body triggers the generic catch -> 500")
        void streamIOExceptionTriggersGenericError() throws Exception {
            final HttpServletRequest request = requestWithThrowingStream();
            final ClamavService service = mockService(true, new Result(Status.PASSED, "stream: OK"));
            final ClamavFilter filter = filterWith(service);
            final HttpServletResponse response = mock(HttpServletResponse.class);
            final FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            verify(response).sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            verify(chain, never()).doFilter(any(), any());
            assertThat(appender.list).anySatisfy(event ->
                    assertThat(event.getFormattedMessage()).isEqualTo("Error scanning request for malware"));
        }
    }

    // --- D4: cross-cutting umbrella — chain.doFilter is NEVER called for ANY non-clean outcome --

    @Nested
    @DisplayName("D4: chain.doFilter is never called for any non-clean outcome (fail-closed umbrella)")
    class NeverForwardsOnFailure {

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("org.jahia.community.clamav.filters.ClamavFilterDoFilterTest#failureScenarios")
        @DisplayName("chain.doFilter is never invoked, whatever the failure condition")
        void chainNeverCalled(String description, Scenario scenario) throws Exception {
            final HttpServletResponse response = mock(HttpServletResponse.class);
            final FilterChain chain = mock(FilterChain.class);

            scenario.filter().doFilter(scenario.request(), response, chain);

            verify(chain, never()).doFilter(any(), any());
        }
    }

    /** One row of the D4 umbrella table: a fully-wired filter plus the request that triggers the condition. */
    private record Scenario(ClamavFilter filter, HttpServletRequest request) {
    }

    @SuppressWarnings("unused") // referenced by @MethodSource above
    private static Stream<Arguments> failureScenarios() throws IOException {
        final byte[] cleanMultipart = multipartBody("file", "clean.txt", "clean".getBytes(StandardCharsets.UTF_8));
        final byte[] cleanOctet = "clean".getBytes(StandardCharsets.UTF_8);

        return Stream.of(
                Arguments.of("service never bound (multipart)",
                        new Scenario(filterWith(null), multipartRequest(cleanMultipart))),
                Arguments.of("service never bound (octet-stream)",
                        new Scenario(filterWith(null), octetStreamRequest(cleanOctet))),
                Arguments.of("ping() false (multipart)",
                        new Scenario(filterWith(mockService(false, null)), multipartRequest(cleanMultipart))),
                Arguments.of("ping() false (octet-stream)",
                        new Scenario(filterWith(mockService(false, null)), octetStreamRequest(cleanOctet))),
                Arguments.of("scan() -> Status.ERROR (multipart)",
                        new Scenario(filterWith(mockService(true, new Result(Status.ERROR, "boom"))), multipartRequest(cleanMultipart))),
                Arguments.of("scan() -> Status.ERROR (octet-stream)",
                        new Scenario(filterWith(mockService(true, new Result(Status.ERROR, "boom"))), octetStreamRequest(cleanOctet))),
                Arguments.of("scan() -> Status.FAILED / infected (multipart)",
                        new Scenario(filterWith(mockService(true, new Result(Status.FAILED, "FOUND", "X"))), multipartRequest(cleanMultipart))),
                Arguments.of("scan() -> Status.FAILED / infected (octet-stream)",
                        new Scenario(filterWith(mockService(true, new Result(Status.FAILED, "FOUND", "X"))), octetStreamRequest(cleanOctet))),
                Arguments.of("declared Content-Length exceeds the cap (413 pre-check)",
                        new Scenario(filterWith(mock(ClamavService.class)), declaredOversizeRequest())),
                Arguments.of("streamed body exceeds the cap (413 streaming-cap RequestTooLargeException)",
                        new Scenario(filterWith(mockService(true, new Result(Status.PASSED, "OK"))), streamingCapExceededRequest())),
                Arguments.of("malformed multipart body (MultipartException -> 500)",
                        new Scenario(filterWith(mockService(true, new Result(Status.PASSED, "OK"))), malformedMultipartRequest())),
                Arguments.of("stream IOException while reading the body (-> 500)",
                        new Scenario(filterWith(mockService(true, new Result(Status.PASSED, "OK"))), requestWithThrowingStream()))
        );
    }

    // --- shared stream helpers --------------------------------------------------------------

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            final byte[] buf = new byte[512];
            int read;
            while ((read = stream.read(buf)) >= 0) {
                out.write(buf, 0, read);
            }
            return out.toByteArray();
        }
    }

    /** Minimal ServletInputStream over a fixed byte array. */
    private static final class ByteArrayServletInputStream extends ServletInputStream {
        private final java.io.ByteArrayInputStream delegate;

        ByteArrayServletInputStream(byte[] bytes) {
            this.delegate = new java.io.ByteArrayInputStream(bytes);
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            // no-op
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return delegate.read(b, off, len);
        }
    }

    /**
     * Generates {@code length} zero bytes on the fly without materializing them as a single array,
     * so the streaming-cap test doesn't need to allocate a ~100 MB byte[] itself (the buffering
     * wrapper under test still accumulates up to just under the cap while reading this).
     */
    private static final class ZeroFilledServletInputStream extends ServletInputStream {
        private long remaining;

        ZeroFilledServletInputStream(long length) {
            this.remaining = length;
        }

        @Override
        public boolean isFinished() {
            return remaining <= 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            // no-op
        }

        @Override
        public int read() {
            if (remaining <= 0) {
                return -1;
            }
            remaining--;
            return 0;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (remaining <= 0) {
                return -1;
            }
            final int n = (int) Math.min(len, remaining);
            java.util.Arrays.fill(b, off, off + n, (byte) 0);
            remaining -= n;
            return n;
        }
    }
}
