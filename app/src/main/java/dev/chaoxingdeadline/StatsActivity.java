package dev.chaoxingdeadline;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only overview: pending/expired/done counts, a next-7-days due distribution
 * and a per-course load ranking. Everything is computed from the same activeItems()
 * the list, widgets and notifications use, so the numbers always agree.
 */
public final class StatsActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applySystemBars();
        setContentView(buildContent());
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiTheme.background(this));
        root.setPadding(dp(20), statusBarHeight() + dp(8), dp(20), dp(16));

        root.addView(titleBar("统计概览"), new LinearLayout.LayoutParams(-1, dp(48)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(10), 0, 0);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        List<DeadlineItem> items = new DeadlineStore(this).activeItems();
        long now = System.currentTimeMillis();

        int pending = 0, expired = 0, done = 0, vanished = 0;
        int[] perDay = new int[7];
        Map<String, Integer> perCourse = new HashMap<>();
        for (DeadlineItem item : items) {
            if (item == null) {
                continue;
            }
            if (item.submitted) {
                done++;
                continue;
            }
            if (item.dueAt <= now) {
                expired++;
                continue;
            }
            if (item.vanishedAt > 0L) {
                // Probably revoked: counted separately, never as a pending deadline.
                vanished++;
                continue;
            }
            pending++;
            int day = dayOffset(item.dueAt, now);
            if (day >= 0 && day < perDay.length) {
                perDay[day]++;
            }
            String course = item.course == null || item.course.trim().isEmpty()
                    ? "未识别课程" : item.course.trim();
            Integer count = perCourse.get(course);
            perCourse.put(course, count == null ? 1 : count + 1);
        }

        content.addView(sectionHeader("汇总"));
        LinearLayout summary = card();
        summary.addView(statLine("未完成", String.valueOf(pending), UiTheme.accent(this)));
        summary.addView(statLine("已过期", String.valueOf(expired), UiTheme.warningText(this)));
        summary.addView(statLine("已完成", String.valueOf(done), UiTheme.muted(this)));
        if (AppSettings.autoDeleteExpired(this)) {
            // Without this note "已完成 0" looks like a bug rather than a consequence of the
            // cleanup setting: submitted and expired rows are deleted, so they cannot be counted.
            summary.addView(mutedRow("已开启「自动删除已完成待办」：已提交和已截止的记录会被自动清理，"
                    + "上面这两项只统计尚未清理的部分。"));
        }
        if (vanished > 0) {
            summary.addView(mutedRow("连续两次完整刷新均未出现的待办 " + vanished
                    + " 项，已暂停提醒并标记「可能已撤销」，不计入未完成。"));
        }
        content.addView(summary, groupParams());

        content.addView(sectionHeader("未来 7 天"));
        LinearLayout days = card();
        String[] dayNames = dayNames();
        boolean any = false;
        for (int i = 0; i < perDay.length; i++) {
            if (perDay[i] <= 0) {
                continue;
            }
            any = true;
            days.addView(statLine(dayNames[i], perDay[i] + " 项",
                    i == 0 ? UiTheme.accent(this) : UiTheme.text(this)));
        }
        if (!any) {
            days.addView(mutedRow("未来 7 天没有截止项。"));
        }
        content.addView(days, groupParams());

        content.addView(sectionHeader("课程分布（未完成）"));
        LinearLayout courses = card();
        if (perCourse.isEmpty()) {
            courses.addView(mutedRow("暂无未完成待办。"));
        } else {
            List<Map.Entry<String, Integer>> entries = new ArrayList<>(perCourse.entrySet());
            Collections.sort(entries, (a, b) -> b.getValue() - a.getValue());
            for (Map.Entry<String, Integer> entry : entries) {
                courses.addView(statLine(entry.getKey(), entry.getValue() + " 项", UiTheme.text(this)));
            }
        }
        content.addView(courses, groupParams());

        content.addView(sectionHeader("类型分布（未完成）"));
        LinearLayout types = card();
        int homework = 0, exam = 0, chapter = 0, activity = 0;
        for (DeadlineItem item : items) {
            if (item == null || item.submitted || item.dueAt <= now) {
                continue;
            }
            if ("\u4f5c\u4e1a".equals(item.type)) {
                homework++;
            } else if ("\u8003\u8bd5".equals(item.type)) {
                exam++;
            } else if ("\u7ae0\u8282".equals(item.type)) {
                chapter++;
            } else if ("\u8bfe\u5802\u6d3b\u52a8".equals(item.type)) {
                activity++;
            }
        }
        if (homework + exam + chapter + activity == 0) {
            types.addView(mutedRow("暂无未完成待办。"));
        } else {
            if (homework > 0) types.addView(statLine("作业", homework + " 项", UiTheme.badgeHomework(this)));
            if (exam > 0) types.addView(statLine("考试", exam + " 项", UiTheme.badgeExam(this)));
            if (chapter > 0) types.addView(statLine("章节任务", chapter + " 项", UiTheme.badgeChapter(this)));
            if (activity > 0) types.addView(statLine("课堂活动", activity + " 项", UiTheme.badgeActivity(this)));
        }
        content.addView(types, groupParams());
        return root;
    }

    /** Whole-day offset between the due date and today (0 = today). */
    private static int dayOffset(long dueAt, long now) {
        Calendar due = Calendar.getInstance();
        due.setTimeInMillis(dueAt);
        zeroTime(due);
        Calendar today = Calendar.getInstance();
        today.setTimeInMillis(now);
        zeroTime(today);
        long diff = due.getTimeInMillis() - today.getTimeInMillis();
        return (int) (diff / (24L * 60L * 60L * 1000L));
    }

    private static void zeroTime(Calendar calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
    }

    private String[] dayNames() {
        String[] names = new String[7];
        String[] week = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        Calendar calendar = Calendar.getInstance();
        for (int i = 0; i < names.length; i++) {
            if (i == 0) {
                names[i] = "今天";
            } else if (i == 1) {
                names[i] = "明天";
            } else {
                names[i] = week[calendar.get(Calendar.DAY_OF_WEEK) - 1];
            }
            calendar.add(Calendar.DAY_OF_MONTH, 1);
        }
        return names;
    }

    private View statLine(String label, String value, int valueColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(11), dp(16), dp(11));
        row.addView(text(label, 14, false, UiTheme.text(this)), new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(text(value, 14, true, valueColor), new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private View mutedRow(String value) {
        TextView view = text(value, 13, false, UiTheme.muted(this));
        view.setPadding(dp(16), dp(12), dp(16), dp(12));
        return view;
    }

    private LinearLayout titleBar(String titleValue) {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = icon("←", 28, UiTheme.accent(this));
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(44), -1));
        TextView title = text(titleValue, 22, true, UiTheme.text(this));
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0, -1, 1f));
        top.addView(new View(this), new LinearLayout.LayoutParams(dp(44), -1));
        return top;
    }

    private LinearLayout.LayoutParams groupParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(12));
        return p;
    }
}
