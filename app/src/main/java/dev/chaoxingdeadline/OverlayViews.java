package dev.chaoxingdeadline;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * View builders for the surfaces the hook draws inside the Chaoxing process: the
 * home-panel sheet opened from the floating "Deadline" button, its settings page and
 * the automatic deadline overlay dialog. Everything here is pure view construction —
 * data, settings and callbacks are passed in, so this class holds no hook state.
 */
final class OverlayViews {
    private OverlayViews() {}

    /** Window-size picker callback used by the home settings page. */
    interface OverlayWindowAction {
        void set(int hours);
    }

    /** One row of overlay/panel data; identity fields drive the dedup helpers below. */
    static final class OverlayTodo {
        final String type;
        final String title;
        final String course;
        final long dueAt;

        OverlayTodo(String type, String title, String course, long dueAt) {
            this.type = type;
            this.title = title;
            this.course = course;
            this.dueAt = dueAt;
        }
    }

    static View homePanelView(Activity activity, List<OverlayTodo> todos, long updatedAt,
                              String scanStatus, Runnable closeAction, Runnable settingsAction) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiTheme.dp(activity, 20), UiTheme.dp(activity, 18), UiTheme.dp(activity, 20), UiTheme.dp(activity, 16));
        root.setBackground(UiTheme.rounded(activity, UiTheme.sheet(activity), UiTheme.dp(activity, 22), UiTheme.stroke(activity)));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText("Deadline");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiTheme.text(activity));
        titles.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(activity);
        String updated = updatedAt > 0L ? " · 更新于 " + DateText.deadlineTime(updatedAt) : " · 暂无刷新时间";
        subtitle.setText("共 " + todos.size() + " 个未完成待办" + updated);
        subtitle.setTextSize(12);
        subtitle.setTextColor(UiTheme.muted(activity));
        subtitle.setPadding(0, UiTheme.dp(activity, 3), 0, 0);
        titles.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        TextView scanStatusView = new TextView(activity);
        scanStatusView.setText(scanStatus);
        scanStatusView.setTextSize(12);
        scanStatusView.setTextColor(UiTheme.muted(activity));
        scanStatusView.setPadding(0, UiTheme.dp(activity, 3), 0, 0);
        titles.addView(scanStatusView, new LinearLayout.LayoutParams(-1, -2));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView settings = smallPanelButton(activity, "设置", false);
        settings.setOnClickListener(v -> {
            if (settingsAction != null) {
                settingsAction.run();
            }
        });
        header.addView(settings, new LinearLayout.LayoutParams(-2, -2));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, UiTheme.dp(activity, 14), 0, 0);
        if (todos.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("暂无未完成待办\n打开学习通后会自动刷新");
            empty.setTextSize(14);
            empty.setTextColor(UiTheme.muted(activity));
            empty.setGravity(android.view.Gravity.CENTER);
            empty.setLineSpacing(0f, 1.2f);
            empty.setPadding(0, UiTheme.dp(activity, 28), 0, UiTheme.dp(activity, 28));
            list.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        } else {
            for (OverlayTodo todo : todos) {
                list.addView(overlayRow(activity, todo));
            }
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        int maxHeight = Math.min(UiTheme.dp(activity, 460), (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.62f));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, maxHeight));

        TextView close = smallPanelButton(activity, "关闭", true);
        close.setOnClickListener(v -> {
            if (closeAction != null) {
                closeAction.run();
            }
        });
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, -2);
        closeParams.setMargins(0, UiTheme.dp(activity, 8), 0, 0);
        root.addView(close, closeParams);
        return root;
    }

    static View homeSettingsView(Activity activity, boolean overlayEnabled, int currentWindowHours,
                                 Runnable backAction, Runnable closeAction,
                                 Runnable fullSettingsAction, Runnable toggleOverlayAction,
                                 OverlayWindowAction windowAction) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiTheme.dp(activity, 20), UiTheme.dp(activity, 18), UiTheme.dp(activity, 20), UiTheme.dp(activity, 16));
        root.setBackground(UiTheme.rounded(activity, UiTheme.sheet(activity), UiTheme.dp(activity, 22), UiTheme.stroke(activity)));

        TextView title = new TextView(activity);
        title.setText("设置");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiTheme.text(activity));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        Space topSpace = new Space(activity);
        root.addView(topSpace, new LinearLayout.LayoutParams(1, UiTheme.dp(activity, 12)));

        root.addView(settingsRow(activity,
                "学习通内弹窗",
                "控制进入学习通首页时是否显示待办弹窗",
                overlayEnabled ? "已开启" : "已关闭",
                true,
                toggleOverlayAction));

        TextView section = new TextView(activity);
        section.setText("弹窗范围");
        section.setTextSize(13);
        section.setTypeface(Typeface.DEFAULT_BOLD);
        section.setTextColor(UiTheme.muted(activity));
        section.setPadding(0, UiTheme.dp(activity, 12), 0, UiTheme.dp(activity, 6));
        root.addView(section, new LinearLayout.LayoutParams(-1, -2));

        for (int option : AppSettings.overlayWindowOptions()) {
            String label = overlayWindowLabel(option);
            boolean selected = option == currentWindowHours;
            root.addView(settingsRow(activity,
                    label,
                    selected ? "当前使用" : "点击切换",
                    selected ? "✓" : "",
                    selected,
                    () -> {
                        if (windowAction != null) {
                            windowAction.set(option);
                        }
                    }));
        }

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, UiTheme.dp(activity, 14), 0, 0);
        TextView back = smallPanelButton(activity, "返回待办", false);
        back.setOnClickListener(v -> {
            if (backAction != null) {
                backAction.run();
            }
        });
        actions.addView(back, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView full = smallPanelButton(activity, "完整设置", false);
        full.setOnClickListener(v -> {
            if (fullSettingsAction != null) {
                fullSettingsAction.run();
            }
        });
        LinearLayout.LayoutParams fullParams = new LinearLayout.LayoutParams(0, -2, 1f);
        fullParams.setMargins(UiTheme.dp(activity, 8), 0, 0, 0);
        actions.addView(full, fullParams);
        root.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        TextView close = smallPanelButton(activity, "关闭", true);
        close.setOnClickListener(v -> {
            if (closeAction != null) {
                closeAction.run();
            }
        });
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, -2);
        closeParams.setMargins(0, UiTheme.dp(activity, 8), 0, 0);
        root.addView(close, closeParams);
        return root;
    }

    private static View settingsRow(Activity activity, String title, String subtitle, String value,
                                    boolean highlighted, Runnable action) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(UiTheme.dp(activity, 14), UiTheme.dp(activity, 11), UiTheme.dp(activity, 14), UiTheme.dp(activity, 11));
        row.setClickable(true);
        row.setBackground(UiTheme.rounded(activity,
                highlighted ? UiTheme.card(activity) : UiTheme.sheet(activity),
                UiTheme.dp(activity, 14), UiTheme.stroke(activity)));
        if (action != null) {
            row.setOnClickListener(v -> action.run());
        }

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextSize(14);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(UiTheme.text(activity));
        texts.addView(titleView, new LinearLayout.LayoutParams(-1, -2));
        TextView subView = new TextView(activity);
        subView.setText(subtitle);
        subView.setTextSize(12);
        subView.setTextColor(UiTheme.muted(activity));
        subView.setPadding(0, UiTheme.dp(activity, 3), 0, 0);
        texts.addView(subView, new LinearLayout.LayoutParams(-1, -2));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView valueView = new TextView(activity);
        valueView.setText(value);
        valueView.setTextSize(13);
        valueView.setTypeface(Typeface.DEFAULT_BOLD);
        valueView.setTextColor(highlighted ? UiTheme.accent(activity) : UiTheme.muted(activity));
        valueView.setPadding(UiTheme.dp(activity, 10), 0, 0, 0);
        row.addView(valueView, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, UiTheme.dp(activity, 8));
        row.setLayoutParams(params);
        return row;
    }

    private static TextView smallPanelButton(Activity activity, String text, boolean primary) {
        TextView button = new TextView(activity);
        button.setText(text);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(android.view.Gravity.CENTER);
        button.setPadding(UiTheme.dp(activity, 12), UiTheme.dp(activity, 8), UiTheme.dp(activity, 12), UiTheme.dp(activity, 8));
        if (primary) {
            button.setTextColor(Color.WHITE);
            button.setBackground(UiTheme.fillOnly(activity, UiTheme.accent(activity), UiTheme.dp(activity, 12)));
        } else {
            button.setTextColor(UiTheme.accent(activity));
            button.setBackground(UiTheme.rounded(activity, UiTheme.card(activity), UiTheme.dp(activity, 12), UiTheme.stroke(activity)));
        }
        return button;
    }

    static View overlayView(Activity activity, List<OverlayTodo> todos, boolean realtime,
                            int windowHours, Runnable closeAction) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiTheme.dp(activity, 20), UiTheme.dp(activity, 18), UiTheme.dp(activity, 20), UiTheme.dp(activity, 16));
        root.setBackground(UiTheme.rounded(activity, UiTheme.sheet(activity), UiTheme.dp(activity, 22), UiTheme.stroke(activity)));

        TextView title = new TextView(activity);
        title.setText(windowHours == AppSettings.OVERLAY_WINDOW_ALL ? "待办提醒" : "临近截止待办");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiTheme.text(activity));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView count = new TextView(activity);
        count.setText(overlayWindowLabel(windowHours) + "共 " + todos.size() + " 个待办");
        count.setTextSize(13);
        count.setTextColor(UiTheme.muted(activity));
        count.setPadding(0, UiTheme.dp(activity, 4), 0, UiTheme.dp(activity, 4));
        root.addView(count, new LinearLayout.LayoutParams(-1, -2));

        if (!realtime) {
            TextView notice = new TextView(activity);
            notice.setText("本次结果仅依据上次刷新得出的判断，无法判断最新待办，仅供参考");
            notice.setTextSize(12);
            notice.setTextColor(UiTheme.accent(activity));
            notice.setLineSpacing(0f, 1.12f);
            notice.setPadding(0, 0, 0, UiTheme.dp(activity, 14));
            root.addView(notice, new LinearLayout.LayoutParams(-1, -2));
        }

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        for (OverlayTodo todo : todos) {
            list.addView(overlayRow(activity, todo));
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        int maxHeight = Math.min(UiTheme.dp(activity, 380), (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.52f));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, maxHeight));

        TextView close = new TextView(activity);
        close.setText("知道了");
        close.setTextSize(15);
        close.setTypeface(Typeface.DEFAULT_BOLD);
        close.setGravity(android.view.Gravity.CENTER);
        close.setTextColor(Color.WHITE);
        close.setBackground(UiTheme.fillOnly(activity, UiTheme.accent(activity), UiTheme.dp(activity, 12)));
        close.setPadding(0, UiTheme.dp(activity, 10), 0, UiTheme.dp(activity, 10));
        close.setOnClickListener(v -> {
            if (closeAction != null) {
                closeAction.run();
            }
        });
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, -2);
        closeParams.setMargins(0, UiTheme.dp(activity, 8), 0, 0);
        root.addView(close, closeParams);
        return root;
    }

    private static View overlayRow(Activity activity, OverlayTodo todo) {
        long delta = todo.dueAt - System.currentTimeMillis();
        boolean urgent = delta <= 12L * 60L * 60L * 1000L;

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(UiTheme.dp(activity, 14), UiTheme.dp(activity, 12), UiTheme.dp(activity, 14), UiTheme.dp(activity, 12));
        int rowBg = urgent ? UiTheme.dangerBg(activity) : UiTheme.card(activity);
        int rowStroke = urgent ? overlayDangerStroke(activity) : UiTheme.stroke(activity);
        row.setBackground(UiTheme.rounded(activity, rowBg, UiTheme.dp(activity, 14), rowStroke));

        TextView title = new TextView(activity);
        title.setText(todo.title == null || todo.title.isEmpty() ? "未命名" : todo.title);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiTheme.text(activity));
        title.setLineSpacing(0f, 1.08f);
        row.addView(title, new LinearLayout.LayoutParams(-1, -2));

        if (todo.course != null && !todo.course.isEmpty()) {
            TextView course = new TextView(activity);
            course.setText(todo.course);
            course.setTextSize(13);
            course.setTextColor(UiTheme.muted(activity));
            course.setPadding(0, UiTheme.dp(activity, 5), 0, 0);
            row.addView(course, new LinearLayout.LayoutParams(-1, -2));
        }

        LinearLayout meta = new LinearLayout(activity);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(android.view.Gravity.CENTER_VERTICAL);
        meta.setPadding(0, UiTheme.dp(activity, 10), 0, 0);

        TextView badge = new TextView(activity);
        badge.setText(todo.type);
        badge.setTextSize(12);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        int badgeColor;
        int badgeBg;
        if ("考试".equals(todo.type)) {
            badgeColor = UiTheme.badgeExam(activity);
            badgeBg = UiTheme.badgeExamBg(activity);
        } else if ("课堂活动".equals(todo.type)) {
            badgeColor = UiTheme.badgeActivity(activity);
            badgeBg = UiTheme.badgeActivityBg(activity);
        } else {
            badgeColor = UiTheme.badgeHomework(activity);
            badgeBg = UiTheme.badgeHomeworkBg(activity);
        }
        badge.setTextColor(badgeColor);
        badge.setBackground(UiTheme.fillOnly(activity, badgeBg, UiTheme.dp(activity, 999)));
        badge.setPadding(UiTheme.dp(activity, 8), UiTheme.dp(activity, 3), UiTheme.dp(activity, 8), UiTheme.dp(activity, 3));
        meta.addView(badge, new LinearLayout.LayoutParams(-2, -2));

        TextView due = new TextView(activity);
        due.setText(DateText.dueLine(todo.dueAt));
        due.setTextSize(13);
        due.setTypeface(Typeface.DEFAULT_BOLD);
        due.setTextColor(urgent ? overlayDangerText(activity) : UiTheme.muted(activity));
        due.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        due.setLineSpacing(0f, 1.05f);
        LinearLayout.LayoutParams dueParams = new LinearLayout.LayoutParams(0, -2, 1f);
        dueParams.setMargins(UiTheme.dp(activity, 12), 0, 0, 0);
        meta.addView(due, dueParams);
        row.addView(meta, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, UiTheme.dp(activity, 10));
        row.setLayoutParams(params);
        return row;
    }

    // -- identity helpers shared with the hook's list building --

    /** Same task: same type, same normalized title, deadline within five minutes. */
    static boolean isSameOverlayTodo(OverlayTodo a, OverlayTodo b) {
        if (a == null || b == null) {
            return false;
        }
        if (!safe(a.type).equals(safe(b.type))) {
            return false;
        }
        String titleA = normalizeOverlayText(a.title);
        String titleB = normalizeOverlayText(b.title);
        if (titleA.isEmpty() || !titleA.equals(titleB)) {
            return false;
        }
        return Math.abs(a.dueAt - b.dueAt) <= 5L * 60L * 1000L;
    }

    /** Merges two captures of one task: longest title/course win, earliest deadline wins. */
    static OverlayTodo betterOverlayTodo(OverlayTodo a, OverlayTodo b) {
        String courseA = safe(a.course);
        String courseB = safe(b.course);
        String titleA = safe(a.title);
        String titleB = safe(b.title);
        String course = courseA.length() >= courseB.length() ? courseA : courseB;
        String title = titleA.length() >= titleB.length() ? titleA : titleB;
        long dueAt = Math.min(a.dueAt, b.dueAt);
        return new OverlayTodo(safe(a.type).isEmpty() ? b.type : a.type, title, course, dueAt);
    }

    static String overlayIdentity(OverlayTodo todo) {
        if (todo == null) {
            return "";
        }
        return safe(todo.type) + "|" + normalizeOverlayText(todo.title);
    }

    static String normalizeOverlayText(String value) {
        return safe(value).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    static String overlayWindowLabel(int hours) {
        if (hours == AppSettings.OVERLAY_WINDOW_ALL) {
            return "所有未完成";
        }
        if (hours == 72) {
            return "3天内";
        }
        return hours + "小时内";
    }

    private static int overlayDangerStroke(Context context) {
        return UiTheme.dark(context) ? Color.rgb(127, 55, 55) : Color.rgb(255, 190, 200);
    }

    private static int overlayDangerText(Context context) {
        return UiTheme.dark(context) ? Color.rgb(248, 113, 113) : Color.rgb(194, 65, 72);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
