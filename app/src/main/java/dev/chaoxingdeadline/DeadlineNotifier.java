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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class DeadlineNotifier {
    private static final String CHANNEL_ID = "deadline_alerts";
    private static final String PREFS = "deadline_notify";
    private static final String KEY_ALARMS = "scheduled_alarms";
    private static final String EXTRA_DEADLINE_ID = "deadline_id";

    private DeadlineNotifier() {
    }

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "\u622a\u6b62\u63d0\u9192",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("\u5b66\u4e60\u901a\u4f5c\u4e1a\u548c\u8003\u8bd5\u622a\u6b62\u63d0\u9192");
        manager.createNotificationChannel(channel);
    }

    public static void maybeNotify(Context context, DeadlineItem item) {
        if (item == null) {
            return;
        }
        long delta = item.dueAt - System.currentTimeMillis();
        if (item.submitted || delta <= 0L || !AppSettings.shouldNotifyType(context, item.type)) {
            return;
        }
        if (delta > TimeUnit.HOURS.toMillis(AppSettings.notifyHours(context))) {
            return;
        }
        notifyNow(context, item);
    }

    public static void notifyDue(Context context, String id) {
        if (id == null || id.isEmpty()) {
            rescheduleAll(context);
            return;
        }
        DeadlineItem item = new DeadlineStore(context).itemById(id);
        if (item != null) {
            maybeNotify(context, item);
        }
        rescheduleAll(context);
    }

    public static void checkAll(Context context) {
        List<DeadlineItem> items = new DeadlineStore(context).activeItems();
        for (DeadlineItem item : items) {
            maybeNotify(context, item);
        }
        rescheduleAll(context, items);
    }

    public static void scheduleNextCheck(Context context) {
        rescheduleAll(context);
    }

    public static void rescheduleAll(Context context) {
        rescheduleAll(context, new DeadlineStore(context).activeItems());
    }

    public static void rescheduleAll(Context context, List<DeadlineItem> items) {
        cancelScheduledAlarms(context);
        if (!AppSettings.notificationsEnabled(context)) {
            return;
        }
        ensureChannel(context);
        long now = System.currentTimeMillis();
        long lead = TimeUnit.HOURS.toMillis(AppSettings.notifyHours(context));
        HashSet<String> scheduled = new HashSet<>();
        for (DeadlineItem item : items) {
            if (item == null || item.id == null || item.id.isEmpty()) {
                continue;
            }
            if (!AppSettings.shouldNotifyType(context, item.type) || item.submitted || item.dueAt <= now) {
                continue;
            }
            long triggerAt = item.dueAt - lead;
            if (triggerAt <= now + TimeUnit.SECONDS.toMillis(15)) {
                maybeNotify(context, item);
                continue;
            }
            String key = alarmKey(context, item);
            scheduleAlarm(context, key, triggerAt);
            scheduled.add(key);
        }
        prefs(context).edit().putStringSet(KEY_ALARMS, scheduled).apply();
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
        } else {
            alarm.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pending);
        }
    }

    private static PendingIntent pendingIntent(Context context, String key, int flags) {
        Intent intent = new Intent(context, DeadlineReceiver.class)
                .setAction(DeadlineReceiver.ACTION_NOTIFY)
                .putExtra(EXTRA_DEADLINE_ID, idFromAlarmKey(key));
        BridgeAuth.attach(context, intent);
        return PendingIntent.getBroadcast(
                context,
                key.hashCode(),
                intent,
                flags | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void notifyNow(Context context, DeadlineItem item) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        SharedPreferences prefs = prefs(context);
        String key = "sent_" + item.id + "_" + item.dueAt + "_" + AppSettings.notifyHours(context);
        if (prefs.getBoolean(key, false)) {
            return;
        }
        ensureChannel(context);
        Intent launch = new Intent(context, MainActivity.class);
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                item.id.hashCode(),
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification notification = new android.app.Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(item.type + "\u5feb\u622a\u6b62\u4e86")
                .setContentText(item.title + "\uff0c" + DateText.dueLine(item.dueAt))
                .setStyle(new android.app.Notification.BigTextStyle()
                        .bigText((item.course == null || item.course.isEmpty() ? "" : item.course + "\n")
                                + item.title + "\n" + DateText.dueLine(item.dueAt)))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(item.id.hashCode(), notification);
            prefs.edit().putBoolean(key, true).apply();
        }
    }

    private static String alarmKey(Context context, DeadlineItem item) {
        return item.id + "|" + item.dueAt + "|" + AppSettings.notifyHours(context);
    }

    private static String idFromAlarmKey(String key) {
        int split = key == null ? -1 : key.indexOf('|');
        return split > 0 ? key.substring(0, split) : key;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
