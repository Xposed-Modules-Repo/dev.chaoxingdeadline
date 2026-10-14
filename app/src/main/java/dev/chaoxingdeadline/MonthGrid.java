package dev.chaoxingdeadline;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/**
 * Builds the 6-week month grid for the 待办月历 screen. Pure JDK logic (Calendar), so the
 * layout maths runs in plain unit tests. Each cell is either a blank pad or a real day with
 * its local midnight and the number of deadlines falling on it.
 */
public final class MonthGrid {
    public static final int COLUMNS = 7;

    private MonthGrid() {
    }

    /** One grid slot. {@code dayOfMonth == 0} marks a leading/trailing blank. */
    public static final class Cell {
        public final int dayOfMonth;
        public final long dayStart;
        public final int count;

        Cell(int dayOfMonth, long dayStart, int count) {
            this.dayOfMonth = dayOfMonth;
            this.dayStart = dayStart;
            this.count = count;
        }

        public boolean isBlank() {
            return dayOfMonth == 0;
        }
    }

    /**
     * Grid for the given 1-based month, padded to whole weeks (Sunday first).
     * {@code countByDay} maps local-midnight timestamps to deadline counts.
     */
    public static List<Cell> build(int year, int month, Map<Long, Integer> countByDay) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, 1);
        int leading = calendar.get(Calendar.DAY_OF_WEEK) - 1; // 1=Sunday
        int daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH);

        ArrayList<Cell> cells = new ArrayList<>();
        for (int i = 0; i < leading; i++) {
            cells.add(new Cell(0, 0L, 0));
        }
        for (int day = 1; day <= daysInMonth; day++) {
            calendar.set(year, month - 1, day);
            long dayStart = calendar.getTimeInMillis();
            Integer count = countByDay == null ? null : countByDay.get(dayStart);
            cells.add(new Cell(day, dayStart, count == null ? 0 : count));
        }
        while (cells.size() % COLUMNS != 0) {
            cells.add(new Cell(0, 0L, 0));
        }
        return cells;
    }

    /** Local midnight of a given 1-based month/day, or 0 when out of range. */
    public static long dayStart(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day);
        return calendar.getTimeInMillis();
    }

    public static String monthLabel(int year, int month) {
        return year + "年" + month + "月";
    }

    public static int[] previous(int year, int month) {
        return month <= 1 ? new int[]{year - 1, 12} : new int[]{year, month - 1};
    }

    public static int[] next(int year, int month) {
        return month >= 12 ? new int[]{year + 1, 1} : new int[]{year, month + 1};
    }
}
