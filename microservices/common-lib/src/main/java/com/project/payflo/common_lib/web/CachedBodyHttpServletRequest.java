package com.project.payflo.common_lib.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.SequenceInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * A request whose body has been read once (to fingerprint it) and can still be read by whatever runs next.
 * {@code rest} is whatever of the original stream wasn't read yet: null when the whole body was buffered.
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] prefix;
    private final InputStream rest;

    public CachedBodyHttpServletRequest(HttpServletRequest request, byte[] prefix, InputStream rest) {
        super(request);
        this.prefix = prefix;
        this.rest = rest;
    }

    @Override
    public ServletInputStream getInputStream() {
        InputStream source = rest == null
                ? new ByteArrayInputStream(prefix)
                : new SequenceInputStream(new ByteArrayInputStream(prefix), rest);
        return new ServletInputStream() {
            @Override
            public int read() throws IOException {
                return source.read();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return source.read(b, off, len);
            }

            @Override
            public boolean isFinished() {
                try {
                    return source.available() == 0 && rest == null;
                } catch (IOException e) {
                    return true;
                }
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("Asynchronous reads are not supported on a buffered body");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        String encoding = getCharacterEncoding();
        Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
}
