package org.jahia.community.clamav;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the shared {@link ClamavValidation#isValidHost(String)} whitelist applied at
 * every config trust boundary (GraphQL save mutation and OSGi ManagedService update).
 */
class ClamavValidationTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "localhost", "clamav", "clamav.internal", "192.168.1.10", "10.0.0.5",
            "clamav-daemon-1", "[::1]", "[2001:db8::1]"
    })
    @DisplayName("accepts hostnames, IPv4, and bracketed IPv6")
    void acceptsValidHosts(String host) {
        assertThat(ClamavValidation.isValidHost(host)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "host with space", "host/../etc", "http://evil", "host;rm -rf", "host\nrm",
            "host\tx", "host,other", "host|nc", "host`id`", "héllo"
    })
    @DisplayName("rejects whitespace, path, scheme, and shell/control characters")
    void rejectsInjectionShapes(String host) {
        assertThat(ClamavValidation.isValidHost(host)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("rejects null and empty hosts")
    void rejectsNullAndEmpty(String host) {
        assertThat(ClamavValidation.isValidHost(host)).isFalse();
    }

    @Test
    @DisplayName("rejects over-length hosts")
    void rejectsOverLength() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= ClamavValidation.MAX_HOST_LENGTH; i++) {
            sb.append('a');
        }
        assertThat(ClamavValidation.isValidHost(sb.toString())).isFalse();
    }
}
