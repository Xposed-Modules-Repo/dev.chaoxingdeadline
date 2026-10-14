package dev.chaoxingdeadline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Pure-JVM tests for the deadline parser. The JSON branch needs a real org.json at runtime;
 * the HTML branch (the one that actually produces most items) does not, so it is what these
 * tests pin down.
 */
public class DeadlineParserTest {

    @Test
    public void rejectsBlankAndUnrelatedText() {
        assertFalse(DeadlineParser.looksRelevant(null));
        assertFalse(DeadlineParser.looksRelevant(""));
        assertFalse(DeadlineParser.looksRelevant("太短"));
        assertFalse(DeadlineParser.looksRelevant("今天天气不错，出去走走"));
    }

    @Test
    public void acceptsHomeworkAndExamPages() {
        assertTrue(DeadlineParser.looksRelevant("作业截止时间 2026-10-05 23:59"));
        assertTrue(DeadlineParser.looksRelevant("{\"deadline\":\"2026-10-05 23:59\",\"courseName\":\"高等数学\"}"));
    }

    @Test
    public void parsesWorkPageHtmlItem() {
        String html = "<ul><li data=\"taskId=42&courseId=7\">"
                + "<p>第三章作业</p>"
                + "<span>剩余3天</span><span>高等数学</span>"
                + "<span>截止：2030-01-02 23:59</span>"
                + "</li></ul>";
        List<DeadlineItem> items = DeadlineParser.parsePayload(html, "active.workPage");
        assertEquals(1, items.size());
        DeadlineItem item = items.get(0);
        assertEquals("作业", item.type);
        assertEquals("第三章作业", item.title);
        assertEquals("高等数学", item.course);
        assertTrue("due date must be in the future", item.dueAt > System.currentTimeMillis());
        assertTrue("task id is recovered from the raw html", item.taskId.contains("42"));
    }

    @Test
    public void parsesExamPageHtmlItem() {
        String html = "<ul><li><p>期中考试</p><span>高等数学</span>"
                + "<span>截止时间：2030-05-06 09:30</span></li></ul>";
        List<DeadlineItem> items = DeadlineParser.parsePayload(html, "active.examPage");
        assertEquals(1, items.size());
        assertEquals("考试", items.get(0).type);
        assertEquals("期中考试", items.get(0).title);
    }

    @Test
    public void fallsBackToRelativeRemainingTime() {
        String html = "<ul><li><p>本周作业</p><span>剩余3天</span></li></ul>";
        List<DeadlineItem> items = DeadlineParser.parsePayload(html, "active.workList");
        assertEquals(1, items.size());
        long delta = items.get(0).dueAt - System.currentTimeMillis();
        assertTrue("relative due date should land within ~3 days", delta > 0L
                && delta <= TimeUnit.DAYS.toMillis(3) + TimeUnit.HOURS.toMillis(2));
    }

    @Test
    public void ignoresItemsWithoutAnyDeadline() {
        String html = "<ul><li><p>一个没有截止时间的通知</p><span>高等数学</span></li></ul>";
        assertTrue(DeadlineParser.parsePayload(html, "active.workPage").isEmpty());
    }

    @Test
    public void stableIdIsDeterministic() {
        DeadlineItem a = new DeadlineItem();
        a.type = "作业";
        a.title = "第三章作业";
        a.course = "高等数学";
        a.dueAt = 1_900_000_000_000L;
        DeadlineItem b = new DeadlineItem();
        b.type = "作业";
        b.title = "第三章作业";
        b.course = "高等数学";
        b.dueAt = 1_900_000_000_000L;
        assertEquals(a.stableId(), b.stableId());

        b.dueAt = 1_900_000_060_000L;
        assertFalse("changing the deadline must change the id", a.stableId().equals(b.stableId()));
    }

    @Test
    public void typesTaskActiveListAsClassroomActivity() {
        // taskactivelist payloads (sign-in, poll, quiz race…) must not be typed 作业:
        // they duplicated the real homework rows and belong to their own section.
        String json = "[{\"id\":\"9981\",\"activeType\":2,\"nameOne\":\"第三讲签到\","
                + "\"startTime\":\"2026-10-12 08:00:00\",\"endTime\":\"2030-01-02 22:00:00\",\"status\":1}]";
        List<DeadlineItem> items = DeadlineParser.parsePayload(json, ParseContext.forCourse(
                "active.taskList",
                "https://mobilelearn.chaoxing.com/ppt/activeAPI/taskactivelist?courseId=7&classId=9",
                "7", "9", "", "", "高等数学"));
        assertEquals(1, items.size());
        DeadlineItem item = items.get(0);
        assertEquals("课堂活动", item.type);
        assertEquals("第三讲签到", item.title);
        assertEquals("高等数学", item.course);
        assertTrue("activity id is kept for stable identity", item.taskId.contains("9981"));
        assertTrue("due date must be in the future", item.dueAt > System.currentTimeMillis());
    }

    @Test
    public void parsesChapterTreeFromClassPayload() {
        // Chapters used to have no active fetch at all — the gas/clazz knowledge tree is
        // the endpoint that makes them show up for courses the user never opened.
        String json = "{\"result\":1,\"data\":[{"
                + "\"id\":\"k1\",\"name\":\"第一章 极限\",\"layer\":1,\"parentnodeid\":\"0\",\"jobcount\":3,"
                + "\"begintime\":\"2026-09-01 00:00:00\",\"endtime\":\"2030-01-02 23:59:00\","
                + "\"knowledge\":["
                + "{\"id\":\"k11\",\"name\":\"1.1 数列极限\",\"parentnodeid\":\"k1\",\"layer\":2,"
                + "\"label\":\"节\",\"jobcount\":2,\"endtime\":\"2030-01-02 23:59:00\"},"
                + "{\"id\":\"k12\",\"name\":\"1.2 函数极限\",\"parentnodeid\":\"k1\",\"layer\":2,"
                + "\"label\":\"节\",\"jobcount\":0,\"endtime\":\"2030-01-02 23:59:00\"}"
                + "]}]}";
        List<DeadlineItem> items = DeadlineParser.parsePayload(json, ParseContext.forCourse(
                "active.chapter",
                "https://mooc1-api.chaoxing.com/gas/clazz?id=KEY&personid=1&view=json",
                "7", "9", "123", "456", "高等数学"));
        assertEquals("only the leaf node with open task points becomes a row", 1, items.size());
        DeadlineItem item = items.get(0);
        assertEquals("章节", item.type);
        assertEquals("1.1 数列极限", item.title);
        assertEquals("高等数学", item.course);
        assertTrue("due date must be in the future", item.dueAt > System.currentTimeMillis());
    }

    @Test
    public void fallsBackToLeafNodesWhenThePayloadHasNoCounters() {
        // Some clazz responses omit jobcount; without counters the branch/leaf split is
        // what keeps the 章 row from appearing next to every 节 row.
        String json = "{\"result\":1,\"data\":[{"
                + "\"id\":\"k1\",\"name\":\"第一章 极限\",\"parentnodeid\":\"0\","
                + "\"endtime\":\"2030-01-02 23:59:00\","
                + "\"knowledge\":["
                + "{\"id\":\"k11\",\"name\":\"1.1 数列极限\",\"parentnodeid\":\"k1\",\"endtime\":\"2030-01-02 23:59:00\"},"
                + "{\"id\":\"k12\",\"name\":\"1.2 函数极限\",\"parentnodeid\":\"k1\",\"endtime\":\"2030-01-02 23:59:00\"}"
                + "]}]}";
        List<DeadlineItem> items = DeadlineParser.parsePayload(json, ParseContext.forCourse(
                "active.chapter", "https://mooc1-api.chaoxing.com/gas/clazz?id=KEY&view=json",
                "7", "9", "", "", "高等数学"));
        assertEquals("the two 节 rows survive, the enclosing 章 does not", 2, items.size());
        for (DeadlineItem item : items) {
            assertFalse("no 章节 row may be the enclosing chapter", "第一章 极限".equals(item.title));
        }
    }

    @Test
    public void parsesGroupWorkDeadlineNamedCloseDate() {
        // Group-work rows name the deadline closeDate/endDate instead of endTime; the
        // time scan must accept date-named keys or those rows parse to due=0 and vanish.
        String json = "[{\"workId\":\"7001\",\"title\":\"第三组 小组报告\",\"courseName\":\"高等数学\","
                + "\"closeDate\":\"2030-01-02 23:59\"}]";
        List<DeadlineItem> items = DeadlineParser.parsePayload(json, ParseContext.forCourse(
                "active.workList",
                "https://mooc1-api.chaoxing.com/work/task-list?courseId=7&classId=9",
                "7", "9", "", "", "高等数学"));
        assertEquals(1, items.size());
        DeadlineItem item = items.get(0);
        assertEquals("作业", item.type);
        assertEquals("第三组 小组报告", item.title);
        assertTrue("due date must be in the future", item.dueAt > System.currentTimeMillis());
    }

    @Test
    public void keepsWorkTaskListTypedAsHomework() {
        String json = "[{\"workId\":\"4242\",\"title\":\"第三章作业\",\"courseName\":\"高等数学\","
                + "\"endTime\":\"2030-01-02 23:59\"}]";
        List<DeadlineItem> items = DeadlineParser.parsePayload(json, ParseContext.forCourse(
                "active.workList",
                "https://mooc1-api.chaoxing.com/work/task-list?courseId=7&classId=9",
                "7", "9", "", "", "高等数学"));
        assertEquals(1, items.size());
        assertEquals("作业", items.get(0).type);
        assertEquals("第三章作业", items.get(0).title);
    }
}
