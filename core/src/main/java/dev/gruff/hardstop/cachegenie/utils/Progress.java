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

    private Progress(String label, long everyN, long everyMillis) {
        this.label = label;
        this.everyN = Math.max(1, everyN);
        this.everyMillis = Math.max(0, everyMillis);
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
        if (detail == null || detail.isEmpty()) {
            System.err.printf("[progress] %s: %d processed (%d/s)%n", label, n, rate(n, secs));
        } else {
            System.err.printf("[progress] %s: %d processed (%d/s) — %s%n", label, n, rate(n, secs), detail);
        }
    }

    private long elapsedSeconds() {
        return Math.max(0, (System.currentTimeMillis() - startMillis) / 1000);
    }

    private static long rate(long n, long secs) {
        return secs <= 0 ? n : n / secs;
    }
}
