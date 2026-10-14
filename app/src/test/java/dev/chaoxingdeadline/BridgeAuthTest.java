package dev.chaoxingdeadline;

import android.content.Context;
import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The bridge token guards the exported receiver against forged cross-process data.
 * These tests pin the attach/validate round-trip and the idempotence of ensureToken —
 * the race here is what used to break the notification-shade buttons.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BridgeAuthTest {

    @Test
    public void attachProducesAValidToken() {
        Context context = RuntimeEnvironment.getApplication();
        Intent intent = new Intent(context, DeadlineReceiver.class).setAction("dev.chaoxingdeadline.TEST");
        BridgeAuth.attach(context, intent);
        assertTrue("a broadcast the module just attached a token to must validate",
                BridgeAuth.isValid(context, intent));
    }

    @Test
    public void tokenIsStableAcrossCalls() {
        Context context = RuntimeEnvironment.getApplication();
        String first = BridgeAuth.ensureToken(context);
        String second = BridgeAuth.ensureToken(context);
        assertEquals("ensureToken must be idempotent, not mint a new token per call",
                first, second);
        assertTrue("token is at least 32 chars", first.length() >= 32);
    }

    @Test
    public void broadcastWithoutTokenIsRejected() {
        Context context = RuntimeEnvironment.getApplication();
        Intent intent = new Intent("dev.chaoxingdeadline.FORGED");
        assertFalse(BridgeAuth.isValid(context, intent));
    }

    @Test
    public void wrongTokenIsRejected() {
        Context context = RuntimeEnvironment.getApplication();
        Intent intent = new Intent(context, DeadlineReceiver.class).setAction("dev.chaoxingdeadline.TEST");
        intent.putExtra(BridgeAuth.EXTRA_TOKEN, "not-the-real-token");
        assertFalse(BridgeAuth.isValid(context, intent));
    }
}
