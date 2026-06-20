package dev.chaoxingdeadline;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.List;

import io.github.libxposed.service.XposedService;

public final class OverlayBridge {
    public static final String PREFS = "overlay_data";
    public static final String KEY_ITEMS = "items";
    public static final String KEY_UPDATED_AT = "updated_at";

    private OverlayBridge() {
    }

    public static void publish(Context context) {
        XposedService service = App.getService();
        if (service == null) {
            return;
        }
        try {
            List<DeadlineItem> items = new DeadlineStore(context).activeItems();
            long now = System.currentTimeMillis();
            Collections.sort(items, (a, b) -> {
                boolean aExpired = a.dueAt <= now;
                boolean bExpired = b.dueAt <= now;
                if (aExpired != bExpired) {
                    return aExpired ? 1 : -1;
                }
                return Long.compare(a.dueAt, b.dueAt);
            });
            JSONArray array = new JSONArray();
            for (DeadlineItem item : items) {
                if (item.dueAt <= now) {
                    continue;
                }
                JSONObject json = new JSONObject();
                json.put("type", item.type);
                json.put("title", item.title);
                json.put("course", item.course);
                json.put("dueAt", item.dueAt);
                array.put(json);
            }
            service.getRemotePreferences(PREFS)
                    .edit()
                    .putString(KEY_ITEMS, array.toString())
                    .putLong(KEY_UPDATED_AT, now)
                    .apply();
        } catch (Throwable ignored) {
        }
    }
}
