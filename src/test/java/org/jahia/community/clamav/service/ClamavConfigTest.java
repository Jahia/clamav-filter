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
    @DisplayName("a null dictionary does not throw and leaves a fresh instance on its defaults")
    void nullDictionaryDoesNotThrow() {
        final ClamavConfig config = new ClamavConfig();

        assertThatCode(() -> config.updated(null)).doesNotThrowAnyException();

        assertThat(config.getHost()).isEqualTo(ClamavConstants.DEFAULT_HOST);
    }

    @Test
    @DisplayName("a null dictionary on a CONFIGURED instance reverts every field to defaults (ManagedService delete contract)")
    void nullDictionaryRevertsConfiguredInstanceToDefaults() throws ConfigurationException {
        // The fresh-instance test above cannot tell "no-op" from "revert" — both leave defaults in
        // place. This one configures the instance first, so it fails if updated(null) merely returns
        // and leaves a deleted configuration silently in force.
        final ClamavConfig config = new ClamavConfig();
        final Hashtable<String, Object> props = validProps();
        props.put("host", "clamav.custom");
        props.put("port", "9999");
        props.put("connection_timeout", "1234");
        props.put("read_timeout", "4321");
        config.updated(props);
        assertThat(config.getHost()).isEqualTo("clamav.custom");

        config.updated(null);

        assertThat(config.getHost()).isEqualTo(ClamavConstants.DEFAULT_HOST);
        assertThat(config.getPort()).isEqualTo(ClamavConstants.DEFAULT_PORT);
        assertThat(config.getConnectionTimeout()).isEqualTo(ClamavConstants.DEFAULT_CONNECTION_TIMEOUT);
        assertThat(config.getReadTimeout()).isEqualTo(ClamavConstants.DEFAULT_READ_TIMEOUT);
    }

    @Test
    @DisplayName("a second successful update on the same live instance replaces the first (live reload, no restart)")
    void secondUpdateOnLiveInstanceSticks() throws ConfigurationException {
        // Fixes the F9 downgrade: Stage 3's cited test only ever called updated() once. This
        // proves the ManagedService callback firing again on an already-configured, live
        // instance (exactly what Felix FileInstall/ConfigurationAdmin do on a .cfg change or a
        // GraphQL saveSettings write) actually replaces the first set of values, not just that a
        // fresh instance can be configured once.
        final ClamavConfig config = new ClamavConfig();
        config.updated(validProps());
        assertThat(config.getHost()).isEqualTo("clamav.internal");
        assertThat(config.getPort()).isEqualTo(3310);

        final Hashtable<String, Object> secondProps = new Hashtable<>();
        secondProps.put("host", "clamav2.internal");
        secondProps.put("port", "4433");
        secondProps.put("connection_timeout", "3000");
        secondProps.put("read_timeout", "25000");
        config.updated(secondProps);

        assertThat(config.getHost()).isEqualTo("clamav2.internal");
        assertThat(config.getPort()).isEqualTo(4433);
        assertThat(config.getConnectionTimeout()).isEqualTo(3000);
        assertThat(config.getReadTimeout()).isEqualTo(25000);
    }

    @Test
    @DisplayName("rejects an out-of-range connection_timeout and leaves the running config untouched")
    void rejectsConnectionTimeoutOutOfRangeAtomically() {
        // ClamavConfig.java:37-40's third rejection branch (timeout out of range) had zero
        // coverage of any kind before this test — not even a basic "does it throw" case.
        final ClamavConfig config = new ClamavConfig();
        final Hashtable<String, Object> props = validProps();
        props.put("host", "still-valid-host");
        props.put("connection_timeout", String.valueOf(ClamavConstants.MAX_TIMEOUT_MS + 1));

        assertThatThrownBy(() -> config.updated(props))
                .isInstanceOf(ConfigurationException.class);

        // Atomicity re-check for the timeout branch specifically (only host/port were re-checked
        // by existing tests; nothing re-verified atomicity for a timeout-out-of-range rejection).
        assertThat(config.getHost()).isEqualTo(ClamavConstants.DEFAULT_HOST);
        assertThat(config.getConnectionTimeout()).isEqualTo(ClamavConstants.DEFAULT_CONNECTION_TIMEOUT);
        assertThat(config.getReadTimeout()).isEqualTo(ClamavConstants.DEFAULT_READ_TIMEOUT);
    }

    @Test
    @DisplayName("rejects an out-of-range (zero) read_timeout and leaves the running config untouched")
    void rejectsReadTimeoutOutOfRangeAtomically() {
        final ClamavConfig config = new ClamavConfig();
        final Hashtable<String, Object> props = validProps();
        props.put("read_timeout", "0");

        assertThatThrownBy(() -> config.updated(props))
                .isInstanceOf(ConfigurationException.class);

        assertThat(config.getReadTimeout()).isEqualTo(ClamavConstants.DEFAULT_READ_TIMEOUT);
        assertThat(config.getHost()).isEqualTo(ClamavConstants.DEFAULT_HOST);
    }

    @Test
    @DisplayName("a partial update (host only) preserves the prior, non-default port and timeouts")
    void partialUpdatePreservesPriorNonDefaultValues() throws ConfigurationException {
        // The "partial update" success path: a dictionary supplying only a subset of keys must
        // fall back to the PRIOR live values for the omitted keys, not the class defaults — this
        // matters because ManagedService callbacks and .cfg files may legitimately omit unchanged
        // keys. This was previously untested beyond the from-defaults case in appliesValidConfig.
        final ClamavConfig config = new ClamavConfig();
        config.updated(validProps());
        assertThat(config.getPort()).isEqualTo(3310);

        final Hashtable<String, Object> partial = new Hashtable<>();
        partial.put("host", "only-host-changed");

        config.updated(partial);

        assertThat(config.getHost()).isEqualTo("only-host-changed");
        // Prior (non-default) values retained, not reset to ClamavConstants defaults.
        assertThat(config.getPort()).isEqualTo(3310);
        assertThat(config.getConnectionTimeout()).isEqualTo(2000);
        assertThat(config.getReadTimeout()).isEqualTo(20000);
    }
}
