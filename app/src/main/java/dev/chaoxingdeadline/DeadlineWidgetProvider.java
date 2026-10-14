package dev.chaoxingdeadline;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.List;

/**
 * Home screen widgets showing pending homework/exam deadlines.
 * Widgets run in the module's own process and read DeadlineStore directly,
 * so no extra Xposed scope or permission is required. Refresh happens on
 * module data changes (DeadlineReceiver), boot, 30-minute period fallback
 * and configuration (dark mode) changes.
 */
public abstract class DeadlineWidgetProvider extends AppWidgetProvider {
    static final long URGENT_MS = 12L * 60L * 60L * 1000L;
    private static final String CHAOXING_PACKAGE = "com.chaoxing.mobile";
    private static final int MAX_ROWS = 10;

    // Design tokens following the miuix (HyperOS-style) color scheme.
    private static final int ACCENT_LIGHT = 0xFF3482FF;
    private static final int ACCENT_DARK = 0xFF4788FF;
    private static final int TEXT_LIGHT = 0xE6000000;
    private static final int TEXT_DARK = 0xE6FFFFFF;
    private static final int MUTED_LIGHT = 0x99000000;
    private static final int MUTED_DARK = 0xFF787E96;
    private static final int DANGER_LIGHT = 0xFFE94634;
    private static final int DANGER_DARK = 0xFFF12522;

    static int accent(Context context) {
        return UiTheme.dark(context) ? ACCENT_DARK : ACCENT_LIGHT;
    }

    static int primaryText(Context context) {
        return UiTheme.dark(context) ? TEXT_DARK : TEXT_LIGHT;
    }

    static int mutedText(Context context) {
        return UiTheme.dark(context) ? MUTED_DARK : MUTED_LIGHT;
    }

    static int dangerText(Context context) {
        return UiTheme.dark(context) ? DANGER_DARK : DANGER_LIGHT;
    }

    /** Chapter task points use a neutral/teal accent so they read apart from homework. */
    static int chapterText(Context context) {
        return UiTheme.dark(context) ? 0xFF4FD1C5 : 0xFF0E9F8E;
    }

    /** Classroom activities use a violet accent. */
    static int activityText(Context context) {
        return UiTheme.dark(context) ? 0xFFA78BFA : 0xFF7C3AED;
    }

    /** Badge colour for a given deadline type. */
    static int typeColor(Context context, String type) {
        if ("\u8003\u8bd5".equals(type)) {
            return dangerText(context);
        }
        if ("\u7ae0\u8282".equals(type)) {
            return chapterText(context);
        }
        if ("\u8bfe\u5802\u6d3b\u52a8".equals(type)) {
            return activityText(context);
        }
        return accent(context);
    }

    /** Push fresh data into every widget instance of both sizes. */
    public static void updateAll(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            List<DeadlineItem> items = pendingItems(context);
            updateIds(context, manager, DeadlineWidgetCompact.class,
                    DeadlineWidgetCompact.build(context, items));
            updateIds(context, manager, DeadlineWidgetList.class,
                    DeadlineWidgetList.build(context, items));
        } catch (Throwable ignored) {
        }
    }

    private static void updateIds(Context context, AppWidgetManager manager,
                                  Class<? extends DeadlineWidgetProvider> provider, RemoteViews views) {
        try {
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, provider));
            if (ids == null || ids.length == 0) {
                return;
            }
            manager.updateAppWidget(ids, views);
        } catch (Throwable ignored) {
        }
    }

    /** Pending items: not submitted, not due yet, not blocked, inside the widget window. */
    static List<DeadlineItem> pendingItems(Context context) {
        ArrayList<DeadlineItem> pending = new ArrayList<>();
        try {
            long now = System.currentTimeMillis();
            int windowHours = AppSettings.widgetWindowHours(context);
            long horizon = windowHours == AppSettings.OVERLAY_WINDOW_ALL
                    ? Long.MAX_VALUE
                    : now + windowHours * 60L * 60L * 1000L;
            List<DeadlineItem> items = new DeadlineStore(context).activeItems();
            String courseFilter = AppSettings.widgetCourseFilter(context);
            for (DeadlineItem item : items) {
                if (item == null || item.submitted || item.dueAt <= now || item.dueAt > horizon
                        || item.vanishedAt > 0L) {
                    continue;
                }
                if (!passesCourseFilter(item, courseFilter)) {
                    continue;
                }
                pending.add(item);
            }
        } catch (Throwable ignored) {
        }
        return pending;
    }

    /**
     * Widget course filter. An empty filter keeps everything; a non-empty one matches the
     * course name exactly. Items with no course name drop out of a filtered widget — they
     * are the "which course was this?" leftovers, not something the user picked.
     */
    static boolean passesCourseFilter(DeadlineItem item, String filter) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        String course = item.course == null ? "" : item.course;
        return filter.equals(course);
    }

    /** Manual refresh button: asks the module process to re-render every widget. */
    static PendingIntent refreshPendingIntent(Context context) {
        Intent intent = new Intent(context, DeadlineReceiver.class)
                .setAction(DeadlineReceiver.ACTION_WIDGET_REFRESH)
                .setPackage(context.getPackageName());
        BridgeAuth.attach(context, intent);
        return PendingIntent.getBroadcast(
                context,
                "widget_refresh".hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent mainPendingIntent(Context context) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(context, MainActivity.class));
        return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Open the stored Chaoxing page for an item when the host app can handle it. */
    static PendingIntent itemPendingIntent(Context context, DeadlineItem item, int requestCode) {
        Intent intent = null;
        if (item.url != null && item.url.startsWith("http")) {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(item.url));
            view.setPackage(CHAOXING_PACKAGE);
            try {
                if (context.getPackageManager().resolveActivity(view, 0) != null) {
                    intent = view;
                }
            } catch (Throwable ignored) {
            }
        }
        if (intent == null) {
            return mainPendingIntent(context);
        }
        return PendingIntent.getActivity(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static boolean urgent(DeadlineItem item) {
        return item.dueAt - System.currentTimeMillis() <= URGENT_MS;
    }

    /** Two-line right column: absolute deadline over remaining time. */
    static String dueLine(DeadlineItem item) {
        String time = DateText.deadlineTime(item.dueAt);
        long hours = Math.max(1L, (item.dueAt - System.currentTimeMillis()) / (60L * 60L * 1000L));
        long days = hours / 24L;
        return time + "\n剩 " + (days > 0 ? days + "天" + (hours % 24) + "小时" : hours + "小时");
    }

    static int maxRows() {
        return MAX_ROWS;
    }
}
