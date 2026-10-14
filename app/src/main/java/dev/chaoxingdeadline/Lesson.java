package dev.chaoxingdeadline;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One weekly course slot from Chaoxing's timetable (课表).
 *
 * A lesson is a recurring weekly event: it happens on {@link #dayOfWeek} at section
 * {@link #beginSection} for {@link #sections} sections, in the weeks listed by
 * {@link #weeks}. Real timestamps come from the curriculum-level firstWeekDate plus the
 * school's section-time table (see ScheduleMath), never from guesses.
 */
public final class Lesson {
    public String id;
    public String name;
    public String teacher;
    public String location;
    /** 1 = Monday … 7 = Sunday, matching the API's dayOfWeek. */
    public int dayOfWeek;
    public int beginSection;
    public int sections = 1;
    /** Week list as served, e.g. "1-16", "1,3,5" or "3". */
    public String weeks;

    public Lesson() {
    }

    public Lesson(String id, String name, String teacher, String location,
                  int dayOfWeek, int beginSection, int sections, String weeks) {
        this.id = id;
        this.name = name;
        this.teacher = teacher;
        this.location = location;
        this.dayOfWeek = dayOfWeek;
        this.beginSection = beginSection;
        this.sections = sections <= 0 ? 1 : sections;
        this.weeks = weeks;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("teacher", teacher);
        json.put("location", location);
        json.put("dayOfWeek", dayOfWeek);
        json.put("beginSection", beginSection);
        json.put("sections", sections);
        json.put("weeks", weeks);
        return json;
    }

    public static Lesson fromJson(JSONObject json) {
        Lesson lesson = new Lesson();
        lesson.id = json.optString("id", "");
        lesson.name = json.optString("name", "");
        lesson.teacher = json.optString("teacher", "");
        lesson.location = json.optString("location", "");
        lesson.dayOfWeek = json.optInt("dayOfWeek", 0);
        lesson.beginSection = json.optInt("beginSection", 0);
        lesson.sections = json.optInt("sections", 1);
        lesson.weeks = json.optString("weeks", "");
        return lesson;
    }

    /** Stable identity: the API's lessonId when present, else the slot's shape. */
    public String stableId() {
        if (id != null && !id.isEmpty()) {
            return id;
        }
        String basis = name + "|" + teacher + "|" + location + "|" + dayOfWeek
                + "|" + beginSection + "|" + sections;
        return "lesson_" + Integer.toHexString(basis.toLowerCase(java.util.Locale.ROOT).hashCode());
    }

    public String label() {
        StringBuilder builder = new StringBuilder();
        if (location != null && !location.isEmpty()) {
            builder.append(location);
        }
        if (teacher != null && !teacher.isEmpty()) {
            if (builder.length() > 0) {
                builder.append(" · ");
            }
            builder.append(teacher);
        }
        return builder.toString();
    }
}
