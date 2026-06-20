package dev.chaoxingdeadline;

import java.util.Locale;

public final class ParseContext {
    public final String source;
    public final String url;
    public final String courseId;
    public final String classId;
    public final String cpi;
    public final String uid;
    public final String courseName;
    public final int courseConfidence;

    public ParseContext(String source, String url, String courseId, String classId,
                        String cpi, String uid, String courseName, int courseConfidence) {
        this.source = clean(source);
        this.url = clean(url);
        this.courseId = clean(courseId);
        this.classId = clean(classId);
        this.cpi = clean(cpi);
        this.uid = clean(uid);
        this.courseName = clean(courseName);
        this.courseConfidence = courseConfidence;
    }

    public static ParseContext simple(String source) {
        return fromSource(source, "");
    }

    public static ParseContext fromSource(String source, String url) {
        String cleanSource = clean(source);
        String[] parts = cleanSource.split("\\|", -1);
        String sourceType = parts.length > 0 ? parts[0] : cleanSource;
        String courseName = parts.length > 1 ? parts[1] : "";
        String courseId = parts.length > 2 ? parts[2] : "";
        String classId = parts.length > 3 ? parts[3] : "";
        String cpi = parts.length > 4 ? parts[4] : "";
        String uid = parts.length > 5 ? parts[5] : "";
        return new ParseContext(sourceType, url, courseId, classId, cpi, uid, courseName,
                courseName.isEmpty() ? 0 : 80);
    }

    public static ParseContext forCourse(String source, String url, String courseId, String classId,
                                         String cpi, String uid, String courseName) {
        return new ParseContext(source, url, courseId, classId, cpi, uid, courseName,
                clean(courseName).isEmpty() ? 0 : 90);
    }

    public ParseContext withUrl(String nextUrl) {
        return new ParseContext(source, nextUrl, courseId, classId, cpi, uid, courseName, courseConfidence);
    }

    public boolean isActive() {
        return source.toLowerCase(Locale.ROOT).startsWith("active.");
    }

    public boolean hasCourseIdentity() {
        return !courseId.isEmpty() || !classId.isEmpty();
    }

    public String legacySource() {
        if (courseName.isEmpty()) {
            return source;
        }
        return source + "|" + courseName;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
