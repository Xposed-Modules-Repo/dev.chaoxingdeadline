package dev.chaoxingdeadline;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.util.List;

/**
 * "今日课程" home-screen widget: today's timetable with the in-session class marked.
 *
 * Data comes from the module's own lessons table (captured by the hook from Chaoxing's
 * timetable), so no extra Xposed scope or permission is needed. Rows are static
 * RemoteViews — no inner scrolling, per the HyperOS widget guideline.
 */
public final class DeadlineScheduleWidget extends AppWidgetProvider {
    private static final int MAX_ROWS = 6;

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        updateAll(context);
    }

    static void updateAll(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(
                    new ComponentName(context, DeadlineScheduleWidget.class));
            if (ids == null || ids.length == 0) {
                return;
            }
            manager.updateAppWidget(ids, build(context));
        } catch (Throwable ignored) {
        }
    }

    private static RemoteViews build(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_schedule);
        long now = System.currentTimeMillis();
        DeadlineStore store = new DeadlineStore(context);
        long firstWeekDate = store.scheduleFirstWeekDate();
        String sectionTimes = store.scheduleSectionTimes();
        int week = ScheduleMath.currentWeek(now, firstWeekDate);
        List<Lesson> today = ScheduleMath.todayLessons(store.allLessons(), now, firstWeekDate);
        Lesson current = ScheduleMath.currentLesson(today, now, firstWeekDate, sectionTimes);

        views.setTextViewText(R.id.s_header,
                week > 0 ? "第 " + week + " 周 · 今天 " + today.size() + " 节课"
                        : "课表未获取");
        views.setTextColor(R.id.s_header, DeadlineWidgetProvider.primaryText(context));

        long updatedAt = store.scheduleUpdatedAt();
        if (updatedAt > 0L) {
            views.setTextViewText(R.id.s_updated, "更新于 " + DateText.deadlineTime(updatedAt));
            views.setViewVisibility(R.id.s_updated, android.view.View.VISIBLE);
        } else {
            views.setViewVisibility(R.id.s_updated, android.view.View.GONE);
        }
        views.setTextColor(R.id.s_updated, DeadlineWidgetProvider.mutedText(context));

        views.removeAllViews(R.id.s_rows);
        if (today.isEmpty()) {
            views.setViewVisibility(R.id.s_rows, android.view.View.GONE);
            views.setViewVisibility(R.id.s_empty, android.view.View.VISIBLE);
            views.setTextColor(R.id.s_empty, DeadlineWidgetProvider.mutedText(context));
        } else {
            views.setViewVisibility(R.id.s_rows, android.view.View.VISIBLE);
            views.setViewVisibility(R.id.s_empty, android.view.View.GONE);
            int shown = Math.min(today.size(), MAX_ROWS);
            for (int i = 0; i < shown; i++) {
                views.addView(R.id.s_rows,
                        row(context, today.get(i), current, now, firstWeekDate, sectionTimes, i));
            }
        }

        views.setOnClickPendingIntent(R.id.widget_schedule_root, mainPendingIntent(context));
        views.setTextColor(R.id.s_refresh, DeadlineWidgetProvider.mutedText(context));
        views.setOnClickPendingIntent(R.id.s_refresh, DeadlineWidgetProvider.refreshPendingIntent(context));
        return views;
    }

    private static RemoteViews row(Context context, Lesson lesson, Lesson current, long now,
                                   long firstWeekDate, String sectionTimes, int index) {
        RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_schedule_row);
        int week = ScheduleMath.currentWeek(now, firstWeekDate);
        long start = ScheduleMath.lessonStartAt(firstWeekDate, week, lesson.dayOfWeek,
                lesson.beginSection, sectionTimes);
        long end = ScheduleMath.lessonEndAt(firstWeekDate, week, lesson.dayOfWeek,
                lesson.beginSection, lesson.sections, sectionTimes);
        boolean isCurrent = current != null && current.id != null && current.id.equals(lesson.id);
        boolean isPast = end > 0L && now >= end;

        String range = (start > 0L ? DateText.clock(start) : "--:--")
                + "-" + (end > 0L ? DateText.clock(end) : "--:--");
        row.setTextViewText(R.id.r_time, range + "\n" + ScheduleMath.formatSections(
                lesson.beginSection, lesson.sections));
        int timeColor = isCurrent ? DeadlineWidgetProvider.accent(context)
                : (isPast ? DeadlineWidgetProvider.mutedText(context)
                : DeadlineWidgetProvider.primaryText(context));
        row.setTextColor(R.id.r_time, timeColor);

        row.setTextViewText(R.id.r_name, lesson.name == null || lesson.name.isEmpty()
                ? "未命名课程" : lesson.name);
        row.setTextColor(R.id.r_name, isPast
                ? DeadlineWidgetProvider.mutedText(context)
                : DeadlineWidgetProvider.primaryText(context));

        StringBuilder meta = new StringBuilder();
        String label = lesson.label();
        if (!label.isEmpty()) {
            meta.append(label);
        }
        if (isCurrent) {
            if (meta.length() > 0) {
                meta.append(" · ");
            }
            meta.append("上课中");
        }
        row.setTextViewText(R.id.r_meta, meta.toString());
        row.setTextColor(R.id.r_meta, isCurrent
                ? DeadlineWidgetProvider.accent(context)
                : DeadlineWidgetProvider.mutedText(context));

        row.setOnClickPendingIntent(R.id.row_root, DeadlineWidgetProvider.mainPendingIntent(context));
        return row;
    }

    private static PendingIntent mainPendingIntent(Context context) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(context, ScheduleActivity.class));
        return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static int maxRows() {
        return MAX_ROWS;
    }
}
