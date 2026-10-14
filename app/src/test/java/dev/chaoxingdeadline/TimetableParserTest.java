package dev.chaoxingdeadline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

/** Timetable payload recognition and field extraction (org.json is on the test classpath). */
public class TimetableParserTest {

    @Test
    public void shapeGateIsCheapAndSpecific() {
        assertTrue(TimetableParser.looksLikeTimetable(
                "{\"data\":{\"lessonArray\":[{\"name\":\"高等数学\"}]}}"));
        assertTrue(TimetableParser.looksLikeTimetable(
                "{\"list\":[{\"dayOfWeek\":1,\"beginNumber\":1}]}"));
        // The mobile app's variant spells the section start beginSection, not beginNumber.
        assertTrue(TimetableParser.looksLikeTimetable(
                "{\"data\":{\"lessonList\":[{\"dayOfWeek\":1,\"beginSection\":1}]}}"));
        assertFalse(TimetableParser.looksLikeTimetable("{\"deadline\":\"2026-10-05 23:59\"}"));
        assertFalse(TimetableParser.looksLikeTimetable(null));
        assertFalse(TimetableParser.looksLikeTimetable("短"));
    }

    @Test
    public void parsesMobileShapeWithLessonListKey() {
        String json = "{\"data\":{\"lessonList\":["
                + "{\"id\":\"M1\",\"courseName\":\"信号与系统\",\"teacherName\":\"周七\","
                + "\"classRoom\":\"教五505\",\"dayOfWeek\":3,\"beginSection\":5,"
                + "\"sectionLength\":3,\"weeks\":\"1-16\"}],"
                + "\"curriculum\":{\"firstWeekDate\":\"2026-09-07\"}}}";
        TimetableParser.Result result = TimetableParser.parse(json);
        assertNotNull(result);
        assertEquals(1, result.lessons.size());
        Lesson lesson = result.lessons.get(0);
        assertEquals("信号与系统", lesson.name);
        assertEquals("周七", lesson.teacher);
        assertEquals("教五505", lesson.location);
        assertEquals(3, lesson.dayOfWeek);
        assertEquals(5, lesson.beginSection);
        assertEquals(3, lesson.sections);
        // "2026-09-07" as a plain date must still anchor the week maths.
        assertTrue("firstWeekDate parsed from ISO date", result.firstWeekDate > 0L);
    }

    @Test
    public void parsesDocumentedGetMyLessonsPayload() {
        String json = "{\"data\":{\"lessonArray\":["
                + "{\"lessonId\":\"L1\",\"name\":\"高等数学\",\"teacherName\":\"张三\","
                + "\"location\":\"教一101\",\"dayOfWeek\":1,\"beginNumber\":1,\"length\":2,\"weeks\":\"1-16\"},"
                + "{\"lessonId\":\"L2\",\"name\":\"大学英语\",\"teacherName\":\"李四\","
                + "\"location\":\"教二202\",\"dayOfWeek\":3,\"beginNumber\":3,\"length\":2,\"weeks\":\"1-8,10-16\"}"
                + "],\"curriculum\":{\"firstWeekDate\":1789948800000,"
                + "\"lessonTimeConfigArray\":[\"8:00-8:45\",\"8:55-9:40\",\"10:00-10:45\",\"10:55-11:40\"]}}}";
        TimetableParser.Result result = TimetableParser.parse(json);
        assertNotNull(result);
        assertEquals(2, result.lessons.size());
        Lesson first = result.lessons.get(0);
        assertEquals("L1", first.id);
        assertEquals("高等数学", first.name);
        assertEquals("张三", first.teacher);
        assertEquals("教一101", first.location);
        assertEquals(1, first.dayOfWeek);
        assertEquals(1, first.beginSection);
        assertEquals(2, first.sections);
        assertEquals("1-16", first.weeks);
        assertTrue("firstWeekDate carried through", result.firstWeekDate > 0L);
        assertNotNull(result.sectionTimesJson);
        assertTrue(result.sectionTimesJson.contains("8:00-8:45"));
    }

    @Test
    public void toleratesAlternateFieldNamesAndShapes() {
        // Some payloads (or the app's own mobile variant) use different key names and
        // nest the list directly under data.
        String json = "{\"data\":{\"lessons\":["
                + "{\"id\":\"77\",\"courseName\":\"线性代数\",\"teacher\":\"王五\",\"room\":\"教三303\","
                + "\"day_of_week\":2,\"beginSection\":5,\"sectionLength\":2,\"week\":\"1-16\"}]}}";
        TimetableParser.Result result = TimetableParser.parse(json);
        assertNotNull(result);
        assertEquals(1, result.lessons.size());
        Lesson lesson = result.lessons.get(0);
        assertEquals("线性代数", lesson.name);
        assertEquals("王五", lesson.teacher);
        assertEquals("教三303", lesson.location);
        assertEquals(2, lesson.dayOfWeek);
        assertEquals(5, lesson.beginSection);
        assertEquals(2, lesson.sections);
        assertEquals("77", lesson.id);
    }

    @Test
    public void fillsMissingIdentityWithStableId() {
        String json = "{\"data\":{\"lessonArray\":["
                + "{\"name\":\"形势与政策\",\"dayOfWeek\":4,\"beginNumber\":7}]}}";
        TimetableParser.Result result = TimetableParser.parse(json);
        assertNotNull(result);
        Lesson lesson = result.lessons.get(0);
        assertFalse(lesson.id.isEmpty());
        assertEquals(lesson.id, lesson.stableId());
        assertEquals("missing length defaults to one section", 1, lesson.sections);
        assertEquals("形势与政策", lesson.name);
    }

    @Test
    public void rejectsNonTimetablePayloads() {
        assertNull(TimetableParser.parse("{\"data\":{\"channelList\":[]}}"));
        assertNull(TimetableParser.parse("not json at all"));
        assertNull(TimetableParser.parse(null));
        // A lessonArray without any usable day/section is dropped, not crashed on.
        assertNull(TimetableParser.parse("{\"data\":{\"lessonArray\":[{\"name\":\"坏数据\"}]}}"));
    }

    @Test
    public void sectionConfigAsObjectArrayAlsoParses() throws Exception {
        JSONArray times = new JSONArray();
        times.put(new JSONArray().put("08:00").put("08:45"));
        times.put(new JSONArray().put("08:55").put("09:40"));
        String json = "{\"data\":{\"lessonArray\":["
                + "{\"name\":\"课\",\"dayOfWeek\":1,\"beginNumber\":1,\"length\":1,\"weeks\":\"1\"}],"
                + "\"curriculum\":{\"lessonTimeConfigArray\":" + times.toString() + "}}}";
        TimetableParser.Result result = TimetableParser.parse(json);
        assertNotNull(result);
        assertNotNull(result.sectionTimesJson);
        // The [["8:00","8:45"], …] shape must feed the same section math.
        assertEquals(8 * 60, ScheduleMath.sectionStartMinutes(result.sectionTimesJson, 1));
        assertEquals(8 * 60 + 45, ScheduleMath.sectionEndMinutes(result.sectionTimesJson, 1));
        assertEquals(8 * 60 + 55, ScheduleMath.sectionStartMinutes(result.sectionTimesJson, 2));
    }

    @Test
    public void lessonJsonRoundTrips() throws Exception {
        Lesson lesson = new Lesson("L9", "计算机组成原理", "赵六", "教四404", 5, 3, 2, "1-16");
        Lesson restored = Lesson.fromJson(lesson.toJson());
        assertEquals("L9", restored.id);
        assertEquals("计算机组成原理", restored.name);
        assertEquals("赵六", restored.teacher);
        assertEquals("教四404", restored.location);
        assertEquals(5, restored.dayOfWeek);
        assertEquals(3, restored.beginSection);
        assertEquals(2, restored.sections);
        assertEquals("1-16", restored.weeks);
        assertEquals("教四404 · 赵六", restored.label());
        List<Lesson> none = Collections.emptyList();
        assertEquals(0, none.size());
    }
}
