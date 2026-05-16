package org.jahia.community.clamav.service.impl;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.jahia.community.clamav.scan.Result;
import org.jahia.community.clamav.scan.Status;
import org.jahia.community.clamav.service.ClamavConfig;
import org.jahia.community.clamav.service.ClamavService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(immediate = true, service = {ClamavService.class})
public class ClamavServiceImpl implements ClamavService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClamavServiceImpl.class);
    private static final String ERROR_SUFFIX = "ERROR";
    private static final String FOUND_SUFFIX = "FOUND";
    private static final String PONG = "PONG";
    private static final String RESPONSE_OK = "stream: OK";
    private static final String STREAM_PREFIX = "stream:";
    private static final int CHUNK_SIZE = 2048;
    private static final int MAX_REPLY_BYTES = 4096;
    private ClamavConfig clamavConfig;

    @Reference
    public void setConfig(ClamavConfig clamavConfig) {
        this.clamavConfig = clamavConfig;
    }

    @Override
    public boolean ping() {
        try {
            return processCommand("zPING\0".getBytes(StandardCharsets.US_ASCII)).trim().equalsIgnoreCase(PONG);
        } catch (IOException ex) {
            LOGGER.error("Impossible to ping ClamAV: {}", sanitize(ex.getMessage()));
            return false;
        }
    }

    @Override
    public Result scan(final InputStream inputStream) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(clamavConfig.getHost(), clamavConfig.getPort()), clamavConfig.getConnectionTimeout());
            socket.setSoTimeout(clamavConfig.getReadTimeout());

            try (OutputStream outStream = new BufferedOutputStream(socket.getOutputStream());
                 InputStream inStream = socket.getInputStream()) {
                outStream.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                outStream.flush();

                final byte[] buffer = new byte[CHUNK_SIZE];
                int read = inputStream.read(buffer);
                while (read >= 0) {
                    outStream.write(ByteBuffer.allocate(4).putInt(read).array());
                    outStream.write(buffer, 0, read);
                    if (inStream.available() > 0) {
                        final String reply = readBounded(inStream, MAX_REPLY_BYTES);
                        throw new IOException("Daemon aborted scan: " + sanitize(reply));
                    }
                    read = inputStream.read(buffer);
                }
                outStream.write(new byte[]{0, 0, 0, 0});
                outStream.flush();

                return populateVirusScanResult(readBounded(inStream, MAX_REPLY_BYTES).trim());
            }
        } catch (IOException ex) {
            final String errMsg = "Impossible to scan inputstream for a malware";
            LOGGER.error("{}: {}", errMsg, sanitize(ex.getMessage()));
            return new Result(Status.ERROR, errMsg);
        }
    }

    private String processCommand(final byte[] cmd) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(clamavConfig.getHost(), clamavConfig.getPort()), clamavConfig.getConnectionTimeout());
            socket.setSoTimeout(clamavConfig.getReadTimeout());

            try (DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {
                dos.write(cmd);
                dos.flush();
                return readBounded(socket.getInputStream(), MAX_REPLY_BYTES);
            }
        }
    }

    private static String readBounded(InputStream in, int maxBytes) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[CHUNK_SIZE];
        int total = 0;
        int read;
        while ((read = in.read(buf)) > 0) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("Daemon reply exceeds " + maxBytes + " bytes");
            }
            out.write(buf, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String sanitize(String s) {
        if (s == null) {
            return "";
        }
        final String cleaned = s.replaceAll("[\\r\\n\\t]", "_");
        return cleaned.length() > 200 ? cleaned.substring(0, 200) + "..." : cleaned;
    }

    private Result populateVirusScanResult(final String result) {
        final Result scanResult = new Result();
        scanResult.setStatus(Status.FAILED);
        scanResult.setOutput(result);

        if (result == null || result.isEmpty()) {
            scanResult.setStatus(Status.ERROR);
        } else if (RESPONSE_OK.equals(result)) {
            scanResult.setStatus(Status.PASSED);
        } else if (result.endsWith(FOUND_SUFFIX) && result.startsWith(STREAM_PREFIX)) {
            final int end = result.lastIndexOf(FOUND_SUFFIX);
            if (end > STREAM_PREFIX.length() + 1) {
                scanResult.setSignature(result.substring(STREAM_PREFIX.length(), end - 1).trim());
            } else {
                scanResult.setStatus(Status.ERROR);
            }
        } else if (result.endsWith(ERROR_SUFFIX)) {
            scanResult.setStatus(Status.ERROR);
        }
        return scanResult;
    }
}
