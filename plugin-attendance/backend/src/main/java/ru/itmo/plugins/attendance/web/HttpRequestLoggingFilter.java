package ru.itmo.plugins.attendance.web;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class HttpRequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(HttpRequestLoggingFilter.class);
    private final AtomicLong requests = new AtomicLong();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!log.isDebugEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        long id = requests.incrementAndGet();
        long start = System.nanoTime();
        String method = request.getMethod();
        String path = request.getRequestURI();
        log.debug("HTTP #{} started: {} {}", id, method, path);
        try {
            chain.doFilter(request, response);
            log.debug("HTTP #{} completed: {} {} -> {} in {} ms", id, method, path,
                    response.getStatus(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        } catch (IOException | ServletException | RuntimeException exception) {
            log.debug("HTTP #{} aborted: {} {} after {} ms ({})", id, method, path,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), exception.getClass().getSimpleName());
            throw exception;
        }
    }
}
