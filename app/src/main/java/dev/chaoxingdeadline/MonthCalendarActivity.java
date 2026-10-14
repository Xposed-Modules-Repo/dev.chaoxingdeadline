package dev.chaoxingdeadline;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 待办月历：整月截止分布一屏可见。每天一个格子带角标计数，点某天在下方展开当天待办。
 * 数据来自 DeadlineStore，纯本地读取；左右箭头切换月份。
 */
public final class MonthCalendarActivity extends BaseActivity {
    private static final String[] WEEK_HEADERS = {"日", "一", "二", "三", "四", "五", "六"};

    private int year;
    private int month;
    private long selectedDayStart;
    private TextView monthTitle;
    private LinearLayout gridContainer;
    private LinearLayout dayDetail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applySystemBars();
        Calendar now = Calendar.getInstance();
        year = now.get(Calendar.YEAR);
        month = now.get(Calendar.MONTH) + 1;
        selectedDayStart = WeekGrouping.dayStart(System.currentTimeMillis());
        setContentView(buildContent());
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiTheme.background(this));
        root.setPadding(dp(16), statusBarHeight() + dp(8), dp(16), dp(16));

        // --- header: ‹ 年月 › ---
        LinearLayout header = hbox();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = icon("←", 28, UiTheme.accent(this));
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(48)));
        TextView prev = icon("‹", 28, UiTheme.accent(this));
        prev.setOnClickListener(v -> shiftMonth(-1));
        header.addView(prev, new LinearLayout.LayoutParams(dp(44), dp(48)));
        monthTitle = text("", 19, true, UiTheme.text(this));
        monthTitle.setGravity(Gravity.CENTER);
        header.addView(monthTitle, new LinearLayout.LayoutParams(0, dp(48), 1f));
        TextView next = icon("›", 28, UiTheme.accent(this));
        next.setOnClickListener(v -> shiftMonth(1));
        header.addView(next, new LinearLayout.LayoutParams(dp(44), dp(48)));
        TextView today = icon("•", 24, UiTheme.accent(this));
        today.setOnClickListener(v -> jumpToToday());
        header.addView(today, new LinearLayout.LayoutParams(dp(44), dp(48)));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(8), 0, 0);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // --- weekday header ---
        LinearLayout weekHeader = hbox();
        for (String label : WEEK_HEADERS) {
            TextView cell = text(label, 12, false, UiTheme.muted(this));
            cell.setGravity(Gravity.CENTER);
            weekHeader.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        content.addView(weekHeader, new LinearLayout.LayoutParams(-1, -2));

        // --- month grid ---
        gridContainer = new LinearLayout(this);
        gridContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(gridContainer, new LinearLayout.LayoutParams(-1, -2));

        // --- selected day detail ---
        dayDetail = new LinearLayout(this);
        dayDetail.setOrientation(LinearLayout.VERTICAL);
        content.addView(dayDetail, new LinearLayout.LayoutParams(-1, -2));

        render();
        return root;
    }

    private void shiftMonth(int delta) {
        int[] moved = delta < 0 ? MonthGrid.previous(year, month) : MonthGrid.next(year, month);
        year = moved[0];
        month = moved[1];
        render();
    }

    private void jumpToToday() {
        Calendar now = Calendar.getInstance();
        year = now.get(Calendar.YEAR);
        month = now.get(Calendar.MONTH) + 1;
        selectedDayStart = WeekGrouping.dayStart(System.currentTimeMillis());
        render();
    }

    private void render() {
        monthTitle.setText(MonthGrid.monthLabel(year, month));
        List<DeadlineItem> items = new DeadlineStore(this).activeItems();
        long now = System.currentTimeMillis();

        // count only still-live deadlines per day; submitted/revoked/past are noise here.
        Map<Long, Integer> counts = new HashMap<>();
        Map<Long, List<DeadlineItem>> byDay = new HashMap<>();
        for (DeadlineItem item : items) {
            if (item == null || item.submitted || item.dueAt <= now || item.vanishedAt > 0L) {
                continue;
            }
            long dayStart = WeekGrouping.dayStart(item.dueAt);
            Integer count = counts.get(dayStart);
            counts.put(dayStart, count == null ? 1 : count + 1);
            List<DeadlineItem> bucket = byDay.get(dayStart);
            if (bucket == null) {
                bucket = new ArrayList<>();
                byDay.put(dayStart, bucket);
            }
            bucket.add(item);
        }

        renderGrid(counts, now);
        renderDetail(byDay.get(selectedDayStart), now);
    }

    private void renderGrid(Map<Long, Integer> counts, long now) {
        gridContainer.removeAllViews();
        long todayStart = WeekGrouping.dayStart(now);
        List<MonthGrid.Cell> cells = MonthGrid.build(year, month, counts);
        for (int row = 0; row < cells.size(); row += MonthGrid.COLUMNS) {
            LinearLayout line = hbox();
            for (int col = 0; col < MonthGrid.COLUMNS; col++) {
                final MonthGrid.Cell cell = cells.get(row + col);
                line.addView(dayCell(cell, cell.dayStart == todayStart,
                        cell.dayStart == selectedDayStart), cellParams());
            }
            gridContainer.addView(line, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private View dayCell(final MonthGrid.Cell cell, boolean isToday, boolean isSelected) {
        TextView view = new TextView(this);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(2), dp(6), dp(2), dp(6));
        if (cell.isBlank()) {
            view.setText("");
            return view;
        }
        String label = String.valueOf(cell.dayOfMonth);
        if (cell.count > 0) {
            label += "\n" + cell.count;
        }
        view.setText(label);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, cell.count > 0 ? 13 : 12);
        view.setLineSpacing(0f, 1.05f);
        int color;
        if (isSelected) {
            color = UiTheme.accent(this);
        } else if (isToday) {
            color = UiTheme.dangerText(this);
        } else if (cell.count > 0) {
            color = UiTheme.text(this);
        } else {
            color = UiTheme.muted(this);
        }
        view.setTextColor(color);
        view.setTypeface(null, isSelected || isToday
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        view.setBackground(UiTheme.rounded(this,
                isSelected ? UiTheme.sheet(this) : 0x00000000, dp(10), UiTheme.stroke(this)));
        view.setOnClickListener(v -> {
            selectedDayStart = cell.dayStart;
            render();
        });
        return view;
    }

    private LinearLayout.LayoutParams cellParams() {
        return new LinearLayout.LayoutParams(0, dp(52), 1f);
    }

    private void renderDetail(List<DeadlineItem> items, long now) {
        dayDetail.removeAllViews();
        if (selectedDayStart <= 0L) {
            return;
        }
        dayDetail.addView(sectionHeader(dayHeading()), groupParams());
        LinearLayout card = card();
        if (items == null || items.isEmpty()) {
            card.addView(mutedRow("这一天没有待办。"));
        } else {
            items.sort((a, b) -> Long.compare(a.dueAt, b.dueAt));
            for (DeadlineItem item : items) {
                card.addView(detailRow(item, now));
            }
        }
        dayDetail.addView(card, groupParams());
    }

    private String dayHeading() {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(selectedDayStart);
        long diff = Math.round((selectedDayStart - WeekGrouping.dayStart(System.currentTimeMillis()))
                / (double) (24L * 60L * 60L * 1000L));
        String base = (calendar.get(Calendar.MONTH) + 1) + "月" + calendar.get(Calendar.DAY_OF_MONTH) + "日";
        if (diff == 0L) {
            return base + " · 今天";
        }
        if (diff == 1L) {
            return base + " · 明天";
        }
        return base;
    }

    private View detailRow(DeadlineItem item, long now) {
        LinearLayout row = hbox();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        TextView dot = text("●", 11, false, DeadlineWidgetProvider.typeColor(this, item.type));
        row.addView(dot, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(item.title, 14, true, UiTheme.text(this));
        texts.addView(title, new LinearLayout.LayoutParams(-1, -2));
        String meta = (item.course == null || item.course.isEmpty() ? "" : item.course + " · ")
                + DateText.deadlineTime(item.dueAt) + " · " + WeekGrouping.countdown(item.dueAt, now);
        TextView sub = text(meta, 11, false, UiTheme.muted(this));
        texts.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    private View mutedRow(String value) {
        TextView view = text(value, 13, false, UiTheme.muted(this));
        view.setPadding(dp(14), dp(14), dp(14), dp(14));
        return view;
    }

    private LinearLayout.LayoutParams groupParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(12));
        return p;
    }
}
