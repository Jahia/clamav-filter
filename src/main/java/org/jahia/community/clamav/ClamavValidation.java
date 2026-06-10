package org.jahia.community.clamav;

/**
 * Shared validation for ClamAV connection settings, applied at every trust boundary that can
 * mutate the running configuration (the GraphQL save mutation and the OSGi {@code ManagedService}
 * update). Centralizing the host whitelist keeps both paths consistent: a host is validated the
 * same way whether it arrives from the admin UI or directly from a {@code .cfg} file.
 */
public final class ClamavValidation {

    /** Max DNS name length (RFC 1035). */
    public static final int MAX_HOST_LENGTH = 253;

    private ClamavValidation() {
    }

    /**
     * Accept hostnames, IPv4 addresses, and bracketed IPv6 addresses by character whitelist
     * (letters, digits, dot, hyphen, colon, brackets). Rejects {@code null}, empty, over-length,
     * path separators, whitespace, and any other character that could enable URL/scheme injection
     * in downstream socket use.
     */
    public static boolean isValidHost(String host) {
        if (host == null || host.isEmpty() || host.length() > MAX_HOST_LENGTH) {
            return false;
        }
        for (int i = 0; i < host.length(); i++) {
            final char c = host.charAt(i);
            final boolean allowed = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == ':' || c == '[' || c == ']';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }
}
