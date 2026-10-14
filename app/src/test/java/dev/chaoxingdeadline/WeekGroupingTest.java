package dev.chaoxingdeadline;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure-JVM coverage for the 本周视图 grouping logic. */
public class WeekGroupingTest {

    private static DeadlineItem item(String title, long dueAt) {
        DeadlineItem item = new DeadlineItem();
        item.type = "作业";
        item.title = title;
        item.course = "高等数学";
        item.dueAt = dueAt;
        item.setSubmissionState(DeadlineItem.SUBMISSION_UNSUBMITTED);
        return item;
    }

    private static long atDaysFromNow(double days) {
        return System.currentTimeMillis() + (long) (days * TimeUnit.DAYS.toMillis(1));
    }

    @Test
    public void groupsByDayWithTodayAndTomorrowLabels() {
        // Pin "now" to 09:00 today so both today-items are guaranteed to be ahead of it
        // whatever wall-clock the suite runs at — a real 23:30 clock would push a +0.8d
        // offset past midnight into tomorrow and make this test time-of-day dependent.
        long now = WeekGrouping.dayStart(System.currentTimeMillis()) + TimeUnit.HOURS.toMillis(9);
        long todayStart = WeekGrouping.dayStart(now);
        List<DeadlineItem> items = Arrays.asList(
                item("今天晚点的作业", todayStart + TimeUnit.HOURS.toMillis(20)),
                item("明天上午的考试",
                        todayStart + TimeUnit.DAYS.toMillis(1) + TimeUnit.HOURS.toMillis(12)),
                item("也是今天的章节", todayStart + TimeUnit.HOURS.toMillis(10)));
        List<WeekGrouping.DayGroup> groups = WeekGrouping.group(items, now);
        assertEquals(2, groups.size());
        assertEquals("今天", groups.get(0).label);
        assertEquals("明天", groups.get(1).label);
        assertEquals(2, groups.get(0).items.size());
        assertEquals(1, groups.get(1).items.size());
        // within 今天 the earlier deadline (10:00) comes first
        assertEquals("也是今天的章节", groups.get(0).items.get(0).title);
        assertEquals("今天晚点的作业", groups.get(0).items.get(1).title);
    }

    @Test
    public void skipsSubmittedRevokedAndPastDue() {
        long now = System.currentTimeMillis();
        DeadlineItem submitted = item("已交", atDaysFromNow(1));
        submitted.submitted = true;
        DeadlineItem revoked = item("已撤销", atDaysFromNow(1));
        revoked.vanishedAt = now;
        DeadlineItem past = item("已过期", now - TimeUnit.HOURS.toMillis(2));
        List<WeekGrouping.DayGroup> groups = WeekGrouping.group(
                Arrays.asList(submitted, revoked, past, item("还有效", atDaysFromNow(1))), now);
        assertEquals(1, groups.size());
        assertEquals(1, groups.get(0).items.size());
        assertEquals("还有效", groups.get(0).items.get(0).title);
    }

    @Test
    public void limitsToSevenDayHorizon() {
        long now = System.currentTimeMillis();
        List<WeekGrouping.DayGroup> groups = WeekGrouping.group(Arrays.asList(
                item("第 6 天", atDaysFromNow(6)),
                item("第 8 天", atDaysFromNow(8)),
                item("第 30 天", atDaysFromNow(30))), now);
        assertEquals("only the item inside the 7-day horizon survives", 1, groups.size());
        assertEquals("第 6 天", groups.get(0).items.get(0).title);
    }

    @Test
    public void dayStartIsLocalMidnight() {
        long noon = System.currentTimeMillis();
        long midnight = WeekGrouping.dayStart(noon);
        assertEquals("dayStart is idempotent", midnight, WeekGrouping.dayStart(midnight));
        assertTrue("midnight is at or before the instant", midnight <= noon);
        assertTrue("midnight is within the same day", noon - midnight < TimeUnit.DAYS.toMillis(1));
    }

    @Test
    public void emptyAndNullInputs() {
        assertEquals(0, WeekGrouping.group(null, System.currentTimeMillis()).size());
        assertEquals(0, WeekGrouping.group(Collections.<DeadlineItem>emptyList(),
                System.currentTimeMillis()).size());
    }

    @Test
    public void dayLabelUsesDateAndWeekdayBeyondTomorrow() {
        long now = System.currentTimeMillis();
        long dayStart = WeekGrouping.dayStart(now) + 3L * TimeUnit.DAYS.toMillis(1);
        String label = WeekGrouping.dayLabel(dayStart, now);
        assertFalse("三天后不再是相对标签", "今天".equals(label) || "明天".equals(label));
        assertTrue("label carries 月 and 日", label.contains("月") && label.contains("日"));
        assertTrue("label carries the weekday", label.contains("周"));
    }

    @Test
    public void countdownFormatsHoursAndDays() {
        long now = System.currentTimeMillis();
        assertTrue(WeekGrouping.countdown(now + TimeUnit.MINUTES.toMillis(30), now).contains("分钟"));
        assertTrue(WeekGrouping.countdown(now + TimeUnit.HOURS.toMillis(5), now).contains("小时"));
        assertTrue(WeekGrouping.countdown(now + TimeUnit.DAYS.toMillis(3), now).contains("天"));
        assertEquals("已截止", WeekGrouping.countdown(now - 1000L, now));
    }

    @Test
    public void widgetCourseFilterMatchesExactlyOrKeepsAll() {
        DeadlineItem math = item("高数作业", System.currentTimeMillis() + TimeUnit.HOURS.toMillis(5));
        math.course = "高等数学";
        DeadlineItem english = item("英语作文", System.currentTimeMillis() + TimeUnit.HOURS.toMillis(6));
        english.course = "大学英语";
        DeadlineItem noCourse = item("杂项", System.currentTimeMillis() + TimeUnit.HOURS.toMillis(7));
        noCourse.course = "";
        assertTrue("empty filter keeps everything",
                DeadlineWidgetProvider.passesCourseFilter(math, ""));
        assertTrue("empty filter is null-safe",
                DeadlineWidgetProvider.passesCourseFilter(math, null));
        assertTrue(DeadlineWidgetProvider.passesCourseFilter(math, "高等数学"));
        assertFalse("a different course is filtered out",
                DeadlineWidgetProvider.passesCourseFilter(english, "高等数学"));
        assertFalse("an item with no course name drops out of a filtered widget",
                DeadlineWidgetProvider.passesCourseFilter(noCourse, "高等数学"));
    }
}
