package org.jahia.community.clamav.filters;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

public class MultiReadHttpServletRequest extends HttpServletRequestWrapper {

    private static final int COPY_BUFFER = 8192;

    private final long maxBytes;
    private byte[] cachedBytes;

    public MultiReadHttpServletRequest(HttpServletRequest request, long maxBytes) {
        super(request);
        this.maxBytes = maxBytes;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (cachedBytes == null) {
            cacheInputStream();
        }
        return new CachedServletInputStream(cachedBytes);
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    private void cacheInputStream() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[COPY_BUFFER];
        long total = 0;
        try (InputStream in = super.getInputStream()) {
            int read;
            while ((read = in.read(buf)) >= 0) {
                total += read;
                if (maxBytes > 0 && total > maxBytes) {
                    throw new RequestTooLargeException(maxBytes);
                }
                out.write(buf, 0, read);
            }
        }
        cachedBytes = out.toByteArray();
    }

    public static final class RequestTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;
        private final long limit;
        RequestTooLargeException(long limit) {
            super("Request body exceeds the configured maximum of " + limit + " bytes");
            this.limit = limit;
        }
        public long getLimit() {
            return limit;
        }
    }

    private static final class CachedServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream input;

        CachedServletInputStream(byte[] bytes) {
            this.input = new ByteArrayInputStream(bytes);
        }

        @Override
        public boolean isFinished() {
            return input.available() == 0;
        }

        @Override
        public boolean isReady() {
            return input.available() > 0;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            // no-op
        }

        @Override
        public int read() {
            return input.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return input.read(b, off, len);
        }
    }
}
