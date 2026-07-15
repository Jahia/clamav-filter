package org.jahia.community.clamav.graphql;

import java.io.IOException;
import java.util.Dictionary;
import java.util.Hashtable;
import org.jahia.community.clamav.ClamavConstants;
import org.jahia.osgi.BundleUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ClamavMutation#saveSettings}. {@code ClamavMutationExtensionTest} (existing)
 * only covers the {@code isValidHost} delegate — this class is the first to exercise the actual
 * save/validate/apply flow: {@code validateInputs}, {@code currentOrDefaults}, {@code applyUpdates},
 * the {@code ConfigurationAdmin} interaction, and the {@code IOException} -> {@code false} path.
 */
class ClamavMutationTest {

    private static final String PID = "org.jahia.community.clamav";

    private MockedStatic<BundleUtils> bundleUtils;
    private ConfigurationAdmin configAdmin;
    private Configuration configuration;

    @BeforeEach
    void setUp() throws IOException {
        bundleUtils = Mockito.mockStatic(BundleUtils.class);
        configAdmin = mock(ConfigurationAdmin.class);
        configuration = mock(Configuration.class);
        when(configAdmin.getConfiguration(PID, null)).thenReturn(configuration);
        bundleUtils.when(() -> BundleUtils.getOsgiService(ConfigurationAdmin.class, null)).thenReturn(configAdmin);
    }

    @AfterEach
    void tearDown() {
        bundleUtils.close();
    }

    @Nested
    @DisplayName("F16: saveSettings() save/validate/apply flow")
    class SaveFlow {

        @Test
        @DisplayName("case A (happy path): no prior config -> every field is written")
        void happyPathWritesAllFieldsWhenNoPriorConfig() throws IOException {
            when(configuration.getProperties()).thenReturn(null);

            final Boolean result = new ClamavMutation().saveSettings("clamav.internal", 3310, 2000, 20000);

            assertThat(result).isTrue();
            final ArgumentCaptor<Dictionary> captor = ArgumentCaptor.forClass(Dictionary.class);
            verify(configuration).update(captor.capture());
            final Dictionary<?, ?> props = captor.getValue();
            assertThat(props.get("host")).isEqualTo("clamav.internal");
            assertThat(props.get("port")).isEqualTo("3310");
            assertThat(props.get("connection_timeout")).isEqualTo("2000");
            assertThat(props.get("read_timeout")).isEqualTo("20000");
        }

        @Test
        @DisplayName("case B (partial update): null params keep the prior values; only the supplied field changes")
        void partialUpdatePreservesPriorValues() throws IOException {
            final Hashtable<String, Object> existing = new Hashtable<>();
            existing.put("host", "old-host");
            existing.put("port", "9999");
            existing.put("connection_timeout", "1500");
            existing.put("read_timeout", "15000");
            when(configuration.getProperties()).thenReturn(existing);

            final Boolean result = new ClamavMutation().saveSettings(null, 4444, null, null);

            assertThat(result).isTrue();
            final ArgumentCaptor<Dictionary> captor = ArgumentCaptor.forClass(Dictionary.class);
            verify(configuration).update(captor.capture());
            final Dictionary<?, ?> props = captor.getValue();
            assertThat(props.get("host")).isEqualTo("old-host");
            assertThat(props.get("port")).isEqualTo("4444");
            assertThat(props.get("connection_timeout")).isEqualTo("1500");
            assertThat(props.get("read_timeout")).isEqualTo("15000");
        }

        @Test
        @DisplayName("case C (validation rejection): an invalid host returns false and never touches ConfigurationAdmin")
        void validationRejectionDoesNotWrite() {
            final Boolean result = new ClamavMutation().saveSettings("bad host/../x", null, null, null);

            assertThat(result).isFalse();
            verifyNoInteractions(configAdmin);
            verifyNoInteractions(configuration);
        }

        @Test
        @DisplayName("case D: ConfigurationAdmin.getConfiguration throwing IOException returns false, does not propagate")
        void ioExceptionFromGetConfigurationReturnsFalse() throws IOException {
            when(configAdmin.getConfiguration(PID, null)).thenThrow(new IOException("simulated ConfigurationAdmin failure"));

            final Boolean result = new ClamavMutation().saveSettings("clamav.internal", null, null, null);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("ConfigurationAdmin service absent returns false")
        void configAdminAbsentReturnsFalse() {
            bundleUtils.when(() -> BundleUtils.getOsgiService(ConfigurationAdmin.class, null)).thenReturn(null);

            final Boolean result = new ClamavMutation().saveSettings("clamav.internal", null, null, null);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("F18: port/timeout rejection on saveSettings() itself (distinct from the host-whitelist sub-claim)")
    class InputValidation {

        @ParameterizedTest
        @ValueSource(ints = {0, 65536})
        @DisplayName("rejects an out-of-range port")
        void rejectsPortOutOfRange(int port) {
            assertThat(new ClamavMutation().saveSettings(null, port, null, null)).isFalse();
            verifyNoInteractions(configAdmin);
        }

        @Test
        @DisplayName("rejects a zero connectionTimeout")
        void rejectsZeroConnectionTimeout() {
            assertThat(new ClamavMutation().saveSettings(null, null, 0, null)).isFalse();
            verifyNoInteractions(configAdmin);
        }

        @Test
        @DisplayName("rejects a connectionTimeout above MAX_TIMEOUT_MS")
        void rejectsConnectionTimeoutOverMax() {
            assertThat(new ClamavMutation().saveSettings(null, null, ClamavConstants.MAX_TIMEOUT_MS + 1, null)).isFalse();
            verifyNoInteractions(configAdmin);
        }

        @Test
        @DisplayName("rejects a negative readTimeout")
        void rejectsNegativeReadTimeout() {
            assertThat(new ClamavMutation().saveSettings(null, null, null, -5)).isFalse();
            verifyNoInteractions(configAdmin);
        }

        @Test
        @DisplayName("accepts a fully valid set of inputs (control case, proves the rejections above are meaningful)")
        void acceptsValidInputs() throws IOException {
            when(configuration.getProperties()).thenReturn(null);

            assertThat(new ClamavMutation().saveSettings("clamav.internal", 3310, 2000, 20000)).isTrue();
        }
    }
}
