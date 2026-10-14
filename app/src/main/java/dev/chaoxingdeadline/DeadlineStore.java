package dev.chaoxingdeadline;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DeadlineStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "deadlines.db";
    private static final int DB_VERSION = 9;
    public static final String PREFS = "blocked_courses";
    private static final String SCHEDULE_PREFS = "schedule_meta";
    private static final String KEY_FIRST_WEEK_DATE = "first_week_date";
    private static final String KEY_SECTION_TIMES = "section_times";
    private static final String KEY_SCHEDULE_UPDATED_AT = "updated_at";
    private static final String KEY_LEGACY_COURSES = "courses";
    private static final String KEY_BLOCKED_RULES = "rules";
    private static final String TYPE_ALL = "\u5168\u90e8";
    private static final String TYPE_HOMEWORK = "\u4f5c\u4e1a";
    private static final String TYPE_EXAM = "\u8003\u8bd5";
    private static final String TYPE_CHAPTER = "\u7ae0\u8282";
    private static final String TYPE_ACTIVITY = "\u8bfe\u5802\u6d3b\u52a8";
    /** Rows past their deadline by this much are housekeeping-deleted on upsert. */
    private static final long EXPIRED_RETENTION_MS = 90L * 24L * 60L * 60L * 1000L;
    // courses table lookups happen on every activeItems()/widget refresh; cache the
    // (small) course-name mapping to avoid the N+1 query pattern.
    private static final Map<String, String> COURSE_NAME_CACHE =
            Collections.synchronizedMap(new HashMap<>());
    // Blocked-course rules are read once per item on the hottest read paths (5s UI poll,
    // every broadcast, every widget refresh); keep them in memory and refresh on write.
    // Only this process ever writes these prefs, so a static cache cannot go stale.
    private static volatile Set<String> legacyCoursesCache;
    private static volatile Set<String> blockedRulesCache;
    private final Context context;

    public DeadlineStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        this.context = context.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE deadlines ("
                + "id TEXT PRIMARY KEY,"
                + "type TEXT NOT NULL,"
                + "title TEXT NOT NULL,"
                + "course TEXT,"
                + "course_id TEXT,"
                + "class_id TEXT,"
                + "cpi TEXT,"
                + "uid TEXT,"
                + "task_id TEXT,"
                + "course_confidence INTEGER NOT NULL DEFAULT 0,"
                + "due_at INTEGER NOT NULL,"
                + "submitted INTEGER NOT NULL DEFAULT 0,"
                + "submit_state INTEGER NOT NULL DEFAULT -1,"
                + "misses INTEGER NOT NULL DEFAULT 0,"
                + "vanished_at INTEGER NOT NULL DEFAULT 0,"
                + "source TEXT,"
                + "url TEXT,"
                + "updated_at INTEGER NOT NULL,"
                + "raw TEXT)");
        db.execSQL("CREATE INDEX idx_deadlines_due ON deadlines(due_at)");
        db.execSQL("CREATE INDEX idx_deadlines_course_ref ON deadlines(course_id, class_id)");
        db.execSQL("CREATE INDEX idx_deadlines_task_ref ON deadlines(type, course_id, class_id, task_id)");
        createCourseTable(db);
        createIgnoredTable(db);
        createLessonsTable(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createLegacyCourseTable(db);
        }
        if (oldVersion < 3) {
            addColumn(db, "deadlines", "course_id", "TEXT");
            addColumn(db, "deadlines", "class_id", "TEXT");
            addColumn(db, "deadlines", "cpi", "TEXT");
            addColumn(db, "deadlines", "uid", "TEXT");
            addColumn(db, "deadlines", "task_id", "TEXT");
            addColumn(db, "deadlines", "course_confidence", "INTEGER NOT NULL DEFAULT 0");
            addColumn(db, "deadlines", "url", "TEXT");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_deadlines_course_ref ON deadlines(course_id, class_id)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_deadlines_task_ref ON deadlines(type, course_id, class_id, task_id)");
            migrateCourseTable(db);
        }
        if (oldVersion < 4) {
            addColumn(db, "deadlines", "submit_state", "INTEGER NOT NULL DEFAULT -1");
            try {
                db.execSQL("UPDATE deadlines SET submit_state = 1 WHERE submitted != 0");
            } catch (Throwable ignored) {
            }
        }
        if (oldVersion < 5) {
            createIgnoredTable(db);
        }
        if (oldVersion < 6) {
            // 1.9.0: taskactivelist payloads used to be typed 作业, which both duplicated
            // the real homework rows and buried classroom activities in the wrong section.
            // Re-type the rows that were captured from that endpoint; the next capture of
            // the same task replaces them via the task-identity dedup in upsert().
            retypeActivityRows(db);
        }
        if (oldVersion < 7) {
            // 1.10.0: revoked-deadline detection state.
            addColumn(db, "deadlines", "misses", "INTEGER NOT NULL DEFAULT 0");
            addColumn(db, "deadlines", "vanished_at", "INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 8) {
            // 1.11.0: weekly timetable (课程表) captured from Chaoxing.
            createLessonsTable(db);
        }
        if (oldVersion < 9) {
            // 1.11.1: sweep duplicate rows written by versions whose deletes could not
            // match across the legacy/real course-id spaces. Runs once, in the upgrade
            // transaction, so existing duplicates heal themselves on install.
            repairDuplicates(db, true);
        }
    }

    private void createLessonsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS lessons ("
                + "id TEXT PRIMARY KEY,"
                + "name TEXT NOT NULL DEFAULT '',"
                + "teacher TEXT,"
                + "location TEXT,"
                + "day_of_week INTEGER NOT NULL DEFAULT 0,"
                + "begin_section INTEGER NOT NULL DEFAULT 0,"
                + "sections INTEGER NOT NULL DEFAULT 1,"
                + "weeks TEXT,"
                + "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_lessons_day ON lessons(day_of_week, begin_section)");
    }

    void retypeActivityRows(SQLiteDatabase db) {
        try {
            db.execSQL("UPDATE deadlines SET type = '" + TYPE_ACTIVITY
                    + "' WHERE type = '" + TYPE_HOMEWORK + "' AND source LIKE 'active.taskList%'");
        } catch (Throwable ignored) {
        }
    }

    private void addColumn(SQLiteDatabase db, String table, String column, String type) {
        try {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        } catch (Throwable ignored) {
        }
    }

    private void createLegacyCourseTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS courses ("
                + "name TEXT PRIMARY KEY,"
                + "updated_at INTEGER NOT NULL)");
    }

    private void createCourseTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS courses ("
                + "course_id TEXT NOT NULL DEFAULT '',"
                + "class_id TEXT NOT NULL DEFAULT '',"
                + "cpi TEXT,"
                + "uid TEXT,"
                + "name TEXT NOT NULL DEFAULT '',"
                + "raw TEXT,"
                + "updated_at INTEGER NOT NULL,"
                + "PRIMARY KEY(course_id, class_id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_courses_name ON courses(name)");
    }

    private void createIgnoredTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS ignored_deadlines ("
                + "id TEXT PRIMARY KEY,"
                + "type TEXT NOT NULL,"
                + "title TEXT NOT NULL,"
                + "course_id TEXT NOT NULL DEFAULT '',"
                + "class_id TEXT NOT NULL DEFAULT '',"
                + "task_id TEXT NOT NULL DEFAULT '',"
                + "due_at INTEGER NOT NULL,"
                + "ignored_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ignored_due ON ignored_deadlines(due_at)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ignored_task ON ignored_deadlines(type, course_id, class_id, task_id)");
    }

    private void migrateCourseTable(SQLiteDatabase db) {
        try {
            db.execSQL("ALTER TABLE courses RENAME TO courses_legacy");
        } catch (Throwable ignored) {
        }
        createCourseTable(db);
        try (Cursor cursor = db.query("courses_legacy", new String[]{"name", "updated_at"},
                "name IS NOT NULL AND name <> ''", null, null, null, null)) {
            while (cursor.moveToNext()) {
                ContentValues values = new ContentValues();
                String name = cursor.getString(0);
                values.put("course_id", "legacy:" + Integer.toHexString(name.hashCode()));
                values.put("class_id", "");
                values.put("name", name);
                values.put("updated_at", cursor.getLong(1));
                db.replace("courses", null, values);
            }
        } catch (Throwable ignored) {
        }
        try {
            db.execSQL("DROP TABLE IF EXISTS courses_legacy");
        } catch (Throwable ignored) {
        }
    }

    public void upsert(DeadlineItem item) {
        if (item == null || item.dueAt <= 0 || item.title == null || item.title.isEmpty()) {
            return;
        }
        resolveCourse(item);
        if (item.id == null || item.id.isEmpty()) {
            item.id = item.stableId();
        }
        boolean manual = "manual".equals(item.source);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            pruneIgnored(db);
            if (isManuallyIgnored(db, item)) {
                db.setTransactionSuccessful();
                return;
            }
            if (manual) {
                // A manual entry corrects an already-captured row for the same task in
                // place instead of duplicating it — and it never deletes captured rows.
                String capturedId = findCapturedWithTitle(db, item.type, item.title.trim(), item.dueAt);
                if (capturedId != null) {
                    ContentValues values = new ContentValues();
                    values.put("due_at", item.dueAt);
                    values.put("updated_at", System.currentTimeMillis());
                    if (!empty(item.course)) {
                        values.put("course", item.course);
                        values.put("course_confidence", 100);
                    }
                    if (item.url != null && !item.url.isEmpty()) {
                        values.put("url", item.url);
                    }
                    db.update("deadlines", values, "id = ?", new String[]{capturedId});
                    item.id = capturedId;
                } else {
                    preserveSubmissionState(db, item);
                    db.replace("deadlines", null, item.toValues());
                }
            } else {
                // Converge capture channels on ONE row. A passive capture carries no task
                // id and often a name-derived "legacy:hash" course id; adopting the richer
                // identity of the row it duplicates keeps every channel writing the same
                // row instead of ping-ponging between two ids — which is how duplicates
                // survived the 1.9.x fixes (the deletes below could not match across the
                // id spaces).
                DeadlineItem twin = findTwin(db, item);
                if (twin != null) {
                    if (empty(item.taskId)) {
                        item.taskId = twin.taskId;
                    }
                    if (empty(item.courseId)) {
                        item.courseId = twin.courseId;
                    }
                    if (empty(item.classId)) {
                        item.classId = twin.classId;
                    }
                    if (empty(item.course)) {
                        item.course = twin.course;
                    }
                    item.id = twin.id;
                    preserveSubmissionState(db, item);
                } else {
                    preserveSubmissionState(db, item);
                }
                db.replace("deadlines", null, item.toValues());
                deleteLikelyDuplicates(db, item);
            }
            if (AppSettings.autoDeleteExpired(context)) {
                prune();
            }
            pruneExpiredBeyondRetention(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Housekeeping: drop rows whose deadline passed more than EXPIRED_RETENTION_MS ago.
     * Without this the table kept every expired row (raw JSON included) forever whenever
     * auto-delete was off; recent expired items still show in the list, only ancient
     * history is reclaimed. due_at is indexed, so this is a cheap range delete.
     */
    private void pruneExpiredBeyondRetention(SQLiteDatabase db) {
        try {
            long cutoff = System.currentTimeMillis() - EXPIRED_RETENTION_MS;
            db.delete("deadlines", "due_at < ?", new String[]{String.valueOf(cutoff)});
        } catch (Throwable ignored) {
        }
    }

    /** Latest captured (non-manual) row with the same type and title, nearest to dueAt. */
    private String findCapturedWithTitle(SQLiteDatabase db, String type, String title, long dueAt) {
        try (Cursor cursor = db.query("deadlines", new String[]{"id"},
                "(source IS NULL OR source != 'manual') AND type = ? AND title = ?",
                new String[]{safe(type), title}, null, null,
                "ABS(due_at - " + dueAt + ") ASC, updated_at DESC", "1")) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void rememberCourse(String course) {
        if (course == null || course.trim().isEmpty()) {
            return;
        }
        rememberCourse("legacy:" + Integer.toHexString(course.trim().hashCode()), "", "", "", course, null);
    }

    public void rememberCourse(String courseId, String classId, String cpi, String uid, String name, String raw) {
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        String cleanName = name.trim();
        String cleanCourseId = empty(courseId) ? "legacy:" + Integer.toHexString(cleanName.hashCode()) : courseId.trim();
        String cleanClassId = classId == null ? "" : classId.trim();
        ContentValues values = new ContentValues();
        values.put("course_id", cleanCourseId);
        values.put("class_id", cleanClassId);
        values.put("cpi", cpi == null ? "" : cpi.trim());
        values.put("uid", uid == null ? "" : uid.trim());
        values.put("name", cleanName);
        values.put("raw", raw);
        values.put("updated_at", System.currentTimeMillis());
        SQLiteDatabase db = getWritableDatabase();
        db.replace("courses", null, values);
        COURSE_NAME_CACHE.remove(cleanCourseId + "|" + cleanClassId);
        COURSE_NAME_CACHE.remove(cleanCourseId + "|");
        backfillCourseName(db, cleanCourseId, cleanClassId, cleanName);
        if (isCourseFullyDisabled(cleanName)) {
            CourseScanScores.forceNeverScan(context, new String[]{cleanCourseId}, new String[]{cleanClassId});
        }
    }

    public String resolveCourse(DeadlineItem item) {
        if (item == null) {
            return "";
        }
        String name = "";
        if (!empty(item.courseId)) {
            name = findCourseName(item.courseId, item.classId);
            if (name.isEmpty()) {
                name = findCourseName(item.courseId, "");
            }
        }
        if (!name.isEmpty() && (empty(item.course) || item.courseConfidence < 90)) {
            item.course = name;
            item.courseConfidence = 90;
        }
        if (empty(item.course)) {
            String inferred = inferCourse((item.title == null ? "" : item.title) + "\n"
                    + (item.raw == null ? "" : item.raw));
            if (!inferred.isEmpty()) {
                item.course = inferred;
                item.courseConfidence = Math.max(item.courseConfidence, 10);
            }
        }
        return item.course == null ? "" : item.course;
    }

    public String findCourseName(String courseId, String classId) {
        if (empty(courseId) && empty(classId)) {
            return "";
        }
        String cacheKey = safe(courseId) + "|" + safe(classId);
        String cached = COURSE_NAME_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String selection;
        String[] args;
        if (!empty(classId)) {
            selection = "course_id = ? AND class_id = ?";
            args = new String[]{courseId, classId};
        } else {
            selection = "course_id = ?";
            args = new String[]{courseId};
        }
        try (Cursor cursor = getReadableDatabase().query("courses", new String[]{"name"}, selection, args,
                null, null, "updated_at DESC", "1")) {
            String name = cursor.moveToFirst() ? cursor.getString(0) : "";
            if (!name.isEmpty()) {
                COURSE_NAME_CACHE.put(cacheKey, name);
            }
            return name;
        } catch (Throwable ignored) {
            return "";
        }
    }

    public String inferCourse(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String best = "";
        for (String course : knownCourses()) {
            if (course == null || course.isEmpty()) {
                continue;
            }
            if (text.contains(course) && course.length() > best.length()) {
                best = course;
            }
        }
        return best;
    }

    private void backfillCourseName(SQLiteDatabase db, String courseId, String classId, String name) {
        if (empty(courseId) || empty(name)) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("course", name);
        values.put("course_confidence", 90);
        String where;
        String[] args;
        if (!empty(classId)) {
            where = "course_id = ? AND class_id = ? AND (course IS NULL OR course = '' OR course_confidence < 90)";
            args = new String[]{courseId, classId};
        } else {
            where = "course_id = ? AND (course IS NULL OR course = '' OR course_confidence < 90)";
            args = new String[]{courseId};
        }
        db.update("deadlines", values, where, args);
    }

    private void preserveSubmissionState(SQLiteDatabase db, DeadlineItem item) {
        if (item == null || item.submissionState != DeadlineItem.SUBMISSION_UNKNOWN
                || item.id == null || item.id.isEmpty()) {
            return;
        }
        if (hasSubmittedMatch(db, "id = ?", new String[]{item.id})) {
            item.setSubmissionState(DeadlineItem.SUBMISSION_SUBMITTED);
            return;
        }
        if (!empty(item.taskId) && !empty(item.courseId)) {
            if (hasSubmittedMatch(db,
                    "type = ? AND course_id = ? AND class_id = ? AND task_id = ?",
                    new String[]{item.type, item.courseId, item.classId == null ? "" : item.classId, item.taskId})) {
                item.setSubmissionState(DeadlineItem.SUBMISSION_SUBMITTED);
                return;
            }
        }
        long window = TYPE_HOMEWORK.equals(item.type) ? 6L * 60L * 60L * 1000L : 30L * 60L * 1000L;
        if (!empty(item.title) && hasSubmittedMatch(db,
                "type = ? AND title = ? AND ABS(due_at - ?) <= ?",
                new String[]{item.type, item.title, String.valueOf(item.dueAt), String.valueOf(window)})) {
            item.setSubmissionState(DeadlineItem.SUBMISSION_SUBMITTED);
            return;
        }
        // A manual entry the user marked done may carry a guessed deadline far off the
        // captured one; the type+title pair is still the same task.
        if (!empty(item.title) && hasSubmittedMatch(db,
                "type = ? AND title = ? AND source = 'manual'",
                new String[]{item.type, item.title})) {
            item.setSubmissionState(DeadlineItem.SUBMISSION_SUBMITTED);
        }
    }

    private boolean hasSubmittedMatch(SQLiteDatabase db, String where, String[] args) {
        try (Cursor cursor = db.query("deadlines", new String[]{"id"},
                "submitted != 0 AND " + where, args, null, null, null, "1")) {
            return cursor.moveToFirst();
        }
    }

    private boolean isManuallyIgnored(SQLiteDatabase db, DeadlineItem item) {
        if (item == null || item.dueAt <= System.currentTimeMillis()) {
            return false;
        }
        if (!empty(item.id) && hasIgnoredMatch(db, "id = ?", new String[]{item.id})) {
            return true;
        }
        if (!empty(item.taskId) && !empty(item.courseId)) {
            if (hasIgnoredMatch(db,
                    "type = ? AND course_id = ? AND class_id = ? AND task_id = ?",
                    new String[]{safe(item.type), safe(item.courseId), safe(item.classId), safe(item.taskId)})) {
                return true;
            }
            // 1.9.0 re-typed taskactivelist rows from 作业 to 课堂活动; an ignore rule
            // recorded under the old type must still suppress the very same task. The
            // title cross-check keeps unrelated tasks that merely share an id namespace
            // (activity ids and work ids are numbered independently) from being suppressed.
            if (hasIgnoredMatch(db,
                    "course_id = ? AND class_id = ? AND task_id = ? AND title = ?",
                    new String[]{safe(item.courseId), safe(item.classId), safe(item.taskId), item.title})) {
                return true;
            }
        }
        long window = TYPE_HOMEWORK.equals(item.type) ? 6L * 60L * 60L * 1000L : 30L * 60L * 1000L;
        if (empty(item.title)) {
            return false;
        }
        if (!empty(item.courseId) || !empty(item.classId)) {
            return hasIgnoredMatch(db,
                    "type = ? AND course_id = ? AND class_id = ? AND title = ? AND ABS(due_at - ?) <= ?",
                    new String[]{safe(item.type), safe(item.courseId), safe(item.classId), item.title,
                            String.valueOf(item.dueAt), String.valueOf(window)});
        }
        return hasIgnoredMatch(db,
                "type = ? AND title = ? AND ABS(due_at - ?) <= ?",
                new String[]{safe(item.type), item.title, String.valueOf(item.dueAt), String.valueOf(window)});
    }

    private boolean hasIgnoredMatch(SQLiteDatabase db, String where, String[] args) {
        try (Cursor cursor = db.query("ignored_deadlines", new String[]{"id"},
                "due_at > ? AND " + where, mergeArgs(String.valueOf(System.currentTimeMillis()), args),
                null, null, null, "1")) {
            return cursor.moveToFirst();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void rememberIgnored(SQLiteDatabase db, DeadlineItem item) {
        if (item == null || item.dueAt <= System.currentTimeMillis() || empty(item.id)
                || empty(item.type) || empty(item.title)) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("id", item.id);
        values.put("type", item.type);
        values.put("title", item.title);
        values.put("course_id", safe(item.courseId));
        values.put("class_id", safe(item.classId));
        values.put("task_id", safe(item.taskId));
        values.put("due_at", item.dueAt);
        values.put("ignored_at", System.currentTimeMillis());
        db.replace("ignored_deadlines", null, values);
    }

    private void pruneIgnored(SQLiteDatabase db) {
        try {
            db.delete("ignored_deadlines", "due_at <= ?", new String[]{String.valueOf(System.currentTimeMillis())});
        } catch (Throwable ignored) {
        }
    }

    /**
     * The captured row a fresh capture duplicates: same type, same title, deadline within
     * the merge window, and a compatible course (same name, or either side unnamed — a
     * passive capture often has no course name at all). Prefers rows carrying a task id,
     * so the richer identity wins the merge.
     */
    private DeadlineItem findTwin(SQLiteDatabase db, DeadlineItem item) {
        if (item.type == null || item.title == null || item.id == null) {
            return null;
        }
        long window = mergeWindow(item.type);
        String course = item.course == null ? "" : item.course;
        String courseId = item.courseId == null ? "" : item.courseId;
        String classId = item.classId == null ? "" : item.classId;
        String where = "id <> ? AND (source IS NULL OR source != 'manual') AND type = ? AND title = ? "
                + "AND ABS(due_at - ?) <= ? "
                + "AND (course = ? OR course = '' OR course IS NULL OR ? = '') "
                + "AND (course_id = ? OR course_id = '' OR course_id IS NULL OR ? = '' "
                + "OR course_id LIKE 'legacy:%' OR ? LIKE 'legacy:%') "
                + "AND (class_id = ? OR class_id = '' OR class_id IS NULL OR ? = '' "
                + "OR class_id LIKE 'legacy:%' OR ? LIKE 'legacy:%')";
        String[] args = {item.id, item.type, item.title,
                String.valueOf(item.dueAt), String.valueOf(window), course, course,
                courseId, courseId, courseId, classId, classId, classId};
        try (Cursor cursor = db.query("deadlines", null, where, args, null, null,
                "CASE WHEN task_id IS NULL OR task_id = '' THEN 1 ELSE 0 END ASC, "
                        + "ABS(due_at - " + item.dueAt + ") ASC", "1")) {
            return cursor.moveToFirst() ? DeadlineItem.fromCursor(cursor) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long mergeWindow(String type) {
        return TYPE_HOMEWORK.equals(type) ? 6L * 60L * 60L * 1000L : 30L * 60L * 1000L;
    }

    private void deleteLikelyDuplicates(SQLiteDatabase db, DeadlineItem item) {
        if (item.type == null || item.title == null || item.id == null) {
            return;
        }
        long window = mergeWindow(item.type);
        String course = item.course == null ? "" : item.course;
        String courseId = item.courseId == null ? "" : item.courseId;
        String classId = item.classId == null ? "" : item.classId;
        // 1) Exact task identity — the strongest signal, unchanged.
        if (!empty(item.taskId) && !empty(item.courseId)) {
            db.delete("deadlines",
                    "id <> ? AND type = ? AND course_id = ? AND class_id = ? AND task_id = ?",
                    new String[]{item.id, item.type, courseId, classId, item.taskId});
        }
        // 2) Same course + same title + close due. The course is matched by NAME with a
        //    course-id guard: the same task arrives with a real id from the active scan
        //    and a name-derived "legacy:hash" id from a passive capture, and those two
        //    must still merge, while two genuinely different courses that happen to
        //    share a homework title must not. A derived or missing id therefore falls
        //    back to the name, an authoritative one on both sides has to be equal.
        db.delete("deadlines",
                "id <> ? AND type = ? AND title = ? AND ABS(due_at - ?) <= ? "
                        + "AND (course = ? OR course = '' OR course IS NULL OR ? = '') "
                        + "AND (course_id = ? OR course_id = '' OR course_id IS NULL OR ? = '' "
                        + "OR course_id LIKE 'legacy:%' OR ? LIKE 'legacy:%') "
                        + "AND (class_id = ? OR class_id = '' OR class_id IS NULL OR ? = '' "
                        + "OR class_id LIKE 'legacy:%' OR ? LIKE 'legacy:%')",
                new String[]{item.id, item.type, item.title,
                        String.valueOf(item.dueAt), String.valueOf(window), course, course,
                        courseId, courseId, courseId, classId, classId, classId});
    }

    // -- duplicate repair (for rows written by older versions) --

    /** How many rows {@link #repairDuplicates()} would remove right now. */
    public int countDuplicates() {
        return repairDuplicates(getReadableDatabase(), false);
    }

    /**
     * Merge rows that are already duplicates in the table: same type, same title,
     * deadline within the merge window and a compatible course. Keeps the row with the
     * richest identity (task id, then course refs), absorbs the rest into it, and drops
     * them. Manual entries are never touched. Repairs databases written by versions
     * whose deletes could not match across the legacy/real course-id spaces.
     */
    public int repairDuplicates() {
        return repairDuplicates(getWritableDatabase(), true);
    }

    /** @param apply false to only measure the damage (used by {@link #countDuplicates()}). */
    private int repairDuplicates(SQLiteDatabase db, boolean apply) {
        List<DeadlineItem> rows = allRows(db);
        ArrayList<DeadlineItem> keepers = new ArrayList<>();
        ArrayList<String> removeIds = new ArrayList<>();
        ArrayList<DeadlineItem> updates = new ArrayList<>();
        for (DeadlineItem row : rows) {
            if (row == null || row.id == null || row.id.isEmpty()
                    || "manual".equals(row.source) || row.title == null || row.title.isEmpty()) {
                continue;
            }
            DeadlineItem keeper = null;
            for (DeadlineItem candidate : keepers) {
                if (isSameTask(candidate, row)) {
                    keeper = candidate;
                    break;
                }
            }
            if (keeper == null) {
                keepers.add(row);
                continue;
            }
            mergeInto(keeper, row);
            removeIds.add(row.id);
            updates.add(keeper);
        }
        if (removeIds.isEmpty() || !apply) {
            return removeIds.size();
        }
        db.beginTransaction();
        try {
            for (DeadlineItem keeper : updates) {
                db.update("deadlines", keeper.toValues(), "id = ?", new String[]{keeper.id});
            }
            for (String id : removeIds) {
                db.delete("deadlines", "id = ?", new String[]{id});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return removeIds.size();
    }

    private static boolean isSameTask(DeadlineItem a, DeadlineItem b) {
        if (a == null || b == null || a.type == null || b.type == null || a.title == null || b.title == null) {
            return false;
        }
        if (!a.type.equals(b.type) || !a.title.equals(b.title)) {
            return false;
        }
        if (Math.abs(a.dueAt - b.dueAt) > mergeWindow(a.type)) {
            return false;
        }
        String courseA = a.course == null ? "" : a.course;
        String courseB = b.course == null ? "" : b.course;
        if (!courseA.isEmpty() && !courseB.isEmpty() && !courseA.equals(courseB)) {
            return false;
        }
        // Same shape as the SQL guard in upsert: an authoritative course id on both
        // sides has to match, a derived "legacy:hash" one falls back to the name.
        return !refConflicts(a.courseId, b.courseId) && !refConflicts(a.classId, b.classId);
    }

    private static boolean refConflicts(String left, String right) {
        String a = left == null ? "" : left.trim();
        String b = right == null ? "" : right.trim();
        boolean realA = !a.isEmpty() && !a.startsWith("legacy:");
        boolean realB = !b.isEmpty() && !b.startsWith("legacy:");
        return realA && realB && !a.equals(b);
    }

    /** Absorb {@code row} into {@code keeper}: fill gaps, prefer submitted, keep earliest. */
    private static void mergeInto(DeadlineItem keeper, DeadlineItem row) {
        if (empty(keeper.taskId)) {
            keeper.taskId = row.taskId;
        }
        if (empty(keeper.courseId)) {
            keeper.courseId = row.courseId;
        }
        if (empty(keeper.classId)) {
            keeper.classId = row.classId;
        }
        if (empty(keeper.course)) {
            keeper.course = row.course;
        }
        if (empty(keeper.url)) {
            keeper.url = row.url;
        }
        if (row.submitted) {
            keeper.submitted = true;
            if (keeper.submissionState == DeadlineItem.SUBMISSION_UNKNOWN) {
                keeper.submissionState = row.submissionState;
            }
        }
        keeper.courseConfidence = Math.max(keeper.courseConfidence, row.courseConfidence);
    }

    private List<DeadlineItem> allRows(SQLiteDatabase db) {
        ArrayList<DeadlineItem> items = new ArrayList<>();
        try (Cursor cursor = db.query("deadlines", null, null, null, null, null, "due_at ASC")) {
            while (cursor.moveToNext()) {
                items.add(DeadlineItem.fromCursor(cursor));
            }
        } catch (Throwable ignored) {
        }
        return items;
    }

    public List<DeadlineItem> activeItems() {
        ArrayList<DeadlineItem> items = new ArrayList<>();
        // No prune() here: this is a hot read path (5s UI poll, every widget refresh and
        // broadcast) and must not carry write side effects. Expired rows are pruned on
        // upsert and when auto-delete is switched on.
        String where = AppSettings.autoDeleteExpired(context) ? "due_at > ?" : null;
        String[] args = AppSettings.autoDeleteExpired(context)
                ? new String[]{String.valueOf(System.currentTimeMillis())}
                : null;
        try (Cursor cursor = getReadableDatabase().query(
                "deadlines", null, where, args, null, null, "due_at ASC")) {
            while (cursor.moveToNext()) {
                DeadlineItem item = DeadlineItem.fromCursor(cursor);
                if (!isSupportedType(item.type)) {
                    continue;
                }
                resolveCourse(item);
                if (!isBlocked(item.course, item.type)) {
                    items.add(item);
                }
            }
        }
        return items;
    }

    /** Homework, exam, chapter task points and classroom activities are the types this module tracks. */
    public static boolean isSupportedType(String type) {
        return TYPE_HOMEWORK.equals(type) || TYPE_EXAM.equals(type) || TYPE_CHAPTER.equals(type)
                || TYPE_ACTIVITY.equals(type);
    }

    /** Types offered in the per-course management screen, in display order. */
    public static String[] managedTypes() {
        return new String[]{TYPE_HOMEWORK, TYPE_EXAM, TYPE_CHAPTER, TYPE_ACTIVITY};
    }

    /**
     * Consecutive authoritative scans an item may miss before it is flagged as probably
     * revoked. Two rounds, never one: an item passively captured after the round snapshot,
     * or a teacher who merely moved the deadline, must not trip this.
     */
    private static final int VANISH_MISSES = 2;

    /**
     * Fold one completed scan round into the store.
     *
     * The hook reports which courses it actually fetched successfully plus every item id
     * it saw during the round. An item is only judged when its own course was
     * authoritatively scanned; manual entries, chapter task points (passive-only, never
     * actively fetched) and terminal rows are never touched. Returns the number of rows
     * newly flagged as probably revoked.
     */
    public int applyScanRound(String[] courseKeys, String[] seenIds) {
        if (courseKeys == null || courseKeys.length == 0 || seenIds == null) {
            return 0;
        }
        Set<String> scanned = new HashSet<>(Arrays.asList(courseKeys));
        Set<String> seen = new HashSet<>(Arrays.asList(seenIds));
        boolean autoRemove = AppSettings.autoRemoveVanished(context);
        long now = System.currentTimeMillis();
        ArrayList<DeadlineItem> updates = new ArrayList<>();
        ArrayList<String> deleteIds = new ArrayList<>();
        int flagged = 0;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor cursor = db.query("deadlines", null, null, null, null, null, null)) {
                while (cursor.moveToNext()) {
                    DeadlineItem item = DeadlineItem.fromCursor(cursor);
                    if (item.submitted || item.dueAt <= now) {
                        continue;
                    }
                    if ("manual".equals(item.source) || !isVanishableType(item.type)) {
                        continue;
                    }
                    if (empty(item.courseId)
                            || !scanned.contains(safe(item.courseId) + "|" + safe(item.classId))) {
                        continue;
                    }
                    if (seen.contains(item.id)) {
                        if (item.misses != 0 || item.vanishedAt > 0L) {
                            item.misses = 0;
                            item.vanishedAt = 0L;
                            updates.add(item);
                        }
                        continue;
                    }
                    item.misses = item.misses + 1;
                    if (item.misses >= VANISH_MISSES) {
                        if (item.vanishedAt <= 0L) {
                            item.vanishedAt = now;
                            flagged++;
                        }
                        if (autoRemove) {
                            deleteIds.add(item.id);
                            continue;
                        }
                    }
                    updates.add(item);
                }
            }
            for (DeadlineItem item : updates) {
                ContentValues values = new ContentValues();
                values.put("misses", item.misses);
                values.put("vanished_at", item.vanishedAt);
                db.update("deadlines", values, "id = ?", new String[]{item.id});
            }
            for (String id : deleteIds) {
                db.delete("deadlines", "id = ?", new String[]{id});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return flagged;
    }

    /** User says "still valid": clear the flag and the miss counter. */
    public void clearVanished(String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("misses", 0);
        values.put("vanished_at", 0L);
        getWritableDatabase().update("deadlines", values, "id = ?", new String[]{id});
    }

    /** Only the types the active scan endpoints actually report can be judged missing. */
    private static boolean isVanishableType(String type) {
        return TYPE_HOMEWORK.equals(type) || TYPE_EXAM.equals(type) || TYPE_ACTIVITY.equals(type);
    }

    /** Marks an item done (or undone) by hand; the hook still overwrites it on next capture. */
    public void setSubmitted(String id, boolean submitted) {
        if (id == null || id.isEmpty()) {
            return;
        }
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("submitted", submitted ? 1 : 0);
        values.put("submit_state", submitted
                ? DeadlineItem.SUBMISSION_SUBMITTED : DeadlineItem.SUBMISSION_UNSUBMITTED);
        values.put("updated_at", System.currentTimeMillis());
        db.update("deadlines", values, "id = ?", new String[]{id});
    }

    // -- weekly timetable (课程表) --

    /** Replace the whole timetable with the latest capture. */
    public void replaceLessons(List<Lesson> lessons, long firstWeekDate, String sectionTimesJson) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("lessons", null, null);
            long now = System.currentTimeMillis();
            if (lessons != null) {
                for (Lesson lesson : lessons) {
                    if (lesson == null || lesson.name == null || lesson.name.isEmpty()
                            || lesson.dayOfWeek <= 0 || lesson.beginSection <= 0) {
                        continue;
                    }
                    ContentValues values = new ContentValues();
                    values.put("id", lesson.id == null || lesson.id.isEmpty()
                            ? lesson.stableId() : lesson.id);
                    values.put("name", lesson.name);
                    values.put("teacher", lesson.teacher);
                    values.put("location", lesson.location);
                    values.put("day_of_week", lesson.dayOfWeek);
                    values.put("begin_section", lesson.beginSection);
                    values.put("sections", lesson.sections <= 0 ? 1 : lesson.sections);
                    values.put("weeks", lesson.weeks);
                    values.put("updated_at", now);
                    db.replace("lessons", null, values);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        SharedPreferences.Editor editor = schedulePrefs().edit();
        if (firstWeekDate > 0L) {
            editor.putLong(KEY_FIRST_WEEK_DATE, firstWeekDate);
        }
        if (sectionTimesJson != null && !sectionTimesJson.isEmpty()) {
            editor.putString(KEY_SECTION_TIMES, sectionTimesJson);
        }
        editor.putLong(KEY_SCHEDULE_UPDATED_AT, System.currentTimeMillis());
        editor.apply();
    }

    public List<Lesson> allLessons() {
        ArrayList<Lesson> lessons = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("lessons", null, null, null, null, null,
                "day_of_week ASC, begin_section ASC")) {
            while (cursor.moveToNext()) {
                Lesson lesson = new Lesson();
                lesson.id = columnText(cursor, "id");
                lesson.name = columnText(cursor, "name");
                lesson.teacher = columnText(cursor, "teacher");
                lesson.location = columnText(cursor, "location");
                lesson.dayOfWeek = columnInt(cursor, "day_of_week", 0);
                lesson.beginSection = columnInt(cursor, "begin_section", 0);
                lesson.sections = columnInt(cursor, "sections", 1);
                lesson.weeks = columnText(cursor, "weeks");
                if (lesson.dayOfWeek > 0 && lesson.beginSection > 0 && !lesson.name.isEmpty()) {
                    lessons.add(lesson);
                }
            }
        } catch (Throwable ignored) {
        }
        return lessons;
    }

    private static String columnText(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) {
            return "";
        }
        String value = cursor.getString(index);
        return value == null ? "" : value;
    }

    private static int columnInt(Cursor cursor, String column, int fallback) {
        int index = cursor.getColumnIndex(column);
        return index < 0 ? fallback : cursor.getInt(index);
    }

    public long scheduleFirstWeekDate() {
        return schedulePrefs().getLong(KEY_FIRST_WEEK_DATE, 0L);
    }

    public String scheduleSectionTimes() {
        return schedulePrefs().getString(KEY_SECTION_TIMES, "");
    }

    public long scheduleUpdatedAt() {
        return schedulePrefs().getLong(KEY_SCHEDULE_UPDATED_AT, 0L);
    }

    private SharedPreferences schedulePrefs() {
        return context.getSharedPreferences(SCHEDULE_PREFS, Context.MODE_PRIVATE);
    }

    public int countAll() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM deadlines", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public DeadlineItem itemById(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        try (Cursor cursor = getReadableDatabase().query("deadlines", null, "id = ?",
                new String[]{id}, null, null, null, "1")) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            DeadlineItem item = DeadlineItem.fromCursor(cursor);
            resolveCourse(item);
            if (item.submitted || isBlocked(item.course, item.type)) {
                return null;
            }
            return item;
        }
    }

    public void deleteItem(String id) {
        if (id == null || id.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor cursor = db.query("deadlines", null, "id = ?", new String[]{id},
                null, null, null, "1")) {
            if (cursor.moveToFirst()) {
                rememberIgnored(db, DeadlineItem.fromCursor(cursor));
            }
        } catch (Throwable ignored) {
        }
        db.delete("deadlines", "id = ?", new String[]{id});
    }

    public void clear() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("deadlines", null, null);
        db.delete("ignored_deadlines", null, null);
    }

    public void blockCourseType(String course, String type, boolean blocked) {
        setCourseTypeEnabled(course, type, !blocked);
    }

    public void setCourseTypeEnabled(String course, String type, boolean enabled) {
        if (course == null || course.trim().isEmpty()) {
            return;
        }
        String cleanCourse = course.trim();
        String key = blockKey(cleanCourse, type);
        Set<String> rules = new HashSet<>(blockedRules());
        if (enabled) {
            rules.remove(key);
        } else {
            rules.add(key);
        }
        prefs().edit()
                .putStringSet(KEY_BLOCKED_RULES, rules)
                .apply();
        blockedRulesCache = rules;
        syncCourseScanState(cleanCourse);
    }

    public void clearBlockedCourses() {
        String[][] refs = allKnownCourseRefs();
        prefs().edit()
                .remove(KEY_LEGACY_COURSES)
                .remove(KEY_BLOCKED_RULES)
                .apply();
        legacyCoursesCache = new HashSet<>();
        blockedRulesCache = new HashSet<>();
        CourseScanScores.restoreFromNeverScan(context, refs[0], refs[1]);
    }

    public Set<String> blockedCourses() {
        Set<String> result = new HashSet<>(legacyCourses());
        for (String rule : blockedRules()) {
            int bar = rule.lastIndexOf('|');
            if (bar > 0) {
                result.add(rule.substring(0, bar));
            }
        }
        return result;
    }

    public boolean isBlocked(String course) {
        return isBlocked(course, TYPE_ALL);
    }

    public boolean isBlocked(String course, String type) {
        return !isCourseTypeEnabled(course, type);
    }

    public boolean isCourseTypeEnabled(String course, String type) {
        if (course == null || course.trim().isEmpty()) {
            return true;
        }
        String cleanCourse = course.trim();
        if (legacyCourses().contains(cleanCourse)) {
            return false;
        }
        Set<String> rules = blockedRules();
        return !rules.contains(blockKey(cleanCourse, TYPE_ALL)) && !rules.contains(blockKey(cleanCourse, type));
    }

    public void markCourseDisabledByScore(String courseId, String classId, String course) {
        String cleanCourse = course == null ? "" : course.trim();
        if (cleanCourse.isEmpty()) {
            cleanCourse = findCourseName(courseId, classId);
        }
        if (cleanCourse.isEmpty()) {
            return;
        }
        Set<String> rules = new HashSet<>(blockedRules());
        for (String type : managedTypes()) {
            rules.add(blockKey(cleanCourse, type));
        }
        prefs().edit().putStringSet(KEY_BLOCKED_RULES, rules).apply();
        blockedRulesCache = rules;
    }

    public List<String> knownCourses() {
        HashSet<String> courses = new HashSet<>(blockedCourses());
        courses.addAll(knownCoursesFromDb());
        ArrayList<String> result = new ArrayList<>(courses);
        Collections.sort(result);
        return result;
    }

    private Set<String> knownCoursesFromDb() {
        HashSet<String> courses = new HashSet<>();
        try (Cursor cursor = getReadableDatabase().query("courses", new String[]{"name"},
                "name IS NOT NULL AND name <> ''", null, "name", null, "name ASC")) {
            while (cursor.moveToNext()) {
                courses.add(cursor.getString(0));
            }
        }
        try (Cursor cursor = getReadableDatabase().query("deadlines", new String[]{"course"},
                "course IS NOT NULL AND course <> ''", null, "course", null, "course ASC")) {
            while (cursor.moveToNext()) {
                courses.add(cursor.getString(0));
            }
        }
        return courses;
    }

    private boolean isCourseFullyDisabled(String course) {
        for (String type : managedTypes()) {
            if (!isBlocked(course, type)) {
                return false;
            }
        }
        return true;
    }

    private void syncCourseScanState(String course) {
        String[][] refs = knownCourseRefs(course);
        if (refs[0].length == 0) {
            return;
        }
        if (isCourseFullyDisabled(course)) {
            CourseScanScores.forceNeverScan(context, refs[0], refs[1]);
        } else {
            CourseScanScores.restoreFromNeverScan(context, refs[0], refs[1]);
        }
    }

    private String[][] knownCourseRefs(String course) {
        ArrayList<String> courseIds = new ArrayList<>();
        ArrayList<String> classIds = new ArrayList<>();
        if (course == null || course.trim().isEmpty()) {
            return new String[][]{new String[0], new String[0]};
        }
        try (Cursor cursor = getReadableDatabase().query("courses", new String[]{"course_id", "class_id"},
                "name = ?", new String[]{course.trim()}, null, null, null)) {
            while (cursor.moveToNext()) {
                String courseId = cursor.getString(0);
                String classId = cursor.getString(1);
                if (!empty(courseId)) {
                    courseIds.add(courseId);
                    classIds.add(classId == null ? "" : classId);
                }
            }
        } catch (Throwable ignored) {
        }
        return new String[][]{courseIds.toArray(new String[0]), classIds.toArray(new String[0])};
    }

    private String[][] allKnownCourseRefs() {
        ArrayList<String> courseIds = new ArrayList<>();
        ArrayList<String> classIds = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("courses", new String[]{"course_id", "class_id"},
                null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                String courseId = cursor.getString(0);
                String classId = cursor.getString(1);
                if (!empty(courseId)) {
                    courseIds.add(courseId);
                    classIds.add(classId == null ? "" : classId);
                }
            }
        } catch (Throwable ignored) {
        }
        return new String[][]{courseIds.toArray(new String[0]), classIds.toArray(new String[0])};
    }

    private String blockKey(String course, String type) {
        String normalizedType = type == null || type.trim().isEmpty() ? TYPE_ALL : type.trim();
        return course.trim() + "|" + normalizedType;
    }

    private Set<String> blockedRules() {
        Set<String> cached = blockedRulesCache;
        if (cached == null) {
            cached = new HashSet<>(prefs().getStringSet(KEY_BLOCKED_RULES, new HashSet<>()));
            blockedRulesCache = cached;
        }
        return cached;
    }

    private Set<String> legacyCourses() {
        Set<String> cached = legacyCoursesCache;
        if (cached == null) {
            cached = new HashSet<>(prefs().getStringSet(KEY_LEGACY_COURSES, new HashSet<>()));
            legacyCoursesCache = cached;
        }
        return cached;
    }

    /** Drops the in-memory settings caches; next read reloads from prefs. */
    static void invalidateSettingCaches() {
        legacyCoursesCache = null;
        blockedRulesCache = null;
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void prune() {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        db.delete("deadlines", "due_at <= ? OR submitted != 0", new String[]{String.valueOf(now)});
        pruneIgnored(db);
    }

    private static String[] mergeArgs(String first, String[] rest) {
        String[] result = new String[(rest == null ? 0 : rest.length) + 1];
        result[0] = first;
        if (rest != null && rest.length > 0) {
            System.arraycopy(rest, 0, result, 1, rest.length);
        }
        return result;
    }

    private static boolean empty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
