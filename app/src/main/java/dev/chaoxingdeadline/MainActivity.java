package dev.chaoxingdeadline;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Toast;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;

/**
 * Main screen: displays LSPosed activation status, deadline stats, and the deadline list.
 * The app screen only reloads local data; deadline capture is driven by the Chaoxing hook.
 */
public final class MainActivity extends BaseActivity implements App.ServiceListener {
    public static final String EXTRA_OPEN_SETTINGS = "dev.chaoxingdeadline.extra.OPEN_SETTINGS";
    private static final String TARGET_PACKAGE = "com.chaoxing.mobile";

    private final List<DeadlineItem> items = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private DeadlineStore store;
    private LinearLayout itemList;
    private TextView activeBadge;
    private TextView statusText;
    private TextView countPending;
    private TextView countExpired;
    private TextView countBlocked;
    private LinearLayout emptyContainer;
    private LinearLayout scheduleContainer;
    private EditText searchInput;
    private Spinner typeFilter;
    private String searchQuery = "";
    private String typeFilterValue = "";
    /** "list" (flat) or "week" (grouped by day, next 7 days). */
    private String viewMode = "list";
    private TextView listTab;
    private TextView weekTab;

    private final Runnable tick = new Runnable() {
        @Override public void run() { reload(false); handler.postDelayed(this, 5000L); }
    };
    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { reload(false); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applySystemBars();
        store = new DeadlineStore(this);
        setContentView(buildContent());
        DeadlineNotifier.ensureChannel(this);
        requestNotificationPermission();
        reload(true);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            return;
        }
        intent.removeExtra(EXTRA_OPEN_SETTINGS);
        handler.post(() -> startActivity(new Intent(this, SettingsActivity.class)));
    }

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    protected void onStart() {
        super.onStart();
        App.addServiceListener(this);
        IntentFilter filter = new IntentFilter(DeadlineReceiver.ACTION_REFRESH);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(refreshReceiver, filter, RECEIVER_NOT_EXPORTED);
        else
            registerReceiver(refreshReceiver, filter);
        handler.post(tick);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(tick);
        App.removeServiceListener(this);
        unregisterReceiver(refreshReceiver);
        super.onStop();
    }

    @Override
    public void onServiceChanged(XposedService service) {
        runOnUiThread(() -> reload(false));
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(UiTheme.background(this));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), statusBarHeight() + dp(12), dp(20), dp(24));

        // --- header ---
        LinearLayout header = hbox();
        TextView title = text("学习通截止提醒", 26, true, UiTheme.text(this));
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1f));
        TextView addBtn = icon("＋", 26, UiTheme.accent(this));
        addBtn.setOnClickListener(v -> ManualDeadlineDialog.show(this, () -> reload(false)));
        header.addView(addBtn, new LinearLayout.LayoutParams(dp(42), dp(48)));
        TextView settingsBtn = icon("⚙", 26, UiTheme.accent(this));
        settingsBtn.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        header.addView(settingsBtn, new LinearLayout.LayoutParams(dp(42), dp(48)));
        TextView calendarBtn = icon("▦", 24, UiTheme.accent(this));
        calendarBtn.setOnClickListener(v -> startActivity(new Intent(this, MonthCalendarActivity.class)));
        header.addView(calendarBtn, new LinearLayout.LayoutParams(dp(42), dp(48)));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // --- status card ---
        LinearLayout status = card();
        status.setPadding(dp(18), dp(16), dp(18), dp(16));
        activeBadge = text("", 15, true, UiTheme.text(this));
        activeBadge.setPadding(0, 0, 0, dp(6));
        status.addView(activeBadge, new LinearLayout.LayoutParams(-1, -2));
        statusText = text("", 12, false, UiTheme.muted(this));
        status.addView(statusText, new LinearLayout.LayoutParams(-1, -2));
        root.addView(status, marTop(dp(18)));

        // --- 今日课表 card: the timetable's prominent home-screen entry ---
        scheduleContainer = new LinearLayout(this);
        scheduleContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(scheduleContainer, marTop(dp(16)));

        // --- stat chips ---
        LinearLayout stats = hbox();
        countPending = new TextView(this);
        countExpired = new TextView(this);
        countBlocked = new TextView(this);
        stats.addView(statChip("⏳", "待办", countPending), chipParams(1f));
        stats.addView(space(dp(10)));
        stats.addView(statChip("✓", "已完成", countExpired), chipParams(1f));
        stats.addView(space(dp(10)));
        stats.addView(statChip("⊘", "已屏蔽", countBlocked), chipParams(1f));
        root.addView(stats, marTop(dp(16)));

        // --- filter bar ---
        LinearLayout filterBar = new LinearLayout(this);
        filterBar.setOrientation(LinearLayout.HORIZONTAL);
        filterBar.setGravity(Gravity.CENTER_VERTICAL);
        filterBar.setPadding(dp(14), dp(9), dp(14), dp(9));
        filterBar.setBackground(UiTheme.cardBg(this, 14));
        searchInput = new EditText(this);
        searchInput.setHint("搜索标题或课程");
        searchInput.setSingleLine();
        searchInput.setTextSize(13);
        searchInput.setBackground(null);
        searchInput.setPadding(0, 0, 0, 0);
        searchInput.setTextColor(UiTheme.text(this));
        searchInput.setHintTextColor(UiTheme.muted(this));
        filterBar.addView(searchInput, new LinearLayout.LayoutParams(0, -2, 1f));
        typeFilter = new Spinner(this);
        typeFilter.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"全部", "作业", "考试", "章节", "课堂活动"}));
        typeFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String value = String.valueOf(parent.getItemAtPosition(position));
                typeFilterValue = "全部".equals(value) ? "" : value;
                rebuildItemList();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        filterBar.addView(typeFilter, new LinearLayout.LayoutParams(-2, -2));
        root.addView(filterBar, marTop(dp(12)));

        // --- view mode tabs (列表 / 本周) ---
        LinearLayout tabs = hbox();
        listTab = tabButton("列表", true);
        weekTab = tabButton("本周", false);
        listTab.setOnClickListener(v -> setViewMode("list"));
        weekTab.setOnClickListener(v -> setViewMode("week"));
        tabs.addView(listTab, new LinearLayout.LayoutParams(0, -2, 1f));
        tabs.addView(space(dp(10)));
        tabs.addView(weekTab, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(tabs, marTop(dp(10)));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                searchQuery = s.toString().trim();
                rebuildItemList();
            }
        });

        // --- section ---
        root.addView(sectionHeader("待办事项"));

        // --- item list ---
        itemList = new LinearLayout(this);
        itemList.setOrientation(LinearLayout.VERTICAL);
        root.addView(itemList, new LinearLayout.LayoutParams(-1, -2));

        // --- empty state (initially hidden) ---
        emptyContainer = new LinearLayout(this);
        emptyContainer.setGravity(Gravity.CENTER);
        emptyContainer.setPadding(dp(8), dp(20), dp(8), dp(20));
        TextView emptyText = text("", 15, false, UiTheme.muted(this));
        emptyText.setGravity(Gravity.CENTER);
        emptyContainer.addView(emptyText, new LinearLayout.LayoutParams(-1, -2));
        root.addView(emptyContainer, new LinearLayout.LayoutParams(-1, -2));

        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private LinearLayout statChip(String iconChar, String label, TextView countView) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(12), dp(14), dp(12), dp(14));
        chip.setBackground(UiTheme.cardBg(this, 14));

        countView.setText("0");
        countView.setTextSize(22);
        countView.setTextColor(UiTheme.text(this));
        countView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        countView.setGravity(Gravity.CENTER);
        countView.setPadding(0, 0, 0, dp(3));
        chip.addView(countView, new LinearLayout.LayoutParams(-1, -2));

        TextView lbl = text(label, 11, false, UiTheme.muted(this));
        lbl.setGravity(Gravity.CENTER);
        chip.addView(lbl, new LinearLayout.LayoutParams(-1, -2));
        return chip;
    }

    private LinearLayout.LayoutParams chipParams(float weight) {
        return new LinearLayout.LayoutParams(0, -2, weight);
    }

    private View space(int width) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(width, 0));
        return v;
    }

    private LinearLayout.LayoutParams marTop(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = top;
        return p;
    }

    private void reload(boolean firstLoad) {
        items.clear();
        items.addAll(store.activeItems());
        if (firstLoad) {
            DeadlineNotifier.rescheduleUpcomingOnly(this, new ArrayList<>(items));
            OverlayBridge.publish(this);
        }
        rebuildItemList();

        Activation activation = readActivation();
        activeBadge.setText(activation.title);
        activeBadge.setTextColor(activation.active ? UiTheme.successText(this) : UiTheme.warningText(this));
        statusText.setText(activation.detail);

        int pending = 0, expired = 0, blocked = store.blockedCourses().size();
        long now = System.currentTimeMillis();
        for (DeadlineItem item : items) {
            if (item.submitted || item.dueAt <= now) expired++;
            else pending++;
        }

        countPending.setText(String.valueOf(pending));
        countExpired.setText(String.valueOf(expired));
        countBlocked.setText(String.valueOf(blocked));

        scheduleContainer.removeAllViews();
        scheduleContainer.addView(scheduleCard());

        boolean empty = items.isEmpty();
        itemList.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyContainer.setVisibility(empty ? View.VISIBLE : View.GONE);
        ((TextView) emptyContainer.getChildAt(0)).setText(activation.active
                ? "暂无待办\n\n打开学习通后会自动捕获作业和考试截止时间。"
                : "尚未检测到激活\n\n请在 LSPosed 中勾选本模块，作用域选择「学习通」。");

    }

    /**
     * 今日课表卡片：首页最显眼的课表入口。显示周次、今天的课程与「上课中」状态，
     * 点按进入周网格课表页，右侧刷新图标向学习通请求重新拉取。
     */
    private View scheduleCard() {
        LinearLayout card = card();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setOnClickListener(v -> startActivity(new Intent(this, ScheduleActivity.class)));

        DeadlineStore store = new DeadlineStore(this);
        long now = System.currentTimeMillis();
        long firstWeekDate = store.scheduleFirstWeekDate();
        String sectionTimes = store.scheduleSectionTimes();
        int week = ScheduleMath.currentWeek(now, firstWeekDate);
        List<Lesson> today = ScheduleMath.todayLessons(store.allLessons(), now, firstWeekDate);
        Lesson current = ScheduleMath.currentLesson(today, now, firstWeekDate, sectionTimes);

        LinearLayout header = hbox();
        TextView title = text("今日课表", 15, true, UiTheme.text(this));
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView weekLabel = text(week > 0 ? "第 " + week + " 周" : "课表未获取",
                11, false, UiTheme.muted(this));
        header.addView(weekLabel, new LinearLayout.LayoutParams(-2, -2));
        TextView refresh = icon("⟳", 18, UiTheme.accent(this));
        refresh.setOnClickListener(v -> {
            RefreshBridge.requestFull(this);
            Toast.makeText(this, "已请求刷新课表", Toast.LENGTH_SHORT).show();
        });
        header.addView(refresh, new LinearLayout.LayoutParams(dp(30), -2));
        TextView more = icon("›", 20, UiTheme.muted(this));
        header.addView(more, new LinearLayout.LayoutParams(dp(22), -2));
        card.addView(header, new LinearLayout.LayoutParams(-1, -2));

        if (today.isEmpty()) {
            TextView empty = text(store.scheduleUpdatedAt() > 0L
                    ? "今天没有课" : "还没有课表数据\n打开学习通课表页面即可自动获取",
                    12, false, UiTheme.muted(this));
            empty.setPadding(dp(2), dp(8), dp(2), dp(2));
            empty.setLineSpacing(0f, 1.2f);
            card.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return card;
        }

        int shown = Math.min(today.size(), 3);
        for (int i = 0; i < shown; i++) {
            card.addView(todayLessonRow(today.get(i), current, week, firstWeekDate,
                    sectionTimes, now), new LinearLayout.LayoutParams(-1, -2));
        }
        if (today.size() > shown) {
            TextView rest = text("还有 " + (today.size() - shown) + " 节课，点按查看完整课表",
                    11, false, UiTheme.muted(this));
            rest.setPadding(dp(2), dp(6), 0, 0);
            card.addView(rest, new LinearLayout.LayoutParams(-1, -2));
        }
        return card;
    }

    private View todayLessonRow(Lesson lesson, Lesson current, int week, long firstWeekDate,
                                String sectionTimes, long now) {
        boolean isCurrent = current != null && current.id != null && current.id.equals(lesson.id);
        long start = ScheduleMath.lessonStartAt(firstWeekDate, week, lesson.dayOfWeek,
                lesson.beginSection, sectionTimes);
        long end = ScheduleMath.lessonEndAt(firstWeekDate, week, lesson.dayOfWeek,
                lesson.beginSection, lesson.sections, sectionTimes);
        boolean isPast = end > 0L && now >= end;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(6), dp(6), dp(6));
        if (isCurrent) {
            row.setBackground(UiTheme.fillOnly(this, UiTheme.dangerBg(this), dp(10)));
        }

        TextView time = new TextView(this);
        time.setText((start > 0L ? DateText.clock(start) : "--:--") + "\n"
                + ScheduleMath.formatSections(lesson.beginSection, lesson.sections));
        time.setTextSize(10);
        time.setLineSpacing(0f, 1.1f);
        time.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        time.setTextColor(isCurrent ? UiTheme.dangerText(this) : UiTheme.muted(this));
        row.addView(time, new LinearLayout.LayoutParams(dp(56), -2));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(lesson.name + (isCurrent ? " · 上课中" : ""), 13, isCurrent,
                isPast ? UiTheme.muted(this)
                        : (isCurrent ? UiTheme.dangerText(this) : UiTheme.text(this)));
        texts.addView(name, new LinearLayout.LayoutParams(-1, -2));
        String label = lesson.label();
        if (!label.isEmpty()) {
            TextView meta = text(label, 10, false, UiTheme.muted(this));
            meta.setPadding(0, dp(2), 0, 0);
            texts.addView(meta, new LinearLayout.LayoutParams(-1, -2));
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    private TextView tabButton(String label, boolean active) {
        TextView tv = text(label, 14, active, active ? UiTheme.accent(this) : UiTheme.muted(this));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
        tv.setBackground(UiTheme.cardBg(this, 12));
        return tv;
    }

    private void setViewMode(String mode) {
        if (mode.equals(viewMode)) {
            return;
        }
        viewMode = mode;
        boolean list = "list".equals(mode);
        listTab.setTextColor(list ? UiTheme.accent(this) : UiTheme.muted(this));
        listTab.setTypeface(null, list
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        weekTab.setTextColor(list ? UiTheme.muted(this) : UiTheme.accent(this));
        weekTab.setTypeface(null, list
                ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
        rebuildItemList();
    }

    private void rebuildItemList() {
        itemList.removeAllViews();
        if ("week".equals(viewMode)) {
            renderWeek();
            return;
        }
        List<DeadlineItem> sorted = filteredSorted();
        for (DeadlineItem item : sorted) itemList.addView(itemRow(item), rowParams());
        if (sorted.isEmpty() && !items.isEmpty()) {
            TextView noMatch = text("没有匹配的待办\n试试清空搜索或切换类型", 13, false, UiTheme.muted(this));
            noMatch.setGravity(Gravity.CENTER);
            noMatch.setPadding(dp(8), dp(18), dp(8), dp(18));
            itemList.addView(noMatch, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    /** Search + type filter, then the list view's pending-first ordering. */
    private List<DeadlineItem> filteredSorted() {
        long now = System.currentTimeMillis();
        String query = searchQuery.toLowerCase(Locale.ROOT);
        List<DeadlineItem> sorted = new ArrayList<>();
        for (DeadlineItem item : items) {
            if (!typeFilterValue.isEmpty() && !typeFilterValue.equals(item.type)) {
                continue;
            }
            if (!matchesQuery(item, query)) {
                continue;
            }
            sorted.add(item);
        }
        Collections.sort(sorted, (a, b) -> {
            boolean aDone = a.submitted || a.dueAt <= now;
            boolean bDone = b.submitted || b.dueAt <= now;
            if (aDone != bDone) return aDone ? 1 : -1;
            return aDone ? Long.compare(b.dueAt, a.dueAt) : Long.compare(a.dueAt, b.dueAt);
        });
        return sorted;
    }

    /** 本周视图: upcoming deadlines bucketed by calendar day over the next 7 days. */
    private void renderWeek() {
        List<WeekGrouping.DayGroup> groups =
                WeekGrouping.group(filteredSorted(), System.currentTimeMillis());
        if (groups.isEmpty()) {
            TextView empty = text("未来 7 天没有待办\n切换到「列表」可查看全部", 13, false, UiTheme.muted(this));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(8), dp(18), dp(8), dp(18));
            itemList.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (WeekGrouping.DayGroup group : groups) {
            itemList.addView(weekHeader(group), rowParams());
            for (DeadlineItem item : group.items) {
                itemList.addView(itemRow(item), rowParams());
            }
        }
    }

    private View weekHeader(WeekGrouping.DayGroup group) {
        LinearLayout header = hbox();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), dp(14), dp(4), dp(6));
        TextView label = text(group.label, 15, true, UiTheme.accent(this));
        header.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView count = text(group.items.size() + " 项", 12, false, UiTheme.muted(this));
        header.addView(count, new LinearLayout.LayoutParams(-2, -2));
        return header;
    }

    private static boolean matchesQuery(DeadlineItem item, String query) {
        if (query.isEmpty()) {
            return true;
        }
        return (item.title != null && item.title.toLowerCase(Locale.ROOT).contains(query))
                || (item.course != null && item.course.toLowerCase(Locale.ROOT).contains(query));
    }

    private View itemRow(DeadlineItem item) {
        long delta = item.dueAt - System.currentTimeMillis();
        boolean expired = delta <= 0L;
        boolean done = item.submitted || expired;
        boolean urgent = !done && delta <= 24L * 60L * 60L * 1000L;

        // outer wrapper: swipe on expired items
        LinearLayout swipeWrapper = new LinearLayout(this);
        swipeWrapper.setOrientation(LinearLayout.HORIZONTAL);
        swipeWrapper.setClipChildren(false);
        swipeWrapper.setClipToPadding(false);

        // the card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        card.setBackground(urgent ? UiTheme.fillOnly(this, UiTheme.dangerBg(this), dp(16)) : UiTheme.cardBg(this, 16));
        card.setAlpha(done ? 0.55f : 1f);

        TextView badge = new TextView(this);
        badge.setText(badgeLabel(item.type));
        badge.setTextSize(13);
        badge.setGravity(Gravity.CENTER);
        int badgeColor = badgeColor(this, item.type);
        int badgeBg = badgeBg(this, item.type);
        badge.setTextColor(badgeColor);
        badge.setBackground(UiTheme.fillOnly(this, badgeBg, dp(10)));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(36), dp(36));
        bp.rightMargin = dp(14);
        bp.gravity = Gravity.CENTER_VERTICAL;
        card.addView(badge, bp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);

        int titleColor = done ? UiTheme.muted(this) : UiTheme.text(this);
        TextView tvTitle = text(item.title, 15, true, titleColor);
        texts.addView(tvTitle, new LinearLayout.LayoutParams(-1, -2));

        String courseLabel = item.course == null || item.course.isEmpty() ? "未识别课程" : item.course;
        TextView tvCourse = text(courseLabel, 12, false, UiTheme.muted(this));
        tvCourse.setPadding(0, dp(4), 0, 0);
        texts.addView(tvCourse, new LinearLayout.LayoutParams(-1, -2));

        int dueColor = done ? UiTheme.muted(this)
                : (item.vanishedAt > 0L ? UiTheme.warningText(this) : UiTheme.accent(this));
        String dueText = item.submitted ? "已提交 · 截止：" + DateText.deadlineTime(item.dueAt) : DateText.dueLine(item.dueAt);
        if (item.vanishedAt > 0L) {
            dueText = dueText + " · 可能已撤销";
        }
        TextView tvDue = text(dueText, 12, false, dueColor);
        tvDue.setPadding(0, dp(4), 0, 0);
        texts.addView(tvDue, new LinearLayout.LayoutParams(-1, -2));

        card.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        // terminal label on the right for submitted/expired items
        if (done) {
            TextView doneLabel = text(item.submitted ? "已提交" : "已截止", 13, false, UiTheme.muted(this));
            doneLabel.setPadding(dp(10), 0, 0, 0);
            doneLabel.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(doneLabel, new LinearLayout.LayoutParams(-2, -2));
        }

        card.setOnClickListener(v -> showItemMenu(item));

        swipeWrapper.addView(card, new LinearLayout.LayoutParams(-1, -2));

        // swipe-to-delete for terminal items; a tap still opens the item menu
        if (done) {
            card.setTag("swipeable");
            card.setOnTouchListener(new SwipeDismissListener(item, swipeWrapper, card));
        }

        return swipeWrapper;
    }

    private static String badgeLabel(String type) {
        if ("\u4f5c\u4e1a".equals(type)) {
            return "\u4f5c";
        }
        if ("\u8003\u8bd5".equals(type)) {
            return "\u8003";
        }
        if ("\u7ae0\u8282".equals(type)) {
            return "\u7ae0";
        }
        if ("\u8bfe\u5802\u6d3b\u52a8".equals(type)) {
            return "\u8bfe";
        }
        return "\u00b7";
    }

    private static int badgeColor(Context context, String type) {
        if ("\u8003\u8bd5".equals(type)) {
            return UiTheme.badgeExam(context);
        }
        if ("\u7ae0\u8282".equals(type)) {
            return UiTheme.badgeChapter(context);
        }
        if ("\u8bfe\u5802\u6d3b\u52a8".equals(type)) {
            return UiTheme.badgeActivity(context);
        }
        return UiTheme.badgeHomework(context);
    }

    private static int badgeBg(Context context, String type) {
        if ("\u8003\u8bd5".equals(type)) {
            return UiTheme.badgeExamBg(context);
        }
        if ("\u7ae0\u8282".equals(type)) {
            return UiTheme.badgeChapterBg(context);
        }
        if ("\u8bfe\u5802\u6d3b\u52a8".equals(type)) {
            return UiTheme.badgeActivityBg(context);
        }
        return UiTheme.badgeHomeworkBg(context);
    }

    /** Per-item actions: open in Chaoxing, toggle done state, delete. */
    private void showItemMenu(DeadlineItem item) {
        List<String> actions = new ArrayList<>();
        List<Runnable> runners = new ArrayList<>();
        if (item.vanishedAt > 0L) {
            // Two authoritative scans did not re-report it; let the user judge.
            actions.add("仍有效（恢复提醒）");
            runners.add(() -> {
                store.clearVanished(item.id);
                DeadlineNotifier.rescheduleAll(this);
                OverlayBridge.publish(this);
                DeadlineWidgetProvider.updateAll(this);
                reload(false);
            });
            actions.add("确认已撤销（删除）");
            runners.add(() -> DeadlineNotifier.deleteItem(this, item.id));
        }
        if (item.url != null && item.url.startsWith("http")) {
            actions.add("打开学习通页面");
            runners.add(() -> openChaoxingPage(item));
        }
        actions.add(item.submitted ? "标记为未完成" : "标记为已完成");
        runners.add(() -> {
            store.setSubmitted(item.id, !item.submitted);
            DeadlineNotifier.rescheduleAll(this);
            OverlayBridge.publish(this);
            DeadlineWidgetProvider.updateAll(this);
            reload(false);
        });
        actions.add("删除待办");
        runners.add(() -> DeadlineNotifier.deleteItem(this, item.id));
        actions.add("取消");
        runners.add(() -> {
        });
        new android.app.AlertDialog.Builder(this)
                .setTitle(item.title)
                .setItems(actions.toArray(new String[0]),
                        (dialog, which) -> runners.get(which).run())
                .show();
    }

    /**
     * Open the captured page inside Chaoxing.
     *
     * Stored URLs are mostly API endpoints (mooc1-api.chaoxing.com/…) that the Chaoxing
     * app itself cannot ACTION_VIEW — a bare startActivity then threw and the jump died
     * with only a toast. Probe every strategy before committing: Chaoxing-scoped view,
     * any-handler view, and finally the Chaoxing launcher entry.
     */
    private void openChaoxingPage(DeadlineItem item) {
        String url = item.url == null ? "" : item.url.trim();
        if (url.startsWith("http")) {
            if (tryStartActivity(viewIntent(url, true))) {
                return;
            }
            if (tryStartActivity(viewIntent(url, false))) {
                return;
            }
        }
        if (tryStartActivity(chaoxingLaunchIntent())) {
            return;
        }
        Toast.makeText(this, "打开学习通失败", Toast.LENGTH_SHORT).show();
    }

    private Intent viewIntent(String url, boolean scopedToChaoxing) {
        Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
        if (scopedToChaoxing) {
            intent.setPackage(TARGET_PACKAGE);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private Intent chaoxingLaunchIntent() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            return intent;
        } catch (Throwable throwable) {
            return null;
        }
    }

    private boolean tryStartActivity(Intent intent) {
        if (intent == null) {
            return false;
        }
        try {
            if (getPackageManager().resolveActivity(intent, 0) == null) {
                return false;
            }
            startActivity(intent);
            return true;
        } catch (Throwable throwable) {
            return false;
        }
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(10));
        return p;
    }

    private Activation readActivation() {
        SharedPreferences prefs = getSharedPreferences("status", MODE_PRIVATE);
        long activeAt = prefs.getLong("last_active_at", 0L);
        long captureAt = prefs.getLong("last_capture_at", 0L);
        String lastStatus = prefs.getString("last_status", "");
        String lastSource = prefs.getString("last_source", "");

        XposedService service = App.getService();
        boolean serviceReady = service != null;
        boolean targetRunning = false;
        String framework = "";
        if (service != null) {
            try {
                framework = service.getFrameworkName() + " API " + service.getApiVersion();
                for (HookedTarget target : service.getRunningTargets())
                    if (TARGET_PACKAGE.equals(target.getProcessName())) { targetRunning = true; break; }
            } catch (Throwable ignored) {}
        }

        boolean loaded = serviceReady || activeAt > 0L || targetRunning;
        StringBuilder detail = new StringBuilder();
        detail.append(serviceReady ? "LSPosed 已连接" : "LSPosed 未连接");
        if (!framework.isEmpty()) detail.append(" · ").append(framework);
        detail.append("\n学习通进程：").append(targetRunning ? "运行中" : "未运行");
        if (targetRunning) detail.append("\n最近加载：当前运行中");
        else if (activeAt > 0L) detail.append("\n最近加载：").append(format(activeAt));
        else if (serviceReady) detail.append("\n状态说明：模块已加载，打开学习通后开始捕获");
        if (captureAt > 0L) detail.append("\n最近捕获：").append(format(captureAt));
        // emitStatus writes failure/anti-spider notices here; they were stored but never
        // surfaced, so "自动更新中/失败/触发验证码" was invisible outside the log.
        if (lastStatus != null && !lastStatus.isEmpty()) {
            detail.append("\n最近状态：").append(lastStatus);
        }
        String title = targetRunning ? "LSPosed 已激活" : (loaded ? "LSPosed 状态：已加载" : "LSPosed 状态：未连接");
        return new Activation(title, detail.toString(), loaded);
    }


    private String format(long millis) {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date(millis));
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
    }

    private class SwipeDismissListener implements View.OnTouchListener {
        private float startX;
        private boolean swiping;
        private final DeadlineItem item;
        private final View wrapper;
        private final View card;

        SwipeDismissListener(DeadlineItem item, View wrapper, View card) {
            this.item = item;
            this.wrapper = wrapper;
            this.card = card;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getRawX();
                    swiping = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - startX;
                    if (dx < -dp(16)) swiping = true;
                    if (swiping && dx < 0) {
                        card.setTranslationX(dx);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float dx = card.getTranslationX();
                    int threshold = wrapper.getWidth() / 3;
                    if (swiping && dx < -threshold) {
                        // dismiss
                        card.animate().translationX(-wrapper.getWidth()).alpha(0f)
                                .setDuration(250)
                                .withEndAction(() -> {
                                    DeadlineNotifier.deleteItem(MainActivity.this, item.id);
                                    items.remove(item);
                                    itemList.removeView(wrapper);
                                    long now = System.currentTimeMillis();
                                    int pending = 0, expired = 0;
                                    for (DeadlineItem it : items) {
                                        if (it.submitted || it.dueAt <= now) expired++; else pending++;
                                    }
                                    countPending.setText(String.valueOf(pending));
                                    countExpired.setText(String.valueOf(expired));
                                    if (items.isEmpty()) {
                                        itemList.setVisibility(View.GONE);
                                        emptyContainer.setVisibility(View.VISIBLE);
                                    }
                                })
                                .start();
                    } else {
                        // spring back
                        card.animate().translationX(0f).setDuration(200).start();
                        if (!swiping) {
                            // The touch listener swallows ACTION_DOWN, so taps on completed
                            // items have to be forwarded to the click listener by hand.
                            card.performClick();
                        }
                    }
                    swiping = false;
                    return true;
                }
            }
            return false;
        }
    }

    private static final class Activation {
        final String title, detail; final boolean active;
        Activation(String t, String d, boolean a) { title = t; detail = d; active = a; }
    }
}
