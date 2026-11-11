package com.visceral.client;

import com.visceral.Visceral;

import java.util.HashMap;
import java.util.Map;

/**
 * Keeps Visceral from ever taking the game down: every handler runs inside a guard. A failure is
 * logged (the first few times, with the cause), and a part that keeps failing switches itself off
 * for the rest of the session instead of failing every tick. One creature's odd model or one bad
 * frame costs a bit of gore, never a crash.
 */
final class Guard {
    private Guard() {}

    private static final Map<String, Integer> FAILS = new HashMap<>();
    private static final int LOG = 3, GIVE_UP = 200;

    /** The part is still allowed to run (it hasn't failed too often). */
    static boolean ok(String part) {
        Integer n = FAILS.get(part);
        return n == null || n < GIVE_UP;
    }

    /** Something in `part` failed. */
    static void failed(String part, Throwable t) {
        int n = FAILS.merge(part, 1, Integer::sum);
        if (n <= LOG) Visceral.LOG.error("Visceral: {} failed (this part keeps going)", part, t);
        if (n == GIVE_UP) Visceral.LOG.error("Visceral: {} keeps failing, switching it off until you restart", part);
    }

    /** Runs `r` as `part`, catching anything it throws. */
    static void run(String part, Runnable r) {
        if (!ok(part)) return;
        try {
            r.run();
        } catch (RuntimeException | LinkageError | StackOverflowError e) {
            failed(part, e);
        }
    }

    private static final Map<String, double[]> TIME = new HashMap<>();   // {smoothed ms}

    /** Runs `r` as `part` and keeps a smoothed average of how long it takes (for the F3 screen). */
    static void timed(String part, Runnable r) {
        long t0 = System.nanoTime();
        run(part, r);
        double ms = (System.nanoTime() - t0) / 1e6;
        double[] a = TIME.computeIfAbsent(part, k -> new double[1]);
        a[0] = a[0] * 0.95 + ms * 0.05;
    }

    /** Average milliseconds per frame across the timed parts. */
    static double frameMs() {
        double s = 0;
        for (double[] a : TIME.values()) s += a[0];
        return s;
    }

    /** How many parts have switched themselves off (for the F3 screen). */
    static int off() {
        int n = 0;
        for (int v : FAILS.values()) if (v >= GIVE_UP) n++;
        return n;
    }
}
