package org.jahia.community.clamav.service.impl;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.apache.commons.io.IOUtils;
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
    private static final int CONNECTION_TIMEOUT = 2000;
    private static final int READ_TIMEOUT = 20000;
    private ClamavConfig clamavConfig;

    @Reference
    public void setConfig(ClamavConfig clamavConfig) {
        this.clamavConfig = clamavConfig;
    }

    @Override
    public boolean ping() {
        try {
            return processCommand("zPING\0".getBytes()).trim().equalsIgnoreCase(PONG);
        } catch (IOException ex) {
            LOGGER.error("Impossible to ping ClamAV", ex);
            return false;
        }
    }

    @Override
    public Result scan(final InputStream inputStream) {

        try ( Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(clamavConfig.getHost(), clamavConfig.getPort()), CONNECTION_TIMEOUT);
            socket.setSoTimeout(READ_TIMEOUT);

            try ( OutputStream outStream = new BufferedOutputStream(socket.getOutputStream())) {
                outStream.write("zINSTREAM\0".getBytes(StandardCharsets.UTF_8));
                outStream.flush();

                final byte[] buffer = new byte[CHUNK_SIZE];
                try ( InputStream inStream = socket.getInputStream()) {
                    int read = inputStream.read(buffer);

                    while (read >= 0) {
                        final byte[] chunkSize = ByteBuffer.allocate(4).putInt(read).array();
                        outStream.write(chunkSize);
                        outStream.write(buffer, 0, read);

                        if (inStream.available() > 0) {
                            byte[] reply = IOUtils.toByteArray(inStream);
                            throw new IOException(
                                    "Reply from server: " + new String(reply, StandardCharsets.UTF_8));
                        }
                        read = inputStream.read(buffer);
                    }
                    outStream.write(new byte[]{0, 0, 0, 0});
                    outStream.flush();

                    return populateVirusScanResult(new String(IOUtils.toByteArray(inStream)).trim());
                }
            }
        } catch (IOException ex) {
            final String errMsg = "Impossible to scan inputstream for a malware";
            LOGGER.error(errMsg, ex);
            return new Result(Status.ERROR, errMsg);
        }
    }

    private String processCommand(final byte[] cmd) throws IOException {
        String response = "";

        try ( Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(clamavConfig.getHost(), clamavConfig.getPort()), CONNECTION_TIMEOUT);
            socket.setSoTimeout(READ_TIMEOUT);

            try ( DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {
                dos.write(cmd);
                dos.flush();

                final InputStream is = socket.getInputStream();

                int read = CHUNK_SIZE;
                final byte[] buffer = new byte[CHUNK_SIZE];

                while (read == CHUNK_SIZE) {
                    try {
                        read = is.read(buffer);
                    } catch (IOException ex) {
                        LOGGER.error("Error reading result from socket", ex);
                        break;
                    }
                    response = new String(buffer, 0, read);
                }
            }
        }
        return response;
    }

    private Result populateVirusScanResult(final String result) {
        final Result scanResult = new Result();
        scanResult.setStatus(Status.FAILED);
        scanResult.setOutput(result);

        if (result == null || result.isEmpty()) {
            scanResult.setStatus(Status.ERROR);
        } else if (RESPONSE_OK.equals(result)) {
            scanResult.setStatus(Status.PASSED);
        } else if (result.endsWith(FOUND_SUFFIX)) {
            scanResult.setSignature(
                    result.substring(STREAM_PREFIX.length(), result.lastIndexOf(FOUND_SUFFIX) - 1).trim());
        } else if (result.endsWith(ERROR_SUFFIX)) {
            scanResult.setStatus(Status.ERROR);
        }
        return scanResult;
    }
}
