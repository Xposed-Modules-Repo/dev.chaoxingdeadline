package dev.chaoxingdeadline;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.app.TimePickerDialog;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public final class SettingsActivity extends BaseActivity {
    private EditText notifyHours;

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

        root.addView(titleBar("设置"), new LinearLayout.LayoutParams(-1, dp(48)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(8), 0, 0);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // -- 改动说明 --
        LinearLayout note = card();
        note.setPadding(dp(16), dp(14), dp(16), dp(14));
        note.addView(text("本 fork 修改说明（suiren0219）", 15, true, UiTheme.text(this)), new LinearLayout.LayoutParams(-1, -2));
        TextView noteBody = text("在原作者功能基础上，本 fork 补上了桌面小组件、课堂活动捕获、免打扰时段、"
                + "每日摘要、备份导出与手动添加待办；早期实验性的「强制深色模式」已整体移除。"
                + "—— suiren0219",
                12, false, UiTheme.muted(this));
        noteBody.setLineSpacing(0f, 1.25f);
        noteBody.setPadding(0, dp(6), 0, 0);
        note.addView(noteBody, new LinearLayout.LayoutParams(-1, -2));
        content.addView(note, new LinearLayout.LayoutParams(-1, -2));

        // -- 原作者功能（上游 qingzhou704）--
        content.addView(sectionHeader("原作者功能 · qingzhou704"));
        LinearLayout upstreamGroup = card();
        upstreamGroup.addView(switchRow("隐藏桌面图标", "隐藏后仍可从 LSPosed 模块设置入口打开", AppSettings.launcherHidden(this),
                (b, c) -> AppSettings.setLauncherHidden(this, c)));
        upstreamGroup.addView(divider());
        upstreamGroup.addView(switchRow("学习通内弹窗", "打开学习通时显示待办摘要", AppSettings.overlayEnabled(this),
                (b, c) -> AppSettings.setOverlayEnabled(this, c)));
        upstreamGroup.addView(divider());
        upstreamGroup.addView(overlayWindowRow());
        upstreamGroup.addView(divider());
        upstreamGroup.addView(switchRow("自动删除已完成待办", "已提交或已截止的项目自动移除", AppSettings.autoDeleteExpired(this),
                (b, c) -> AppSettings.setAutoDeleteExpired(this, c)));
        content.addView(upstreamGroup, groupParams());

        // -- 通知（原作者功能）--
        content.addView(sectionHeader("通知 · 原作者功能"));
        LinearLayout group2 = card();
        group2.addView(switchRow("最后提醒", "任务的截止时间到达3小时和30分钟时提醒", AppSettings.finalReminderEnabled(this),
                (b, c) -> { AppSettings.prefs(this).edit().putBoolean("notify_enabled", c).apply(); DeadlineNotifier.rescheduleUpcomingOnly(this); }));
        group2.addView(divider());
        group2.addView(switchRow("作业提醒", "作业到达设定的提醒时间提醒", AppSettings.notifyHomework(this),
                (b, c) -> { AppSettings.prefs(this).edit().putBoolean("notify_homework", c).apply(); DeadlineNotifier.rescheduleUpcomingOnly(this); }));
        group2.addView(divider());
        group2.addView(switchRow("考试提醒", "考试到达设定的提醒时间提醒", AppSettings.notifyExam(this),
                (b, c) -> { AppSettings.prefs(this).edit().putBoolean("notify_exam", c).apply(); DeadlineNotifier.rescheduleUpcomingOnly(this); }));
        group2.addView(divider());
        group2.addView(switchRow("章节任务提醒", "章节任务点的截止时间提醒，可能产生较多通知", AppSettings.notifyChapter(this),
                (b, c) -> { AppSettings.prefs(this).edit().putBoolean("notify_chapter", c).apply(); DeadlineNotifier.rescheduleUpcomingOnly(this); }));
        group2.addView(divider());
        group2.addView(switchRow("课堂活动提醒", "课堂活动（签到、投票、抢答等）的截止时间提醒，默认关闭", AppSettings.notifyActivity(this),
                (b, c) -> { AppSettings.prefs(this).edit().putBoolean("notify_activity", c).apply(); DeadlineNotifier.rescheduleUpcomingOnly(this); }));
        group2.addView(divider());
        group2.addView(switchRow("上课期间加强检测",
                "上课前 15 分钟对当期课程的课堂活动每 2 分钟刷新一次，签到投票不错过",
                AppSettings.classSessionBoostEnabled(this),
                (b, c) -> AppSettings.setClassSessionBoostEnabled(this, c)));
        group2.addView(divider());
        group2.addView(switchRow("截止时提醒", "到达截止时间的那一刻再提醒一次", AppSettings.notifyAtDue(this),
                (b, c) -> { AppSettings.prefs(this).edit().putBoolean("notify_at_due", c).apply(); DeadlineNotifier.rescheduleUpcomingOnly(this); }));
        group2.addView(divider());
        group2.addView(switchRow("紧急提醒渠道", "30 分钟内或已到截止时，使用独立的「即将截止」通知渠道（可单独设置铃声与免打扰）", AppSettings.urgentChannelEnabled(this),
                (b, c) -> { AppSettings.prefs(this).edit().putBoolean("urgent_channel", c).apply(); }));
        group2.addView(divider());
        group2.addView(switchRow("超级岛 · 最近截止",
                "常驻显示最近截止的待办（小米超级岛 / 实时活动），带倒计时进度，点按打开列表",
                AppSettings.liveNotificationEnabled(this),
                (b, c) -> AppSettings.setLiveNotificationEnabled(this, c)));
        group2.addView(divider());
        group2.addView(hourRow());
        group2.addView(divider());
        group2.addView(typeHoursRow("\u4f5c\u4e1a", "作业"));
        group2.addView(divider());
        group2.addView(typeHoursRow("\u8003\u8bd5", "考试"));
        group2.addView(divider());
        group2.addView(typeHoursRow("\u7ae0\u8282", "章节"));
        group2.addView(divider());
        TextView activityNote = text("课堂活动为即时提醒，不设提前量（签到、投票窗口通常只有几分钟）",
                12, false, UiTheme.muted(this));
        activityNote.setPadding(dp(16), dp(13), dp(16), dp(13));
        group2.addView(activityNote, new LinearLayout.LayoutParams(-1, -2));
        group2.addView(divider());
        View exactAlarm = innerActionRow("精确闹钟权限", exactAlarmSubtitle());
        exactAlarm.setOnClickListener(v -> openExactAlarmSettings());
        group2.addView(exactAlarm);
        group2.addView(divider());
        View testNotification = innerActionRow("发送测试通知", "立即发送一条作业提醒，用来查看通知效果");
        testNotification.setOnClickListener(v -> sendTestNotification());
        group2.addView(testNotification);
        content.addView(group2, groupParams());

        // -- 免打扰（本 fork 新增）--
        content.addView(sectionHeader("免打扰 · 本 fork 新增"));
        LinearLayout quietGroup = card();
        quietGroup.addView(switchRow("免打扰时段", "时段内的提醒不发声、不弹窗，仍会保留在通知栏",
                AppSettings.quietHoursEnabled(this),
                (b, c) -> { AppSettings.setQuietHoursEnabled(this, c); recreate(); }));
        if (AppSettings.quietHoursEnabled(this)) {
            quietGroup.addView(divider());
            View quietTime = innerActionRow("时段", "当前 " + quietRangeLabel());
            quietTime.setOnClickListener(v -> pickQuietHours());
            quietGroup.addView(quietTime);
            quietGroup.addView(divider());
            quietGroup.addView(switchRow("顺延到时段结束", "原本落在时段内的提醒，改为时段结束后第一时间发出",
                    AppSettings.quietDeferEnabled(this),
                    (b, c) -> {
                        AppSettings.prefs(this).edit().putBoolean("quiet_defer", c).apply();
                        DeadlineNotifier.rescheduleAll(this);
                    }));
        }
        content.addView(quietGroup, groupParams());

        // -- 每日摘要（本 fork 新增）--
        content.addView(sectionHeader("每日摘要 · 本 fork 新增"));
        LinearLayout digestGroup = card();
        digestGroup.addView(switchRow("每日摘要", "每天定时推送一条汇总：今天和未来 3 天要截止什么",
                AppSettings.dailyDigestEnabled(this),
                (b, c) -> { AppSettings.setDailyDigestEnabled(this, c); recreate(); }));
        if (AppSettings.dailyDigestEnabled(this)) {
            digestGroup.addView(divider());
            View digestTime = innerActionRow("推送时间", "每天 " + digestClockLabel() + "；点击修改");
            digestTime.setOnClickListener(v -> pickDigestTime());
            digestGroup.addView(digestTime);
        }
        content.addView(digestGroup, groupParams());

        // -- 小组件（本 fork 新增）--
        content.addView(sectionHeader("小组件 · 本 fork 新增"));
        LinearLayout widgetGroup = card();
        widgetGroup.addView(switchRow("显示课程名", "关闭后小组件只显示标题和剩余时间",
                AppSettings.widgetShowCourse(this),
                (b, c) -> { AppSettings.setWidgetShowCourse(this, c); DeadlineWidgetProvider.updateAll(this); }));
        widgetGroup.addView(divider());
        View widgetWindow = innerActionRow("显示范围", "当前：" + AppSettings.overlayWindowLabel(
                AppSettings.widgetWindowHours(this)));
        widgetWindow.setOnClickListener(v -> showWidgetWindowDialog());
        widgetGroup.addView(widgetWindow);
        content.addView(widgetGroup, groupParams());

        // -- 管理 --
        content.addView(sectionHeader("管理"));
        LinearLayout manageGroup = card();
        View course = innerActionRow("课程管理", "手动选择哪些课程的作业、考试、章节任务和课堂活动需要显示");
        course.setOnClickListener(v -> startActivity(new Intent(this, CourseBlockActivity.class)));
        manageGroup.addView(course);
        manageGroup.addView(divider());
        View backup = innerActionRow("备份与导出", "导出 JSON 备份或 ICS 日历，也可从备份恢复");
        backup.setOnClickListener(v -> startActivity(new Intent(this, BackupActivity.class)));
        manageGroup.addView(backup);
        manageGroup.addView(divider());
        View schedule = innerActionRow("课表", "周网格课表：今天高亮、周次切换、手动刷新；首页也有今日课表卡片");
        schedule.setOnClickListener(v -> startActivity(new Intent(this, ScheduleActivity.class)));
        manageGroup.addView(schedule);
        manageGroup.addView(divider());
        manageGroup.addView(switchRow("自动移除已撤销待办",
                "连续两次完整刷新都没出现的待办直接删除；关闭则只在列表里标记「可能已撤销」、暂停提醒",
                AppSettings.autoRemoveVanished(this),
                (b, c) -> AppSettings.setAutoRemoveVanished(this, c)));
        manageGroup.addView(divider());
        manageGroup.addView(repairDuplicatesRow());
        manageGroup.addView(divider());
        View stats = innerActionRow("统计概览", "未完成、已过期、未来 7 天分布与课程分布");
        stats.setOnClickListener(v -> startActivity(new Intent(this, StatsActivity.class)));
        manageGroup.addView(stats);
        content.addView(manageGroup, groupParams());

        // -- 关于 --
        content.addView(sectionHeader("其他"));
        View update = actionRow("获取更新",
                "当前 " + versionSubtitle() + "（" + signatureLabel() + "）；点按打开最新版下载页");
        update.setOnClickListener(v -> openLatestRelease());
        content.addView(update, groupParams());
        View about = actionRow("关于", "版本信息与开源许可");
        about.setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));
        content.addView(about, groupParams());

        return root;
    }

    private String quietRangeLabel() {
        return minuteLabel(AppSettings.quietStartMinute(this))
                + " — " + minuteLabel(AppSettings.quietEndMinute(this))
                + (AppSettings.quietSpansMidnight(this) ? "（跨天）" : "");
    }

    private static String minuteLabel(int minuteOfDay) {
        return String.format(java.util.Locale.CHINA, "%02d:%02d",
                minuteOfDay / 60, minuteOfDay % 60);
    }

    /** Two-step picker: start time first, then end time. */
    private void pickQuietHours() {
        int start = AppSettings.quietStartMinute(this);
        new TimePickerDialog(this, (view, hourOfDay, minute) -> {
            int startMinute = hourOfDay * 60 + minute;
            int end = AppSettings.quietEndMinute(this);
            new TimePickerDialog(this, (endView, endHour, endMinute) -> {
                AppSettings.setQuietHours(this, startMinute, endHour * 60 + endMinute);
                DeadlineNotifier.rescheduleAll(this);
                recreate();
            }, end / 60, end % 60, true).show();
        }, start / 60, start % 60, true).show();
    }

    private void showWidgetWindowDialog() {
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
        new AlertDialog.Builder(this)
                .setTitle("小组件显示范围")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    AppSettings.setWidgetWindowHours(this, values[which]);
                    DeadlineWidgetProvider.updateAll(this);
                    dialog.dismiss();
                    recreate();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String versionSubtitle() {
        try {
            android.content.pm.PackageInfo info =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName + "（versionCode " + info.versionCode + "）";
        } catch (Throwable ignored) {
            return "未知";
        }
    }

    /** debug / release 签名互不兼容；标出来避免用户下载到不能覆盖安装的包。 */
    private String signatureLabel() {
        boolean debuggable = (getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        return debuggable ? "debug 签名" : "release 签名";
    }

    private void openLatestRelease() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/suiren0219/dev.chaoxingdeadline/releases/latest")));
        } catch (Throwable throwable) {
            Toast.makeText(this, "未找到可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private String digestClockLabel() {
        int minute = AppSettings.digestMinuteOfDay(this);
        return String.format(java.util.Locale.CHINA, "%02d:%02d", minute / 60, minute % 60);
    }

    private void pickDigestTime() {
        int minute = AppSettings.digestMinuteOfDay(this);
        new TimePickerDialog(this, (view, hourOfDay, minuteOfHour) -> {
            AppSettings.setDigestMinuteOfDay(this, hourOfDay * 60 + minuteOfHour);
            recreate();
        }, minute / 60, minute % 60, true).show();
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

    private View hourRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(13), dp(14), dp(13));
        row.setMinimumHeight(dp(70));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        texts.addView(text("提醒时间", 16, true, UiTheme.text(this)), new LinearLayout.LayoutParams(-1, -2));
        TextView sub = text("小时，范围 1 — 168", 13, false, UiTheme.muted(this));
        sub.setPadding(0, dp(2), 0, 0);
        texts.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        notifyHours = new EditText(this);
        notifyHours.setInputType(InputType.TYPE_CLASS_NUMBER);
        notifyHours.setSingleLine(true);
        notifyHours.setText(String.valueOf(AppSettings.notifyHours(this)));
        notifyHours.setTextColor(UiTheme.text(this));
        notifyHours.setTextSize(16);
        notifyHours.setGravity(Gravity.CENTER);
        notifyHours.setBackground(UiTheme.fillOnly(this, UiTheme.background(this), dp(12)));
        notifyHours.setPadding(dp(12), 0, dp(12), 0);
        row.addView(notifyHours, new LinearLayout.LayoutParams(dp(64), dp(40)));

        TextView save = icon("✓", 20, UiTheme.accent(this));
        save.setPadding(dp(8), 0, dp(4), 0);
        save.setOnClickListener(v -> saveNotifyHours());
        row.addView(save, new LinearLayout.LayoutParams(-2, dp(40)));
        return row;
    }

    private void saveNotifyHours() {
        int hours;
        try { hours = Integer.parseInt(notifyHours.getText().toString().trim()); }
        catch (Throwable ignored) { hours = 24; }
        hours = Math.max(1, Math.min(168, hours));
        notifyHours.setText(String.valueOf(hours));
        AppSettings.prefs(this).edit().putInt("notify_hours", hours).apply();
        DeadlineNotifier.rescheduleUpcomingOnly(this);
        OverlayBridge.publish(this);
        Toast.makeText(this, "操作成功", Toast.LENGTH_SHORT).show();
    }

    /** Per-type advance row: shows the effective hours, "跟随默认" until overridden. */
    private View typeHoursRow(String type, String label) {
        int hours = AppSettings.notifyHoursForType(this, type);
        int fallback = AppSettings.notifyHours(this);
        String sub = hours == fallback
                ? hours + " 小时（跟随默认）"
                : hours + " 小时（默认 " + fallback + "）";
        View row = innerActionRow(label + "提前量", sub);
        row.setOnClickListener(v -> showTypeHoursDialog(type, label));
        return row;
    }

    private void showTypeHoursDialog(String type, String label) {
        int[] values = {1, 3, 6, 12, 24, 48, 72, 168};
        String[] labels = new String[values.length];
        int current = AppSettings.notifyHoursForType(this, type);
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i] + " 小时";
            if (values[i] == current) {
                checked = i;
            }
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(label + "提前量")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    AppSettings.setNotifyHoursForType(this, type, values[which]);
                    dialog.dismiss();
                    DeadlineNotifier.rescheduleUpcomingOnly(this);
                    recreate();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private View overlayWindowRow() {
        View row = innerActionRow("弹窗范围", "当前显示" + AppSettings.overlayWindowLabel(this));
        row.setOnClickListener(v -> showOverlayWindowDialog());
        return row;
    }

    private void showOverlayWindowDialog() {
        int[] values = AppSettings.overlayWindowOptions();
        String[] labels = new String[values.length];
        int current = AppSettings.overlayWindowHours(this);
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = AppSettings.overlayWindowLabel(values[i]);
            if (values[i] == current) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("弹窗范围")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    AppSettings.setOverlayWindowHours(this, values[which]);
                    OverlayBridge.publish(this);
                    Toast.makeText(this, "操作成功", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                    recreate();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String exactAlarmSubtitle() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return "当前系统无需单独授权";
        }
        return DeadlineNotifier.canScheduleExactAlarms(this)
                ? "已允许，提醒会更准时"
                : "未允许，提醒可能延迟，点击前往开启";
    }

    private void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Toast.makeText(this, "当前系统无需单独开启精确闹钟", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable throwable) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + getPackageName())));
        }
    }

    private void sendTestNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1002);
            Toast.makeText(this, "请先允许通知权限", Toast.LENGTH_SHORT).show();
            return;
        }
        DeadlineNotifier.ensureChannel(this);
        DeadlineNotifier.sendTestNotification(this);
        Toast.makeText(this, "已发送测试通知", Toast.LENGTH_SHORT).show();
    }

    private View switchRow(String title, String subtitle, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(13), dp(14), dp(13));
        row.setMinimumHeight(dp(68));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        texts.addView(text(title, 15, true, UiTheme.text(this)), new LinearLayout.LayoutParams(-1, -2));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 12, false, UiTheme.muted(this));
            sub.setPadding(0, dp(2), 0, 0);
            texts.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        Switch toggle = new Switch(this);
        toggle.setText("");
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(listener);
        row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private View innerActionRow(String title, String subtitle) {
        return actionRow(title, subtitle, false);
    }

    /**
     * Duplicate-repair row. The count is computed live so the row reads "暂无重复" when
     * there is nothing to do — duplicates are also swept once on database upgrade, this
     * is the manual escape hatch for anything a later bug still produces.
     */
    private View repairDuplicatesRow() {
        final TextView[] subtitle = new TextView[1];
        View row = actionRowWithSubtitleView("清理重复待办", subtitle);
        row.setOnClickListener(v -> {
            int removed;
            try {
                removed = new DeadlineStore(this).repairDuplicates();
            } catch (Throwable throwable) {
                removed = 0;
            }
            if (removed > 0) {
                DeadlineNotifier.rescheduleAll(this);
                OverlayBridge.publish(this);
                DeadlineWidgetProvider.updateAll(this);
                DeadlineScheduleWidget.updateAll(this);
                Toast.makeText(this, "已合并 " + removed + " 条重复记录", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "没有需要清理的重复记录", Toast.LENGTH_SHORT).show();
            }
            refreshRepairRow(row, subtitle[0]);
        });
        refreshRepairRow(row, subtitle[0]);
        return row;
    }

    private void refreshRepairRow(View row, TextView subtitle) {
        int duplicates;
        try {
            duplicates = new DeadlineStore(this).countDuplicates();
        } catch (Throwable throwable) {
            duplicates = 0;
        }
        row.setAlpha(duplicates > 0 ? 1f : 0.6f);
        if (subtitle != null) {
            subtitle.setText(duplicates > 0
                    ? "发现 " + duplicates + " 条重复记录，点此合并（保留信息最全的一条）"
                    : "当前没有检测到重复记录");
        }
    }

    private View actionRowWithSubtitleView(String title, TextView[] subtitleOut) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(15), dp(16), dp(15));
        row.setMinimumHeight(dp(70));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        texts.addView(text(title, 15, true, UiTheme.text(this)), new LinearLayout.LayoutParams(-1, -2));
        TextView subtitle = text("", 12, false, UiTheme.muted(this));
        subtitle.setPadding(0, dp(2), 0, 0);
        texts.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));
        subtitleOut[0] = subtitle;
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    private View actionRow(String title, String subtitle) {
        return actionRow(title, subtitle, true);
    }

    private View actionRow(String title, String subtitle, boolean standalone) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(15), dp(16), dp(15));
        row.setMinimumHeight(dp(70));
        if (standalone) {
            row.setBackground(UiTheme.cardBg(this));
        }

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        texts.addView(text(title, 15, true, UiTheme.text(this)), new LinearLayout.LayoutParams(-1, -2));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 12, false, UiTheme.muted(this));
            sub.setPadding(0, dp(2), 0, 0);
            texts.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView arrow = icon("›", 28);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(32), dp(44)));
        return row;
    }

    private LinearLayout.LayoutParams groupParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(16));
        return p;
    }
}
