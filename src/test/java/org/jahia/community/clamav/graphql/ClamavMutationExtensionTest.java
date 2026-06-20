package org.jahia.community.clamav.graphql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ClamavMutation#isValidHost(String)} — the host whitelist applied
 * before a caller-supplied daemon host is persisted and later used to open a raw socket. Rejecting
 * whitespace, path separators, and scheme characters limits the shapes that could enable
 * URL/scheme injection in downstream socket use.
 */
class ClamavMutationExtensionTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "localhost", "clamav", "clamav.internal", "192.168.1.10", "10.0.0.5",
            "clamav-daemon-1", "[::1]", "[2001:db8::1]"
    })
    @DisplayName("accepts hostnames, IPv4, and bracketed IPv6")
    void acceptsValidHosts(String host) {
        assertThat(ClamavMutation.isValidHost(host)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "host with space", "host/../etc", "http://evil", "host;rm -rf", "host\nrm",
            "host\tx", "host,other", "host|nc", "host`id`", "héllo"
    })
    @DisplayName("rejects whitespace, path, scheme, and shell/control characters")
    void rejectsInjectionShapes(String host) {
        assertThat(ClamavMutation.isValidHost(host)).isFalse();
    }

    @Test
    @DisplayName("rejects empty and over-length hosts")
    void rejectsEmptyAndOverLength() {
        assertThat(ClamavMutation.isValidHost("")).isFalse();
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 254; i++) {
            sb.append('a');
        }
        assertThat(ClamavMutation.isValidHost(sb.toString())).isFalse();
    }
}
