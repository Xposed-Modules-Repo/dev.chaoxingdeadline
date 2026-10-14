package dev.chaoxingdeadline;

import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;
import java.util.List;

/**
 * 课表页：拾光风格的周网格。节次为行、周一~周日为列，多节课程跨行成色块；
 * 今天列高亮，正在上的课加描边，已过的课置灰；支持周次切换与手动刷新。
 *
 * 数据来自 Hook 捕获后经广播落入本进程 lessons 表，纯本地读取；刷新通过
 * {@link RefreshBridge} 请求宿主（学习通进程）重新拉取。
 */
public final class ScheduleActivity extends BaseActivity {
    private static final String[] DAY_NAMES = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final int CELL_HEIGHT_DP = 52;
    private static final int MAX_WEEK = 30;
    private static final long REFRESH_GUARD_MS = 10_000L;

    /** Course block palettes: {background, text}, light then dark theme. */
    private static final int[][] LIGHT_COURSE_COLORS = {
            {0xFFE3EDFF, 0xFF2B5CB8}, {0xFFFFE7EE, 0xFFB5325F},
            {0xFFFFF3D6, 0xFF8F6500}, {0xFFDFF5EC, 0xFF14785A},
            {0xFFEFE6FF, 0xFF6D3FC0}, {0xFFFFE9D6, 0xFFB4531A},
            {0xFFD9F1F5, 0xFF0E7490}, {0xFFFBE3D0, 0xFF9A4A1B},
            {0xFFE8E8F6, 0xFF4A4E8A}, {0xFFF6E2E4, 0xFFA04550},
    };
    private static final int[][] DARK_COURSE_COLORS = {
            {0xFF1D2A45, 0xFF9CC0FF}, {0xFF3A1F2C, 0xFFF3A9C4},
            {0xFF3B3117, 0xFFE8C56A}, {0xFF17362B, 0xFF7FD4B4},
            {0xFF2C2450, 0xFFC4AEF7}, {0xFF3D2717, 0xFFF0B183},
            {0xFF143340, 0xFF7CC7DC}, {0xFF3A2415, 0xFFE5A86F},
            {0xFF232648, 0xFFAAB2E8}, {0xFF3B2226, 0xFFE8A3AE},
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Week the grid shows; <= 0 means "follow the current teaching week". */
    private int displayedWeek;
    private String lastSignature;
    private long refreshBlockedUntil;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            reloadIfChanged();
            handler.postDelayed(this, 3000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applySystemBars();
        setContentView(buildContent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        lastSignature = null; // a returning user always gets fresh data
        reloadIfChanged();
        handler.postDelayed(tick, 3000L);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(tick);
        super.onStop();
    }

    /** Rebuilds only when the stored timetable actually changed, so scrolling survives. */
    private void reloadIfChanged() {
        String signature = dataSignature();
        if (signature.equals(lastSignature)) {
            return;
        }
        lastSignature = signature;
        setContentView(buildContent());
    }

    /** Forces a rebuild even when the data signature is unchanged (week switches). */
    private void rebuild() {
        lastSignature = null;
        reloadIfChanged();
    }

    private String dataSignature() {
        DeadlineStore store = new DeadlineStore(this);
        List<Lesson> lessons = store.allLessons();
        int hash = 0;
        for (Lesson lesson : lessons) {
            hash = hash * 31 + (lesson.id == null ? 0 : lesson.id.hashCode());
        }
        return store.scheduleUpdatedAt() + ":" + lessons.size() + ":" + hash
                + ":" + store.scheduleFirstWeekDate();
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiTheme.background(this));
        root.setPadding(dp(12), statusBarHeight() + dp(8), dp(12), dp(16));

        root.addView(titleBar(), new LinearLayout.LayoutParams(-1, dp(48)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(6), 0, 0);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        DeadlineStore store = new DeadlineStore(this);
        long now = System.currentTimeMillis();
        long firstWeekDate = store.scheduleFirstWeekDate();
        String sectionTimes = store.scheduleSectionTimes();
        int currentWeek = ScheduleMath.currentWeek(now, firstWeekDate);
        List<Lesson> lessons = store.allLessons();
        int week = displayedWeek > 0 ? displayedWeek : Math.max(1, currentWeek);
        Lesson current = ScheduleMath.currentLesson(
                ScheduleMath.todayLessons(lessons, now, firstWeekDate), now, firstWeekDate, sectionTimes);

        content.addView(weekSwitcher(week, currentWeek, firstWeekDate), groupParams());

        if (lessons.isEmpty()) {
            content.addView(emptyCard(), groupParams());
            return root;
        }

        TimetableGrid.Grid grid = TimetableGrid.build(lessons, week);
        if (grid.rows == 0) {
            content.addView(emptyWeekCard(week), groupParams());
        } else {
            content.addView(gridView(grid, week, currentWeek, current, firstWeekDate, sectionTimes, now),
                    groupParams());
        }

        long updatedAt = store.scheduleUpdatedAt();
        if (updatedAt > 0L) {
            TextView stamp = text("更新于 " + DateText.deadlineTime(updatedAt)
                    + " · 数据来自学习通课表页面", 11, false, UiTheme.muted(this));
            stamp.setPadding(dp(4), dp(2), dp(4), dp(8));
            content.addView(stamp, new LinearLayout.LayoutParams(-1, -2));
        }
        return root;
    }

    // -- chrome --

    private View titleBar() {
        LinearLayout top = hbox();
        TextView back = icon("←", 26, UiTheme.accent(this));
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(40), -1));
        TextView title = text("课表", 20, true, UiTheme.text(this));
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0, -1, 1f));
        TextView refresh = icon("⟳", 22, UiTheme.accent(this));
        refresh.setOnClickListener(v -> requestRefresh());
        top.addView(refresh, new LinearLayout.LayoutParams(dp(40), -1));
        return top;
    }

    private View weekSwitcher(int week, int currentWeek, long firstWeekDate) {
        LinearLayout bar = hbox();
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        bar.setBackground(UiTheme.cardBg(this, 14));

        TextView prev = icon("‹", 26, UiTheme.accent(this));
        prev.setOnClickListener(v -> {
            displayedWeek = Math.max(1, week - 1);
            rebuild();
        });
        bar.addView(prev, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        TextView weekLabel = text("第 " + week + " 周", 15, true, UiTheme.text(this));
        weekLabel.setGravity(Gravity.CENTER);
        center.addView(weekLabel, new LinearLayout.LayoutParams(-1, -2));
        String range = weekRangeLabel(firstWeekDate, week);
        if (currentWeek > 0 && week == currentWeek) {
            range = range + " · 本周";
        }
        TextView dateLabel = text(range, 11, false, UiTheme.muted(this));
        dateLabel.setGravity(Gravity.CENTER);
        center.addView(dateLabel, new LinearLayout.LayoutParams(-1, -2));
        bar.addView(center, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView next = icon("›", 26, UiTheme.accent(this));
        next.setOnClickListener(v -> {
            displayedWeek = Math.min(MAX_WEEK, week + 1);
            rebuild();
        });
        bar.addView(next, new LinearLayout.LayoutParams(dp(40), dp(40)));

        if (currentWeek > 0 && week != currentWeek) {
            TextView jump = text("本周", 13, true, UiTheme.accent(this));
            jump.setGravity(Gravity.CENTER);
            jump.setOnClickListener(v -> {
                displayedWeek = currentWeek;
                rebuild();
            });
            bar.addView(jump, new LinearLayout.LayoutParams(dp(44), -1));
        }
        return bar;
    }

    // -- grid --

    private View gridView(TimetableGrid.Grid grid, int week, int currentWeek, Lesson current,
                          long firstWeekDate, String sectionTimes, long now) {
        LinearLayout table = new LinearLayout(this);
        table.setOrientation(LinearLayout.HORIZONTAL);
        table.setBackground(UiTheme.cardBg(this, 16));
        table.setPadding(dp(4), dp(4), dp(4), dp(4));

        LinearLayout axis = new LinearLayout(this);
        axis.setOrientation(LinearLayout.VERTICAL);
        axis.addView(axisCorner(), axisParams());
        for (int section = 1; section <= grid.rows; section++) {
            axis.addView(axisLabel(section, sectionTimes), axisParams());
        }
        table.addView(axis, new LinearLayout.LayoutParams(dp(28), -2));

        int todayDay = ScheduleMath.todayDayOfWeek(now);
        for (int day = 1; day <= 7; day++) {
            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            boolean isToday = currentWeek > 0 && week == currentWeek && day == todayDay;
            column.addView(dayHeader(day, week, firstWeekDate, isToday), headerParams());
            for (TimetableGrid.Cell cell : grid.column(day)) {
                if (cell.isBlock()) {
                    column.addView(blockView(cell, current, week, currentWeek,
                            firstWeekDate, sectionTimes, now), cellParams(cell));
                } else {
                    column.addView(emptyCell(isToday), emptyParams());
                }
            }
            table.addView(column, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        return table;
    }

    private View axisCorner() {
        TextView view = text("节", 9, false, UiTheme.muted(this));
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private View axisLabel(int section, String sectionTimes) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        TextView number = text(String.valueOf(section), 11, true, UiTheme.muted(this));
        number.setGravity(Gravity.CENTER);
        box.addView(number, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView clock = text(ScheduleMath.formatMinutes(
                ScheduleMath.sectionStartMinutes(sectionTimes, section)), 8, false, UiTheme.muted(this));
        clock.setGravity(Gravity.CENTER);
        box.addView(clock, new LinearLayout.LayoutParams(-1, 0, 1f));
        return box;
    }

    private View dayHeader(int day, int week, long firstWeekDate, boolean isToday) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER);
        if (isToday) {
            header.setBackground(UiTheme.fillOnly(this, UiTheme.accent(this), dp(8)));
        }
        TextView name = text(DAY_NAMES[day - 1], 10, true,
                isToday ? Color.WHITE : UiTheme.muted(this));
        name.setGravity(Gravity.CENTER);
        header.addView(name, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView date = text(isToday ? "今天" : dateLabel(firstWeekDate, week, day), 8, false,
                isToday ? Color.WHITE : UiTheme.muted(this));
        date.setGravity(Gravity.CENTER);
        header.addView(date, new LinearLayout.LayoutParams(-1, 0, 1f));
        return header;
    }

    private View emptyCell(boolean isToday) {
        View view = new View(this);
        view.setBackgroundColor(isToday ? todayTint() : UiTheme.divider(this));
        return view;
    }

    private View blockView(TimetableGrid.Cell cell, Lesson current, int week, int currentWeek,
                           long firstWeekDate, String sectionTimes, long now) {
        Lesson lesson = cell.lesson;
        int[] colors = courseColors(lesson.name);
        boolean isCurrent = current != null && current.id != null && current.id.equals(lesson.id);
        boolean isPast = false;
        if (currentWeek > 0 && week == currentWeek) {
            long end = ScheduleMath.lessonEndAt(firstWeekDate, currentWeek, lesson.dayOfWeek,
                    lesson.beginSection, lesson.sections, sectionTimes);
            isPast = end > 0L && now >= end;
        }

        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(3), dp(3), dp(3), dp(3));
        block.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        int radius = dp(6);
        block.setBackground(isCurrent
                ? UiTheme.rounded(this, colors[0], radius, UiTheme.accent(this))
                : UiTheme.fillOnly(this, colors[0], radius));
        block.setAlpha(isPast ? 0.55f : 1f);
        block.setOnClickListener(v -> showLessonDialog(lesson));

        TextView name = text(lesson.name, 9, true, colors[1]);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        block.addView(name, new LinearLayout.LayoutParams(-1, 0, 1f));

        String meta = lesson.location != null && !lesson.location.isEmpty()
                ? lesson.location : (lesson.teacher == null ? "" : lesson.teacher);
        if (cell.span >= 2 && !meta.isEmpty()) {
            TextView metaView = text(meta, 8, false, colors[1]);
            metaView.setGravity(Gravity.CENTER_HORIZONTAL);
            metaView.setSingleLine();
            metaView.setEllipsize(TextUtils.TruncateAt.END);
            block.addView(metaView, new LinearLayout.LayoutParams(-1, 0, 1f));
        }
        return block;
    }

    // -- empty states --

    private View emptyCard() {
        LinearLayout card = card();
        card.setPadding(dp(20), dp(24), dp(20), dp(24));
        TextView title = text("还没有获取到课表", 16, true, UiTheme.text(this));
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView body = text("打开学习通的「课表」页面，模块会自动捕获页面数据；"
                + "随后每次刷新也会主动拉取。", 13, false, UiTheme.muted(this));
        body.setPadding(0, dp(8), 0, dp(16));
        body.setLineSpacing(0f, 1.25f);
        card.addView(body, new LinearLayout.LayoutParams(-1, -2));
        card.addView(refreshButton("刷新课表", 0), buttonParams());
        return card;
    }

    private View emptyWeekCard(int week) {
        LinearLayout card = card();
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        TextView title = text("第 " + week + " 周没有课程数据", 15, true, UiTheme.text(this));
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView body = text("模块默认只拉取当前周。点下面按钮向学习通请求这一周的课表。",
                12, false, UiTheme.muted(this));
        body.setPadding(0, dp(6), 0, dp(14));
        body.setLineSpacing(0f, 1.25f);
        card.addView(body, new LinearLayout.LayoutParams(-1, -2));
        card.addView(refreshButton("获取第 " + week + " 周", week), buttonParams());
        return card;
    }

    private View refreshButton(String label, int week) {
        TextView button = text(label, 14, true, Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(22), dp(10), dp(22), dp(10));
        button.setBackground(UiTheme.fillOnly(this, UiTheme.accent(this), dp(12)));
        button.setOnClickListener(v -> {
            if (week > 0) {
                RefreshBridge.requestWeek(this, week);
                Toast.makeText(this, "已请求第 " + week + " 周课表", Toast.LENGTH_SHORT).show();
            } else {
                requestRefresh();
            }
        });
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.gravity = Gravity.CENTER_HORIZONTAL;
        return p;
    }

    // -- actions --

    private void requestRefresh() {
        long now = System.currentTimeMillis();
        if (now < refreshBlockedUntil) {
            Toast.makeText(this, "刷新刚请求过，稍候几秒", Toast.LENGTH_SHORT).show();
            return;
        }
        refreshBlockedUntil = now + REFRESH_GUARD_MS;
        RefreshBridge.requestFull(this);
        Toast.makeText(this, "已请求刷新，获取到新数据会自动更新", Toast.LENGTH_SHORT).show();
    }

    private void showLessonDialog(Lesson lesson) {
        String day = lesson.dayOfWeek >= 1 && lesson.dayOfWeek <= 7
                ? DAY_NAMES[lesson.dayOfWeek - 1] : "周" + lesson.dayOfWeek;
        StringBuilder message = new StringBuilder();
        message.append("时间：").append(day).append(" ")
                .append(ScheduleMath.formatSections(lesson.beginSection, lesson.sections));
        String label = lesson.label();
        if (!label.isEmpty()) {
            message.append("\n地点／教师：").append(label);
        }
        message.append("\n周次：").append(
                lesson.weeks == null || lesson.weeks.trim().isEmpty()
                        ? "每周" : lesson.weeks + " 周");
        new AlertDialog.Builder(this)
                .setTitle(lesson.name)
                .setMessage(message.toString())
                .setPositiveButton("知道了", null)
                .show();
    }

    // -- helpers --

    private int[] courseColors(String name) {
        int[][] palette = UiTheme.dark(this) ? DARK_COURSE_COLORS : LIGHT_COURSE_COLORS;
        int hash = name == null ? 0 : Math.abs(name.hashCode());
        return palette[hash % palette.length];
    }

    private int todayTint() {
        int accent = UiTheme.accent(this);
        return Color.argb(UiTheme.dark(this) ? 34 : 20, Color.red(accent),
                Color.green(accent), Color.blue(accent));
    }

    private String dateLabel(long firstWeekDate, int week, int day) {
        if (firstWeekDate <= 0L) {
            return "";
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(firstWeekDate);
        calendar.add(Calendar.DAY_OF_MONTH, (week - 1) * 7 + (day - 1));
        return (calendar.get(Calendar.MONTH) + 1) + "/" + calendar.get(Calendar.DAY_OF_MONTH);
    }

    private String weekRangeLabel(long firstWeekDate, int week) {
        if (firstWeekDate <= 0L) {
            return "开学时间未知";
        }
        return dateLabel(firstWeekDate, week, 1) + " – " + dateLabel(firstWeekDate, week, 7);
    }

    private LinearLayout.LayoutParams groupParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(12));
        return p;
    }

    private LinearLayout.LayoutParams headerParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(38));
        p.setMargins(dp(1), dp(1), dp(1), dp(2));
        return p;
    }

    private LinearLayout.LayoutParams axisParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(CELL_HEIGHT_DP));
        p.setMargins(dp(1), dp(1), dp(1), dp(1));
        return p;
    }

    private LinearLayout.LayoutParams emptyParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(CELL_HEIGHT_DP));
        p.setMargins(dp(1), dp(1), dp(1), dp(1));
        return p;
    }

    private LinearLayout.LayoutParams cellParams(TimetableGrid.Cell cell) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, cell.span * dp(CELL_HEIGHT_DP));
        p.setMargins(dp(1), dp(1), dp(1), dp(1));
        return p;
    }
}
