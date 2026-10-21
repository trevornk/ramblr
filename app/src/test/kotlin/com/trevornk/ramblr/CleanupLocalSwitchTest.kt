package com.trevornk.ramblr

import android.app.Application
import android.content.Intent
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.UUID

/**
 * #345: choosing Local before the cleanup model is installed parks the choice as pending and tells
 * the user the switch finishes after the download. A successful download used to select the model
 * but never finish the switch, so cleanup kept going to the cloud step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CleanupLocalSwitchTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val model = LOCAL_CLEANUP_MODEL_CATALOG.first()

    @Before fun setUp() {
        if (!androidx.work.WorkManager.isInitialized()) {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        // Start from a Cloud cleanup user with the model not installed.
        CloudFeatureToggle.setCleanupEnabled(app, true)
        ModelDownloader.modelDir(app, model).deleteRecursively()
    }

    private fun launch(): CleanupActivity =
        Robolectric.buildActivity(CleanupActivity::class.java, Intent(app, CleanupActivity::class.java))
            .setup().get()

    private fun installModel() {
        val dir = ModelDownloader.modelDir(app, model)
        dir.mkdirs()
        File(dir, ".complete").writeText("ok")
        assertTrue(ModelDownloader.isInstalled(app, model))
    }

    private fun info(id: UUID, state: WorkInfo.State) = WorkInfo(id, state, emptySet())

    /** RUNNING then SUCCEEDED for the same work id, as WorkManager delivers a real download. */
    private fun CleanupActivity.completeDownload() {
        val id = UUID.randomUUID()
        onCleanupModelWorkInfos(model, listOf(info(id, WorkInfo.State.RUNNING)))
        installModel()
        onCleanupModelWorkInfos(model, listOf(info(id, WorkInfo.State.SUCCEEDED)))
    }

    @Test fun `Local chosen before the model exists finishes switching when the download succeeds`() {
        val activity = launch()
        activity.onSelectSimpleCleanup(SimpleCleanupChoice.LOCAL)
        assertTrue(activity.pendingLocalCleanupSelection)
        assertTrue("still cloud until the model exists", CloudFeatureToggle.cleanupEnabled(app))

        activity.completeDownload()

        assertFalse(activity.pendingLocalCleanupSelection)
        assertFalse("cleanup must now route to the on-device model", CloudFeatureToggle.cleanupEnabled(app))
        val local = ProviderChainStore.load(app).entries.single { it.kind == ProviderKind.LOCAL }
        assertEquals(model.archive, local.model)
        assertEquals("${model.name} ready — now used for cleanup", ShadowToast.getTextOfLatestToast())
    }

    @Test fun `download with no Local choice pending only selects the model and keeps the tap-Local toast`() {
        val activity = launch()

        activity.completeDownload()

        assertTrue("must not switch on its own", CloudFeatureToggle.cleanupEnabled(app))
        assertEquals(
            "${model.name} ready — tap Local above to use it for cleanup",
            ShadowToast.getTextOfLatestToast(),
        )
        assertEquals(model.archive, app.getSharedPreferences("ramblr", 0).getString("local_cleanup_model_name", null))
    }

    @Test fun `tapping Cloud after Local cancels the pending switch so a later download does not flip it`() {
        val activity = launch()
        activity.onSelectSimpleCleanup(SimpleCleanupChoice.LOCAL)
        activity.onSelectSimpleCleanup(SimpleCleanupChoice.CLOUD)
        assertFalse(activity.pendingLocalCleanupSelection)

        activity.completeDownload()

        assertTrue(CloudFeatureToggle.cleanupEnabled(app))
        assertEquals(
            "${model.name} ready — tap Local above to use it for cleanup",
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test fun `a SUCCEEDED state never seen in flight (stale replay) does not finish the switch`() {
        val activity = launch()
        activity.onSelectSimpleCleanup(SimpleCleanupChoice.LOCAL)
        installModel()

        activity.onCleanupModelWorkInfos(model, listOf(info(UUID.randomUUID(), WorkInfo.State.SUCCEEDED)))

        assertTrue(activity.pendingLocalCleanupSelection)
        assertTrue(CloudFeatureToggle.cleanupEnabled(app))
    }
}
