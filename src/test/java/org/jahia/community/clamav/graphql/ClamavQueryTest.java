package org.jahia.community.clamav.graphql;

import java.util.Base64;
import org.jahia.community.clamav.ClamavConstants;
import org.jahia.community.clamav.scan.Result;
import org.jahia.community.clamav.scan.Status;
import org.jahia.community.clamav.service.ClamavConfig;
import org.jahia.community.clamav.service.ClamavService;
import org.jahia.osgi.BundleUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ClamavQuery} — previously zero unit tests existed for this class (Stage 2's
 * Java test inventory: "no unit test exists for this class"). Uses {@code Mockito.mockStatic} on
 * {@link BundleUtils} (Mockito 5.14.2's default inline mock maker supports this with no new pom
 * dependency) so the resolver logic can be exercised without a live OSGi container.
 */
class ClamavQueryTest {

    private MockedStatic<BundleUtils> bundleUtils;

    @BeforeEach
    void mockBundleUtils() {
        bundleUtils = Mockito.mockStatic(BundleUtils.class);
    }

    @AfterEach
    void closeMock() {
        bundleUtils.close();
    }

    @Nested
    @DisplayName("settings()")
    class Settings {

        @Test
        @DisplayName("F13: returns the live config's values when the ClamavConfig service is present")
        void returnsConfigValuesWhenPresent() {
            final ClamavConfig config = mock(ClamavConfig.class);
            when(config.getHost()).thenReturn("clamav.internal");
            when(config.getPort()).thenReturn(3310);
            when(config.getConnectionTimeout()).thenReturn(2500);
            when(config.getReadTimeout()).thenReturn(21000);
            bundleUtils.when(() -> BundleUtils.getOsgiService(ClamavConfig.class, null)).thenReturn(config);

            final ClamavQuery.GqlClamavSettings settings = new ClamavQuery().settings();

            assertThat(settings.getHost()).isEqualTo("clamav.internal");
            assertThat(settings.getPort()).isEqualTo(3310);
            assertThat(settings.getConnectionTimeout()).isEqualTo(2500);
            assertThat(settings.getReadTimeout()).isEqualTo(21000);
        }

        @Test
        @DisplayName("F13: returns ClamavConstants defaults when the ClamavConfig service is absent")
        void returnsDefaultsWhenConfigAbsent() {
            bundleUtils.when(() -> BundleUtils.getOsgiService(ClamavConfig.class, null)).thenReturn(null);

            final ClamavQuery.GqlClamavSettings settings = new ClamavQuery().settings();

            assertThat(settings.getHost()).isEqualTo(ClamavConstants.DEFAULT_HOST);
            assertThat(settings.getPort()).isEqualTo(ClamavConstants.DEFAULT_PORT);
            assertThat(settings.getConnectionTimeout()).isEqualTo(ClamavConstants.DEFAULT_CONNECTION_TIMEOUT);
            assertThat(settings.getReadTimeout()).isEqualTo(ClamavConstants.DEFAULT_READ_TIMEOUT);
        }
    }

    @Nested
    @DisplayName("ping()")
    class Ping {

        @Test
        @DisplayName("F14: delegates to service.ping() == true")
        void delegatesTrue() {
            final ClamavService service = mock(ClamavService.class);
            when(service.ping()).thenReturn(true);
            bundleUtils.when(() -> BundleUtils.getOsgiService(ClamavService.class, null)).thenReturn(service);

            assertThat(new ClamavQuery().ping()).isTrue();
        }

        @Test
        @DisplayName("F14: delegates to service.ping() == false")
        void delegatesFalse() {
            final ClamavService service = mock(ClamavService.class);
            when(service.ping()).thenReturn(false);
            bundleUtils.when(() -> BundleUtils.getOsgiService(ClamavService.class, null)).thenReturn(service);

            assertThat(new ClamavQuery().ping()).isFalse();
        }

        @Test
        @DisplayName("F14/F20: returns false (not null, not a thrown exception) when the service is absent")
        void falseWhenServiceAbsent() {
            bundleUtils.when(() -> BundleUtils.getOsgiService(ClamavService.class, null)).thenReturn(null);

            assertThat(new ClamavQuery().ping()).isFalse();
        }
    }

    @Nested
    @DisplayName("scanTest() — consolidates F15, F19, F20, U8, D3 (single scaffold, per Stage 3's own recommendation)")
    class ScanTest {

        @Test
        @DisplayName("F15: PASSED round-trips with a null signature")
        void passedRoundTrips() {
            final ClamavService service = mock(ClamavService.class);
            when(service.scan(any())).thenReturn(new Result(Status.PASSED, "stream: OK"));
            bundleUtils.when(() -> BundleUtils.getOsgiService(ClamavService.class, null)).thenReturn(service);
            final String content = Base64.getEncoder().encodeToString("clean".getBytes());

            final ClamavQuery.GqlScanResult result = new ClamavQuery().scanTest(content);

            assertThat(result.getStatus()).isEqualTo("PASSED");
            assertThat(result.getSignature()).isNull();
        }

        @Test
        @DisplayName("F15: FAILED round-trips the signature unchanged")
        void failedRoundTripsSignature() {
            final ClamavService service = mock(ClamavService.class);
            when(service.scan(any())).thenReturn(new Result(Status.FAILED, "stream: X FOUND", "X"));
            bundleUtils.when(() -> BundleUtils.getOsgiService(ClamavService.class, null)).thenReturn(service);
            final String content = Base64.getEncoder().encodeToString("infected".getBytes());

            final ClamavQuery.GqlScanResult result = new ClamavQuery().scanTest(content);

            assertThat(result.getStatus()).isEqualTo("FAILED");
            assertThat(result.getSignature()).isEqualTo("X");
        }

        @Test
        @DisplayName("D3: null content -> ERROR, without ever looking up the service")
        void nullContentIsError() {
            final ClamavQuery.GqlScanResult result = new ClamavQuery().scanTest(null);

            assertThat(result.getStatus()).isEqualTo(Status.ERROR.name());
            assertThat(result.getSignature()).isNull();
            bundleUtils.verifyNoInteractions();
        }

        @Test
        @DisplayName("D3: empty content -> ERROR, without ever looking up the service")
        void emptyContentIsError() {
            final ClamavQuery.GqlScanResult result = new ClamavQuery().scanTest("");

            assertThat(result.getStatus()).isEqualTo(Status.ERROR.name());
            assertThat(result.getSignature()).isNull();
            bundleUtils.verifyNoInteractions();
        }

        @Test
        @DisplayName("F19/D3: oversize base64 -> ERROR, short-circuits before BundleUtils.getOsgiService is ever called")
        void oversizeContentShortCircuitsBeforeServiceLookup() {
            final String oversize = "A".repeat(ClamavConstants.MAX_BASE64_INPUT_CHARS + 1);

            final ClamavQuery.GqlScanResult result = new ClamavQuery().scanTest(oversize);

            assertThat(result.getStatus()).isEqualTo(Status.ERROR.name());
            assertThat(result.getSignature()).isNull();
            bundleUtils.verifyNoInteractions();
        }

        @Test
        @DisplayName("D3: malformed (non-decodable) base64 -> ERROR, after the service lookup but before scan()")
        void malformedBase64IsError() {
            final ClamavService service = mock(ClamavService.class);
            bundleUtils.when(() -> BundleUtils.getOsgiService(ClamavService.class, null)).thenReturn(service);

            final ClamavQuery.GqlScanResult result = new ClamavQuery().scanTest("not-valid-base64!!!");

            assertThat(result.getStatus()).isEqualTo(Status.ERROR.name());
            assertThat(result.getSignature()).isNull();
            Mockito.verify(service, Mockito.never()).scan(any());
        }

        @Test
        @DisplayName("F20/D3: service absent -> CONNECTION_FAILED, distinct from all the ERROR cases above")
        void serviceAbsentIsConnectionFailedNotError() {
            bundleUtils.when(() -> BundleUtils.getOsgiService(ClamavService.class, null)).thenReturn(null);
            final String content = Base64.getEncoder().encodeToString("clean".getBytes());

            final ClamavQuery.GqlScanResult result = new ClamavQuery().scanTest(content);

            assertThat(result.getStatus()).isEqualTo("CONNECTION_FAILED");
            assertThat(result.getSignature()).isNull();
        }

        @Test
        @DisplayName("U8/D3: an unchecked RuntimeException from scan() maps to ERROR, not CONNECTION_FAILED, and does not propagate")
        void runtimeExceptionDuringScanMapsToError() {
            final ClamavService service = mock(ClamavService.class);
            when(service.scan(any())).thenThrow(new IllegalStateException("simulated scan failure"));
            bundleUtils.when(() -> BundleUtils.getOsgiService(ClamavService.class, null)).thenReturn(service);
            final String content = Base64.getEncoder().encodeToString("clean".getBytes());

            final ClamavQuery.GqlScanResult result = new ClamavQuery().scanTest(content);

            assertThat(result.getStatus()).isEqualTo(Status.ERROR.name());
            assertThat(result.getSignature()).isNull();
        }
    }
}
