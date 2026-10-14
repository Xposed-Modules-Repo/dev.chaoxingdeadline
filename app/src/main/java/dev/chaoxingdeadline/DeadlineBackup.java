package dev.chaoxingdeadline;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Export / import helpers. The module owns no cloud storage and holds no INTERNET
 * permission, so backup is a plain file the user picks: JSON round-trips this module's
 * own data (todos plus settings), ICS drops the same deadlines into any calendar app.
 *
 * Settings cover the three module-local preference files. The bridge-auth token file is
 * deliberately NOT included: it is a device-local secret, and a fresh install generates
 * its own on first launch.
 */
public final class DeadlineBackup {
    public static final String FORMAT = "chaoxingdeadline-backup";
    public static final int VERSION = 2;
    private static final String PRODID = "-//dev.chaoxingdeadline//Deadline//CN";
    private static final String[] SETTINGS_FILES = {
            AppSettings.PREFS, AppSettings.WIDGET_PREFS, DeadlineStore.PREFS
    };

    private DeadlineBackup() {
    }

    public static JSONObject exportJson(Context context) throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("version", VERSION);
        root.put("exportedAt", System.currentTimeMillis());
        JSONArray items = new JSONArray();
        for (DeadlineItem item : new DeadlineStore(context).activeItems()) {
            items.put(item.toJson());
        }
        root.put("items", items);
        root.put("settings", exportSettings(context));
        return root;
    }

    /** Number of items actually restored; -1 when the payload is not a backup file. */
    public static int importJson(Context context, String payload, boolean replaceExisting) {
        return importJson(context, payload, replaceExisting, false);
    }

    /**
     * @param restoreSettings when true, a v2 file's settings section replaces the current
     *        settings and course-block rules; v1 files without that section leave them be.
     */
    public static int importJson(Context context, String payload, boolean replaceExisting,
                                 boolean restoreSettings) {
        if (payload == null || payload.trim().isEmpty()) {
            return -1;
        }
        try {
            JSONObject root = new JSONObject(payload);
            if (!FORMAT.equals(root.optString("format"))) {
                return -1;
            }
            JSONArray items = root.optJSONArray("items");
            if (items == null) {
                return -1;
            }
            // Parse and validate the whole payload before touching the store, so a
            // malformed entry can never leave a replace-import with a cleared,
            // half-restored database.
            ArrayList<DeadlineItem> parsed = new ArrayList<>(items.length());
            for (int i = 0; i < items.length(); i++) {
                DeadlineItem item = DeadlineItem.fromJson(items.getJSONObject(i).toString());
                if (item.title == null || item.title.isEmpty() || item.dueAt <= 0L) {
                    continue;
                }
                if (!DeadlineStore.isSupportedType(item.type)) {
                    continue;
                }
                if (item.id == null || item.id.isEmpty()) {
                    item.id = item.stableId();
                }
                parsed.add(item);
            }
            DeadlineStore store = new DeadlineStore(context);
            if (replaceExisting) {
                store.clear();
            }
            int restored = 0;
            for (DeadlineItem item : parsed) {
                store.upsert(item);
                restored++;
            }
            int settingsRestored = restoreSettings ? importSettings(context, root) : 0;
            if (settingsRestored > 0) {
                // The launcher alias is driven by a component setting, not just the pref.
                AppSettings.setLauncherHidden(context, AppSettings.launcherHidden(context));
            }
            DeadlineNotifier.rescheduleAll(context);
            DeadlineNotifier.scheduleNextDigest(context);
            OverlayBridge.publish(context);
            DeadlineWidgetProvider.updateAll(context);
            context.sendBroadcast(new Intent(DeadlineReceiver.ACTION_REFRESH)
                    .setPackage(context.getPackageName()));
            return restored;
        } catch (Throwable throwable) {
            return -1;
        }
    }

    private static JSONObject exportSettings(Context context) throws Exception {
        JSONObject settings = new JSONObject();
        for (String file : SETTINGS_FILES) {
            settings.put(file, prefsToJson(context.getSharedPreferences(file, Context.MODE_PRIVATE)));
        }
        return settings;
    }

    private static JSONObject prefsToJson(SharedPreferences prefs) throws Exception {
        JSONObject json = new JSONObject();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Set) {
                JSONArray array = new JSONArray();
                for (String item : (Set<String>) value) {
                    array.put(item);
                }
                json.put(entry.getKey(), array);
            } else {
                json.put(entry.getKey(), value);
            }
        }
        return json;
    }

    /** @return how many preference files were replaced (0 when the file has no settings). */
    private static int importSettings(Context context, JSONObject root) {
        JSONObject settings = root.optJSONObject("settings");
        if (settings == null) {
            return 0;
        }
        int restored = 0;
        for (String file : SETTINGS_FILES) {
            JSONObject json = settings.optJSONObject(file);
            if (json == null) {
                continue;
            }
            SharedPreferences.Editor editor =
                    context.getSharedPreferences(file, Context.MODE_PRIVATE).edit();
            editor.clear();
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = json.opt(key);
                if (value instanceof JSONArray) {
                    JSONArray array = (JSONArray) value;
                    Set<String> set = new HashSet<>();
                    for (int i = 0; i < array.length(); i++) {
                        set.add(array.optString(i));
                    }
                    editor.putStringSet(key, set);
                } else if (value instanceof Boolean) {
                    editor.putBoolean(key, (Boolean) value);
                } else if (value instanceof Integer) {
                    editor.putInt(key, (Integer) value);
                } else if (value instanceof Long) {
                    editor.putLong(key, (Long) value);
                } else if (value instanceof Double) {
                    editor.putFloat(key, ((Double) value).floatValue());
                } else if (value instanceof String) {
                    editor.putString(key, (String) value);
                }
            }
            editor.apply();
            restored++;
        }
        if (restored > 0) {
            AppSettings.syncRemotePreferences(context);
            DeadlineStore.invalidateSettingCaches();
        }
        return restored;
    }

    /** RFC 5545 calendar with one VEVENT per deadline and a single advance alarm. */
    public static String exportIcs(Context context) {
        List<DeadlineItem> items = new DeadlineStore(context).activeItems();
        SimpleDateFormat stamp = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        stamp.setTimeZone(TimeZone.getTimeZone("UTC"));
        StringBuilder builder = new StringBuilder();
        builder.append("BEGIN:VCALENDAR\r\n")
                .append("VERSION:2.0\r\n")
                .append("PRODID:").append(PRODID).append("\r\n")
                .append("CALSCALE:GREGORIAN\r\n")
                .append("METHOD:PUBLISH\r\n")
                .append("X-WR-CALNAME:").append(escapeText("学习通待办")).append("\r\n");
        int hours = AppSettings.notifyHours(context);
        for (DeadlineItem item : items) {
            if (item == null || item.dueAt <= 0L) {
                continue;
            }
            String start = stamp.format(new Date(item.dueAt));
            String end = stamp.format(new Date(item.dueAt + TimeUnit.MINUTES.toMillis(30)));
            String stampNow = stamp.format(new Date());
            builder.append("BEGIN:VEVENT\r\n")
                    .append("UID:").append(safe(item.id)).append("@chaoxingdeadline\r\n")
                    .append("DTSTAMP:").append(stampNow).append("\r\n")
                    .append("DTSTART:").append(start).append("\r\n")
                    .append("DTEND:").append(end).append("\r\n")
                    .append("SUMMARY:").append(escapeText("[" + safe(item.type) + "] " + safe(item.title)))
                    .append("\r\n")
                    .append("DESCRIPTION:").append(escapeText(describe(item))).append("\r\n")
                    .append("BEGIN:VALARM\r\n")
                    .append("ACTION:DISPLAY\r\n")
                    .append("TRIGGER:-PT").append(Math.max(1, hours)).append("H\r\n")
                    .append("DESCRIPTION:").append(escapeText(safe(item.title))).append("\r\n")
                    .append("END:VALARM\r\n")
                    .append("END:VEVENT\r\n");
        }
        builder.append("END:VCALENDAR\r\n");
        return builder.toString();
    }

    private static String describe(DeadlineItem item) {
        StringBuilder text = new StringBuilder();
        if (item.course != null && !item.course.isEmpty()) {
            text.append(item.course).append(" / ");
        }
        text.append(safe(item.type));
        if (item.url != null && item.url.startsWith("http")) {
            text.append("\n").append(item.url);
        }
        return text.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String escapeText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\n")
                .replace("\n", "\\n");
    }
}
