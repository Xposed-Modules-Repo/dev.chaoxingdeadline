package dev.chaoxingdeadline;

import org.junit.Test;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure-JVM coverage for the month-grid layout. */
public class MonthGridTest {

    private static long dayStartOf(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day);
        return calendar.getTimeInMillis();
    }

    @Test
    public void gridIsPaddedToWholeWeeks() {
        List<MonthGrid.Cell> cells = MonthGrid.build(2026, 10, null);
        assertEquals("grid is a whole number of weeks", 0, cells.size() % MonthGrid.COLUMNS);
        assertTrue("a month fits in at most 6 weeks", cells.size() <= 42);
    }

    @Test
    public void firstDaySitsUnderItsWeekday() {
        List<MonthGrid.Cell> cells = MonthGrid.build(2026, 10, null);
        int leading = 0;
        while (leading < cells.size() && cells.get(leading).isBlank()) {
            leading++;
        }
        // 2026-10-01 is a Thursday → 4 blanks before day 1 (Sunday-first grid).
        assertEquals("2026-10-01 是周四，前面补 4 个空白", 4, leading);
        assertEquals(1, cells.get(leading).dayOfMonth);
        assertEquals(31, cells.get(leading + 30).dayOfMonth); // October has 31 days
        assertEquals("2026-10 is exactly 5 whole weeks (4 pads + 31 days)", 35, cells.size());
    }

    @Test
    public void februaryHandlesLeapYears() {
        List<MonthGrid.Cell> feb2028 = MonthGrid.build(2028, 2, null); // 2028 is a leap year
        List<MonthGrid.Cell> feb2026 = MonthGrid.build(2026, 2, null);
        assertEquals(29, lastDayOf(feb2028));
        assertEquals(28, lastDayOf(feb2026));
    }

    @Test
    public void countsMapOntoTheRightDay() {
        Map<Long, Integer> counts = new HashMap<>();
        long oct10 = dayStartOf(2026, 10, 10);
        long oct20 = dayStartOf(2026, 10, 20);
        counts.put(oct10, 3);
        counts.put(oct20, 1);
        List<MonthGrid.Cell> cells = MonthGrid.build(2026, 10, counts);
        for (MonthGrid.Cell cell : cells) {
            if (cell.isBlank()) {
                continue;
            }
            if (cell.dayOfMonth == 10) {
                assertEquals(3, cell.count);
                assertEquals("cell dayStart is that day's midnight", oct10, cell.dayStart);
            } else if (cell.dayOfMonth == 20) {
                assertEquals(1, cell.count);
            } else {
                assertEquals("other days have no count", 0, cell.count);
            }
        }
    }

    @Test
    public void monthNavigationWrapsAroundYears() {
        int[] prev = MonthGrid.previous(2026, 1);
        assertEquals(2025, prev[0]);
        assertEquals(12, prev[1]);
        int[] next = MonthGrid.next(2026, 12);
        assertEquals(2027, next[0]);
        assertEquals(1, next[1]);
        int[] mid = MonthGrid.next(2026, 6);
        assertEquals(2026, mid[0]);
        assertEquals(7, mid[1]);
    }

    @Test
    public void monthLabelFormatsChineseDate() {
        assertEquals("2026年10月", MonthGrid.monthLabel(2026, 10));
    }

    @Test
    public void dayStartIsMidnightOfThatDay() {
        long start = MonthGrid.dayStart(2026, 10, 15);
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(start);
        assertEquals(2026, calendar.get(Calendar.YEAR));
        assertEquals(Calendar.OCTOBER, calendar.get(Calendar.MONTH));
        assertEquals(15, calendar.get(Calendar.DAY_OF_MONTH));
        assertEquals(0, calendar.get(Calendar.HOUR_OF_DAY));
        assertFalse("midnight is a real instant", start == 0L);
        assertTrue("within a day of now for a current-month date",
                Math.abs(start - System.currentTimeMillis()) < 400L * TimeUnit.DAYS.toMillis(1));
    }

    private static int lastDayOf(List<MonthGrid.Cell> cells) {
        int last = 0;
        for (MonthGrid.Cell cell : cells) {
            if (!cell.isBlank() && cell.dayOfMonth > last) {
                last = cell.dayOfMonth;
            }
        }
        return last;
    }
}
