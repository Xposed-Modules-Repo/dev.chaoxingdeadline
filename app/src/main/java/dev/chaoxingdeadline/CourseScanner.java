package dev.chaoxingdeadline;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import dev.chaoxingdeadline.CourseRefs.CourseRef;

import android.net.Uri;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-course deadline scan, extracted from ChaoxingHook.
 *
 * For every course reference the scanner pulls three endpoints (work task list,
 * classroom-activity list, exam task list) on a small thread pool, feeds each payload
 * back through the host's fetch/inspect pipeline, and reports scan statistics so the
 * adaptive scanner can pace future refreshes. HTTP, cookies, logging and the broadcast
 * bridge stay in the host — this class only orchestrates.
 */
final class CourseScanner {
    private static final String TAG = "ChaoxingDeadline";
    private final ChaoxingHook host;

    CourseScanner(ChaoxingHook host) {
        this.host = host;
    }

    void fetchCourseDeadlines(List<CourseRef> refs) throws InterruptedException {
        List<CourseRef> plan = courseScanPlan(refs);
        if (plan.isEmpty()) {
            host.log(Log.INFO, TAG, "course deadline scan skipped: no eligible courses");
            return;
        }
        long startedAt = System.currentTimeMillis();
        String cookieUid = host.uidFromCookies();
        AtomicInteger scheduled = new AtomicInteger();
        AtomicInteger scanned = new AtomicInteger();
        AtomicInteger foundItems = new AtomicInteger();
        List<CourseScanResult> results = Collections.synchronizedList(new ArrayList<>());
        int threadCount = courseFetchThreads();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount, runnable -> {
            Thread thread = new Thread(runnable, "ChaoxingDeadlineCourseScan");
            thread.setDaemon(true);
            return thread;
        });
        try {
            for (CourseRef ref : plan) {
                int index = scheduled.incrementAndGet();
                executor.execute(() -> {
                    try {
                        Thread.sleep((long) (index % threadCount) * 40L);
                        int found = fetchCourseDeadline(ref, cookieUid);
                        if (found >= 0) {
                            results.add(new CourseScanResult(ref, found > 0));
                            foundItems.addAndGet(found);
                            scanned.incrementAndGet();
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable throwable) {
                        host.log(Log.WARN, TAG, "course deadline fetch failed: " + throwable.getClass().getSimpleName());
                    }
                });
            }
        } finally {
            executor.shutdown();
        }
        boolean completed = executor.awaitTermination(90L, TimeUnit.SECONDS);
        if (!completed) {
            executor.shutdownNow();
            host.log(Log.WARN, TAG, "course deadline scan timeout: " + scanned.get() + "/" + scheduled.get()
                    + ", score update skipped");
        } else {
            emitCourseScanBatch(results);
            emitScanRound(results);
        }
        long elapsed = System.currentTimeMillis() - startedAt;
        host.lastCourseScanThreads = threadCount;
        host.lastCourseScanRefs = refs.size();
        host.lastCourseScanScanned = scanned.get();
        emitCourseScanPerformance(threadCount, elapsed, refs.size(), scheduled.get(), scanned.get());
        host.log(Log.INFO, TAG, "course deadline scan complete: " + scanned.get() + "/" + scheduled.get()
                + " courses, refs=" + refs.size() + ", items=" + foundItems.get()
                + ", elapsed=" + elapsed + "ms, threads=" + threadCount);
    }

    private int fetchCourseDeadline(CourseRef ref, String cookieUid) {
        String refUid = ref.uid.isEmpty() ? cookieUid : ref.uid;
        String uid = refUid.isEmpty() ? "" : "&uid=" + urlEncode(refUid);
        String cpi = ref.cpi.isEmpty() ? "" : "&cpi=" + urlEncode(ref.cpi);
        int failures = 0;
        int found = 0;
        int result = host.fetchUrl("https://mooc1-api.chaoxing.com/work/task-list?courseId="
                        + urlEncode(ref.courseId) + "&classId=" + urlEncode(ref.classId) + cpi,
                parseContextForCourse("active.workList", ref, refUid));
        if (result < 0) {
            failures++;
        } else {
            found += result;
        }
        result = host.fetchUrl("https://mobilelearn.chaoxing.com/ppt/activeAPI/taskactivelist?courseId="
                        + urlEncode(ref.courseId) + "&classId=" + urlEncode(ref.classId) + uid,
                parseContextForCourse("active.taskList", ref, refUid));
        if (result < 0) {
            failures++;
        } else {
            found += result;
        }
        result = host.fetchUrl("https://mooc1-api.chaoxing.com/mooc-ans/exam/phone/task-list?courseId="
                        + urlEncode(ref.courseId) + "&classId=" + urlEncode(ref.classId) + cpi,
                parseContextForCourse("active.examList", ref, refUid));
        if (result < 0) {
            failures++;
        } else {
            found += result;
        }
        // Chapter task points: without this endpoint chapters only ever appear when the
        // user happens to open the course in the app, which is why "有些章节不显示".
        // gas/clazz takes the class KEY (not classId) and returns the knowledge tree with
        // each node's begintime/endtime; leaves with an endtime become 章节 deadlines.
        if (!ref.key.isEmpty()) {
            String fields = "id,bbsid,classscore,isstart,allowdownload,chatid,name,state,isfiled,"
                    + "visiblescore,begindate,coursesetting.fields(id,courseid,hiddencoursecover,"
                    + "coursefacecheck),course.fields(id,name,infocontent,objectid,app,"
                    + "bulletformat,mappingcourseid,imageurl,teacherfactor,jobcount,knowledge.fields("
                    + "id,name,indexOrder,parentnodeid,status,layer,label,jobcount,begintime,endtime))";
            // personid is the account id (uid) — cpi is a course-person mapping id and
            // only used as a last resort when no uid is known for this course.
            String personId = !ref.uid.isEmpty() ? ref.uid : (!refUid.isEmpty() ? refUid : ref.cpi);
            String chapterUrl = "https://mooc1-api.chaoxing.com/gas/clazz?id=" + Uri.encode(ref.key)
                    + "&personid=" + Uri.encode(personId)
                    + "&fields=" + Uri.encode(fields)
                    + "&view=json";
            result = host.fetchUrl(chapterUrl,
                    parseContextForCourse("active.chapter", ref, refUid));
            if (result < 0) {
                failures++;
            } else {
                found += result;
            }
        }
        if (failures > 0 && found == 0) {
            return -1;
        }
        return found;
    }

    private List<CourseRef> courseScanPlan(List<CourseRef> refs) {
        long startedAt = System.currentTimeMillis();
        LinkedHashMap<String, CourseRef> unique = new LinkedHashMap<>();
        if (refs != null) {
            for (CourseRef ref : refs) {
                if (ref == null || ref.courseId.isEmpty() || ref.classId.isEmpty()) {
                    continue;
                }
                unique.putIfAbsent(courseKey(ref), ref);
            }
        }
        ArrayList<CourseRef> candidates = new ArrayList<>(unique.values());
        SharedPreferences prefs = host.courseScanPrefs();
        if (prefs == null) {
            return candidates;
        }
        long now = System.currentTimeMillis();
        ArrayList<CourseRef> result = new ArrayList<>();
        int skipped = 0;
        for (CourseRef ref : candidates) {
            if (shouldScanCourse(ref, prefs, now)) {
                result.add(ref);
            } else {
                skipped++;
            }
        }
        result.sort(Comparator
                .comparingInt((CourseRef ref) -> CourseScanScores.score(prefs, courseKey(ref))).reversed()
                .thenComparingLong(ref -> CourseScanScores.lastScanAt(prefs, courseKey(ref)))
                .thenComparing(ref -> safe(ref.courseName)));
        long elapsed = System.currentTimeMillis() - startedAt;
        host.log(Log.INFO, TAG, "course scan plan: selected=" + result.size()
                + ", skipped=" + skipped + ", refs=" + (refs == null ? 0 : refs.size())
                + ", elapsed=" + elapsed + "ms");
        return result;
    }

    private boolean shouldScanCourse(CourseRef ref, SharedPreferences prefs, long now) {
        return CourseScanScores.shouldScan(prefs, courseKey(ref), now);
    }

    private int courseFetchThreads() {
        return CourseScanThreads.current(host.courseScanPrefs());
    }

    /**
     * Tell the module process which courses were authoritatively scanned this round and
     * which item ids were seen, so it can flag deadlines that stopped being reported
     * (teacher revoked or moved them). Only sent for a fully completed scan: a timeout
     * leaves the remaining courses unknown, and judging them would guess.
     */
    private void emitScanRound(List<CourseScanResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        Context context = host.ensureHostContext();
        if (context == null) {
            return;
        }
        try {
            ArrayList<String> keys = new ArrayList<>();
            for (CourseScanResult result : results) {
                if (result == null || result.ref == null) {
                    continue;
                }
                keys.add(safe(result.ref.courseId) + "|" + safe(result.ref.classId));
            }
            if (keys.isEmpty()) {
                return;
            }
            Intent intent = new Intent(DeadlineReceiver.ACTION_SCAN_ROUND);
            intent.setComponent(new ComponentName(ChaoxingHook.MODULE_PACKAGE, ChaoxingHook.MODULE_PACKAGE + ".DeadlineReceiver"));
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtra("course_keys", keys.toArray(new String[0]));
            intent.putExtra("seen_ids", host.snapshotSeenItemIds());
            host.attachBridgeToken(intent);
            context.sendBroadcast(intent);
            host.log(Log.INFO, TAG, "scan round emitted: courses=" + keys.size());
        } catch (Throwable throwable) {
            host.log(Log.WARN, TAG, "scan round emit failed: " + throwable.getClass().getSimpleName());
        }
    }

    private void emitCourseScanPerformance(int threads, long elapsedMs, int refs, int scheduled, int scanned) {
        Context context = host.ensureHostContext();
        if (context == null || scheduled <= 0 || elapsedMs <= 0L) {
            return;
        }
        try {
            Intent intent = new Intent(DeadlineReceiver.ACTION_COURSE_SCAN_PERF);
            intent.setComponent(new ComponentName(ChaoxingHook.MODULE_PACKAGE, ChaoxingHook.MODULE_PACKAGE + ".DeadlineReceiver"));
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtra("threads", threads);
            intent.putExtra("elapsed_ms", elapsedMs);
            intent.putExtra("refs", refs);
            intent.putExtra("scheduled", scheduled);
            intent.putExtra("scanned", scanned);
            host.attachBridgeToken(intent);
            context.sendBroadcast(intent);
        } catch (Throwable throwable) {
            host.log(Log.WARN, TAG, "course scan perf emit failed: " + throwable.getClass().getSimpleName());
        }
    }

    private void emitCourseScanBatch(List<CourseScanResult> results) {
        Context context = host.ensureHostContext();
        if (context == null || results == null || results.isEmpty()) {
            return;
        }
        try {
            ArrayList<String> courseIds = new ArrayList<>();
            ArrayList<String> classIds = new ArrayList<>();
            ArrayList<String> courseNames = new ArrayList<>();
            boolean[] foundDeadlines = new boolean[results.size()];
            int count = 0;
            synchronized (results) {
                for (CourseScanResult result : results) {
                    if (result == null || result.ref == null) {
                        continue;
                    }
                    courseIds.add(result.ref.courseId);
                    classIds.add(result.ref.classId);
                    courseNames.add(result.ref.courseName);
                    foundDeadlines[count] = result.foundDeadline;
                    count++;
                }
            }
            if (count <= 0) {
                return;
            }
            if (count != foundDeadlines.length) {
                boolean[] compact = new boolean[count];
                System.arraycopy(foundDeadlines, 0, compact, 0, count);
                foundDeadlines = compact;
            }
            Intent intent = new Intent(DeadlineReceiver.ACTION_COURSE_SCAN_BATCH);
            intent.setComponent(new ComponentName(ChaoxingHook.MODULE_PACKAGE, ChaoxingHook.MODULE_PACKAGE + ".DeadlineReceiver"));
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtra("course_ids", courseIds.toArray(new String[0]));
            intent.putExtra("class_ids", classIds.toArray(new String[0]));
            intent.putExtra("course_names", courseNames.toArray(new String[0]));
            intent.putExtra("found_deadlines", foundDeadlines);
            host.attachBridgeToken(intent);
            context.sendBroadcast(intent);
            host.log(Log.INFO, TAG, "course scan score batch emitted: " + count + " courses");
        } catch (Throwable throwable) {
            host.log(Log.WARN, TAG, "course scan batch emit failed: " + throwable.getClass().getSimpleName());
        }
    }

    private ParseContext parseContextForCourse(String prefix, CourseRef ref, String fallbackUid) {
        if (ref == null) {
            return ParseContext.simple(prefix);
        }
        String uid = ref.uid.isEmpty() ? fallbackUid : ref.uid;
        return ParseContext.forCourse(prefix, "", ref.courseId, ref.classId, ref.cpi, uid, ref.courseName);
    }

    private String courseKey(CourseRef ref) {
        if (ref == null) {
            return "";
        }
        return safe(ref.courseId) + "|" + safe(ref.classId);
    }

    private static String urlEncode(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Throwable ignored) {
            return value;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class CourseScanResult {
        final CourseRef ref;
        final boolean foundDeadline;

        CourseScanResult(CourseRef ref, boolean foundDeadline) {
            this.ref = ref;
            this.foundDeadline = foundDeadline;
        }
    }
}
