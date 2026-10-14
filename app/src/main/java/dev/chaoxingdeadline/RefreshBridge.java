package dev.chaoxingdeadline;

import android.content.Context;
import android.content.Intent;

/**
 * Module-app → Chaoxing-host refresh requests.
 *
 * The hook lives inside the Chaoxing process and drives all data capture, so an
 * in-app refresh button has to reach across the process boundary. The host keeps a
 * token-authenticated receiver registered for this action (see ChaoxingHook); the
 * token comes from the module's remote prefs, shared with the hook through the
 * Xposed service, so only this module can trigger a round.
 */
public final class RefreshBridge {
    /** The hooked app; refresh requests are addressed here explicitly. */
    public static final String TARGET_PACKAGE = "com.chaoxing.mobile";

    private RefreshBridge() {
    }

    /** Full refresh: courses, work, exams and the current timetable week. */
    public static void requestFull(Context context) {
        send(context, 0);
    }

    /** Timetable-only refresh for a specific teaching week (1-based). */
    public static void requestWeek(Context context, int week) {
        send(context, week);
    }

    private static void send(Context context, int week) {
        try {
            Intent intent = new Intent(DeadlineReceiver.ACTION_REQUEST_REFRESH);
            // setPackage keeps the exported receiver reachable only from this app
            // pair; without it any app could trigger a refresh round.
            intent.setPackage(TARGET_PACKAGE);
            if (week > 0) {
                intent.putExtra(DeadlineReceiver.EXTRA_WEEK, week);
            }
            intent.putExtra(DeadlineReceiver.EXTRA_REASON, "moduleRequest");
            BridgeAuth.attach(context, intent);
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }
}
