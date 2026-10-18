package com.trevornk.ramblr

import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The keyboard window reaches behind the navigation bar, so the panel pads itself above it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RamblrImeInsetsTest {

    private fun navBar(bottom: Int) = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottom))
        .build()

    @Test fun `bottom padding grows by the navigation bar height and does not stack`() {
        val service = Robolectric.buildService(RamblrImeService::class.java).create().get()
        val root = service.onCreateInputView()
        val base = root.paddingBottom

        ViewCompat.dispatchApplyWindowInsets(root, navBar(48))
        assertEquals(base + 48, root.paddingBottom)

        // A later dispatch (rotation, nav mode change) replaces the inset instead of adding to it.
        ViewCompat.dispatchApplyWindowInsets(root, navBar(20))
        assertEquals(base + 20, root.paddingBottom)
    }
}
