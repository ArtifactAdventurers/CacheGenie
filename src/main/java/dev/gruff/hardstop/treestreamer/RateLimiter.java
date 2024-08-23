package dev.gruff.hardstop.treestreamer;
import java.time.Duration;
import java.time.Instant;

public class RateLimiter {
    private final int maxRequests;
    private final Duration timeWindow;
    private final long minimumIntervalMillis;
    private Instant lastRequestTime;

    public RateLimiter(int maxRequests, Duration timeWindow) {
        this.maxRequests = maxRequests;
        this.timeWindow = timeWindow;
        this.minimumIntervalMillis = timeWindow.toMillis() / maxRequests;
        this.lastRequestTime = Instant.now().minus(Duration.ofMillis(minimumIntervalMillis));
    }

    public synchronized void waitForPermission() throws InterruptedException {

        Instant now = Instant.now();
        long timeSinceLastRequest = Duration.between(lastRequestTime, now).toMillis();

        if (timeSinceLastRequest < minimumIntervalMillis) {
            long waitTime = minimumIntervalMillis - timeSinceLastRequest;
            Thread.sleep(waitTime);
        }

        lastRequestTime = Instant.now(); // Update the last request time
    }
}

