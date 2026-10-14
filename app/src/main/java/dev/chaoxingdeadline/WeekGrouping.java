package dev.chaoxingdeadline;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Groups upcoming deadlines by calendar day for the "本周视图" screen. Pure logic (JDK
 * Calendar only) so it runs in plain unit tests without Android.
 *
 * Only still-live items inside the next {@link #HORIZON_DAYS} days are kept: submitted,
 * revoked and past-due rows are dropped, matching what the list view shows at the top.
 */
public final class WeekGrouping {
    /** How many days ahead the week view looks. */
    public static final int HORIZON_DAYS = 7;

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final String[] WEEK_LABELS = {
            "周日", "周一", "周二", "周三", "周四", "周五", "周六"
    };

    private WeekGrouping() {
    }

    /** One calendar day and the deadlines that fall on it. */
    public static final class DayGroup {
        public final long dayStart;
        public final String label;
        public final List<DeadlineItem> items;

        DayGroup(long dayStart, String label, List<DeadlineItem> items) {
            this.dayStart = dayStart;
            this.label = label;
            this.items = items;
        }
    }

    /**
     * Group the given items by day. The caller passes its already filtered list
     * (search + type filter); this only applies the live/upcoming/7-day gates and sorting.
     */
    public static List<DayGroup> group(List<DeadlineItem> items, long now) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        long horizon = now + HORIZON_DAYS * DAY_MS;
        LinkedHashMap<Long, List<DeadlineItem>> byDay = new LinkedHashMap<>();
        for (DeadlineItem item : items) {
            if (item == null || item.submitted || item.vanishedAt > 0L
                    || item.dueAt <= now || item.dueAt > horizon) {
                continue;
            }
            long dayStart = dayStart(item.dueAt);
            List<DeadlineItem> bucket = byDay.get(dayStart);
            if (bucket == null) {
                bucket = new ArrayList<>();
                byDay.put(dayStart, bucket);
            }
            bucket.add(item);
        }
        ArrayList<DayGroup> groups = new ArrayList<>();
        for (Map.Entry<Long, List<DeadlineItem>> entry : byDay.entrySet()) {
            List<DeadlineItem> bucket = entry.getValue();
            Collections.sort(bucket, (a, b) -> Long.compare(a.dueAt, b.dueAt));
            groups.add(new DayGroup(entry.getKey(), dayLabel(entry.getKey(), now), bucket));
        }
        Collections.sort(groups, (a, b) -> Long.compare(a.dayStart, b.dayStart));
        return groups;
    }

    /** Local midnight of the day {@code millis} falls on. */
    public static long dayStart(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    /** 今天 / 明天 / 后天, otherwise "M月D日 周X". */
    public static String dayLabel(long dayStart, long now) {
        long today = dayStart(now);
        long diffDays = Math.round((dayStart - today) / (double) DAY_MS);
        if (diffDays == 0L) {
            return "今天";
        }
        if (diffDays == 1L) {
            return "明天";
        }
        if (diffDays == 2L) {
            return "后天";
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(dayStart);
        int weekday = calendar.get(Calendar.DAY_OF_WEEK) - 1;
        String week = weekday >= 0 && weekday < WEEK_LABELS.length ? WEEK_LABELS[weekday] : "";
        return (calendar.get(Calendar.MONTH) + 1) + "月" + calendar.get(Calendar.DAY_OF_MONTH)
                + "日 " + week;
    }

    /** "剩余 3 小时 20 分" style countdown, shared with the week view header. */
    public static String countdown(long dueAt, long now) {
        long millis = dueAt - now;
        if (millis <= 0L) {
            return "已截止";
        }
        long minutes = millis / 60_000L;
        if (minutes < 60L) {
            return "剩余 " + minutes + " 分钟";
        }
        long hours = minutes / 60L;
        if (hours < 24L) {
            return "剩余 " + hours + " 小时 " + (minutes % 60L) + " 分";
        }
        long days = hours / 24L;
        return "剩余 " + days + " 天 " + (hours % 24L) + " 小时";
    }

    static String weekLabelForTest(int dayOfWeek) {
        int index = dayOfWeek - 1;
        return index >= 0 && index < WEEK_LABELS.length ? WEEK_LABELS[index] : "";
    }

    /** Debug helper kept tiny; Locale import kept for label formatting stability. */
    static String localeTag() {
        return Locale.getDefault().toLanguageTag();
    }
}
