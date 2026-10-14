package dev.chaoxingdeadline;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Coverage for the 超级岛 picker: the permanent notification must show the nearest
 * still-live deadline and never a submitted / revoked / past-due row.
 */
public class DeadlineNotifierLiveTest {

    private static DeadlineItem item(String title, long dueAt) {
        DeadlineItem item = new DeadlineItem();
        item.type = "作业";
        item.title = title;
        item.course = "高等数学";
        item.dueAt = dueAt;
        item.setSubmissionState(DeadlineItem.SUBMISSION_UNSUBMITTED);
        return item;
    }

    private static long inHours(long hours) {
        return System.currentTimeMillis() + TimeUnit.HOURS.toMillis(hours);
    }

    @Test
    public void picksTheEarliestUpcomingItem() {
        List<DeadlineItem> items = Arrays.asList(
                item("远期的作业", inHours(200)),
                item("最近的考试", inHours(5)),
                item("中期的章节", inHours(50)));
        DeadlineItem best = DeadlineNotifier.pickLiveItem(items);
        assertNotNull(best);
        assertEquals("最近的考试", best.title);
    }

    @Test
    public void skipsSubmittedRevokedAndPastDue() {
        DeadlineItem submitted = item("已交的作业", inHours(1));
        submitted.submitted = true;
        submitted.setSubmissionState(DeadlineItem.SUBMISSION_SUBMITTED);
        DeadlineItem revoked = item("已撤销的作业", inHours(2));
        revoked.vanishedAt = System.currentTimeMillis();
        DeadlineItem past = item("已过期的作业", System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1));
        DeadlineItem live = item("还有效的作业", inHours(30));
        DeadlineItem best = DeadlineNotifier.pickLiveItem(Arrays.asList(submitted, revoked, past, live));
        assertNotNull(best);
        assertEquals("还有效的作业", best.title);
    }

    @Test
    public void returnsNullWhenNothingUpcoming() {
        assertNull(DeadlineNotifier.pickLiveItem(Collections.<DeadlineItem>emptyList()));
        assertNull(DeadlineNotifier.pickLiveItem(null));
        DeadlineItem past = item("已过期", System.currentTimeMillis() - 1_000L);
        assertNull(DeadlineNotifier.pickLiveItem(Collections.singletonList(past)));
    }
}
