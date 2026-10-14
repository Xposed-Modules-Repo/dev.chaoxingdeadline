package dev.chaoxingdeadline;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Shown once when a widget is dropped on the home screen. Widgets live in the module
 * process and read these settings directly, so no remote preference round-trip is needed.
 */
public final class DeadlineWidgetConfigActivity extends BaseActivity {
    private int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applySystemBars();
        appWidgetId = getIntent() == null
                ? AppWidgetManager.INVALID_APPWIDGET_ID
                : getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                        AppWidgetManager.INVALID_APPWIDGET_ID);
        setResult(Activity.RESULT_CANCELED,
                new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId));
        setContentView(buildContent());
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiTheme.background(this));
        root.setPadding(dp(20), statusBarHeight() + dp(8), dp(20), dp(16));

        root.addView(titleBar("小组件设置"), new LinearLayout.LayoutParams(-1, dp(48)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(8), 0, 0);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout group = card();
        group.addView(switchRow("显示课程名", "关闭后只显示标题和剩余时间",
                AppSettings.widgetShowCourse(this),
                (b, c) -> AppSettings.setWidgetShowCourse(this, c)));
        group.addView(divider());
        View window = innerActionRow("显示范围", "当前：" + AppSettings.overlayWindowLabel(
                AppSettings.widgetWindowHours(this)));
        window.setOnClickListener(v -> showWindowDialog());
        group.addView(window);
        group.addView(divider());
        View courseFilter = innerActionRow("筛选课程", "当前：" + AppSettings.widgetCourseFilterLabel(this));
        courseFilter.setOnClickListener(v -> showCourseFilterDialog());
        group.addView(courseFilter);
        content.addView(group, groupParams());

        content.addView(sectionHeader("生效方式"));
        LinearLayout note = card();
        note.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView body = text("这些设置对该尺寸的每一个小组件同时生效。"
                + "添加后点击小组件右上角的 ⟳ 可立即刷新。",
                12, false, UiTheme.muted(this));
        body.setLineSpacing(0f, 1.25f);
        note.addView(body, new LinearLayout.LayoutParams(-1, -2));
        content.addView(note, groupParams());

        View done = actionRow("完成", "添加小组件并应用以上设置");
        done.setOnClickListener(v -> finishWithResult());
        content.addView(done, groupParams());

        return root;
    }

    private void showCourseFilterDialog() {
        java.util.List<DeadlineItem> items = new DeadlineStore(this).activeItems();
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (DeadlineItem item : items) {
            if (item.course != null && !item.course.isEmpty()) {
                names.add(item.course);
            }
        }
        java.util.List<String> sorted = new java.util.ArrayList<>(names);
        java.util.Collections.sort(sorted);
        String[] labels = new String[sorted.size() + 1];
        labels[0] = "全部课程";
        for (int i = 0; i < sorted.size(); i++) {
            labels[i + 1] = sorted.get(i);
        }
        int checked = 0;
        String current = AppSettings.widgetCourseFilter(this);
        if (!current.isEmpty()) {
            for (int i = 0; i < sorted.size(); i++) {
                if (sorted.get(i).equals(current)) {
                    checked = i + 1;
                    break;
                }
            }
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("筛选课程")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    AppSettings.setWidgetCourseFilter(this, which == 0 ? "" : sorted.get(which - 1));
                    dialog.dismiss();
                    recreate();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showWindowDialog() {
        int[] values = AppSettings.overlayWindowOptions();
        String[] labels = new String[values.length];
        int current = AppSettings.widgetWindowHours(this);
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = AppSettings.overlayWindowLabel(values[i]);
            if (values[i] == current) {
                checked = i;
            }
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("显示范围")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    AppSettings.setWidgetWindowHours(this, values[which]);
                    dialog.dismiss();
                    recreate();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void finishWithResult() {
        DeadlineWidgetProvider.updateAll(this);
        Intent result = new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        setResult(Activity.RESULT_OK, result);
        Toast.makeText(this, "已添加小组件", Toast.LENGTH_SHORT).show();
        finish();
    }

    private LinearLayout titleBar(String titleValue) {
        LinearLayout top = hbox();
        TextView back = icon("←", 28, UiTheme.accent(this));
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(44), -1));
        TextView title = text(titleValue, 22, true, UiTheme.text(this));
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0, -1, 1f));
        top.addView(new View(this), new LinearLayout.LayoutParams(dp(44), -1));
        return top;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(UiTheme.divider(this));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(1));
        p.leftMargin = dp(16);
        p.rightMargin = dp(16);
        v.setLayoutParams(p);
        return v;
    }

    private View switchRow(String title, String subtitle, boolean checked,
                           CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(13), dp(14), dp(13));
        row.setMinimumHeight(dp(68));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        texts.addView(text(title, 15, true, UiTheme.text(this)), new LinearLayout.LayoutParams(-1, -2));
        TextView sub = text(subtitle, 12, false, UiTheme.muted(this));
        sub.setPadding(0, dp(2), 0, 0);
        texts.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch toggle = new Switch(this);
        toggle.setText("");
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(listener);
        row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private LinearLayout innerActionRow(String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(15), dp(16), dp(15));
        row.setMinimumHeight(dp(70));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        texts.addView(text(title, 15, true, UiTheme.text(this)), new LinearLayout.LayoutParams(-1, -2));
        TextView sub = text(subtitle, 12, false, UiTheme.muted(this));
        sub.setPadding(0, dp(2), 0, 0);
        texts.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView arrow = icon("›", 28);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(32), dp(44)));
        return row;
    }

    private View actionRow(String title, String subtitle) {
        LinearLayout row = innerActionRow(title, subtitle);
        row.setBackground(UiTheme.cardBg(this));
        return row;
    }

    private LinearLayout.LayoutParams groupParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(16));
        return p;
    }
}
