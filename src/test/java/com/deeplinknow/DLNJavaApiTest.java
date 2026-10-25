package com.deeplinknow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Intent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;

/**
 * SDK-86..91 / AND-35..40 from Java (1.4.0): the new statics compile and run without
 * DLN.Companion, with Java lambdas, and the 1.3.0 calls still compile next to them.
 * Requirement coverage is counted from DLNLinkTest.kt ("Java overloads compile").
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE)
public class DLNJavaApiTest {

    @Before
    public void setUp() {
        DLNTestSupport.INSTANCE.resetStatics();
    }

    @After
    public void tearDown() {
        DLNTestSupport.INSTANCE.resetStatics();
    }

    @Test
    public void newApiCompilesAndRunsFromJava() {
        DLNLinkSubscription plain = DLN.onLink(link -> {
            String path = link.getPath();
            DLNLinkType type = link.getType();
            DLNLinkSource source = link.getSource();
        });
        DLNLinkOptions defaults = new DLNLinkOptions();
        DLNLinkOptions held = new DLNLinkOptions(null, false, true);
        DLNLinkOptions all = new DLNLinkOptions(50.0, false, true, true, Collections.singleton("myapp"));
        DLNLinkSubscription withOptions = DLN.onLink(held, new DLNLinkHandler() {
            @Override
            public void onLink(DLNLink link) {
                // no-op
            }
        });
        assertNotNull(plain);
        assertNotNull(withOptions);
        assertEquals(true, all.getHold());
        assertFalse(defaults.getResolve());

        assertFalse(DLN.handleIntent(null));
        assertFalse(DLN.handleIntent(new Intent(Intent.ACTION_MAIN)));
        assertNull(DLN.getPendingLink());
        java.util.List<DLNLink> heldLinks = DLN.getPendingLinks();
        assertEquals(0, heldLinks.size());
        assertFalse(DLN.releasePendingLink());
        DLN.clearPendingLink();
        DLN.resolveLink("https://myapp.deeplinknow.com/x", link -> { });
        withOptions.remove();
        plain.remove();

        assertEquals("appLink", DLNLinkSource.APP_LINK.getValue());
        assertEquals("direct", DLNLinkType.DIRECT.getValue());
        assertEquals(DLNLinkSource.REFERRER, DLNLinkSource.from(DeferredDeepLinkSource.REFERRER));
    }

    @Test
    public void oldApiStillCompilesFromJava() {
        DLN.setDeepLinkCallback(data -> { });
        assertFalse(DLN.handleDeepLink("https://myapp.deeplinknow.com/x"));
        DLN.checkDeferredDeepLink(null, false, (DeferredDeepLinkCallback) (link, error) -> { });
        DLN.resetDeferredDeepLink();
        assertFalse(DLN.isInitialized());
        assertNull(DLN.getLastError());
    }
}
