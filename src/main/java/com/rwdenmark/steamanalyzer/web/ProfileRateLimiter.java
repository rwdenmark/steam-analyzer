package com.rwdenmark.steamanalyzer.web;

import com.rwdenmark.steamanalyzer.error.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory sliding-window rate limit on the profile endpoints, keyed by client.
 * Single-instance only, matching the deployment. Move to a shared store to scale out.
 */
@Component
public class ProfileRateLimiter extends OncePerRequestFilter {

    private static final int DEFAULT_MAX_PER_WINDOW = 15;
    private static final long WINDOW_MS = 60_000;
    private static final int SWEEP_EVERY = 500; // sweep idle clients every N calls
    private static final String PROTECTED_PREFIX = "/api/profile/";

    private final int maxPerWindow;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final AtomicInteger callsSinceSweep = new AtomicInteger();

    public ProfileRateLimiter() {
        this(DEFAULT_MAX_PER_WINDOW);
    }

    // For tests that need a small budget.
    ProfileRateLimiter(int maxPerWindow) {
        this.maxPerWindow = maxPerWindow;
    }

    /** Only /api/profile/** pays the toll. Static files and /api/health pass free. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PROTECTED_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (allow(clientKey(request))) {
            chain.doFilter(request, response);
            return;
        }
        // Same envelope GlobalExceptionHandler uses, via the shared formatter. The filter
        // answers before the dispatcher, so the advice never sees this request.
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(ApiError.json(HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests. Give it a minute and try again."));
    }

    private String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // First hop is the client behind a proxy. Client-settable, so it only
            // stops casual spam, not a caller who rotates the header.
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public boolean allow(String key) {
        long now = nowMillis();
        if (callsSinceSweep.incrementAndGet() >= SWEEP_EVERY) {
            callsSinceSweep.set(0);
            sweepExpired(now);
        }
        Deque<Long> window = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > WINDOW_MS) {
                window.pollFirst();
            }
            if (window.size() >= maxPerWindow) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    // Clock seam so tests can drive the window without sleeping.
    protected long nowMillis() {
        return System.currentTimeMillis();
    }

    /** Drop every tracked window. For tests. */
    public void clear() {
        hits.clear();
        callsSinceSweep.set(0);
    }

    // Drop clients whose window has fully aged out. The key is removed only if still
    // mapped to the same empty deque, so a racing allow() at worst lets one through.
    private void sweepExpired(long now) {
        for (Map.Entry<String, Deque<Long>> entry : hits.entrySet()) {
            Deque<Long> window = entry.getValue();
            synchronized (window) {
                while (!window.isEmpty() && now - window.peekFirst() > WINDOW_MS) {
                    window.pollFirst();
                }
                if (window.isEmpty()) {
                    hits.remove(entry.getKey(), window);
                }
            }
        }
    }
}
