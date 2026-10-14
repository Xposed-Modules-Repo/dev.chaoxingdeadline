package dev.chaoxingdeadline;

import android.content.Context;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Robolectric coverage for DeadlineStore: the upsert/dedup contract, the 1.9.0
 * type-migration helper, cross-type ignore rules and the retention prune.
 * Pinned to SDK 34 so the suite does not depend on a matching android-all drop
 * for the current compileSdk.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DeadlineStoreTest {
    private Context context;
    private DeadlineStore store;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        DeadlineStore.invalidateSettingCaches();
        store = new DeadlineStore(context);
    }

    @After
    public void tearDown() {
        store.clear();
        DeadlineStore.invalidateSettingCaches();
    }

    @Test
    public void mergesSameTaskCapturedWithDifferentTaskIds() {
        // The work task-list and the classroom-activity list number their tasks
        // independently; the same homework therefore arrives with different taskIds.
        store.upsert(item("作业", "第三章作业", dueIn(48), "work-111", "7", "9"));
        store.upsert(item("作业", "第三章作业", dueIn(48), "activity-222", "7", "9"));
        assertEquals("different channel ids must still collapse to one row",
                1, store.activeItems().size());
    }

    @Test
    public void mergesRowCapturedWithoutCourseReference() {
        // A passively intercepted payload carries no course context; whichever row
        // arrives second, the duplicate must still be merged.
        store.upsert(item("作业", "第三章作业", dueIn(48), "", "", ""));
        store.upsert(item("作业", "第三章作业", dueIn(48), "work-111", "7", "9"));
        assertEquals("no-ref row first, reffed row second",
                1, store.activeItems().size());

        store.clear();
        store.upsert(item("作业", "第三章作业", dueIn(48), "work-111", "7", "9"));
        store.upsert(item("作业", "第三章作业", dueIn(48), "", "", ""));
        assertEquals("reffed row first, no-ref row second",
                1, store.activeItems().size());
    }

    @Test
    public void mergesPassiveAndActiveRowsAcrossCourseIdSpaces() {
        // The two id spaces that kept duplicates alive: the active scan writes the real
        // course/class ids, a passive capture writes ids derived from the course name.
        DeadlineItem active = item("作业", "第三章作业", dueIn(48), "work-111", "7", "9");
        DeadlineItem passive = item("作业", "第三章作业", dueIn(48), "", "legacy:9f3a", "legacy:1c2d");
        store.upsert(passive);
        store.upsert(active);

        List<DeadlineItem> items = store.activeItems();
        assertEquals("the passive row must adopt the active row's identity, not sit beside it",
                1, items.size());
        assertEquals("the richer identity survives the merge", "work-111", items.get(0).taskId);
        assertEquals("7", items.get(0).courseId);
    }

    @Test
    public void repairMergesHistoricalDuplicates() {
        // Rows written by an older build: two ids for the same task, inserted straight
        // into the table so the upsert-time dedup cannot hide them.
        DeadlineItem active = item("作业", "第三章作业", dueIn(48), "work-111", "7", "9");
        DeadlineItem passive = item("作业", "第三章作业", dueIn(48), "", "legacy:9f3a", "legacy:1c2d");
        insertRaw(active);
        insertRaw(passive);
        assertEquals("two rows before the repair", 2, store.activeItems().size());

        assertEquals("countDuplicates must not silently read an empty plan", 1, store.countDuplicates());
        assertEquals(1, store.repairDuplicates());

        List<DeadlineItem> items = store.activeItems();
        assertEquals(1, items.size());
        assertEquals("the keeper absorbs the task id of the row it replaces",
                "work-111", items.get(0).taskId);
        assertEquals(0, store.countDuplicates());
    }

    @Test
    public void repairKeepsManualEntriesAndDistinctTasks() {
        DeadlineItem manual = item("作业", "手动作业", dueIn(48), "", "7", "9");
        manual.source = "manual";
        store.upsert(manual);
        store.upsert(item("作业", "第三章作业", dueIn(48), "w1", "7", "9"));
        store.upsert(item("作业", "第四章作业", dueIn(48), "w2", "7", "9"));
        store.upsert(item("考试", "第三章作业", dueIn(48), "e1", "7", "9"));
        assertEquals("manual rows, other titles and other types are untouched",
                0, store.countDuplicates());
    }

    @Test
    public void keepsDistinctTasksApart() {
        store.upsert(item("作业", "第三章作业", dueIn(48), "work-111", "7", "9"));
        store.upsert(item("作业", "第四章作业", dueIn(48), "work-222", "7", "9"));
        store.upsert(item("作业", "第三章作业", dueIn(48), "work-333", "8", "9"));
        assertEquals("different titles or courses are different tasks",
                3, store.activeItems().size());
    }

    @Test
    public void migrationRetypesTaskListRowsAsClassroomActivity() {
        DeadlineItem stale = item("作业", "第三讲签到", dueIn(24), "act-1", "7", "9");
        stale.source = "active.taskList";
        store.upsert(stale);
        store.retypeActivityRows(store.getWritableDatabase());
        List<DeadlineItem> items = store.activeItems();
        assertEquals(1, items.size());
        assertEquals("课堂活动", items.get(0).type);
    }

    @Test
    public void ignoreRuleMatchesAcrossTypeChange() {
        store.upsert(item("作业", "第三讲签到", dueIn(24), "act-1", "7", "9"));
        String id = store.activeItems().get(0).id;
        store.deleteItem(id);
        // Same task re-captured under the new type after the 1.9.0 re-typing.
        store.upsert(item("课堂活动", "第三讲签到", dueIn(24), "act-1", "7", "9"));
        assertEquals("an ignored task must stay ignored across the type change",
                0, store.activeItems().size());
    }

    @Test
    public void ignoreRuleDoesNotLeakToSameIdDifferentTask() {
        store.upsert(item("作业", "第三讲签到", dueIn(24), "act-1", "7", "9"));
        store.deleteItem(store.activeItems().get(0).id);
        // Same id namespace, different task: the title cross-check must keep it.
        store.upsert(item("课堂活动", "第三周投票", dueIn(24), "act-1", "7", "9"));
        assertEquals(1, store.activeItems().size());
    }

    @Test
    public void prunesRowsExpiredBeyondRetention() {
        store.upsert(item("作业", "上古作业",
                System.currentTimeMillis() - TimeUnit.DAYS.toMillis(100), "w-1", "7", "9"));
        assertFalse("100 days past due is reclaimed on insert",
                containsTitle(store.activeItems(), "上古作业"));

        store.upsert(item("作业", "刚截止的作业",
                System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30), "w-2", "7", "9"));
        assertTrue("recently expired rows stay visible",
                containsTitle(store.activeItems(), "刚截止的作业"));
    }

    @Test
    public void tracksClassroomActivities() {
        assertTrue(DeadlineStore.isSupportedType("课堂活动"));
        assertEquals(4, DeadlineStore.managedTypes().length);
    }

    @Test
    public void flagsItemMissingFromTwoAuthoritativeScans() {
        store.upsert(item("作业", "第三章作业", dueIn(48), "work-1", "7", "9"));
        String id = store.activeItems().get(0).id;
        String[] keys = {"7|9"};
        assertEquals("first miss only counts", 0, store.applyScanRound(keys, new String[]{"other"}));
        assertEquals("second consecutive miss flags it", 1, store.applyScanRound(keys, new String[]{"other"}));
        DeadlineItem stored = store.itemById(id);
        assertNotNull(stored);
        assertTrue("vanished_at is set", stored.vanishedAt > 0L);
        assertEquals(2, stored.misses);
    }

    @Test
    public void seenItemClearsVanishedFlag() {
        store.upsert(item("作业", "第三章作业", dueIn(48), "work-1", "7", "9"));
        String id = store.activeItems().get(0).id;
        String[] keys = {"7|9"};
        store.applyScanRound(keys, new String[0]);
        store.applyScanRound(keys, new String[0]);
        assertTrue(store.itemById(id).vanishedAt > 0L);
        // The teacher put it back: re-captured in a later round, flag clears.
        store.applyScanRound(keys, new String[]{id});
        DeadlineItem after = store.itemById(id);
        assertEquals(0, after.misses);
        assertEquals(0L, after.vanishedAt);
    }

    @Test
    public void untouchedWhenCourseNotScanned() {
        store.upsert(item("作业", "第三章作业", dueIn(48), "work-1", "7", "9"));
        String id = store.activeItems().get(0).id;
        // Another course was scanned; this item's course was never authoritative.
        store.applyScanRound(new String[]{"8|9"}, new String[0]);
        store.applyScanRound(new String[]{"8|9"}, new String[0]);
        DeadlineItem stored = store.itemById(id);
        assertEquals(0, stored.misses);
        assertEquals(0L, stored.vanishedAt);
    }

    @Test
    public void manualAndChapterItemsNeverVanish() {
        DeadlineItem manual = item("作业", "手动作业", dueIn(48), "work-1", "7", "9");
        manual.source = "manual";
        store.upsert(manual);
        store.upsert(item("章节", "第三章任务点", dueIn(48), "k-1", "7", "9"));
        String[] keys = {"7|9"};
        store.applyScanRound(keys, new String[0]);
        store.applyScanRound(keys, new String[0]);
        for (DeadlineItem stored : store.activeItems()) {
            assertEquals("manual entries and chapters are never judged missing",
                    0, stored.misses);
            assertEquals(0L, stored.vanishedAt);
        }
    }

    @Test
    public void autoRemoveDeletesInsteadOfFlagging() {
        AppSettings.setAutoRemoveVanished(context, true);
        try {
            store.upsert(item("作业", "第三章作业", dueIn(48), "work-1", "7", "9"));
            String id = store.activeItems().get(0).id;
            String[] keys = {"7|9"};
            store.applyScanRound(keys, new String[0]);
            assertEquals(1, store.applyScanRound(keys, new String[0]));
            assertNull("auto-remove deletes the row outright", store.itemById(id));
        } finally {
            AppSettings.setAutoRemoveVanished(context, false);
        }
    }

    @Test
    public void backupRoundTripsSettings() throws Exception {
        AppSettings.prefs(context).edit().putInt("notify_hours", 72).apply();
        JSONObject backup = DeadlineBackup.exportJson(context);
        JSONObject settings = backup.getJSONObject("settings");
        assertTrue(settings.has(AppSettings.PREFS));
        assertTrue(settings.has(AppSettings.WIDGET_PREFS));
        assertTrue(settings.has(DeadlineStore.PREFS));
        assertFalse("the bridge token file must never be exported",
                settings.has(BridgeAuth.PREFS_NAME));

        AppSettings.prefs(context).edit().putInt("notify_hours", 6).apply();
        int restored = DeadlineBackup.importJson(context, backup.toString(), false, true);
        assertEquals("items restored (none here); settings ride along", 0, restored);
        assertEquals(72, AppSettings.notifyHours(context));
    }

    @Test
    public void importWithoutSettingsLeavesThemAlone() throws Exception {
        AppSettings.prefs(context).edit().putInt("notify_hours", 72).apply();
        JSONObject backup = DeadlineBackup.exportJson(context);
        AppSettings.prefs(context).edit().putInt("notify_hours", 6).apply();
        DeadlineBackup.importJson(context, backup.toString(), false, false);
        assertEquals("opt-out keeps current settings", 6, AppSettings.notifyHours(context));
    }

    @Test
    public void lessonsRoundTripThroughTheStore() {
        List<Lesson> lessons = Arrays.asList(
                new Lesson("L1", "高等数学", "张三", "教一101", 1, 1, 2, "1-16"),
                new Lesson("L2", "大学英语", "李四", "教二202", 3, 3, 2, "1-8,10-16"));
        store.replaceLessons(lessons, 1_800_000_000_000L, "[\"8:00-8:45\",\"8:55-9:40\"]");
        List<Lesson> stored = store.allLessons();
        assertEquals(2, stored.size());
        assertEquals("sorted by day then section", 1, stored.get(0).dayOfWeek);
        assertEquals("L2", stored.get(1).id);
        assertEquals("教二202", stored.get(1).location);
        assertEquals(1_800_000_000_000L, store.scheduleFirstWeekDate());
        assertTrue(store.scheduleSectionTimes().contains("8:00-8:45"));
        assertTrue(store.scheduleUpdatedAt() > 0L);

        // A fresh capture replaces the whole table.
        store.replaceLessons(Collections.singletonList(
                new Lesson("L3", "体育", "", "", 5, 6, 2, "1-16")), 0L, "");
        assertEquals(1, store.allLessons().size());
        assertEquals("L3", store.allLessons().get(0).id);
        assertEquals("firstWeekDate kept when the payload has none",
                1_800_000_000_000L, store.scheduleFirstWeekDate());
    }

    @Test
    public void dropsMalformedLessons() {
        List<Lesson> lessons = Arrays.asList(
                new Lesson("L1", "", "老师", "教室", 1, 1, 2, "1-16"),   // no name
                new Lesson("L2", "课", "老师", "教室", 0, 1, 2, "1-16"), // no weekday
                new Lesson("L3", "课", "老师", "教室", 1, 0, 2, "1-16"), // no section
                new Lesson("L4", "正常课", "老师", "教室", 2, 5, 2, "1-16"));
        store.replaceLessons(lessons, 0L, "");
        assertEquals(1, store.allLessons().size());
        assertEquals("L4", store.allLessons().get(0).id);
    }

    /** Writes a row the way an older build would have: straight into the table. */
    private void insertRaw(DeadlineItem item) {
        item.id = item.stableId();
        store.getWritableDatabase().insert("deadlines", null, item.toValues());
    }

    private static boolean containsTitle(List<DeadlineItem> items, String title) {
        for (DeadlineItem item : items) {
            if (title.equals(item.title)) {
                return true;
            }
        }
        return false;
    }

    private static DeadlineItem item(String type, String title, long dueAt,
                                     String taskId, String courseId, String classId) {
        DeadlineItem item = new DeadlineItem();
        item.type = type;
        item.title = title;
        item.course = "高等数学";
        item.courseConfidence = 100;
        item.dueAt = dueAt;
        item.taskId = taskId;
        item.courseId = courseId;
        item.classId = classId;
        item.setSubmissionState(DeadlineItem.SUBMISSION_UNSUBMITTED);
        item.source = "active.workList";
        return item;
    }

    private static long dueIn(long hours) {
        return System.currentTimeMillis() + TimeUnit.HOURS.toMillis(hours);
    }
}
