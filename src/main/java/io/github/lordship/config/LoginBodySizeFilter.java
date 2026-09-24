package io.github.lordship.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Turns away an oversized sign-in body before Jackson reads it. The throttle lives in
 * the service, which runs only after the whole body is parsed -- and Tomcat's size
 * limit covers form posts, not JSON.
 *
 * <p>Not a @Bean: Boot registers every Filter bean with the servlet container too,
 * and this would run twice. SecurityConfig adds it to the chain by hand.
 */
class LoginBodySizeFilter extends OncePerRequestFilter {

    static final String LOGIN_PATH = "/api/agents/auth";
    static final long MAX_BYTES = 4096;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && LOGIN_PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long length = request.getContentLengthLong();

        if (length > MAX_BYTES) {
            response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"message\":\"Sign-in request is too large\"}");
            return;
        }

        // -1 is a chunked body that declared no size, so cap it while it is read instead.
        // Going over surfaces as an unreadable body: a 400 from ApiExceptionHandler.
        chain.doFilter(length < 0 ? new CappedBody(request) : request, response);
    }

    private static final class CappedBody extends HttpServletRequestWrapper {

        CappedBody(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream in = super.getInputStream();
            return new ServletInputStream() {
                private long total;

                @Override
                public int read() throws IOException {
                    int b = in.read();
                    if (b >= 0) {
                        count(1);
                    }
                    return b;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int n = in.read(buffer, offset, length);
                    if (n > 0) {
                        count(n);
                    }
                    return n;
                }

                private void count(int n) throws IOException {
                    total += n;
                    if (total > MAX_BYTES) {
                        throw new IOException("Sign-in request is larger than " + MAX_BYTES + " bytes");
                    }
                }

                @Override
                public boolean isFinished() {
                    return in.isFinished();
                }

                @Override
                public boolean isReady() {
                    return in.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    in.setReadListener(listener);
                }
            };
        }
    }
}
