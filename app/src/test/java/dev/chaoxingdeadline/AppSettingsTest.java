package dev.chaoxingdeadline;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Per-type advance hours: fallback, independent overrides, and the offsets they feed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AppSettingsTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        AppSettings.prefs(context).edit().clear().apply();
    }

    @After
    public void tearDown() {
        AppSettings.prefs(context).edit().clear().apply();
    }

    @Test
    public void typeHoursFallBackToShared() {
        AppSettings.prefs(context).edit().putInt("notify_hours", 24).apply();
        assertEquals(24, AppSettings.notifyHoursForType(context, "作业"));
        assertEquals(24, AppSettings.notifyHoursForType(context, "考试"));
        assertEquals("unknown types use the shared value too",
                24, AppSettings.notifyHoursForType(context, "不存在"));
    }

    @Test
    public void typeHoursOverrideIndependently() {
        AppSettings.prefs(context).edit().putInt("notify_hours", 24).apply();
        AppSettings.setNotifyHoursForType(context, "作业", 72);
        AppSettings.setNotifyHoursForType(context, "考试", 3);
        assertEquals(72, AppSettings.notifyHoursForType(context, "作业"));
        assertEquals(3, AppSettings.notifyHoursForType(context, "考试"));
        assertEquals("章节未设置，仍跟随默认", 24, AppSettings.notifyHoursForType(context, "章节"));
        assertEquals("the shared default is untouched", 24, AppSettings.notifyHours(context));
    }

    @Test
    public void hoursAreClampedToOneTo168() {
        AppSettings.setNotifyHoursForType(context, "作业", 0);
        assertEquals(1, AppSettings.notifyHoursForType(context, "作业"));
        AppSettings.setNotifyHoursForType(context, "作业", 9999);
        assertEquals(168, AppSettings.notifyHoursForType(context, "作业"));
    }

    @Test
    public void offsetsUseTypeHours() {
        AppSettings.prefs(context).edit().putInt("notify_hours", 24).apply();
        AppSettings.setNotifyHoursForType(context, "作业", 72);
        long[] homework = AppSettings.notifyOffsetsMillis(context, "作业");
        assertTrue("72h is the main offset for 作业",
                contains(homework, TimeUnit.HOURS.toMillis(72)));
        assertFalse("24h no longer appears for 作业",
                contains(homework, TimeUnit.HOURS.toMillis(24)));
        long[] exam = AppSettings.notifyOffsetsMillis(context, "考试");
        assertTrue("考试 falls back to the shared 24h",
                contains(exam, TimeUnit.HOURS.toMillis(24)));
        long[] shared = AppSettings.notifyOffsetsMillis(context);
        assertTrue("the no-arg overload uses the shared 24h",
                contains(shared, TimeUnit.HOURS.toMillis(24)));
    }

    @Test
    public void classroomActivitiesHaveNoAdvanceOffsets() {
        AppSettings.prefs(context).edit().putInt("notify_hours", 24).apply();
        long[] activity = AppSettings.notifyOffsetsMillis(context, "课堂活动");
        assertEquals("only the due-moment reminder, no 24h/3h/30min advance",
                1, activity.length);
        assertEquals(0L, activity[0]);
        // with the due switch off the activity gets no timed reminder at all
        AppSettings.prefs(context).edit().putBoolean("notify_at_due", false).apply();
        assertEquals(0, AppSettings.notifyOffsetsMillis(context, "课堂活动").length);
        // homework keeps its full advance ladder
        assertTrue(contains(AppSettings.notifyOffsetsMillis(context, "作业"),
                TimeUnit.HOURS.toMillis(24)));
    }

    private static boolean contains(long[] array, long value) {
        if (array == null) {
            return false;
        }
        for (long candidate : array) {
            if (candidate == value) {
                return true;
            }
        }
        return false;
    }
}
