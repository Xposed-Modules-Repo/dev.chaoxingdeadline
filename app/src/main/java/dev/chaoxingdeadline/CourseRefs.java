package dev.chaoxingdeadline;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Course references harvested from course-list payloads (mycourse/backclazzdata and
 * friends). The scanner needs courseId + classId (+ uid/cpi when present) to pull each
 * course's deadline endpoints; the raw JSON is kept for field-summary logging.
 */
final class CourseRefs {
    private CourseRefs() {}

    static final class CourseRef {
        final String courseId;
        final String classId;
        final String uid;
        final String cpi;
        final String courseName;
        /** Class key (channelList[].key) — the gas/clazz chapter endpoint takes this, not classId. */
        final String key;
        final String raw;

        CourseRef(String courseId, String classId, String uid, String cpi, String courseName,
                  String key, String raw) {
            this.courseId = courseId == null ? "" : courseId;
            this.classId = classId == null ? "" : classId;
            this.uid = uid == null ? "" : uid;
            this.cpi = cpi == null ? "" : cpi;
            this.courseName = courseName == null ? "" : courseName;
            this.key = key == null ? "" : key;
            this.raw = raw == null ? "" : raw;
        }
    }

    /**
     * Depth-first walk collecting every object that carries a courseId + classId pair.
     * Bounded by depth and count so a pathological payload cannot stall the hook.
     */
    static void collectCourseRefs(Object node, List<CourseRef> out, int depth) throws Exception {
        if (node == null || depth > 12 || out.size() > 80) {
            return;
        }
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject content = object.optJSONObject("content");
            if (content != null) {
                // key/cpi live on the channelList item, not inside content.
                CourseRef ref = refFromContent(content,
                        firstString(object, "key"), firstString(object, "cpi"));
                if (ref != null) {
                    out.add(ref);
                }
            }
            String courseId = firstString(object, "courseid", "courseId", "courseIdStr", "courseidStr");
            String classId = firstString(object, "classid", "classId", "clazzid", "clazzId", "clazzIdStr", "clazzidStr");
            String uid = firstString(object, "uid", "puid", "personid", "personId");
            String cpi = firstString(object, "cpi", "cpiId");
            String key = firstString(object, "key");
            String courseName = firstString(object, "courseName", "coursename", "name", "title", "clazzName", "className");
            if (!courseId.isEmpty() && !classId.isEmpty()) {
                out.add(new CourseRef(courseId, classId, uid, cpi, courseName, key, object.toString()));
            }
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                Object child = object.opt(keys.next());
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    collectCourseRefs(child, out, depth + 1);
                }
            }
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                Object child = array.opt(i);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    collectCourseRefs(child, out, depth + 1);
                }
            }
        }
    }

    /** backclazzdata nests the real identity under content.course.data[0] + content.id. */
    static CourseRef refFromContent(JSONObject content, String parentKey, String parentCpi) {
        JSONObject course = content.optJSONObject("course");
        if (course == null) {
            return null;
        }
        JSONObject courseData = null;
        JSONArray data = course.optJSONArray("data");
        if (data != null && data.length() > 0) {
            courseData = data.optJSONObject(0);
        }
        if (courseData == null) {
            courseData = course;
        }
        String courseId = firstString(courseData, "id", "courseid", "courseId", "courseIdStr");
        String classId = firstString(content, "id", "clazzid", "clazzId", "classid", "classId");
        String uid = firstString(content, "uid", "puid", "personid", "personId");
        String cpi = firstString(content, "cpi", "cpiId");
        if (cpi.isEmpty()) {
            cpi = parentCpi == null ? "" : parentCpi;
        }
        String key = parentKey == null ? "" : parentKey;
        String courseName = firstString(courseData, "name", "courseName", "coursename", "title");
        if (courseName.isEmpty()) {
            courseName = firstString(content, "courseName", "coursename", "name", "title", "clazzName", "className");
        }
        if (courseId.isEmpty() || classId.isEmpty()) {
            return null;
        }
        return new CourseRef(courseId, classId, uid, cpi, courseName, key, content.toString());
    }

    static String firstString(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.optString(key, "");
            if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) {
                return value;
            }
        }
        return "";
    }
}
