package com.project.payflo.api_gateway_service.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Refuses a request body larger than {@code app.security.max-request-body-bytes} (1 MB by default) with
 * {@code 413 REQUEST_TOO_LARGE}, before it is authenticated or proxied. Every request body in this API is a small JSON
 * document, so the limit costs honest callers nothing and stops a caller from tying up a thread and memory with a huge
 * one.
 *
 * <p>A declared {@code Content-Length} over the limit is refused outright, without reading a byte. A body with no
 * declared length (chunked) is counted as it is read and cut off the moment it passes the limit.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1) // after the security headers, ahead of authentication
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public RequestSizeLimitFilter(@Value("${app.security.max-request-body-bytes:1048576}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            tooLarge(request, response);
            return;
        }

        try {
            chain.doFilter(new LimitedRequest(request, maxBytes), response);
        } catch (RuntimeException | IOException e) {
            if (isTooLarge(e) && !response.isCommitted()) {
                tooLarge(request, response);
                return;
            }
            throw e;
        }
    }

    private void tooLarge(HttpServletRequest request, HttpServletResponse response) throws IOException {
        log.warn("Refused a request body over {} bytes: {} {}", maxBytes, request.getMethod(), request.getRequestURI());
        response.setStatus(413);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"errorCode\":\"REQUEST_TOO_LARGE\",\"errorDescription\":\"The request body is larger than "
                + maxBytes + " bytes\"}");
    }

    private static boolean isTooLarge(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof BodyTooLargeException) {
                return true;
            }
        }
        return false;
    }

    static class BodyTooLargeException extends IOException {
        BodyTooLargeException(long max) {
            super("Request body is larger than " + max + " bytes");
        }
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long max;

        LimitedRequest(HttpServletRequest request, long max) {
            super(request);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {
                private long read;

                private void count(long n) throws IOException {
                    read += n;
                    if (read > max) {
                        throw new BodyTooLargeException(max);
                    }
                }

                @Override
                public int read() throws IOException {
                    int b = delegate.read();
                    if (b >= 0) {
                        count(1);
                    }
                    return b;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int n = delegate.read(buffer, offset, length);
                    if (n > 0) {
                        count(n);
                    }
                    return n;
                }

                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    delegate.setReadListener(listener);
                }
            };
        }
    }
}
