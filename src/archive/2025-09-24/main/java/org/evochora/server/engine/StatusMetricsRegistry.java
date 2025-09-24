package org.evochora.server.engine;

public class StatusMetricsRegistry {
    private static volatile long startNanos = System.nanoTime();
    private static volatile long lastTick = -1L;

    public static void onTick(long tick) {
        lastTick = tick;
    }

    public static double getTicksPerSecond() {
        long now = System.nanoTime();
        double seconds = (now - startNanos) / 1_000_000_000.0;
        if (seconds <= 0.0) return 0.0;
        if (lastTick < 0) return 0.0;
        return lastTick / seconds;
    }
}


