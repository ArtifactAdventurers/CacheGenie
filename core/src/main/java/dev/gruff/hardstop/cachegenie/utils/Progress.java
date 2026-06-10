package dev.gruff.hardstop.cachegenie.utils;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight, opt-in progress reporter for long-running commands.
 *
 * <p>Disabled by default; {@link #setEnabled(boolean)} is called once at start-up
 * from the {@code -P/--progress} global CLI option. When disabled every method is
 * a cheap no-op, so callers can sprinkle {@link #tick()} into hot loops without
 * worrying about overhead.
 *
 * <p>Progress is written to {@code System.err} on purpose: it is status, not
 * result. stdout carries real command output (DOT graphs, CSV, SQL query results,
 * the analyse report), so keeping progress on stderr means {@code --progress}
 * never corrupts a piped/redirected result, and it stays independent of the
 * {@code -l/--log} level.
 *
 * <p>Thread-safe: the counters are atomic and a compare-and-set guards each
 * emitted line, so it is safe to call {@link #tick()} from the parallel streams
 * used by the meta and cache walks.
 *
 * <pre>
 *   Progress p = Progress.start("Fetch POMs");
 *   items.parallelStream().forEach(i -> { ...; p.tick(i.name()); });
 *   p.done();
 * </pre>
 */
public final class Progress {

    private static volatile boolean enabled = false;

    /** Enable or disable progress reporting process-wide. */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Start a named progress span with default throttling (every 250 items or 2s). */
    public static Progress start(String label) {
        return new Progress(label, 250, 2000L);
    }

    /** Start a named progress span with custom throttling. */
    public static Progress start(String label, long everyN, long everyMillis) {
        return new Progress(label, everyN, everyMillis);
    }

    private final String label;
    private final long everyN;
    private final long everyMillis;
    private final long startMillis = System.currentTimeMillis();
    private final AtomicLong count = new AtomicLong();
    private final AtomicLong lastReportMillis = new AtomicLong(startMillis);
    /** Total expected items; when > 0, progress lines include count/total and an ETA. */
    private volatile long total = -1;

    private Progress(String label, long everyN, long everyMillis) {
        this.label = label;
        this.everyN = Math.max(1, everyN);
        this.everyMillis = Math.max(0, everyMillis);
    }

    /** Set the total expected item count so progress lines can show {@code n/total} and an ETA. */
    public void total(long total) {
        this.total = total;
    }

    public void tick() {
        tick(null);
    }

    /** Count one item; {@code detail} (e.g. the current artifact) is shown if a line is emitted. */
    public void tick(String detail) {
        if (!enabled) return;
        long n = count.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = lastReportMillis.get();
        boolean byCount = (n % everyN) == 0;
        boolean byTime = everyMillis > 0 && (now - last) >= everyMillis;
        if (byCount || byTime) {
            // Only one thread wins the slot, so we emit at most one line per interval.
            if (lastReportMillis.compareAndSet(last, now)) {
                emit(n, detail);
            }
        }
    }

    /** Emit a final summary line for this span. */
    public void done() {
        if (!enabled) return;
        long secs = elapsedSeconds();
        System.err.printf("[progress] %s: done — %d items in %ds (%d/s)%n",
                label, count.get(), secs, rate(count.get(), secs));
    }

    private void emit(long n, String detail) {
        long secs = elapsedSeconds();
        long t = total;
        String head;
        if (t > 0) {
            head = String.format("[progress] %s: %d/%d (%d/s, ETA %s)", label, n, t, rate(n, secs), eta(n, t));
        } else {
            head = String.format("[progress] %s: %d processed (%d/s)", label, n, rate(n, secs));
        }
        if (detail == null || detail.isEmpty()) {
            System.err.println(head);
        } else {
            System.err.println(head + " — " + detail);
        }
    }

    /** Estimated time remaining based on the average rate so far. */
    private String eta(long n, long total) {
        if (n <= 0) return "?";
        long remaining = total - n;
        if (remaining <= 0) return "0s";
        long elapsedMs = System.currentTimeMillis() - startMillis;
        long etaMs = (long) (elapsedMs * (remaining / (double) n));
        return formatDuration(etaMs);
    }

    private static String formatDuration(long ms) {
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return String.format("%dh%02dm", h, m);
        if (m > 0) return String.format("%dm%02ds", m, sec);
        return sec + "s";
    }

    private long elapsedSeconds() {
        return Math.max(0, (System.currentTimeMillis() - startMillis) / 1000);
    }

    private static long rate(long n, long secs) {
        return secs <= 0 ? n : n / secs;
    }
}
