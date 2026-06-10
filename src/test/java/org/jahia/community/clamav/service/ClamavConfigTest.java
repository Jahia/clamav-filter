package org.jahia.community.clamav.service;

import java.util.Hashtable;
import org.jahia.community.clamav.ClamavConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.osgi.service.cm.ConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ClamavConfig#updated} — the OSGi ManagedService trust boundary that applies
 * configuration directly from a {@code .cfg} file or any ConfigurationAdmin writer. Validation must
 * be atomic: an invalid update is rejected wholesale and the running config is left untouched.
 */
class ClamavConfigTest {

    private static Hashtable<String, Object> validProps() {
        final Hashtable<String, Object> props = new Hashtable<>();
        props.put("host", "clamav.internal");
        props.put("port", "3310");
        props.put("connection_timeout", "2000");
        props.put("read_timeout", "20000");
        return props;
    }

    @Test
    @DisplayName("applies a valid configuration")
    void appliesValidConfig() throws ConfigurationException {
        final ClamavConfig config = new ClamavConfig();

        config.updated(validProps());

        assertThat(config.getHost()).isEqualTo("clamav.internal");
        assertThat(config.getPort()).isEqualTo(3310);
        assertThat(config.getConnectionTimeout()).isEqualTo(2000);
        assertThat(config.getReadTimeout()).isEqualTo(20000);
    }

    @Test
    @DisplayName("rejects an empty host and leaves the running config untouched")
    void rejectsEmptyHostAtomically() {
        final ClamavConfig config = new ClamavConfig();
        final Hashtable<String, Object> props = validProps();
        props.put("host", "");
        props.put("port", "4444");

        assertThatThrownBy(() -> config.updated(props))
                .isInstanceOf(ConfigurationException.class);

        // Atomicity: nothing was mutated, defaults still in place.
        assertThat(config.getHost()).isEqualTo(ClamavConstants.DEFAULT_HOST);
        assertThat(config.getPort()).isEqualTo(ClamavConstants.DEFAULT_PORT);
    }

    @Test
    @DisplayName("rejects a malformed host containing injection characters")
    void rejectsMalformedHost() {
        final ClamavConfig config = new ClamavConfig();
        final Hashtable<String, Object> props = validProps();
        props.put("host", "evil host/../x");

        assertThatThrownBy(() -> config.updated(props))
                .isInstanceOf(ConfigurationException.class);
    }

    @Test
    @DisplayName("rejects a port out of range")
    void rejectsPortOutOfRange() {
        final ClamavConfig config = new ClamavConfig();
        final Hashtable<String, Object> props = validProps();
        props.put("port", "70000");

        assertThatThrownBy(() -> config.updated(props))
                .isInstanceOf(ConfigurationException.class);
    }

    @Test
    @DisplayName("a null dictionary is a no-op")
    void nullDictionaryIsNoOp() {
        final ClamavConfig config = new ClamavConfig();

        assertThatCode(() -> config.updated(null)).doesNotThrowAnyException();

        assertThat(config.getHost()).isEqualTo(ClamavConstants.DEFAULT_HOST);
    }
}
