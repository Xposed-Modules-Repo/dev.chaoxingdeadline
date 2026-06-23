package dev.chaoxingdeadline;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Tunes course-scan concurrency per device/network. Hook code only reads the next tier;
 * the module process records completed scan performance and writes the next tier.
 */
public final class CourseScanThreads {
    public static final int DEFAULT = 10;
    public static final int[] TIERS = {3, 5, 10, 15, 20};

    private static final String TAG = "ChaoxingDeadline";
    private static final String KEY_NEXT = "thread_next";
    private static final String KEY_SAMPLE_COUNT_PREFIX = "thread_count_";
    private static final String KEY_EWMA_PREFIX = "thread_ewma_";
    private static final String KEY_FAILURE_PREFIX = "thread_fail_";
    private static final String KEY_LAST_THREADS = "thread_last_threads";
    private static final String KEY_LAST_REFS = "thread_last_refs";
    private static final String KEY_LAST_SCHEDULED = "thread_last_scheduled";
    private static final String KEY_LAST_SCANNED = "thread_last_scanned";
    private static final String KEY_LAST_ELAPSED = "thread_last_elapsed";
    private static final int MIN_SAMPLE_COURSES = 3;
    private static final int IMPROVEMENT_PERCENT = 10;
    private static final int FAILURE_LIMIT = 2;

    private CourseScanThreads() {
    }

    public static int current(SharedPreferences prefs) {
        return nearestTier(prefs == null ? DEFAULT : prefs.getInt(KEY_NEXT, DEFAULT));
    }

    public static String summary(SharedPreferences prefs) {
        int next = current(prefs);
        if (prefs == null) {
            return "线程：" + next + "｜暂无扫描记录";
        }
        int lastThreads = nearestTier(prefs.getInt(KEY_LAST_THREADS, next));
        int refs = Math.max(0, prefs.getInt(KEY_LAST_REFS, 0));
        int scanned = Math.max(0, prefs.getInt(KEY_LAST_SCANNED, 0));
        if (refs <= 0) {
            return "线程：" + next + "｜暂无扫描记录";
        }
        String threadText = next == lastThreads ? String.valueOf(next) : next + "（上次" + lastThreads + "）";
        return "线程：" + threadText + "｜扫描：" + Math.min(scanned, refs) + "/" + refs + " 门";
    }

    public static void record(Context context, int threads, long elapsedMs, int refs, int scheduled, int scanned) {
        if (context == null || App.getService() == null || elapsedMs <= 0L || scheduled < MIN_SAMPLE_COURSES) {
            return;
        }
        int tier = nearestTier(threads);
        int safeScanned = Math.max(0, scanned);
        int safeRefs = Math.max(Math.max(0, refs), scheduled);
        int failed = Math.max(0, scheduled - safeScanned);
        boolean reliable = failed == 0 && safeScanned >= MIN_SAMPLE_COURSES;
        long perCourseMs = Math.max(1L, elapsedMs / Math.max(1, safeScanned));
        try {
            SharedPreferences prefs = App.getService().getRemotePreferences(CourseScanScores.PREFS);
            int oldCount = prefs.getInt(countKey(tier), 0);
            long oldEwma = prefs.getLong(ewmaKey(tier), 0L);
            long ewma = oldEwma <= 0L ? perCourseMs : (oldEwma * 7L + perCourseMs * 3L) / 10L;
            int oldFailures = prefs.getInt(failKey(tier), 0);
            int failures = reliable ? Math.max(0, oldFailures - 1) : oldFailures + 1;
            int next = chooseNext(prefs, tier, reliable, ewma, oldCount + 1, failures);
            prefs.edit()
                    .putInt(countKey(tier), oldCount + 1)
                    .putLong(ewmaKey(tier), ewma)
                    .putInt(failKey(tier), failures)
                    .putInt(KEY_NEXT, next)
                    .putInt(KEY_LAST_THREADS, tier)
                    .putInt(KEY_LAST_REFS, safeRefs)
                    .putInt(KEY_LAST_SCHEDULED, Math.max(0, scheduled))
                    .putInt(KEY_LAST_SCANNED, safeScanned)
                    .putLong(KEY_LAST_ELAPSED, elapsedMs)
                    .apply();
            Log.i(TAG, "course scan thread tune: current=" + tier
                    + ", next=" + next + ", perCourse=" + perCourseMs
                    + "ms, ewma=" + ewma + "ms, scheduled=" + scheduled
                    + ", scanned=" + scanned + ", failed=" + failed);
        } catch (Throwable throwable) {
            Log.w(TAG, "course scan thread tune failed: " + throwable.getClass().getSimpleName());
        }
    }

    private static int chooseNext(SharedPreferences prefs, int current, boolean reliable,
                                  long currentEwma, int currentCount, int currentFailures) {
        if (!reliable) {
            return lowerTier(current);
        }
        int count10 = sampleCount(prefs, 10, current, currentCount);
        int count15 = sampleCount(prefs, 15, current, currentCount);
        if (count10 <= 0) {
            return 10;
        }
        if (count15 <= 0) {
            return 15;
        }
        long ewma10 = ewma(prefs, 10, current, currentEwma);
        long ewma15 = ewma(prefs, 15, current, currentEwma);
        boolean fifteenWorthIt = ewma10 > 0L && ewma15 > 0L
                && ewma15 * 100L <= ewma10 * (100L - IMPROVEMENT_PERCENT);
        if (fifteenWorthIt) {
            if (sampleCount(prefs, 20, current, currentCount) <= 0) {
                return 20;
            }
        } else {
            if (sampleCount(prefs, 5, current, currentCount) <= 0) {
                return 5;
            }
            long ewma5 = ewma(prefs, 5, current, currentEwma);
            long bestAmongMiddle = minPositive(ewma5, ewma10, ewma15);
            if (bestAmongMiddle > 0L && ewma5 > 0L && ewma5 <= bestAmongMiddle * 110L / 100L
                    && sampleCount(prefs, 3, current, currentCount) <= 0) {
                return 3;
            }
        }
        return bestStableTier(prefs, current, currentEwma, currentCount, currentFailures);
    }

    private static int bestStableTier(SharedPreferences prefs, int current, long currentEwma,
                                      int currentCount, int currentFailures) {
        long best = Long.MAX_VALUE;
        for (int tier : TIERS) {
            if (sampleCount(prefs, tier, current, currentCount) <= 0
                    || failureCount(prefs, tier, current, currentFailures) >= FAILURE_LIMIT) {
                continue;
            }
            long value = ewma(prefs, tier, current, currentEwma);
            if (value > 0L && value < best) {
                best = value;
            }
        }
        if (best == Long.MAX_VALUE) {
            return DEFAULT;
        }
        for (int tier : TIERS) {
            if (sampleCount(prefs, tier, current, currentCount) <= 0
                    || failureCount(prefs, tier, current, currentFailures) >= FAILURE_LIMIT) {
                continue;
            }
            long value = ewma(prefs, tier, current, currentEwma);
            if (value > 0L && value <= best * 110L / 100L) {
                return tier;
            }
        }
        return DEFAULT;
    }

    private static int sampleCount(SharedPreferences prefs, int tier, int current, int currentCount) {
        return tier == current ? currentCount : prefs.getInt(countKey(tier), 0);
    }

    private static int failureCount(SharedPreferences prefs, int tier, int current, int currentFailures) {
        return tier == current ? currentFailures : prefs.getInt(failKey(tier), 0);
    }

    private static long ewma(SharedPreferences prefs, int tier, int current, long currentEwma) {
        return tier == current ? currentEwma : prefs.getLong(ewmaKey(tier), 0L);
    }

    private static long minPositive(long... values) {
        long result = Long.MAX_VALUE;
        for (long value : values) {
            if (value > 0L && value < result) {
                result = value;
            }
        }
        return result == Long.MAX_VALUE ? 0L : result;
    }

    private static int lowerTier(int current) {
        int previous = TIERS[0];
        for (int tier : TIERS) {
            if (tier >= current) {
                return previous;
            }
            previous = tier;
        }
        return previous;
    }

    private static int nearestTier(int value) {
        int nearest = TIERS[0];
        int bestDistance = Math.abs(value - nearest);
        for (int tier : TIERS) {
            int distance = Math.abs(value - tier);
            if (distance < bestDistance) {
                nearest = tier;
                bestDistance = distance;
            }
        }
        return nearest;
    }

    private static String countKey(int tier) {
        return KEY_SAMPLE_COUNT_PREFIX + tier;
    }

    private static String ewmaKey(int tier) {
        return KEY_EWMA_PREFIX + tier;
    }

    private static String failKey(int tier) {
        return KEY_FAILURE_PREFIX + tier;
    }
}
