package dev.chaoxingdeadline;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class DeadlineStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "deadlines.db";
    private static final int DB_VERSION = 5;
    private static final String PREFS = "blocked_courses";
    private static final String KEY_LEGACY_COURSES = "courses";
    private static final String KEY_BLOCKED_RULES = "rules";
    private static final String TYPE_ALL = "\u5168\u90e8";
    private static final String TYPE_HOMEWORK = "\u4f5c\u4e1a";
    private static final String TYPE_EXAM = "\u8003\u8bd5";
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
                + "source TEXT,"
                + "url TEXT,"
                + "updated_at INTEGER NOT NULL,"
                + "raw TEXT)");
        db.execSQL("CREATE INDEX idx_deadlines_due ON deadlines(due_at)");
        db.execSQL("CREATE INDEX idx_deadlines_course_ref ON deadlines(course_id, class_id)");
        db.execSQL("CREATE INDEX idx_deadlines_task_ref ON deadlines(type, course_id, class_id, task_id)");
        createCourseTable(db);
        createIgnoredTable(db);
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
        SQLiteDatabase db = getWritableDatabase();
        pruneIgnored(db);
        if (isManuallyIgnored(db, item)) {
            return;
        }
        preserveSubmissionState(db, item);
        db.replace("deadlines", null, item.toValues());
        deleteLikelyDuplicates(db, item);
        if (AppSettings.autoDeleteExpired(context)) {
            prune();
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
            return cursor.moveToFirst() ? cursor.getString(0) : "";
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
            return hasIgnoredMatch(db,
                    "type = ? AND course_id = ? AND class_id = ? AND task_id = ?",
                    new String[]{safe(item.type), safe(item.courseId), safe(item.classId), safe(item.taskId)});
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

    private void deleteLikelyDuplicates(SQLiteDatabase db, DeadlineItem item) {
        if (item.type == null || item.title == null || item.id == null) {
            return;
        }
        if (!empty(item.taskId) && !empty(item.courseId)) {
            db.delete("deadlines",
                    "id <> ? AND type = ? AND course_id = ? AND class_id = ? AND task_id = ?",
                    new String[]{item.id, item.type, item.courseId, item.classId == null ? "" : item.classId, item.taskId});
            return;
        }
        long window = TYPE_HOMEWORK.equals(item.type) ? 6L * 60L * 60L * 1000L : 30L * 60L * 1000L;
        if (!empty(item.courseId) || !empty(item.classId)) {
            db.delete(
                    "deadlines",
                    "id <> ? AND type = ? AND course_id = ? AND class_id = ? AND title = ? AND ABS(due_at - ?) <= ?",
                    new String[]{item.id, item.type, item.courseId == null ? "" : item.courseId,
                            item.classId == null ? "" : item.classId, item.title,
                            String.valueOf(item.dueAt), String.valueOf(window)});
            return;
        }
        db.delete(
                "deadlines",
                "id <> ? AND type = ? AND title = ? AND ABS(due_at - ?) <= ?",
                new String[]{item.id, item.type, item.title, String.valueOf(item.dueAt), String.valueOf(window)});
    }

    public List<DeadlineItem> activeItems() {
        ArrayList<DeadlineItem> items = new ArrayList<>();
        if (AppSettings.autoDeleteExpired(context)) {
            prune();
        }
        String where = AppSettings.autoDeleteExpired(context) ? "due_at > ?" : null;
        String[] args = AppSettings.autoDeleteExpired(context)
                ? new String[]{String.valueOf(System.currentTimeMillis())}
                : null;
        try (Cursor cursor = getReadableDatabase().query(
                "deadlines", null, where, args, null, null, "due_at ASC")) {
            while (cursor.moveToNext()) {
                DeadlineItem item = DeadlineItem.fromCursor(cursor);
                if (!TYPE_HOMEWORK.equals(item.type) && !TYPE_EXAM.equals(item.type)) {
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
        syncCourseScanState(cleanCourse);
    }

    public void clearBlockedCourses() {
        String[][] refs = allKnownCourseRefs();
        prefs().edit()
                .remove(KEY_LEGACY_COURSES)
                .remove(KEY_BLOCKED_RULES)
                .apply();
        CourseScanScores.restoreFromNeverScan(context, refs[0], refs[1]);
    }

    public Set<String> blockedCourses() {
        Set<String> result = new HashSet<>(prefs().getStringSet(KEY_LEGACY_COURSES, new HashSet<>()));
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
        Set<String> legacyCourses = prefs().getStringSet(KEY_LEGACY_COURSES, new HashSet<>());
        if (legacyCourses.contains(cleanCourse)) {
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
        rules.add(blockKey(cleanCourse, TYPE_HOMEWORK));
        rules.add(blockKey(cleanCourse, TYPE_EXAM));
        prefs().edit().putStringSet(KEY_BLOCKED_RULES, rules).apply();
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
        return isBlocked(course, TYPE_HOMEWORK) && isBlocked(course, TYPE_EXAM);
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
        return new HashSet<>(prefs().getStringSet(KEY_BLOCKED_RULES, new HashSet<>()));
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
