package dev.chaoxingdeadline;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Weekly-timetable math, pure and Android-free so it can be unit tested.
 *
 * The timetable is a set of recurring weekly slots; real timestamps come from the
 * curriculum-level {@code firstWeekDate} (Monday 00:00 of week 1) plus the school's
 * section-time table. When the school published no section table, a 45-minute section
 * with 10-minute breaks starting at 08:00 is assumed.
 */
public final class ScheduleMath {
    /** Fallback section table: 45 min each, 10 min breaks, first section at 08:00. */
    private static final int DEFAULT_SECTION_MINUTES = 45;
    private static final int DEFAULT_BREAK_MINUTES = 10;
    private static final int DEFAULT_FIRST_START = 8 * 60;

    private ScheduleMath() {
    }

    /** 1 = Monday … 7 = Sunday, matching the timetable's dayOfWeek. */
    public static int todayDayOfWeek(long now) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(now);
        int sundayFirst = calendar.get(Calendar.DAY_OF_WEEK);
        return sundayFirst == Calendar.SUNDAY ? 7 : sundayFirst - 1;
    }

    /** 1-based teaching week; 0 when {@code now} is before the semester started. */
    public static int currentWeek(long now, long firstWeekDate) {
        if (firstWeekDate <= 0L) {
            return 0;
        }
        long delta = now - firstWeekDate;
        if (delta < 0L) {
            return 0;
        }
        return (int) (delta / (7L * 24L * 60L * 60L * 1000L)) + 1;
    }

    /**
     * Whether {@code week} is listed by a weeks string. Accepts "3", "1-16",
     * "1-8,10-16", "1，3，5" and stray suffixes like "1-16周".
     */
    public static boolean weeksContains(String weeks, int week) {
        if (weeks == null || weeks.trim().isEmpty() || week <= 0) {
            return false;
        }
        String[] tokens = weeks.split("[,;，；]");
        for (String token : tokens) {
            String cleaned = digitsAndDash(token);
            if (cleaned.isEmpty()) {
                continue;
            }
            int dash = cleaned.indexOf('-');
            if (dash < 0) {
                if (parseIntOrZero(cleaned) == week) {
                    return true;
                }
                continue;
            }
            int from = parseIntOrZero(cleaned.substring(0, dash));
            int to = parseIntOrZero(cleaned.substring(dash + 1));
            if (from > 0 && to >= from && week >= from && week <= to) {
                return true;
            }
        }
        return false;
    }

    /** Section start as minutes since midnight; falls back to the default table. */
    public static int sectionStartMinutes(String sectionTimesJson, int section) {
        int[] bounds = sectionBounds(sectionTimesJson, section);
        return bounds[0];
    }

    /** Section end as minutes since midnight (exclusive-ish: the bell time). */
    public static int sectionEndMinutes(String sectionTimesJson, int section) {
        int[] bounds = sectionBounds(sectionTimesJson, section);
        return bounds[1];
    }

    public static long lessonStartAt(long firstWeekDate, int week, int dayOfWeek,
                                     int beginSection, String sectionTimesJson) {
        if (firstWeekDate <= 0L || week <= 0) {
            return 0L;
        }
        return atSection(firstWeekDate, week, dayOfWeek,
                sectionStartMinutes(sectionTimesJson, beginSection));
    }

    public static long lessonEndAt(long firstWeekDate, int week, int dayOfWeek,
                                   int beginSection, int sections, String sectionTimesJson) {
        if (firstWeekDate <= 0L || week <= 0) {
            return 0L;
        }
        int last = beginSection + Math.max(1, sections) - 1;
        return atSection(firstWeekDate, week, dayOfWeek,
                sectionEndMinutes(sectionTimesJson, last));
    }

    public static boolean isActiveInWeek(Lesson lesson, int week) {
        if (lesson == null || week <= 0) {
            return false;
        }
        // An empty weeks string means "every week" (some schools omit it).
        if (lesson.weeks == null || lesson.weeks.trim().isEmpty()) {
            return true;
        }
        return weeksContains(lesson.weeks, week);
    }

    /** Today's lessons, earliest section first; empty when nothing is scheduled. */
    public static List<Lesson> todayLessons(List<Lesson> lessons, long now, long firstWeekDate) {
        int week = currentWeek(now, firstWeekDate);
        int day = todayDayOfWeek(now);
        ArrayList<Lesson> result = new ArrayList<>();
        if (lessons == null || week <= 0) {
            return result;
        }
        for (Lesson lesson : lessons) {
            if (lesson.dayOfWeek == day && isActiveInWeek(lesson, week)) {
                result.add(lesson);
            }
        }
        Collections.sort(result, Comparator.comparingInt(l -> l.beginSection));
        return result;
    }

    /** The lesson in session right now, or null. */
    public static Lesson currentLesson(List<Lesson> lessons, long now, long firstWeekDate,
                                       String sectionTimesJson) {
        for (Lesson lesson : todayLessons(lessons, now, firstWeekDate)) {
            long start = lessonStartAt(firstWeekDate, currentWeek(now, firstWeekDate),
                    lesson.dayOfWeek, lesson.beginSection, sectionTimesJson);
            long end = lessonEndAt(firstWeekDate, currentWeek(now, firstWeekDate),
                    lesson.dayOfWeek, lesson.beginSection, lesson.sections, sectionTimesJson);
            if (start > 0L && now >= start && now < end) {
                return lesson;
            }
        }
        return null;
    }

    public static String formatSections(int beginSection, int sections) {
        int count = Math.max(1, sections);
        if (count == 1) {
            return "第 " + beginSection + " 节";
        }
        return "第 " + beginSection + "-" + (beginSection + count - 1) + " 节";
    }

    public static String formatMinutes(int minutes) {
        return String.format(Locale.CHINA, "%02d:%02d", minutes / 60, minutes % 60);
    }

    // -- internals --

    private static long atSection(long firstWeekDate, int week, int dayOfWeek, int minutes) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(firstWeekDate);
        calendar.add(Calendar.DAY_OF_MONTH, (week - 1) * 7 + (dayOfWeek - 1));
        calendar.set(Calendar.HOUR_OF_DAY, minutes / 60);
        calendar.set(Calendar.MINUTE, minutes % 60);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    /** @return {startMinutes, endMinutes}; index i of the table is section i+1. */
    private static int[] sectionBounds(String sectionTimesJson, int section) {
        int fallbackStart = DEFAULT_FIRST_START + (section - 1) * (DEFAULT_SECTION_MINUTES + DEFAULT_BREAK_MINUTES);
        int fallbackEnd = fallbackStart + DEFAULT_SECTION_MINUTES;
        if (sectionTimesJson == null || sectionTimesJson.trim().isEmpty()) {
            return new int[]{fallbackStart, fallbackEnd};
        }
        try {
            JSONArray array = new JSONArray(sectionTimesJson);
            if (array.length() == 0) {
                return new int[]{fallbackStart, fallbackEnd};
            }
            // Two shapes exist: ["8:00-8:45", …] and [["8:00","8:45"], …].
            JSONArray entry = array.optJSONArray(section - 1);
            if (entry != null && entry.length() >= 2) {
                int start = parseClock(entry.optString(0, ""));
                int end = parseClock(entry.optString(1, ""));
                if (start >= 0 && end >= 0) {
                    return new int[]{start, end};
                }
            } else {
                int[] bounds = parseRange(String.valueOf(array.opt(section - 1)));
                if (bounds != null) {
                    return bounds;
                }
            }
        } catch (Throwable ignored) {
        }
        return new int[]{fallbackStart, fallbackEnd};
    }

    /** Parses "8:00-8:45", "8:00~8:45", "08时00分-08时45分"-ish into minutes. */
    static int[] parseRange(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.replace('—', '-').replace('~', '-').replace('～', '-');
        int dash = cleaned.indexOf('-');
        if (dash < 0) {
            return null;
        }
        int start = parseClock(cleaned.substring(0, dash));
        int end = parseClock(cleaned.substring(dash + 1));
        if (start < 0 || end < 0) {
            return null;
        }
        return new int[]{start, end};
    }

    private static int parseClock(String text) {
        String digits = text.replaceAll("[^0-9:]", "");
        int colon = digits.indexOf(':');
        if (colon < 0) {
            return -1;
        }
        try {
            int hour = Integer.parseInt(digits.substring(0, colon));
            int minute = Integer.parseInt(digits.substring(colon + 1));
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return -1;
            }
            return hour * 60 + minute;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String digitsAndDash(String text) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '0' && c <= '9') || c == '-') {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private static int parseIntOrZero(String text) {
        try {
            return Integer.parseInt(text);
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
