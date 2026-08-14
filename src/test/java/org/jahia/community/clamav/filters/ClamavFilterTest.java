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
    @DisplayName("isRawBinaryUpload")
    class RawBinaryUpload {

        @ParameterizedTest(name = "[{index}] contentType={0}, method={1}, contentLength={2} -> {3}")
        @CsvSource({
                "application/octet-stream, POST, 1024, true",
                "'application/octet-stream; charset=binary', POST, 2048, true",
                "text/plain, PUT, 100, true",
                "text/plain, PUT, 0, false",
                "application/json, POST, 512, false"
        })
        @DisplayName("classifies octet-stream POSTs and non-empty-body PUTs as raw-binary uploads (SEC-141)")
        void classifiesRawBinaryUploads(String contentType, String method, long contentLength, boolean expected) {
            final HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getContentType()).thenReturn(contentType);
            when(request.getMethod()).thenReturn(method);
            when(request.getContentLengthLong()).thenReturn(contentLength);

            assertThat(ClamavFilter.isRawBinaryUpload(request)).isEqualTo(expected);
        }

        @Test
        @DisplayName("rejects a GET request regardless of content type")
        void rejectsGetMethod() {
            final HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getContentType()).thenReturn("application/json");
            when(request.getMethod()).thenReturn("GET");
            when(request.getContentLengthLong()).thenReturn(512L);

            assertThat(ClamavFilter.isRawBinaryUpload(request)).isFalse();
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
