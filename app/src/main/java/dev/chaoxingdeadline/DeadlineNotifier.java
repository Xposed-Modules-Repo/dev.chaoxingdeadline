package dev.chaoxingdeadline;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class DeadlineNotifier {
    /** Advance reminders: visible and audible, but not heads-up. */
    public static final String CHANNEL_ID = "deadline_alerts";
    /** Final call: the item is due within 30 minutes or right now. */
    public static final String CHANNEL_URGENT = "deadline_urgent";
    /** Quiet hours: delivered silently so nothing is dropped while you sleep. */
    public static final String CHANNEL_QUIET = "deadline_quiet";
    /** Daily digest: one morning summary notification. */
    public static final String CHANNEL_DIGEST = "deadline_digest";
    /**
     * Permanent "next deadline" notification — the one HyperOS 2 lifts into the 超级岛.
     * Low importance so it never buzzes; it is an ambient at-a-glance view.
     */
    public static final String CHANNEL_LIVE = "deadline_live";
    private static final int LIVE_NOTIFICATION_ID = 0x4C49;
    /** Progress window: the bar fills over the last 24h before the deadline. */
    private static final long LIVE_WINDOW_MS = TimeUnit.HOURS.toMillis(24);

    private static final String PREFS = "deadline_notify";
    private static final String KEY_ALARMS = "scheduled_alarms";
    public static final String EXTRA_DEADLINE_ID = "deadline_id";
    public static final String EXTRA_OFFSET_MILLIS = "offset_millis";
    /** Sentinel for "no offset supplied": 0 ms is a real offset (the due-time reminder). */
    public static final long NO_OFFSET = -1L;

    private static final long URGENT_OFFSET = TimeUnit.MINUTES.toMillis(30);
    /** Snooze delay used by the notification action. */
    private static final long SNOOZE_DELAY = TimeUnit.MINUTES.toMillis(30);
    private static final String SNOOZE_LABEL = "\u7a0d\u540e\u63d0\u9192";

    private DeadlineNotifier() {
    }

    public static void ensureChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        createChannel(manager, CHANNEL_ID, "\u622a\u6b62\u63d0\u9192",
                "\u5b66\u4e60\u901a\u4f5c\u4e1a\u3001\u8003\u8bd5\u3001\u7ae0\u8282\u4efb\u52a1\u548c\u8bfe\u5802\u6d3b\u52a8\u7684\u63d0\u524d\u63d0\u9192",
                NotificationManager.IMPORTANCE_DEFAULT);
        createChannel(manager, CHANNEL_URGENT, "\u5373\u5c06\u622a\u6b62",
                "\u5269\u4f5930\u5206\u949f\u5185\u622a\u6b62\u6216\u5df2\u5230\u622a\u6b62\u65f6\u95f4\u7684\u7d27\u6025\u63d0\u9192",
                NotificationManager.IMPORTANCE_HIGH);
        createChannel(manager, CHANNEL_QUIET, "\u514d\u6253\u6270\u63d0\u9192",
                "\u514d\u6253\u6270\u65f6\u6bb5\u5185\u4ea7\u751f\u7684\u63d0\u9192\uff0c\u4e0d\u4f1a\u53d1\u58f0\u6216\u5f39\u51fa",
                NotificationManager.IMPORTANCE_LOW);
        createChannel(manager, CHANNEL_DIGEST, "每日摘要",
                "每天定时推送的待办汇总，列出今天和未来 3 天的截止项",
                NotificationManager.IMPORTANCE_DEFAULT);
        createChannel(manager, CHANNEL_LIVE, "最近截止（超级岛）",
                "常驻显示最近截止的待办，小米超级岛 / 实时活动会渲染这条通知",
                NotificationManager.IMPORTANCE_LOW);
    }

    private static void createChannel(NotificationManager manager, String id,
                                      String name, String description, int importance) {
        NotificationChannel channel = new NotificationChannel(id, name, importance);
        channel.setDescription(description);
        manager.createNotificationChannel(channel);
    }

    public static boolean canScheduleExactAlarms(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return alarm == null || alarm.canScheduleExactAlarms();
    }

    public static void maybeNotify(Context context, DeadlineItem item) {
        notifyBestMissedReminder(context, item);
    }

    public static void notifyDue(Context context, String id, long offsetMillis) {
        if (id == null || id.isEmpty()) {
            rescheduleAll(context);
            return;
        }
        DeadlineItem item = new DeadlineStore(context).itemById(id);
        if (item != null) {
            long[] offsets = AppSettings.notifyOffsetsMillis(context, item.type);
            // A type with no offsets at all (classroom activities without the due switch)
            // has no [0] to fall back to.
            long offset = offsetMillis >= 0L ? offsetMillis
                    : (offsets.length > 0 ? offsets[0] : NO_OFFSET);
            notifyNow(context, item, offset, false);
        }
        rescheduleAll(context);
    }

    public static void checkAll(Context context) {
        List<DeadlineItem> items = new DeadlineStore(context).activeItems();
        for (DeadlineItem item : items) {
            notifyBestMissedReminder(context, item);
        }
        rescheduleAll(context, items);
    }

    public static void scheduleNextCheck(Context context) {
        rescheduleAll(context);
    }

    public static void rescheduleAll(Context context) {
        rescheduleAll(context, new DeadlineStore(context).activeItems(), true);
    }

    public static void rescheduleAll(Context context, List<DeadlineItem> items) {
        rescheduleAll(context, items, true);
    }

    public static void rescheduleUpcomingOnly(Context context) {
        rescheduleUpcomingOnly(context, new DeadlineStore(context).activeItems());
    }

    public static void rescheduleUpcomingOnly(Context context, List<DeadlineItem> items) {
        rescheduleAll(context, items, false);
    }

    private static void rescheduleAll(Context context, List<DeadlineItem> items, boolean allowCatchUp) {
        cancelScheduledAlarms(context);
        cleanupSentMarkers(context, items);
        ensureChannel(context);
        long now = System.currentTimeMillis();
        HashSet<String> scheduled = new HashSet<>();
        for (DeadlineItem item : items) {
            // Per-type advance hours: an exam and a homework can remind at different leads.
            long[] offsets = AppSettings.notifyOffsetsMillis(context, item.type);
            boolean notifiable = canNotify(context, item, now);
            if (!notifiable && !dueTimeReminderStillPending(context, item, offsets, now)) {
                continue;
            }
            if (!notifiable) {
                // Past its deadline, but the due-time reminder deferred out of quiet hours
                // is still pending: keep exactly that alarm alive across reschedules.
                String dueKey = alarmKey(item, 0L);
                long dueTrigger = shiftForQuietHours(context, item.dueAt);
                if (dueTrigger > now) {
                    scheduleAlarm(context, dueKey, dueTrigger);
                    scheduled.add(dueKey);
                }
                continue;
            }
            // offsets arrive longest-first; catch up with the *closest* missed window so a
            // freshly captured item due in 20 minutes reports 提前 30 分钟, not 提前 24 小时.
            // Future-vs-past is judged on the quiet-shifted trigger: a reminder deferred
            // past its original moment is still pending and must survive any reschedule.
            long catchUpOffset = NO_OFFSET;
            for (long offset : offsets) {
                long triggerAt = shiftForQuietHours(context, item.dueAt - offset);
                if (triggerAt <= now) {
                    if (allowCatchUp && shouldCatchUp(item, offset, now)
                            && (catchUpOffset == NO_OFFSET || offset < catchUpOffset)) {
                        catchUpOffset = offset;
                    }
                    continue;
                }
                String key = alarmKey(item, offset);
                scheduleAlarm(context, key, triggerAt);
                scheduled.add(key);
            }
            if (catchUpOffset != NO_OFFSET) {
                notifyNow(context, item, catchUpOffset, true);
            }
        }
        prefs(context).edit().putStringSet(KEY_ALARMS, scheduled).apply();
        updateLiveNotification(context, items);
    }

    // -- 超级岛 / 常驻「最近截止」实时通知 (HyperOS 2 Live Updates) --

    /**
     * The next deadline to keep in the permanent notification. HyperOS 2 renders an
     * ongoing progress notification in the 超级岛; other OEMs show it as a low-priority
     * ongoing notice. Submitted / revoked / past-due rows are skipped, and the list is
     * already type- and block-filtered by the caller (activeItems).
     */
    static DeadlineItem pickLiveItem(List<DeadlineItem> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        DeadlineItem best = null;
        for (DeadlineItem item : items) {
            if (item == null || item.submitted || item.vanishedAt > 0L || item.dueAt <= now) {
                continue;
            }
            if (best == null || item.dueAt < best.dueAt) {
                best = item;
            }
        }
        return best;
    }

    /** Post or refresh the permanent 超级岛 notification; cancel it when nothing is due. */
    public static void updateLiveNotification(Context context, List<DeadlineItem> items) {
        android.app.NotificationManager manager =
                context.getSystemService(android.app.NotificationManager.class);
        if (manager == null) {
            return;
        }
        if (!AppSettings.liveNotificationEnabled(context)) {
            manager.cancel(LIVE_NOTIFICATION_ID);
            return;
        }
        ensureChannel(context);
        DeadlineItem item = pickLiveItem(items);
        if (item == null) {
            manager.cancel(LIVE_NOTIFICATION_ID);
            return;
        }
        long now = System.currentTimeMillis();
        long start = item.dueAt - LIVE_WINDOW_MS;
        if (start < 0L || start >= item.dueAt) {
            start = now;
        }
        int progress = item.dueAt <= start ? 100
                : (int) Math.max(0L, Math.min(100L, (now - start) * 100L / (item.dueAt - start)));
        android.content.Intent intent = new android.content.Intent(context, MainActivity.class);
        intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
        android.app.PendingIntent pending = android.app.PendingIntent.getActivity(
                context, LIVE_NOTIFICATION_ID, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        String course = item.course == null ? "" : item.course;
        android.app.Notification.Builder builder = new android.app.Notification.Builder(context, CHANNEL_LIVE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(item.title)
                .setContentText((course.isEmpty() ? "" : course + " · ")
                        + "剩余 " + formatRemaining(item.dueAt - now))
                .setOngoing(true)
                .setAutoCancel(false)
                .setShowWhen(false)
                .setCategory(android.app.Notification.CATEGORY_PROGRESS)
                .setProgress(100, progress, false)
                .setContentIntent(pending);
        try {
            manager.notify(LIVE_NOTIFICATION_ID, builder.build());
        } catch (Throwable ignored) {
            // Some OEM builds refuse ongoing progress notices; nothing else to do.
        }
    }

    public static void cancelLiveNotification(Context context) {
        android.app.NotificationManager manager =
                context.getSystemService(android.app.NotificationManager.class);
        if (manager != null) {
            manager.cancel(LIVE_NOTIFICATION_ID);
        }
    }

    private static String formatRemaining(long millis) {
        if (millis <= 0L) {
            return "已截止";
        }
        long minutes = millis / 60_000L;
        if (minutes < 60L) {
            return minutes + " 分钟";
        }
        long hours = minutes / 60L;
        if (hours < 24L) {
            return hours + " 小时 " + (minutes % 60L) + " 分";
        }
        long days = hours / 24L;
        return days + " 天 " + (hours % 24L) + " 小时";
    }

    /**
     * A due-time reminder deferred out of quiet hours can still lie in the future after the
     * deadline itself has passed; such items must keep that alarm across reschedules
     * instead of being dropped by the canNotify gate.
     */
    private static boolean dueTimeReminderStillPending(
            Context context, DeadlineItem item, long[] offsets, long now) {
        if (item.submitted || item.vanishedAt > 0L || item.dueAt > now) {
            return false;
        }
        boolean dueTimeEnabled = false;
        for (long offset : offsets) {
            if (offset == 0L) {
                dueTimeEnabled = true;
                break;
            }
        }
        return dueTimeEnabled
                && AppSettings.shouldNotifyType(context, item.type)
                && shiftForQuietHours(context, item.dueAt) > now;
    }

    /** Reminders falling inside the quiet window are pushed to the moment it ends. */
    private static long shiftForQuietHours(Context context, long triggerAt) {
        return AppSettings.shiftOutOfQuietHours(context, triggerAt);
    }

    // -- daily digest --

    /** Schedule (or cancel) the next morning-digest alarm from current settings. */
    public static void scheduleNextDigest(Context context) {
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) {
            return;
        }
        if (!AppSettings.dailyDigestEnabled(context)) {
            cancelDigestAlarm(context);
            return;
        }
        int minute = AppSettings.digestMinuteOfDay(context);
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, minute / 60);
        calendar.set(Calendar.MINUTE, minute % 60);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_MONTH, 1);
        }
        long triggerAt = shiftForQuietHours(context, calendar.getTimeInMillis());
        PendingIntent pending = digestAlarmIntent(context, PendingIntent.FLAG_UPDATE_CURRENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
            return;
        }
        alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
    }

    private static void cancelDigestAlarm(Context context) {
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) {
            return;
        }
        PendingIntent pending = digestAlarmIntent(context, PendingIntent.FLAG_NO_CREATE);
        if (pending != null) {
            alarm.cancel(pending);
            pending.cancel();
        }
    }

    private static PendingIntent digestAlarmIntent(Context context, int flags) {
        Intent intent = new Intent(context, DeadlineReceiver.class)
                .setAction(DeadlineReceiver.ACTION_DAILY_DIGEST);
        BridgeAuth.attach(context, intent);
        return PendingIntent.getBroadcast(context, "daily_digest".hashCode(), intent,
                flags | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Fire today's digest, then schedule tomorrow's. */
    public static void sendDailyDigest(Context context) {
        try {
            if (AppSettings.dailyDigestEnabled(context)) {
                sendDigestNow(context);
            }
        } catch (Throwable throwable) {
            Log.w("ChaoxingDeadline", "daily digest failed: " + throwable);
        } finally {
            scheduleNextDigest(context);
        }
    }

    private static void sendDigestNow(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ensureChannel(context);
        List<DeadlineItem> items = new DeadlineStore(context).activeItems();
        long now = System.currentTimeMillis();
        Calendar endOfDay = Calendar.getInstance();
        endOfDay.set(Calendar.HOUR_OF_DAY, 23);
        endOfDay.set(Calendar.MINUTE, 59);
        endOfDay.set(Calendar.SECOND, 59);
        endOfDay.set(Calendar.MILLISECOND, 999);
        long threeDays = now + TimeUnit.DAYS.toMillis(3);
        List<DeadlineItem> today = new ArrayList<>();
        List<DeadlineItem> soon = new ArrayList<>();
        for (DeadlineItem item : items) {
            if (item == null || item.submitted || item.dueAt <= now) {
                continue;
            }
            if (!AppSettings.shouldNotifyType(context, item.type)) {
                continue;
            }
            if (item.dueAt <= endOfDay.getTimeInMillis()) {
                today.add(item);
            } else if (item.dueAt <= threeDays) {
                soon.add(item);
            }
        }
        if (today.isEmpty() && soon.isEmpty()) {
            return;
        }
        StringBuilder big = new StringBuilder();
        if (today.isEmpty()) {
            big.append("今天没有截止的待办。");
        } else {
            int shown = Math.min(today.size(), 6);
            for (int i = 0; i < shown; i++) {
                DeadlineItem item = today.get(i);
                big.append(String.format(java.util.Locale.CHINA, "%tR", item.dueAt))
                        .append(" 截止 · ").append(item.title == null ? "" : item.title);
                if (item.course != null && !item.course.isEmpty()) {
                    big.append("（").append(item.course).append("）");
                }
                big.append('\n');
            }
            if (today.size() > shown) {
                big.append("…等 ").append(today.size()).append(" 项\n");
            }
        }
        if (!soon.isEmpty()) {
            big.append("未来 3 天还有 ").append(soon.size()).append(" 项：\n");
            int shown = Math.min(soon.size(), 3);
            for (int i = 0; i < shown; i++) {
                DeadlineItem item = soon.get(i);
                big.append(DateText.deadlineTime(item.dueAt)).append(" · ")
                        .append(item.title == null ? "" : item.title).append('\n');
            }
            if (soon.size() > shown) {
                big.append("…等 ").append(soon.size()).append(" 项\n");
            }
        }
        String summary = today.isEmpty()
                ? "未来 3 天有 " + soon.size() + " 项截止"
                : "今天 " + today.size() + " 项截止，3 天内共 " + (today.size() + soon.size()) + " 项";
        Intent launch = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(context,
                "daily_digest_ui".hashCode(), launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification.Builder builder = new android.app.Notification.Builder(context, CHANNEL_DIGEST)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("待办摘要")
                .setContentText(summary)
                .setStyle(new android.app.Notification.BigTextStyle().bigText(big.toString().trim()))
                .setContentIntent(contentIntent)
                .setAutoCancel(true);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify("daily_digest".hashCode(), builder.build());
        }
    }

    public static void cancelScheduledAlarms(Context context) {
        SharedPreferences prefs = prefs(context);
        Set<String> scheduled = new HashSet<>(prefs.getStringSet(KEY_ALARMS, new HashSet<>()));
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm != null) {
            for (String key : scheduled) {
                PendingIntent pending = pendingIntent(context, key, PendingIntent.FLAG_NO_CREATE);
                if (pending != null) {
                    alarm.cancel(pending);
                    pending.cancel();
                }
            }
        }
        prefs.edit().remove(KEY_ALARMS).apply();
    }

    public static void ignoreItem(Context context, String id) {
        DeadlineItem item = new DeadlineStore(context).itemById(id);
        if (item == null) {
            return;
        }
        SharedPreferences.Editor editor = prefs(context).edit();
        for (long offset : AppSettings.notifyOffsetsMillis(context, item.type)) {
            editor.putBoolean(sentKey(item, offset), true);
        }
        editor.apply();
        cancelNotification(context, item);
        rescheduleAll(context);
    }

    public static void deleteItem(Context context, String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        DeadlineStore store = new DeadlineStore(context);
        DeadlineItem item = store.itemById(id);
        store.deleteItem(id);
        if (item != null) {
            cancelNotification(context, item);
        }
        rescheduleAll(context);
        OverlayBridge.publish(context);
        context.sendBroadcast(new Intent(DeadlineReceiver.ACTION_REFRESH).setPackage(context.getPackageName()));
    }

    public static void sendTestNotification(Context context) {
        DeadlineItem item = new DeadlineItem();
        item.id = "test_" + System.currentTimeMillis();
        item.type = "\u4f5c\u4e1a";
        item.title = "\u901a\u77e5\u6548\u679c\u6d4b\u8bd5";
        item.course = "\u8c03\u8bd5\u8bfe\u7a0b";
        item.dueAt = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1);
        notifyNow(context, item, URGENT_OFFSET, false);
    }

    private static boolean canNotify(Context context, DeadlineItem item, long now) {
        return item != null
                && item.id != null && !item.id.isEmpty()
                && !item.submitted
                && item.vanishedAt <= 0L
                && item.dueAt > now
                && AppSettings.shouldNotifyType(context, item.type);
    }

    private static boolean shouldCatchUp(DeadlineItem item, long offset, long now) {
        long delta = item.dueAt - now;
        if (delta <= 0L) {
            return false;
        }
        // offset == 0 means "due right now": its window is the instant of the deadline, so
        // there is nothing meaningful to catch up once it has passed.
        if (offset <= 0L) {
            return false;
        }
        // The reminder window for this offset has started but its trigger time already
        // passed, so it counts as missed and is eligible for exactly one catch-up send.
        return delta <= offset;
    }

    private static void notifyBestMissedReminder(Context context, DeadlineItem item) {
        long now = System.currentTimeMillis();
        if (!canNotify(context, item, now)) {
            return;
        }
        long bestOffset = NO_OFFSET;
        for (long offset : AppSettings.notifyOffsetsMillis(context, item.type)) {
            if (item.dueAt - offset > now || !shouldCatchUp(item, offset, now)) {
                continue;
            }
            if (bestOffset == NO_OFFSET || offset < bestOffset) {
                bestOffset = offset;
            }
        }
        if (bestOffset != NO_OFFSET) {
            notifyNow(context, item, bestOffset, true);
        }
    }

    private static void scheduleAlarm(Context context, String key, long triggerAt) {
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) {
            return;
        }
        PendingIntent pending = pendingIntent(context, key, PendingIntent.FLAG_UPDATE_CURRENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
            return;
        }
        alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
    }

    private static PendingIntent pendingIntent(Context context, String key, int flags) {
        Intent intent = new Intent(context, DeadlineReceiver.class)
                .setAction(DeadlineReceiver.ACTION_NOTIFY)
                .putExtra(EXTRA_DEADLINE_ID, idFromAlarmKey(key))
                .putExtra(EXTRA_OFFSET_MILLIS, offsetFromAlarmKey(key));
        BridgeAuth.attach(context, intent);
        return PendingIntent.getBroadcast(
                context,
                key.hashCode(),
                intent,
                flags | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void notifyNow(Context context, DeadlineItem item, long offsetMillis, boolean catchUp) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        SharedPreferences prefs = prefs(context);
        String key = sentKey(item, offsetMillis);
        if (prefs.getBoolean(key, false)) {
            return;
        }
        ensureChannel(context);
        PendingIntent contentIntent = openModuleIntent(context, item);
        android.app.Notification.Builder builder = new android.app.Notification.Builder(context, channelFor(context, item, offsetMillis))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(item.type + "\u5feb\u622a\u6b62\u4e86")
                .setContentText(notificationLine(item, offsetMillis, catchUp))
                .setStyle(new android.app.Notification.BigTextStyle().bigText(bigText(item, offsetMillis, catchUp)))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .addAction(notificationAction(context, android.R.drawable.ic_menu_save,
                        "\u6807\u8bb0\u4e3a\u5df2\u5b8c\u6210", actionIntent(context, DeadlineReceiver.ACTION_MARK_DONE, item.id)))
                .addAction(notificationAction(context, android.R.drawable.ic_menu_recent_history,
                        "\u7a0d\u540e 30 \u5206\u949f", actionIntent(context, DeadlineReceiver.ACTION_SNOOZE, item.id)))
                .addAction(notificationAction(context, android.R.drawable.ic_menu_view,
                        "\u6253\u5f00\u5b66\u4e60\u901a", openChaoxingIntent(context)))
                .addAction(notificationAction(context, android.R.drawable.ic_menu_close_clear_cancel,
                        "\u5ffd\u7565\u672c\u6b21", actionIntent(context, DeadlineReceiver.ACTION_IGNORE, item.id)));
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(notificationId(item), builder.build());
            prefs.edit().putBoolean(key, true).apply();
        }
    }

    /**
     * Mark an item done straight from the notification shade — the most common thing to
     * do right after submitting the work in Chaoxing, before the next capture notices.
     * Deletion stays in the in-app item menu (it always did per the 1.8.0 changelog;
     * the shade button was a leftover).
     */
    public static void markDone(Context context, String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        DeadlineStore store = new DeadlineStore(context);
        DeadlineItem item = store.itemById(id);
        if (item == null) {
            return;
        }
        store.setSubmitted(id, true);
        cancelNotification(context, item);
        rescheduleAll(context);
        OverlayBridge.publish(context);
        DeadlineWidgetProvider.updateAll(context);
        context.sendBroadcast(new Intent(DeadlineReceiver.ACTION_REFRESH).setPackage(context.getPackageName()));
    }

    /** Urgent (<= 30 min left or due now) gets heads-up; quiet hours downgrade everything. */
    private static String channelFor(Context context, DeadlineItem item, long offsetMillis) {
        if (AppSettings.quietHoursEnabled(context)
                && AppSettings.inQuietWindow(context, System.currentTimeMillis())) {
            return CHANNEL_QUIET;
        }
        long remaining = (item == null ? Long.MAX_VALUE : item.dueAt) - System.currentTimeMillis();
        boolean urgent = offsetMillis <= 0L || remaining <= URGENT_OFFSET;
        if (AppSettings.urgentChannelEnabled(context) && urgent) {
            return CHANNEL_URGENT;
        }
        return CHANNEL_ID;
    }

    /** Re-post the reminder SNOOZE_DELAY minutes later; independent of rescheduleAll. */
    public static void snooze(Context context, String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        DeadlineItem item = new DeadlineStore(context).itemById(id);
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (item == null || alarm == null) {
            return;
        }
        Intent intent = new Intent(context, DeadlineReceiver.class)
                .setAction(DeadlineReceiver.ACTION_SNOOZE_FIRE)
                .putExtra(EXTRA_DEADLINE_ID, id);
        BridgeAuth.attach(context, intent);
        PendingIntent pending = PendingIntent.getBroadcast(context,
                ("snooze|" + id).hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long triggerAt = System.currentTimeMillis() + SNOOZE_DELAY;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
        } else {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
        }
        cancelNotification(context, item);
    }

    /** Re-fire a snoozed reminder: same look, its own label, ignores sent markers. */
    public static void notifySnoozed(Context context, String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        DeadlineItem item = new DeadlineStore(context).itemById(id);
        // Re-check everything that could have changed during the 30 quiet minutes: the item
        // may be done, and the type's notifications may have been switched off meanwhile.
        if (item == null || item.submitted || item.vanishedAt > 0L
                || !AppSettings.shouldNotifyType(context, item.type)) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ensureChannel(context);
        android.app.Notification.Builder builder = new android.app.Notification.Builder(context, channelFor(context, item, NO_OFFSET))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(item.type + "\u5feb\u622a\u6b62\u4e86")
                .setContentText(notificationLine(item, SNOOZE_LABEL, false))
                .setStyle(new android.app.Notification.BigTextStyle().bigText(bigText(item, SNOOZE_LABEL, false)))
                .setContentIntent(openModuleIntent(context, item))
                .setAutoCancel(true)
                .addAction(notificationAction(context, android.R.drawable.ic_menu_save,
                        "\u6807\u8bb0\u4e3a\u5df2\u5b8c\u6210", actionIntent(context, DeadlineReceiver.ACTION_MARK_DONE, item.id)))
                .addAction(notificationAction(context, android.R.drawable.ic_menu_recent_history,
                        "\u7a0d\u540e 30 \u5206\u949f", actionIntent(context, DeadlineReceiver.ACTION_SNOOZE, item.id)))
                .addAction(notificationAction(context, android.R.drawable.ic_menu_view,
                        "\u6253\u5f00\u5b66\u4e60\u901a", openChaoxingIntent(context)))
                .addAction(notificationAction(context, android.R.drawable.ic_menu_close_clear_cancel,
                        "\u5ffd\u7565\u672c\u6b21", actionIntent(context, DeadlineReceiver.ACTION_IGNORE, item.id)));
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(notificationId(item), builder.build());
        }
    }

    /** Dismiss posted reminders for items flagged as probably revoked. */
    public static void cancelVanishedNotifications(Context context) {
        try {
            for (DeadlineItem item : new DeadlineStore(context).activeItems()) {
                if (item.vanishedAt > 0L) {
                    cancelNotification(context, item);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static android.app.Notification.Action notificationAction(
            Context context,
            int iconRes,
            String title,
            PendingIntent intent) {
        return new android.app.Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(context, iconRes),
                title,
                intent)
                .build();
    }

    private static PendingIntent openModuleIntent(Context context, DeadlineItem item) {
        Intent launch = new Intent(context, MainActivity.class);
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                context,
                item.id.hashCode(),
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent openChaoxingIntent(Context context) {
        Intent target = context.getPackageManager().getLaunchIntentForPackage("com.chaoxing.mobile");
        if (target == null) {
            target = new Intent(context, MainActivity.class);
        }
        target.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                context,
                "open_chaoxing".hashCode(),
                target,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent actionIntent(Context context, String action, String id) {
        Intent intent = new Intent(context, DeadlineReceiver.class)
                .setAction(action)
                .putExtra(EXTRA_DEADLINE_ID, id);
        BridgeAuth.attach(context, intent);
        return PendingIntent.getBroadcast(
                context,
                (action + "|" + id).hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void cancelNotification(Context context, DeadlineItem item) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && item != null) {
            manager.cancel(notificationId(item));
        }
    }

    private static int notificationId(DeadlineItem item) {
        String basis = (item.id == null || item.id.isEmpty()) ? item.stableId() : item.id;
        return basis.hashCode();
    }

    private static String notificationLine(DeadlineItem item, long offsetMillis, boolean catchUp) {
        return notificationLine(item, ReminderText.label(item, offsetMillis, System.currentTimeMillis()), catchUp);
    }

    private static String notificationLine(DeadlineItem item, String label, boolean catchUp) {
        String prefix = label;
        if (catchUp) {
            prefix += "\u00b7\u8865\u53d1";
        }
        return prefix + "\uff1a" + item.title + "\uff0c" + DateText.dueLine(item.dueAt);
    }

    private static String bigText(DeadlineItem item, long offsetMillis, boolean catchUp) {
        return bigText(item, ReminderText.label(item, offsetMillis, System.currentTimeMillis()), catchUp);
    }

    private static String bigText(DeadlineItem item, String label, boolean catchUp) {
        StringBuilder builder = new StringBuilder();
        if (item.course != null && !item.course.isEmpty()) {
            builder.append(item.course).append('\n');
        }
        builder.append(item.title).append('\n')
                .append(DateText.dueLine(item.dueAt)).append('\n')
                .append("\u63d0\u9192\uff1a").append(label);
        if (catchUp) {
            builder.append("\uff08\u8865\u53d1\uff09");
        }
        return builder.toString();
    }

    /**
     * Drop "sent" markers whose item is gone or whose deadline moved.
     *
     * Keys are {@code sent_<id>_<dueAt>_<offset>} and the id itself contains underscores
     * (stableId is type_hex), so the old startsWith-against-every-prefix loop was
     * O(items × markers). Parsing from the right and looking the id up in a map makes it
     * linear; malformed keys are removed rather than kept.
     */
    private static void cleanupSentMarkers(Context context, List<DeadlineItem> items) {
        SharedPreferences prefs = prefs(context);
        Map<String, ?> all = prefs.getAll();
        if (all.isEmpty()) {
            return;
        }
        Map<String, Set<Long>> valid = new HashMap<>();
        for (DeadlineItem item : items) {
            if (item == null || item.id == null || item.id.isEmpty()) {
                continue;
            }
            valid.computeIfAbsent(item.id, k -> new HashSet<>()).add(item.dueAt);
        }
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        for (String key : all.keySet()) {
            if (!key.startsWith("sent_")) {
                continue;
            }
            int last = key.lastIndexOf('_');
            int prev = last > 0 ? key.lastIndexOf('_', last - 1) : -1;
            Long dueAt = null;
            if (prev > "sent_".length() - 1) {
                try {
                    dueAt = Long.valueOf(key.substring(prev + 1, last));
                } catch (NumberFormatException ignored) {
                    dueAt = null;
                }
            }
            Set<Long> dues = dueAt == null ? null : valid.get(key.substring("sent_".length(), prev));
            if (dues != null && dues.contains(dueAt)) {
                continue;
            }
            editor.remove(key);
            changed = true;
        }
        if (changed) {
            editor.apply();
        }
    }

    private static String alarmKey(DeadlineItem item, long offsetMillis) {
        return item.id + "|" + item.dueAt + "|" + offsetMillis;
    }

    private static String sentKey(DeadlineItem item, long offsetMillis) {
        return "sent_" + item.id + "_" + item.dueAt + "_" + offsetMillis;
    }

    private static String idFromAlarmKey(String key) {
        int split = key == null ? -1 : key.indexOf('|');
        return split > 0 ? key.substring(0, split) : key;
    }

    private static long offsetFromAlarmKey(String key) {
        if (key == null) {
            return NO_OFFSET;
        }
        int last = key.lastIndexOf('|');
        if (last < 0 || last + 1 >= key.length()) {
            return NO_OFFSET;
        }
        try {
            return Long.parseLong(key.substring(last + 1));
        } catch (Throwable ignored) {
            return NO_OFFSET;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
