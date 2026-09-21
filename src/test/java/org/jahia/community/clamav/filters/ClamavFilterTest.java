package org.jahia.community.clamav.filters;

import java.util.List;
import javax.servlet.http.HttpServletRequest;
import org.jahia.community.clamav.ClamavConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the pure decision logic of {@link ClamavFilter}: which uploads are in scope for
 * scanning and the up-front size guard. The scan decision must never depend on a value an attacker
 * can set, so these tests pin the request-shape gating that survives that requirement.
 */
class ClamavFilterTest {

    @Nested
    @DisplayName("shouldScan")
    class ShouldScan {

        private static HttpServletRequest request(String contentType, String method, long contentLength) {
            final HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getContentType()).thenReturn(contentType);
            when(request.getMethod()).thenReturn(method);
            when(request.getContentLengthLong()).thenReturn(contentLength);
            return request;
        }

        @ParameterizedTest(name = "[{index}] {1} {0} (len={2}) -> scanned")
        @CsvSource(nullValues = "NULL", value = {
                // --- SEC-418 method gap: the old predicate only knew PUT ---------------------
                "application/pdf,             PATCH,  100",
                "application/pdf,             DELETE, 100",
                "image/png,                   PATCH,  100",
                // --- SEC-418 case gap: media types are case-insensitive (RFC 7231 3.1.1.1) ---
                "Application/OCTET-STREAM,    POST,   100",
                "APPLICATION/OCTET-STREAM,    PUT,    100",
                // --- SEC-418 type gap: any declared type could carry a file ------------------
                "application/pdf,             POST,   100",
                "text/plain,                  POST,   100",
                "image/png,                   POST,   100",
                "application/xml,             POST,   100",
                // --- a body with no declared Content-Type (bare WebDAV PUT) ------------------
                "NULL,                        PUT,    100",
                "NULL,                        POST,   100",
                // --- SEC-141 coverage that must not regress ----------------------------------
                "application/octet-stream,    POST,   1024",
                "'application/octet-stream; charset=binary', POST, 2048",
                "text/plain,                  PUT,    100"
        })
        @DisplayName("scans any request carrying a body whose media type is not explicitly skippable (SEC-418)")
        void scansBodyBearingRequests(String contentType, String method, long contentLength) {
            assertThat(ClamavFilter.shouldScan(request(contentType, method, contentLength))).isTrue();
        }

        @ParameterizedTest(name = "[{index}] {1} {0} (len={2}) -> skipped")
        @CsvSource(nullValues = "NULL", value = {
                // --- the explicit skip-list: Jahia's own structured-API traffic --------------
                "application/json,                       POST,  512",
                "'application/json; charset=UTF-8',      POST,  512",
                "APPLICATION/JSON,                       POST,  512",
                "application/x-www-form-urlencoded,      POST,  512",
                "'application/x-www-form-urlencoded; charset=UTF-8', POST, 512",
                "application/graphql,                    POST,  512",
                "application/ld+json,                    POST,  512",
                "application/merge-patch+json,           PATCH, 512",
                // --- no body: nothing to scan -------------------------------------------------
                "text/plain,                             PUT,   0",
                "application/pdf,                        POST,  0",
                "NULL,                                   GET,   -1",
                "application/json,                       GET,   -1"
        })
        @DisplayName("skips structured-API media types and every request with no body")
        void skipsSkippableAndBodilessRequests(String contentType, String method, long contentLength) {
            assertThat(ClamavFilter.shouldScan(request(contentType, method, contentLength))).isFalse();
        }

        @ParameterizedTest(name = "[{index}] {1} {0} with an unknown length -> scanned")
        @CsvSource(nullValues = "NULL", value = {
                "application/pdf, POST",
                "application/pdf, PATCH",
                "NULL,            PUT",
                "NULL,            POST"
        })
        @DisplayName("scans an unknown-length body, with no Transfer-Encoding to go on (HTTP/2 forbids it, RFC 9113 8.2.2)")
        void scansUnknownLengthBody(String contentType, String method) {
            assertThat(ClamavFilter.shouldScan(request(contentType, method, -1L))).isTrue();
        }

        @Test
        @DisplayName("scans an unknown-length body whose method is unreadable rather than guessing")
        void scansUnknownLengthBodyWithNullMethod() {
            assertThat(ClamavFilter.shouldScan(request("application/pdf", null, -1L))).isTrue();
        }

        @Test
        @DisplayName("skips an unknown-length body whose media type is on the skip-list")
        void skipsUnknownLengthSkippableBody() {
            assertThat(ClamavFilter.shouldScan(request("application/json", "POST", -1L))).isFalse();
        }

        @ParameterizedTest(name = "[{index}] {0} with an unknown length -> skipped")
        @CsvSource({"GET", "HEAD"})
        @DisplayName("skips the read-only methods when no body length is declared (no body semantics, bulk of the traffic)")
        void skipsBodilessMethods(String method) {
            assertThat(ClamavFilter.shouldScan(request("application/pdf", method, -1L))).isFalse();
        }
    }

    @Nested
    @DisplayName("exceedsScanLimit")
    class ScanLimit {

        @Test
        @DisplayName("rejects a declared length above the scan cap")
        void rejectsOverCap() {
            assertThat(ClamavFilter.exceedsScanLimit(ClamavConstants.DEFAULT_MAX_SCAN_BYTES + 1)).isTrue();
        }

        @Test
        @DisplayName("allows a declared length exactly at the cap")
        void allowsAtCap() {
            assertThat(ClamavFilter.exceedsScanLimit(ClamavConstants.DEFAULT_MAX_SCAN_BYTES)).isFalse();
        }

        @ParameterizedTest
        @ValueSource(longs = {-1L, 0L, 1024L})
        @DisplayName("allows unknown (negative) and small declared lengths so they hit the streaming cap")
        void allowsUnknownAndSmall(long length) {
            assertThat(ClamavFilter.exceedsScanLimit(length)).isFalse();
        }
    }

    @Nested
    @DisplayName("collectFiles")
    class CollectFiles {

        @Test
        @DisplayName("returns every file even when multiple parts share one field name (no AV-bypass via getFileMap collapsing)")
        void collectsDuplicateFieldNameParts() {
            final MultipartFile fileA = mock(MultipartFile.class);
            final MultipartFile fileB = mock(MultipartFile.class);
            final MultipartFile other = mock(MultipartFile.class);
            final MultiValueMap<String, MultipartFile> map = new LinkedMultiValueMap<>();
            // Two files posted under the SAME field name — getFileMap() would keep only one.
            map.add("upload", fileA);
            map.add("upload", fileB);
            map.add("attachment", other);
            final MultipartHttpServletRequest resolved = mock(MultipartHttpServletRequest.class);
            when(resolved.getMultiFileMap()).thenReturn(map);

            final List<MultipartFile> files = ClamavFilter.collectFiles(resolved);

            assertThat(files).containsExactlyInAnyOrder(fileA, fileB, other);
        }

        @Test
        @DisplayName("returns an empty list when there are no file parts")
        void emptyWhenNoParts() {
            final MultipartHttpServletRequest resolved = mock(MultipartHttpServletRequest.class);
            when(resolved.getMultiFileMap()).thenReturn(new LinkedMultiValueMap<>());

            assertThat(ClamavFilter.collectFiles(resolved)).isEmpty();
        }
    }
}
