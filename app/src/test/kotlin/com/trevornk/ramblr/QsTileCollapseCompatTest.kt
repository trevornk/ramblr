package com.trevornk.ramblr

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the `startActivityAndCollapse` overload split. Both overloads throw outside their own
 * API window, and `minSdk 30` straddles the boundary, so an off-by-one here is a hard crash on
 * real devices rather than a degraded affordance. See [shouldUsePendingIntentCollapse].
 */
class QsTileCollapseCompatTest {

    @Test fun `API 34 takes the PendingIntent overload -- the version that added it`() {
        assertTrue(shouldUsePendingIntentCollapse(Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
    }

    @Test fun `API 33 takes the Intent overload -- PendingIntent does not exist yet`() {
        assertFalse(shouldUsePendingIntentCollapse(Build.VERSION_CODES.TIRAMISU))
    }

    /**
     * The regression #136 actually shipped: minSdk is 30, and the unguarded PendingIntent call
     * threw NoSuchMethodError on every device in the 30..33 band.
     */
    @Test fun `every API level from minSdk 30 up to 33 uses the Intent overload`() {
        for (sdk in 30..33) {
            assertFalse("API $sdk must not call the API-34-only PendingIntent overload", shouldUsePendingIntentCollapse(sdk))
        }
    }

    @Test fun `API 34 and above always use the PendingIntent overload`() {
        for (sdk in 34..36) {
            assertTrue("API $sdk must not call the Intent overload, which throws from 34", shouldUsePendingIntentCollapse(sdk))
        }
    }
}
