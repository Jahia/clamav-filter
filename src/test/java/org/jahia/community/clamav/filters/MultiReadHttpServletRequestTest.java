package org.jahia.community.clamav.filters;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MultiReadHttpServletRequest} — the wrapper that buffers the request body so
 * it can be scanned and then replayed downstream, while bounding the buffered size to defend against
 * an unauthenticated heap-DoS via an under-declared or chunked oversize body.
 */
class MultiReadHttpServletRequestTest {

    private static HttpServletRequest requestWithBody(byte[] body) throws IOException {
        final HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getInputStream()).thenReturn(new ByteArrayServletInputStream(body));
        return request;
    }

    @Test
    @DisplayName("buffers the body and replays identical bytes on repeated reads")
    void replaysBodyAcrossMultipleReads() throws IOException {
        final byte[] body = "hello clamav".getBytes(StandardCharsets.UTF_8);
        final MultiReadHttpServletRequest wrapped = new MultiReadHttpServletRequest(requestWithBody(body), 1024);

        assertThat(readAll(wrapped.getInputStream())).isEqualTo(body);
        // Second read must return the same buffered bytes (the whole point of the wrapper).
        assertThat(readAll(wrapped.getInputStream())).isEqualTo(body);
    }

    @Test
    @DisplayName("throws RequestTooLargeException when the streamed body exceeds the cap")
    void rejectsBodyOverCap() throws IOException {
        final byte[] body = new byte[2048];
        final MultiReadHttpServletRequest wrapped = new MultiReadHttpServletRequest(requestWithBody(body), 1024);

        assertThatThrownBy(wrapped::getInputStream)
                .isInstanceOf(MultiReadHttpServletRequest.RequestTooLargeException.class)
                .hasMessageContaining("1024");
    }

    @Test
    @DisplayName("RequestTooLargeException exposes the configured limit")
    void exceptionCarriesLimit() throws IOException {
        final MultiReadHttpServletRequest wrapped = new MultiReadHttpServletRequest(requestWithBody(new byte[10]), 4);

        assertThatThrownBy(wrapped::getInputStream)
                .isInstanceOfSatisfying(MultiReadHttpServletRequest.RequestTooLargeException.class,
                        ex -> assertThat(ex.getLimit()).isEqualTo(4L));
    }

    @Test
    @DisplayName("allows a body exactly at the cap")
    void allowsBodyAtCap() throws IOException {
        final byte[] body = new byte[1024];
        final MultiReadHttpServletRequest wrapped = new MultiReadHttpServletRequest(requestWithBody(body), 1024);

        assertThatCode(() -> readAll(wrapped.getInputStream())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a non-positive cap disables the size limit")
    void zeroCapIsUnbounded() throws IOException {
        final byte[] body = new byte[5000];
        final MultiReadHttpServletRequest wrapped = new MultiReadHttpServletRequest(requestWithBody(body), 0);

        assertThat(readAll(wrapped.getInputStream())).hasSize(5000);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        // Own and close the supplied stream so every caller is leak-safe.
        try (InputStream stream = in; java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            final byte[] buf = new byte[256];
            int read;
            while ((read = stream.read(buf)) >= 0) {
                out.write(buf, 0, read);
            }
            return out.toByteArray();
        }
    }

    /** Minimal ServletInputStream over a byte array for stubbing the wrapped request. */
    private static final class ByteArrayServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream delegate;

        ByteArrayServletInputStream(byte[] bytes) {
            this.delegate = new ByteArrayInputStream(bytes);
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            // no-op
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return delegate.read(b, off, len);
        }
    }
}
