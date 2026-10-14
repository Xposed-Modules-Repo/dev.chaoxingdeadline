package dev.chaoxingdeadline;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONArray;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DeadlineReceiver extends BroadcastReceiver {
    public static final String ACTION_ITEM = "dev.chaoxingdeadline.DEADLINE_ITEM";
    public static final String ACTION_STATUS = "dev.chaoxingdeadline.STATUS";
    public static final String ACTION_REFRESH = "dev.chaoxingdeadline.REFRESH";
    public static final String ACTION_CHECK = "dev.chaoxingdeadline.CHECK";
    public static final String ACTION_NOTIFY = "dev.chaoxingdeadline.NOTIFY";
    public static final String ACTION_IGNORE = "dev.chaoxingdeadline.IGNORE";
    public static final String ACTION_DELETE = "dev.chaoxingdeadline.DELETE";
    public static final String ACTION_MARK_DONE = "dev.chaoxingdeadline.MARK_DONE";
    public static final String ACTION_SCAN_ROUND = "dev.chaoxingdeadline.SCAN_ROUND";
    public static final String ACTION_TIMETABLE = "dev.chaoxingdeadline.TIMETABLE";
    public static final String ACTION_COURSE = "dev.chaoxingdeadline.COURSE";
    public static final String ACTION_COURSE_SCAN_RESULT = "dev.chaoxingdeadline.COURSE_SCAN_RESULT";
    public static final String ACTION_COURSE_SCAN_BATCH = "dev.chaoxingdeadline.COURSE_SCAN_BATCH";
    public static final String ACTION_COURSE_SCAN_PERF = "dev.chaoxingdeadline.COURSE_SCAN_PERF";
    public static final String ACTION_SETTINGS_UPDATE = "dev.chaoxingdeadline.SETTINGS_UPDATE";
    public static final String ACTION_WIDGET_REFRESH = "dev.chaoxingdeadline.WIDGET_REFRESH";
    public static final String ACTION_DAILY_DIGEST = "dev.chaoxingdeadline.DAILY_DIGEST";
    public static final String ACTION_SNOOZE = "dev.chaoxingdeadline.SNOOZE";
    public static final String ACTION_SNOOZE_FIRE = "dev.chaoxingdeadline.SNOOZE_FIRE";
    /**
     * Module app → Chaoxing host: asks the hook for a refresh round. Unlike every
     * other action here this one is addressed at the host package (see
     * {@link RefreshBridge}), so it never reaches this receiver.
     */
    public static final String ACTION_REQUEST_REFRESH = "dev.chaoxingdeadline.REQUEST_REFRESH";
    public static final String EXTRA_WEEK = "week";
    public static final String EXTRA_REASON = "reason";
    public static final String EXTRA_ITEM_B64 = "item_b64";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_SOURCE = "source";
    private static final String TAG = "ChaoxingDeadline";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "chaoxingdeadline-receiver");
        thread.setDaemon(true);
        return thread;
    });
    /**
     * One active refresh can deliver dozens of ACTION_ITEM broadcasts. Each used to run a
     * full rescheduleAll + publish + widget render; now the expensive tail is coalesced
     * into a single flush shortly after the burst ends. The flush runs on its own
     * scheduler so it can never be stuck behind a long item queue.
     */
    private static final long FLUSH_DELAY_MS = 400L;
    private static final ScheduledExecutorService FLUSH_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "chaoxingdeadline-flush");
                thread.setDaemon(true);
                return thread;
            });
    private static final Object FLUSH_LOCK = new Object();
    private static final List<PendingResult> PENDING_RESULTS = new ArrayList<>();
    private static ScheduledFuture<?> flushFuture;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        // Every branch touches SQLite, AlarmManager or widgets; keep that off the main
        // thread so a large backlog can't ANR the receiver. goAsync keeps the process
        // alive until handle() finishes, and the single-threaded executor preserves
        // broadcast ordering.
        PendingResult result = goAsync();
        AtomicBoolean delegated = new AtomicBoolean(false);
        EXECUTOR.execute(() -> {
            try {
                handle(context, intent, result, delegated);
            } catch (Throwable throwable) {
                Log.e(TAG, "receiver failed for action " + intent.getAction(), throwable);
            } finally {
                if (!delegated.get()) {
                    finishQuietly(result);
                }
            }
        });
    }

    /** Batches the expensive post-upsert work; ownership of {@code result} moves to the flush. */
    private static void enqueueFlush(Context context, PendingResult result) {
        synchronized (FLUSH_LOCK) {
            PENDING_RESULTS.add(result);
            if (flushFuture == null || flushFuture.isDone()) {
                flushFuture = FLUSH_EXECUTOR.schedule(
                        () -> flushPending(context.getApplicationContext()),
                        FLUSH_DELAY_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    private static void flushPending(Context context) {
        List<PendingResult> results;
        synchronized (FLUSH_LOCK) {
            results = new ArrayList<>(PENDING_RESULTS);
            PENDING_RESULTS.clear();
            flushFuture = null;
        }
        try {
            DeadlineNotifier.rescheduleAll(context);
            OverlayBridge.publish(context);
            DeadlineWidgetProvider.updateAll(context);
            context.sendBroadcast(new Intent(ACTION_REFRESH).setPackage(context.getPackageName()));
        } catch (Throwable throwable) {
            Log.e(TAG, "coalesced flush failed", throwable);
        } finally {
            for (PendingResult pending : results) {
                finishQuietly(pending);
            }
        }
    }

    private static void finishQuietly(PendingResult result) {
        try {
            result.finish();
        } catch (Throwable ignored) {
            // Double-finish is harmless; never let it kill the flush for the rest.
        }
    }

    private static void handle(Context context, Intent intent, PendingResult result, AtomicBoolean delegated) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            DeadlineNotifier.rescheduleAll(context);
            DeadlineNotifier.scheduleNextDigest(context);
            DeadlineWidgetProvider.updateAll(context);
            return;
        }
        // Notification-shade actions (标记完成 / 稍后 / 忽略 / 稍后触发) are self-addressed:
        // the module posts them through its own PendingIntents at an explicit component in
        // its own process, so they never cross the Hook→module trust boundary the bridge
        // token guards. Gating them behind BridgeAuth.isValid() made all three buttons
        // silently no-op whenever the token was missing or raced between attach() and
        // validate() — only 打开学习通 (an activity intent) kept working.
        String shadeAction = intent.getAction();
        if (ACTION_MARK_DONE.equals(shadeAction)) {
            DeadlineNotifier.markDone(context, intent.getStringExtra(DeadlineNotifier.EXTRA_DEADLINE_ID));
            return;
        }
        if (ACTION_SNOOZE.equals(shadeAction)) {
            DeadlineNotifier.snooze(context, intent.getStringExtra(DeadlineNotifier.EXTRA_DEADLINE_ID));
            return;
        }
        if (ACTION_SNOOZE_FIRE.equals(shadeAction)) {
            DeadlineNotifier.notifySnoozed(context, intent.getStringExtra(DeadlineNotifier.EXTRA_DEADLINE_ID));
            return;
        }
        if (ACTION_IGNORE.equals(shadeAction)) {
            DeadlineNotifier.ignoreItem(context, intent.getStringExtra(DeadlineNotifier.EXTRA_DEADLINE_ID));
            OverlayBridge.publish(context);
            DeadlineWidgetProvider.updateAll(context);
            return;
        }
        // Everything below crosses the process boundary (the Chaoxing hook, alarms) and
        // stays authenticated.
        if (!BridgeAuth.isValid(context, intent)) {
            return;
        }
        if (ACTION_DAILY_DIGEST.equals(intent.getAction())) {
            DeadlineNotifier.sendDailyDigest(context);
            return;
        }
        if (ACTION_TIMETABLE.equals(intent.getAction())) {
            String lessonsJson = intent.getStringExtra("lessons");
            if (lessonsJson != null && !lessonsJson.isEmpty()) {
                try {
                    JSONArray array = new JSONArray(lessonsJson);
                    ArrayList<Lesson> lessons = new ArrayList<>(array.length());
                    for (int i = 0; i < array.length(); i++) {
                        lessons.add(Lesson.fromJson(array.getJSONObject(i)));
                    }
                    new DeadlineStore(context).replaceLessons(lessons,
                            intent.getLongExtra("first_week_date", 0L),
                            intent.getStringExtra("section_times"));
                    DeadlineWidgetProvider.updateAll(context);
                    DeadlineScheduleWidget.updateAll(context);
                    context.sendBroadcast(new Intent(ACTION_REFRESH).setPackage(context.getPackageName()));
                } catch (Throwable throwable) {
                    Log.e(TAG, "timetable receive failed", throwable);
                }
            }
            return;
        }
        if (ACTION_SCAN_ROUND.equals(intent.getAction())) {
            DeadlineStore store = new DeadlineStore(context);
            int flagged = store.applyScanRound(
                    intent.getStringArrayExtra("course_keys"),
                    intent.getStringArrayExtra("seen_ids"));
            if (flagged > 0) {
                // Reminders for now-revoked items must stop even if already posted.
                DeadlineNotifier.cancelVanishedNotifications(context);
                DeadlineNotifier.rescheduleAll(context);
            }
            OverlayBridge.publish(context);
            DeadlineWidgetProvider.updateAll(context);
            context.sendBroadcast(new Intent(ACTION_REFRESH).setPackage(context.getPackageName()));
            return;
        }
        if (ACTION_STATUS.equals(intent.getAction())) {
            context.getSharedPreferences("status", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("active", true)
                    .putLong("last_active_at", System.currentTimeMillis())
                    .putString("last_status", intent.getStringExtra(EXTRA_STATUS))
                    .putString("last_source", intent.getStringExtra(EXTRA_SOURCE))
                    .apply();
            context.sendBroadcast(new Intent(ACTION_REFRESH).setPackage(context.getPackageName()));
            return;
        }
        if (ACTION_CHECK.equals(intent.getAction())) {
            DeadlineNotifier.checkAll(context);
            OverlayBridge.publish(context);
            DeadlineWidgetProvider.updateAll(context);
            return;
        }
        if (ACTION_NOTIFY.equals(intent.getAction())) {
            DeadlineNotifier.notifyDue(context,
                    intent.getStringExtra(DeadlineNotifier.EXTRA_DEADLINE_ID),
                    intent.getLongExtra(DeadlineNotifier.EXTRA_OFFSET_MILLIS, DeadlineNotifier.NO_OFFSET));
            OverlayBridge.publish(context);
            DeadlineWidgetProvider.updateAll(context);
            return;
        }
        if (ACTION_DELETE.equals(intent.getAction())) {
            DeadlineNotifier.deleteItem(context, intent.getStringExtra(DeadlineNotifier.EXTRA_DEADLINE_ID));
            DeadlineWidgetProvider.updateAll(context);
            return;
        }
        if (ACTION_COURSE.equals(intent.getAction())) {
            DeadlineStore store = new DeadlineStore(context);
            store.rememberCourse(
                    intent.getStringExtra("course_id"),
                    intent.getStringExtra("class_id"),
                    intent.getStringExtra("cpi"),
                    intent.getStringExtra("uid"),
                    intent.getStringExtra("course"),
                    intent.getStringExtra("raw"));
            OverlayBridge.publish(context);
            DeadlineWidgetProvider.updateAll(context);
            context.sendBroadcast(new Intent(ACTION_REFRESH).setPackage(context.getPackageName()));
            return;
        }
        if (ACTION_COURSE_SCAN_RESULT.equals(intent.getAction())) {
            CourseScanScores.record(context,
                    intent.getStringExtra("course_id"),
                    intent.getStringExtra("class_id"),
                    intent.getBooleanExtra("found_deadline", false));
            return;
        }
        if (ACTION_COURSE_SCAN_BATCH.equals(intent.getAction())) {
            CourseScanScores.recordBatch(context,
                    intent.getStringArrayExtra("course_ids"),
                    intent.getStringArrayExtra("class_ids"),
                    intent.getStringArrayExtra("course_names"),
                    intent.getBooleanArrayExtra("found_deadlines"));
            return;
        }
        if (ACTION_COURSE_SCAN_PERF.equals(intent.getAction())) {
            CourseScanThreads.record(context,
                    intent.getIntExtra("threads", CourseScanThreads.DEFAULT),
                    intent.getLongExtra("elapsed_ms", 0L),
                    intent.getIntExtra("refs", 0),
                    intent.getIntExtra("scheduled", 0),
                    intent.getIntExtra("scanned", 0));
            return;
        }
        if (ACTION_SETTINGS_UPDATE.equals(intent.getAction())) {
            // Generic pass-through: any extra whose key is a known setting is written by
            // type, so new toggles do not need another branch here.
            AppSettings.applyRemoteUpdate(context, intent);
            OverlayBridge.publish(context);
            DeadlineWidgetProvider.updateAll(context);
            context.sendBroadcast(new Intent(ACTION_REFRESH).setPackage(context.getPackageName()));
            return;
        }
        if (ACTION_WIDGET_REFRESH.equals(intent.getAction())) {
            DeadlineWidgetProvider.updateAll(context);
            return;
        }
        if (!ACTION_ITEM.equals(intent.getAction())) {
            return;
        }
        try {
            String encoded = intent.getStringExtra(EXTRA_ITEM_B64);
            if (encoded == null || encoded.isEmpty()) {
                return;
            }
            String payload = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            DeadlineItem item = DeadlineItem.fromJson(payload);
            DeadlineStore store = new DeadlineStore(context);
            store.resolveCourse(item);
            store.upsert(item);
            if (item.course != null && !item.course.trim().isEmpty()) {
                store.rememberCourse(item.courseId, item.classId, item.cpi, item.uid, item.course, item.raw);
            }
            context.getSharedPreferences("status", Context.MODE_PRIVATE)
                    .edit()
                    .putLong("last_capture_at", System.currentTimeMillis())
                    .putString("last_capture_source", item.source)
                    .apply();
            Log.i(TAG, "Stored deadline from " + item.source + " type=" + item.type);
            // The heavy tail (alarm reschedule, overlay publish, widget render, UI
            // refresh) is shared by every item in a burst; hand this broadcast's
            // PendingResult to the coalescer instead of paying for it per item.
            enqueueFlush(context, result);
            delegated.set(true);
        } catch (Throwable throwable) {
            Log.e(TAG, "Failed to receive deadline", throwable);
        }
    }
}
