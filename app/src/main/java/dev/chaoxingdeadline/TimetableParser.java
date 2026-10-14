package dev.chaoxingdeadline;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parser for Chaoxing timetable payloads.
 *
 * The documented endpoint is {@code POST kb.chaoxing.com/pc/curriculum/getMyLessons}
 * (form body {@code week=N}; without it only the current week is returned), whose data
 * carries {@code lessonArray} plus {@code curriculum.firstWeekDate} and
 * {@code curriculum.lessonTimeConfigArray}. The mobile app itself may hit a different
 * URL for the same page, so recognition is by payload shape — any object holding
 * {@code dayOfWeek} + {@code beginNumber} + a course name counts — which also makes
 * passively intercepted traffic parse for free.
 */
public final class TimetableParser {
    private static final String[] NAME_KEYS = {"name", "courseName", "coursename", "title"};
    private static final String[] TEACHER_KEYS = {"teacherName", "teacher", "teachName"};
    private static final String[] LOCATION_KEYS = {"location", "classRoom", "room", "address"};
    private static final String[] WEEKS_KEYS = {"weeks", "weekList", "week"};

    private TimetableParser() {
    }

    /** Cheap shape gate so the hot intercept path never pays for a JSON walk. */
    public static boolean looksLikeTimetable(String text) {
        if (text == null || text.length() < 20 || text.length() > 2_000_000) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("lessonarray") || lower.contains("lessonlist")) {
            return true;
        }
        // The PC endpoint spells the section start beginNumber; the mobile app's own
        // variant uses beginSection/startSection, so any of them opens the gate.
        return lower.contains("dayofweek")
                && (lower.contains("beginnumber") || lower.contains("beginsection")
                || lower.contains("startsection") || lower.contains("section"));
    }

    public static final class Result {
        public final List<Lesson> lessons;
        /** Monday 00:00 of week 1, epoch millis; 0 when the payload had none. */
        public final long firstWeekDate;
        /** School section-time table as raw JSON text, e.g. ["8:00-8:45", …]. */
        public final String sectionTimesJson;

        Result(List<Lesson> lessons, long firstWeekDate, String sectionTimesJson) {
            this.lessons = lessons;
            this.firstWeekDate = firstWeekDate;
            this.sectionTimesJson = sectionTimesJson;
        }
    }

    public static Result parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            String trimmed = text.trim();
            JSONObject root = trimmed.startsWith("{") ? new JSONObject(trimmed) : null;
            if (root == null) {
                return null;
            }
        JSONObject data = root.optJSONObject("data");
        JSONObject source = data != null ? data : root;
        JSONArray array = firstArray(source, "lessonArray", "lessons", "lessonList",
                "courseList", "kbList", "myLessons", "list");
        if (array == null) {
            return null;
        }
            ArrayList<Lesson> lessons = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json == null) {
                    continue;
                }
                Lesson lesson = readLesson(json);
                if (lesson != null) {
                    lessons.add(lesson);
                }
            }
            long firstWeekDate = 0L;
            String sectionTimesJson = "";
            JSONObject curriculum = source.optJSONObject("curriculum");
            if (curriculum == null) {
                curriculum = source;
            }
            if (curriculum.has("firstWeekDate")) {
                firstWeekDate = parseTimestamp(curriculum.opt("firstWeekDate"));
            }
            JSONArray times = curriculum.optJSONArray("lessonTimeConfigArray");
            if (times == null) {
                times = curriculum.optJSONArray("lessonTimeConfig");
            }
            if (times != null && times.length() > 0) {
                sectionTimesJson = times.toString();
            }
            if (lessons.isEmpty()) {
                return null;
            }
            return new Result(lessons, firstWeekDate, sectionTimesJson);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Lesson readLesson(JSONObject json) {
        int dayOfWeek = firstInt(json, "dayOfWeek", "dayofweek", "day_of_week", "weekDay");
        int beginSection = firstInt(json, "beginNumber", "beginnumber", "beginSection",
                "begin_section", "startSection", "section");
        if (dayOfWeek <= 0 || dayOfWeek > 7 || beginSection <= 0) {
            return null;
        }
        String name = firstString(json, NAME_KEYS);
        if (name.isEmpty()) {
            return null;
        }
        int sections = firstInt(json, "length", "sectionLength", "sections", "sectionCount");
        if (sections <= 0) {
            sections = 1;
        }
        String teacher = firstString(json, TEACHER_KEYS);
        String location = firstString(json, LOCATION_KEYS);
        String weeks = firstString(json, WEEKS_KEYS);
        String id = firstString(json, "lessonId", "lessonid", "id");
        Lesson lesson = new Lesson(id, name, teacher, location, dayOfWeek, beginSection, sections, weeks);
        if (lesson.id.isEmpty()) {
            lesson.id = lesson.stableId();
        }
        return lesson;
    }

    private static long parseTimestamp(Object value) {
        if (value instanceof Number) {
            long number = ((Number) value).longValue();
            if (number > 0 && number < 10_000_000_000L) {
                number *= 1000L;
            }
            return number;
        }
        String text = String.valueOf(value).trim();
        if (text.matches("\\d{10,13}")) {
            long number = Long.parseLong(text);
            return text.length() == 10 ? number * 1000L : number;
        }
        // Some payloads spell firstWeekDate as a plain date ("2026-09-01").
        if (text.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
            try {
                java.text.SimpleDateFormat format =
                        new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
                format.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
                return format.parse(text).getTime();
            } catch (Throwable ignored) {
            }
        }
        return 0L;
    }

    private static JSONArray firstArray(JSONObject json, String... keys) {
        for (String key : keys) {
            JSONArray array = json.optJSONArray(key);
            if (array != null) {
                return array;
            }
        }
        return null;
    }

    private static int firstInt(JSONObject json, String... keys) {
        for (String key : keys) {
            if (json.has(key)) {
                int value = json.optInt(key, 0);
                if (value > 0) {
                    return value;
                }
            }
        }
        return 0;
    }

    private static String firstString(JSONObject json, String... keys) {
        for (String key : keys) {
            String value = json.optString(key, "");
            if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) {
                return value.trim();
            }
        }
        return "";
    }
}
