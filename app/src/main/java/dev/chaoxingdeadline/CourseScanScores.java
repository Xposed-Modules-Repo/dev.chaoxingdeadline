package dev.chaoxingdeadline;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.concurrent.TimeUnit;

/**
 * Stores course scan priority metadata in LSPosed RemotePreferences.
 * Hook code reads it to plan scans; module process writes it after receiving scan results.
 */
public final class CourseScanScores {
    public static final String PREFS = "course_scan_scores";
    public static final int INITIAL = 50;
    public static final int MAX = 200;
    public static final int NEVER_SCAN = 0;
    public static final int FINDING_BONUS = 8;
    public static final int EMPTY_PENALTY = 1;

    private CourseScanScores() {
    }

    public static String key(String courseId, String classId) {
        return safe(courseId) + "|" + safe(classId);
    }

    public static int score(SharedPreferences prefs, String key) {
        return prefs == null ? INITIAL : prefs.getInt("score_" + safe(key), INITIAL);
    }

    public static long lastScanAt(SharedPreferences prefs, String key) {
        return prefs == null ? 0L : prefs.getLong("last_" + safe(key), 0L);
    }

    public static boolean shouldScan(SharedPreferences prefs, String key, long now) {
        int score = score(prefs, key);
        if (score <= NEVER_SCAN) {
            return false;
        }
        long lastScanAt = lastScanAt(prefs, key);
        if (lastScanAt <= 0L) {
            return true;
        }
        long age = now - lastScanAt;
        if (score >= 30) {
            return true;
        }
        if (score >= 10) {
            return age >= TimeUnit.DAYS.toMillis(1);
        }
        return age >= TimeUnit.DAYS.toMillis(3);
    }

    public static void record(Context context, String courseId, String classId, boolean foundDeadline) {
        recordBatch(context,
                new String[]{courseId},
                new String[]{classId},
                null,
                new boolean[]{foundDeadline});
    }

    public static void recordBatch(Context context, String[] courseIds, String[] classIds, boolean[] foundDeadlines) {
        recordBatch(context, courseIds, classIds, null, foundDeadlines);
    }

    public static void recordBatch(Context context, String[] courseIds, String[] classIds,
                                   String[] courseNames, boolean[] foundDeadlines) {
        if (context == null || App.getService() == null || courseIds == null || classIds == null || foundDeadlines == null) {
            return;
        }
        int count = Math.min(courseIds.length, Math.min(classIds.length, foundDeadlines.length));
        if (count <= 0) {
            return;
        }
        try {
            SharedPreferences prefs = App.getService().getRemotePreferences(PREFS);
            DeadlineStore store = new DeadlineStore(context);
            long now = System.currentTimeMillis();
            long today = now / TimeUnit.DAYS.toMillis(1);
            SharedPreferences.Editor editor = prefs.edit();
            for (int i = 0; i < count; i++) {
                String key = key(courseIds[i], classIds[i]);
                if (key.equals("|")) {
                    continue;
                }
                editor.putLong("last_" + key, now);
                if (prefs.getLong("day_" + key, -1L) == today) {
                    continue;
                }
                boolean foundDeadline = foundDeadlines[i];
                int score = score(prefs, key);
                score += foundDeadline ? FINDING_BONUS : -EMPTY_PENALTY;
                score = Math.max(NEVER_SCAN, Math.min(MAX, score));
                editor.putInt("score_" + key, score).putLong("day_" + key, today);
                if (foundDeadline) {
                    editor.putLong("active_" + key, now);
                }
                if (score <= NEVER_SCAN) {
                    String name = courseNames != null && i < courseNames.length ? courseNames[i] : "";
                    store.markCourseDisabledByScore(courseIds[i], classIds[i], name);
                }
            }
            editor.apply();
        } catch (Throwable ignored) {
        }
    }

    public static void forceNeverScan(Context context, String[] courseIds, String[] classIds) {
        setNeverScan(context, courseIds, classIds, true);
    }

    public static void restoreFromNeverScan(Context context, String[] courseIds, String[] classIds) {
        setNeverScan(context, courseIds, classIds, false);
    }

    private static void setNeverScan(Context context, String[] courseIds, String[] classIds, boolean neverScan) {
        if (context == null || App.getService() == null || courseIds == null || classIds == null) {
            return;
        }
        int count = Math.min(courseIds.length, classIds.length);
        if (count <= 0) {
            return;
        }
        try {
            SharedPreferences prefs = App.getService().getRemotePreferences(PREFS);
            SharedPreferences.Editor editor = prefs.edit();
            long now = System.currentTimeMillis();
            for (int i = 0; i < count; i++) {
                String key = key(courseIds[i], classIds[i]);
                if (key.equals("|")) {
                    continue;
                }
                if (neverScan) {
                    editor.putInt("score_" + key, NEVER_SCAN).putLong("last_" + key, now);
                } else if (score(prefs, key) <= NEVER_SCAN) {
                    editor.putInt("score_" + key, INITIAL).remove("day_" + key);
                }
            }
            editor.apply();
        } catch (Throwable ignored) {
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
