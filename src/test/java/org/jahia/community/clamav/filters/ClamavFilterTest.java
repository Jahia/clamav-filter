package org.jahia.community.clamav.filters;

import org.jahia.community.clamav.ClamavConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure decision logic of {@link ClamavFilter}: which uploads are in scope for
 * scanning and the up-front size guard. The scan decision must never depend on a value an attacker
 * can set, so these tests pin the request-shape gating that survives that requirement.
 */
class ClamavFilterTest {

    @Nested
    @DisplayName("isFormsOctetStreamUpload")
    class FormsOctetStreamUpload {

        @Test
        @DisplayName("matches octet-stream POSTs to the Forms upload path")
        void matchesFormsUpload() {
            assertThat(ClamavFilter.isFormsOctetStreamUpload(
                    "application/octet-stream", "/modules/forms/live/fileupload")).isTrue();
        }

        @Test
        @DisplayName("matches when content type carries extra parameters")
        void matchesWithContentTypeParameters() {
            assertThat(ClamavFilter.isFormsOctetStreamUpload(
                    "application/octet-stream; charset=binary", "/modules/forms/live/fileupload/x")).isTrue();
        }

        @Test
        @DisplayName("rejects the Forms path with a non-octet-stream content type")
        void rejectsWrongContentType() {
            assertThat(ClamavFilter.isFormsOctetStreamUpload(
                    "application/json", "/modules/forms/live/fileupload")).isFalse();
        }

        @Test
        @DisplayName("rejects octet-stream to a different path")
        void rejectsWrongPath() {
            assertThat(ClamavFilter.isFormsOctetStreamUpload(
                    "application/octet-stream", "/cms/render/live/en/sites/x")).isFalse();
        }

        @Test
        @DisplayName("rejects null content type or null URI")
        void rejectsNulls() {
            assertThat(ClamavFilter.isFormsOctetStreamUpload(null, "/modules/forms/live/fileupload")).isFalse();
            assertThat(ClamavFilter.isFormsOctetStreamUpload("application/octet-stream", null)).isFalse();
            assertThat(ClamavFilter.isFormsOctetStreamUpload(null, null)).isFalse();
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
}
