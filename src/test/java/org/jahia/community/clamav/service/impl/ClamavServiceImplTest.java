package org.jahia.community.clamav.service.impl;

import org.jahia.community.clamav.scan.Result;
import org.jahia.community.clamav.scan.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the daemon-reply parsing and log-sanitization of {@link ClamavServiceImpl}.
 * The classification is security-critical: a "FOUND" reply must map to {@link Status#FAILED} so the
 * filter blocks and logs it as malware, and anything ambiguous must fail safe to {@link Status#ERROR}
 * (which the filter treats as fail-closed). Log sanitization guards against CRLF log injection from
 * daemon-controlled text.
 */
class ClamavServiceImplTest {

    @Nested
    @DisplayName("populateVirusScanResult")
    class ReplyClassification {

        @ParameterizedTest
        @CsvSource({
                "stream: OK,                 PASSED",   // clean stream
                "stream: some failure ERROR, ERROR",    // explicit daemon error
                "stream: FOUND,              ERROR",    // malformed FOUND with no signature body — never silently clean
                "garbage response,           FAILED"    // unrecognized reply stays FAILED (fail-closed default, not PASSED)
        })
        @DisplayName("classifies daemon replies, defaulting unrecognized output to a blocking status")
        void classifiesReplyStatus(String reply, Status expected) {
            assertThat(ClamavServiceImpl.populateVirusScanResult(reply).getStatus()).isEqualTo(expected);
        }

        @Test
        @DisplayName("a FOUND reply maps to FAILED and extracts the signature")
        void foundMapsToFailedWithSignature() {
            final Result r = ClamavServiceImpl.populateVirusScanResult("stream: Win.Test.EICAR_HDB-1 FOUND");
            assertThat(r.getStatus()).isEqualTo(Status.FAILED);
            assertThat(r.getSignature()).isEqualTo("Win.Test.EICAR_HDB-1");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("null, empty, or blank reply maps to ERROR (fail-closed)")
        void blankOrNullMapsToError(String reply) {
            // The service trims before calling this; a blank/empty/absent verdict must not be treated as clean.
            final String normalized = (reply == null) ? null : reply.trim();
            assertThat(ClamavServiceImpl.populateVirusScanResult(normalized).getStatus()).isEqualTo(Status.ERROR);
        }
    }

    @Nested
    @DisplayName("sanitize")
    class LogSanitization {

        @Test
        @DisplayName("strips CR, LF and tab to neutralize log injection")
        void stripsControlChars() {
            assertThat(ClamavServiceImpl.sanitize("line1\r\nFAKE LOG\tentry"))
                    .doesNotContain("\r").doesNotContain("\n").doesNotContain("\t");
        }

        @Test
        @DisplayName("truncates very long messages")
        void truncatesLongMessages() {
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 500; i++) {
                sb.append('a');
            }
            assertThat(ClamavServiceImpl.sanitize(sb.toString())).hasSizeLessThanOrEqualTo(203).endsWith("...");
        }

        @Test
        @DisplayName("null becomes empty string")
        void nullBecomesEmpty() {
            assertThat(ClamavServiceImpl.sanitize(null)).isEmpty();
        }
    }
}
