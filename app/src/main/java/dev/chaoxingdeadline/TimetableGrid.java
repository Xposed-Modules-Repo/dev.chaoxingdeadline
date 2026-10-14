package dev.chaoxingdeadline;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure layout model for the 拾光-style weekly grid: section rows × weekday columns,
 * with multi-section lessons occupying a vertical span. Android-free so the grid
 * maths (spans, overlaps, week filtering) is unit-testable; ScheduleActivity only
 * renders what this returns.
 */
public final class TimetableGrid {
    private TimetableGrid() {}

    public static final int DAYS = 7;
    /** Beyond this many rows the grid scrolls instead of growing forever. */
    public static final int MAX_ROWS = 14;

    /** One rendered slot: an empty cell or the head of a lesson block. */
    public static final class Cell {
        /** 1 = Monday … 7 = Sunday. */
        public final int day;
        /** 1-based section row this cell occupies. */
        public final int section;
        /** Vertical span in rows; 0 marks an empty cell. */
        public final int span;
        /** The lesson a block shows; null for empty cells. */
        public final Lesson lesson;

        Cell(int day, int section, int span, Lesson lesson) {
            this.day = day;
            this.section = section;
            this.span = span;
            this.lesson = lesson;
        }

        public boolean isBlock() {
            return lesson != null;
        }
    }

    public static final class Grid {
        /** Highest occupied section row (0 when the week has no lessons). */
        public final int rows;
        /** Columns by weekday: index 0 = Monday … 6 = Sunday. */
        public final List<List<Cell>> columns;

        Grid(int rows, List<List<Cell>> columns) {
            this.rows = rows;
            this.columns = columns;
        }

        public List<Cell> column(int day) {
            return columns.get(day - 1);
        }
    }

    /**
     * @param week 1-based teaching week; lessons whose weeks list excludes it are
     *             dropped (an empty weeks string means "every week").
     */
    public static Grid build(List<Lesson> lessons, int week) {
        List<Lesson> active = new ArrayList<>();
        int rows = 0;
        if (lessons != null) {
            for (Lesson lesson : lessons) {
                if (lesson == null || lesson.dayOfWeek < 1 || lesson.dayOfWeek > DAYS
                        || lesson.beginSection <= 0) {
                    continue;
                }
                if (!ScheduleMath.isActiveInWeek(lesson, week)) {
                    continue;
                }
                active.add(lesson);
                int last = lesson.beginSection + Math.max(1, lesson.sections) - 1;
                if (last > rows) {
                    rows = last;
                }
            }
        }
        rows = Math.min(rows, MAX_ROWS);
        List<List<Cell>> columns = new ArrayList<>();
        for (int day = 1; day <= DAYS; day++) {
            columns.add(buildColumn(active, day, rows));
        }
        return new Grid(rows, columns);
    }

    /**
     * Walks the rows top to bottom; the first lesson claiming a row wins, so
     * overlapping data degrades to "first block shown" instead of a broken grid.
     */
    private static List<Cell> buildColumn(List<Lesson> lessons, int day, int rows) {
        List<Cell> cells = new ArrayList<>();
        int section = 1;
        while (section <= rows) {
            Lesson lesson = lessonStartingAt(lessons, day, section);
            if (lesson == null) {
                cells.add(new Cell(day, section, 0, null));
                section++;
                continue;
            }
            int span = Math.min(Math.max(1, lesson.sections), rows - section + 1);
            cells.add(new Cell(day, section, span, lesson));
            section += span;
        }
        return cells;
    }

    private static Lesson lessonStartingAt(List<Lesson> lessons, int day, int section) {
        for (Lesson lesson : lessons) {
            if (lesson.dayOfWeek == day && lesson.beginSection == section) {
                return lesson;
            }
        }
        return null;
    }
}
