package dev.gruff.hardstop.treestreamer;
import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

/**
 * A simple, thread-safe rate limiter that enforces a maximum number of permits per time window.
 *
 * <p>This implementation uses a token-bucket algorithm with nano-second precision. The bucket has
 * a capacity of {@code maxRequests} tokens and refills continuously at a rate of
 * {@code maxRequests / timeWindow}. Each call to {@link #waitForPermission()} consumes one token;
 * if no token is available, the caller will block until enough time passes for a token to refill.</p>
 *
 * <p>Wall-clock changes do not affect the limiter as it relies on {@link System#nanoTime()} for
 * measuring elapsed time. The method is synchronized, so a single instance can be safely shared
 * across threads.</p>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * RateLimiter limiter = new RateLimiter(60, Duration.ofMinutes(1)); // ~1 request per second
 * for (int i = 0; i < 10; i++) {
 *     limiter.waitForPermission();
 *     // perform one rate-limited operation here
 * }
 * }</pre>
 */
public class RateLimiter {
    private final int capacity;                 // Max tokens, equals maxRequests
    private final Duration timeWindow;          // Window for maxRequests
    private final double tokensPerNano;         // Refill rate

    private double availableTokens;             // Current tokens (can be fractional)
    private long lastRefillNanoTime;            // Last refill timestamp using nanoTime

    /**
     * Creates a rate limiter that allows up to {@code maxRequests} operations per {@code timeWindow}.
     *
     * @param maxRequests the maximum number of requests allowed per time window; must be positive
     * @param timeWindow the length of the time window; must be positive
     * @throws IllegalArgumentException if arguments are non-positive
     */
    public RateLimiter(int maxRequests, Duration timeWindow) {
        if (maxRequests <= 0) {
            throw new IllegalArgumentException("maxRequests must be positive");
        }
        if (timeWindow == null || timeWindow.isZero() || timeWindow.isNegative()) {
            throw new IllegalArgumentException("timeWindow must be positive");
        }
        this.capacity = maxRequests;
        this.timeWindow = timeWindow;
        long windowNanos = timeWindow.toNanos();
        // Use double to avoid integer truncation; supports sub-millisecond rates
        this.tokensPerNano = ((double) maxRequests) / windowNanos;
        this.availableTokens = maxRequests; // start full to allow an initial burst
        this.lastRefillNanoTime = System.nanoTime();
    }

    /**
     * Blocks the current thread until the next request is permitted according to the configured rate.
     *
     * <p>If a token is available, it is consumed immediately. Otherwise this method waits precisely
     * until enough time has elapsed for a token to refill.</p>
     *
     * @throws InterruptedException if the current thread is interrupted while sleeping
     */
    public synchronized void waitForPermission() throws InterruptedException {
        for (;;) {
            refill();
            if (availableTokens >= 1.0) {
                availableTokens -= 1.0;
                return;
            }
            // Compute time until one full token is available
            double tokensNeeded = 1.0 - availableTokens;
            long nanosToWait = (long) Math.ceil(tokensNeeded / tokensPerNano);
            if (nanosToWait <= 0) {
                nanosToWait = 1; // safety net to avoid busy-wait
            }
            // Use LockSupport for sub-millisecond precision without altering interrupt status;
            // then check interruption state and propagate as InterruptedException to honor API.
            LockSupport.parkNanos(nanosToWait);
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanoTime;
        if (elapsed <= 0) {
            return;
        }
        double newTokens = elapsed * tokensPerNano;
        availableTokens = Math.min(capacity, availableTokens + newTokens);
        lastRefillNanoTime = now;
    }
}

