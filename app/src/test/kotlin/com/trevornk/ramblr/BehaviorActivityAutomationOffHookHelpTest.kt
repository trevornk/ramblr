package com.trevornk.ramblr

import android.app.AlertDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/**
 * UI-level check for [BehaviorActivity.showAutomationOffHookHelp]'s copy chooser: pins that each
 * of the three entries puts the SAME string on the clipboard that [automationOffHookCommand],
 * [automationDiagnosticCommand], and [automationOffHookEnableCommand] independently generate for
 * the activity's own uid/component, so a future edit to either the dialog or the generators can't
 * silently drift the two apart.
 *
 * Asserts on the chooser's item INDEXES rather than its labels, because the index is what
 * [android.app.AlertDialog.Builder.setItems]'s click handler actually switches on -- a reordering
 * that silently copied the wrong command is precisely the regression worth catching, and label
 * text would keep passing through it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BehaviorActivityAutomationOffHookHelpTest {

    @Test
    fun `each chooser entry places the exact generated command on the clipboard`() {
        val appContext = org.robolectric.RuntimeEnvironment.getApplication()
        if (!androidx.work.WorkManager.isInitialized()) {
            androidx.work.WorkManager.initialize(appContext, androidx.work.Configuration.Builder().build())
        }

        val controller = Robolectric.buildActivity(BehaviorActivity::class.java).setup()
        val activity = controller.get()
        try {
            AutomationOffHookToggle.setEnabled(activity, false)

            // Invoke the private dialog-builder directly: the switch it's normally wired behind
            // has no fixed id in this hand-built screen, and the dialog's own AlertDialog.Builder
            // call is the thing under test, not the row's click plumbing.
            val method = BehaviorActivity::class.java.getDeclaredMethod("showAutomationOffHookHelp")
            method.isAccessible = true
            method.invoke(activity)

            val helpDialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue("expected the help AlertDialog to be shown", helpDialog != null)

            // The help dialog itself only opens the chooser now -- three commands don't fit in
            // AlertDialog's three button slots once Close takes one.
            (helpDialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

            val chooser = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue("expected the copy chooser to replace the help dialog", chooser !== helpDialog)

            val hostingUserId = userIdForUid(android.os.Process.myUid())
            val expected = listOf(
                automationOffHookCommand(activity.packageName, hostingUserId),
                automationDiagnosticCommand(activity.packageName, hostingUserId),
                automationOffHookEnableCommand(
                    InvocationSecureSettings.serviceComponent(activity),
                    hostingUserId,
                ),
            )

            val clipboard = activity.getSystemService(android.content.ClipboardManager::class.java)
            val shadowChooser = org.robolectric.Shadows.shadowOf(chooser as AlertDialog)
            assertEquals("chooser should offer exactly three commands", 3, shadowChooser.items.size)

            expected.forEachIndexed { index, command ->
                // Re-open the chooser for each entry: performItemClick dismisses it.
                if (index > 0) {
                    method.invoke(activity)
                    (ShadowAlertDialog.getLatestAlertDialog() as AlertDialog)
                        .getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                }
                val open = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
                open.listView.performItemClick(null, index, index.toLong())
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                assertEquals(
                    "chooser entry $index copied the wrong command",
                    command,
                    clipboard.primaryClip?.getItemAt(0)?.text.toString(),
                )
            }
        } finally {
            controller.destroy()
        }
    }
}
