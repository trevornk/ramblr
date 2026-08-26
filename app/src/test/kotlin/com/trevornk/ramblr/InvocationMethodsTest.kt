package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The #156 invocation-chooser + guard-rail logic, pinned at the pure layer
 * ([InvocationMethods.kt]) because none of it can be exercised on the JVM through the Activity /
 * ContentResolver code that calls it -- the same reasoning as [SettingsSubtitlesTest].
 *
 * The component-list helpers get the heaviest coverage: with WRITE_SECURE_SETTINGS granted they
 * read-modify-write the OS's real `Settings.Secure` accessibility lists, where corrupting
 * ANOTHER app's entry (e.g. Tasker's) would break that app's accessibility setup with nothing
 * pointing back at Ramblr as the cause.
 */
class InvocationMethodsTest {

    private val ramblr = "com.trevornk.ramblr/com.trevornk.ramblr.WhisperAccessibilityService"
    private val ramblrShort = "com.trevornk.ramblr/.WhisperAccessibilityService"
    private val tasker = "net.dinglisch.android.taskerm/net.dinglisch.android.taskerm.MyAccessibilityService"
    private val other = "com.example.other/.OtherService"

    // --- normalizeComponent / componentEquals -----------------------------------------------

    @Test fun `short flatten form expands to full form`() {
        assertEquals(ramblr, normalizeComponent(ramblrShort))
    }

    @Test fun `full flatten form is unchanged`() {
        assertEquals(ramblr, normalizeComponent(ramblr))
    }

    @Test fun `non component-shaped strings pass through unchanged`() {
        // The helpers must tolerate junk in the settings value without corrupting it.
        assertEquals("garbage", normalizeComponent("garbage"))
        assertEquals("/leading", normalizeComponent("/leading"))
        assertEquals("trailing/", normalizeComponent("trailing/"))
        assertEquals("", normalizeComponent(""))
    }

    @Test fun `componentEquals matches across flatten forms in both directions`() {
        assertTrue(componentEquals(ramblr, ramblrShort))
        assertTrue(componentEquals(ramblrShort, ramblr))
        assertFalse(componentEquals(ramblr, tasker))
    }

    // --- componentListEntries / componentListContains ---------------------------------------

    @Test fun `null and empty lists have no entries`() {
        assertEquals(emptyList<String>(), componentListEntries(null))
        assertEquals(emptyList<String>(), componentListEntries(""))
        assertFalse(componentListContains(null, ramblr))
        assertFalse(componentListContains("", ramblr))
    }

    @Test fun `doubled and stray separators are dropped, not turned into empty entries`() {
        assertEquals(listOf(tasker, ramblr), componentListEntries("$tasker::$ramblr:"))
    }

    @Test fun `contains matches the short form against a full-form list and vice versa`() {
        assertTrue(componentListContains(ramblr, ramblrShort))
        assertTrue(componentListContains(ramblrShort, ramblr))
        assertTrue(componentListContains("$tasker:$ramblrShort", ramblr))
        assertFalse(componentListContains(tasker, ramblr))
    }

    // --- componentListAdd -------------------------------------------------------------------

    @Test fun `add to empty or null list yields just the component`() {
        assertEquals(ramblr, componentListAdd(null, ramblr))
        assertEquals(ramblr, componentListAdd("", ramblr))
    }

    @Test fun `add preserves other apps' entries verbatim and appends last`() {
        assertEquals("$tasker:$other:$ramblr", componentListAdd("$tasker:$other", ramblr))
    }

    @Test fun `add is a no-op when already present`() {
        assertEquals("$tasker:$ramblr", componentListAdd("$tasker:$ramblr", ramblr))
    }

    @Test fun `add is a no-op when present in the other flatten form`() {
        // The OS may have written the short form; adding the full form must not duplicate it.
        assertEquals("$tasker:$ramblrShort", componentListAdd("$tasker:$ramblrShort", ramblr))
    }

    // --- componentListRemove ----------------------------------------------------------------

    @Test fun `remove from null or empty list yields empty string`() {
        assertEquals("", componentListRemove(null, ramblr))
        assertEquals("", componentListRemove("", ramblr))
    }

    @Test fun `removing the only entry yields empty string`() {
        // The OS persists "" (not null) for an emptied target list; "" is what gets written back.
        assertEquals("", componentListRemove(ramblr, ramblr))
    }

    @Test fun `remove preserves other apps' entries verbatim, in order`() {
        assertEquals("$tasker:$other", componentListRemove("$tasker:$ramblr:$other", ramblr))
    }

    @Test fun `remove matches the other flatten form`() {
        assertEquals(tasker, componentListRemove("$tasker:$ramblrShort", ramblr))
        assertEquals(tasker, componentListRemove("$tasker:$ramblr", ramblrShort))
    }

    @Test fun `remove drops duplicates of the same component in mixed forms`() {
        assertEquals(tasker, componentListRemove("$ramblr:$tasker:$ramblrShort", ramblr))
    }

    @Test fun `remove of an absent component leaves the list unchanged`() {
        assertEquals("$tasker:$other", componentListRemove("$tasker:$other", ramblr))
    }

    @Test fun `add then remove round-trips back to the original entries`() {
        val original = "$tasker:$other"
        assertEquals(original, componentListRemove(componentListAdd(original, ramblr), ramblr))
    }

    // --- shouldShowServiceKilledBanner ------------------------------------------------------

    @Test fun `banner fires on the exact invisible-toggle kill state`() {
        assertTrue(shouldShowServiceKilledBanner(
            serviceWasEnabled = true,
            serviceEnabledNow = false,
            anyShortcutBound = false,
            bannerDismissed = false,
        ))
    }

    @Test fun `fresh install that never enabled the service shows nothing`() {
        assertFalse(shouldShowServiceKilledBanner(
            serviceWasEnabled = false,
            serviceEnabledNow = false,
            anyShortcutBound = false,
            bannerDismissed = false,
        ))
    }

    @Test fun `service currently enabled shows nothing`() {
        assertFalse(shouldShowServiceKilledBanner(
            serviceWasEnabled = true,
            serviceEnabledNow = true,
            anyShortcutBound = false,
            bannerDismissed = false,
        ))
    }

    @Test fun `service off but a shortcut still bound is not the invisible-toggle kill`() {
        // A remaining binding means the OS did NOT run the "last shortcut removed" sync -- the
        // service is off for some other reason (and the bound shortcut can self-heal it: the
        // volume-keys path re-enables a button-flag service on use). Don't misdiagnose.
        assertFalse(shouldShowServiceKilledBanner(
            serviceWasEnabled = true,
            serviceEnabledNow = false,
            anyShortcutBound = true,
            bannerDismissed = false,
        ))
    }

    @Test fun `dismissed banner stays quiet until re-armed`() {
        assertFalse(shouldShowServiceKilledBanner(
            serviceWasEnabled = true,
            serviceEnabledNow = false,
            anyShortcutBound = false,
            bannerDismissed = true,
        ))
    }

    // --- subtitle formatters ----------------------------------------------------------------

    @Test fun `main row subtitle lists active methods`() {
        assertEquals(
            "How to start dictation — Floating ring, Volume keys on",
            invocationMainRowSubtitleText(ringVisible = true, systemButtonBound = false, volumeKeysBound = true),
        )
    }

    @Test fun `main row subtitle with nothing active says so`() {
        assertEquals(
            "How to start dictation — no method currently active",
            invocationMainRowSubtitleText(ringVisible = false, systemButtonBound = false, volumeKeysBound = false),
        )
    }

    @Test fun `ring subtitle reflects visibility`() {
        assertEquals("On — tap the ring to start and stop dictation", invocationRingSubtitleText(visible = true))
        assertEquals("Off — the ring is hidden", invocationRingSubtitleText(visible = false))
    }

    @Test fun `system button subtitle distinguishes deep-link and direct-control tiers`() {
        assertEquals(
            "On — the system button/gesture starts dictation · tap to open system settings",
            invocationSystemButtonSubtitleText(bound = true, directControl = false),
        )
        assertEquals(
            "Off · tap to switch in-app",
            invocationSystemButtonSubtitleText(bound = false, directControl = true),
        )
    }

    @Test fun `volume keys subtitle distinguishes deep-link and direct-control tiers`() {
        assertEquals(
            "On — hold both volume keys to start dictation · tap to switch in-app",
            invocationVolumeKeysSubtitleText(bound = true, directControl = true),
        )
        assertEquals(
            "Off · tap to open system settings",
            invocationVolumeKeysSubtitleText(bound = false, directControl = false),
        )
    }

    @Test fun `qs tile subtitle offers one-tap add only where the API exists`() {
        assertTrue(invocationQsTileSubtitleText(canRequestAdd = true).contains("tap to add the tile"))
        assertTrue(invocationQsTileSubtitleText(canRequestAdd = false).contains("tap for setup steps"))
    }

    @Test fun `advanced tier subtitle reflects the feature-detected grant`() {
        assertTrue(invocationAdvancedTierSubtitleText(granted = true).startsWith("Active"))
        assertTrue(invocationAdvancedTierSubtitleText(granted = false).contains("adb"))
    }

    @Test fun `adb command names the exact package and permission`() {
        assertEquals(
            "adb shell pm grant com.trevornk.ramblr android.permission.WRITE_SECURE_SETTINGS",
            wssAdbCommand("com.trevornk.ramblr"),
        )
    }
}
