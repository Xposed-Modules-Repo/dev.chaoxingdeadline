package dev.chaoxingdeadline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Grid layout maths: spans, week filtering, overlap tolerance. */
public class TimetableGridTest {

    private static Lesson lesson(String name, int day, int begin, int sections, String weeks) {
        return new Lesson(name, name, "", "", day, begin, sections, weeks);
    }

    @Test
    public void blocksSpanTheirSections() {
        List<Lesson> lessons = Arrays.asList(
                lesson("高数", 1, 1, 2, "1-16"),
                lesson("英语", 1, 4, 1, "1-16"));
        TimetableGrid.Grid grid = TimetableGrid.build(lessons, 3);
        assertEquals(4, grid.rows);
        List<TimetableGrid.Cell> monday = grid.column(1);
        // Three cells: block(span 2) + empty + block(span 1); spans sum back to rows.
        assertEquals(3, monday.size());
        assertTrue(monday.get(0).isBlock());
        assertEquals(2, monday.get(0).span);
        assertEquals("高数", monday.get(0).lesson.name);
        assertFalse(monday.get(1).isBlock());
        assertTrue(monday.get(2).isBlock());
        assertEquals(1, monday.get(2).span);
        int total = 0;
        for (TimetableGrid.Cell cell : monday) {
            total += cell.isBlock() ? cell.span : 1;
        }
        assertEquals("cells tile the whole column", grid.rows, total);
        for (int day = 2; day <= TimetableGrid.DAYS; day++) {
            for (TimetableGrid.Cell cell : grid.column(day)) {
                assertFalse(cell.isBlock());
            }
        }
    }

    @Test
    public void weekFilterDropsInactiveLessons() {
        List<Lesson> lessons = Arrays.asList(
                lesson("单周课", 2, 1, 1, "1"),
                lesson("全周期课", 2, 2, 1, ""),
                lesson("范围课", 2, 4, 1, "5-8"));
        TimetableGrid.Grid week1 = TimetableGrid.build(lessons, 1);
        assertEquals("rows = deepest active lesson", 2, week1.rows);
        assertTrue(week1.column(2).get(0).isBlock());
        assertEquals("单周课", week1.column(2).get(0).lesson.name);

        TimetableGrid.Grid week9 = TimetableGrid.build(lessons, 9);
        assertEquals("only the every-week lesson survives", 2, week9.rows);
        assertTrue(week9.column(2).get(1).isBlock());
        assertEquals("全周期课", week9.column(2).get(1).lesson.name);
    }

    @Test
    public void emptyWeekProducesNoBlocks() {
        TimetableGrid.Grid grid = TimetableGrid.build(
                Arrays.asList(lesson("课", 3, 1, 1, "2")), 1);
        assertEquals(0, grid.rows);
        for (int day = 1; day <= TimetableGrid.DAYS; day++) {
            assertTrue(grid.column(day).isEmpty());
        }
    }

    @Test
    public void overlappingLessonsDoNotBreakTheGrid() {
        List<Lesson> lessons = Arrays.asList(
                lesson("先占位", 4, 1, 4, "1-16"),
                lesson("被盖住", 4, 2, 2, "1-16"));
        TimetableGrid.Grid grid = TimetableGrid.build(lessons, 2);
        assertEquals(4, grid.rows);
        long blocks = 0;
        for (TimetableGrid.Cell cell : grid.column(4)) {
            if (cell.isBlock()) {
                blocks++;
            }
        }
        assertEquals("the shadowed lesson is skipped, not stacked", 1, blocks);
    }

    @Test
    public void toleratesNullAndJunk() {
        List<Lesson> lessons = new ArrayList<>();
        lessons.add(null);
        lessons.add(lesson("ok", 1, 1, 1, "1-16"));
        lessons.add(lesson("bad-day", 9, 1, 1, "1-16"));
        lessons.add(lesson("bad-section", 1, 0, 1, "1-16"));
        TimetableGrid.Grid grid = TimetableGrid.build(lessons, 1);
        assertEquals(1, grid.rows);
        assertEquals(1, grid.column(1).size());
        assertTrue(grid.column(1).get(0).isBlock());
    }

    @Test
    public void longSpansAreClampedToTheGrid() {
        TimetableGrid.Grid grid = TimetableGrid.build(
                Arrays.asList(lesson("马拉松", 5, TimetableGrid.MAX_ROWS - 1, 4, "1-16")), 1);
        assertEquals(TimetableGrid.MAX_ROWS, grid.rows);
        List<TimetableGrid.Cell> friday = grid.column(5);
        // Twelve empty cells plus one block covering the last two rows.
        assertEquals(TimetableGrid.MAX_ROWS - 1, friday.size());
        assertTrue(friday.get(TimetableGrid.MAX_ROWS - 2).isBlock());
        assertEquals("span clamped to the last two rows", 2,
                friday.get(TimetableGrid.MAX_ROWS - 2).span);
    }
}
