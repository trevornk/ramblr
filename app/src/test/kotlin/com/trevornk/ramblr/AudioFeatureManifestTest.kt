package com.trevornk.ramblr

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** #285: the manifest facts that the platform enforces at runtime on Android 14+. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioFeatureManifestTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val pm get() = ctx.packageManager

    @Suppress("DEPRECATION")
    private fun service(cls: Class<*>) = pm.getServiceInfo(ComponentName(ctx, cls), PackageManager.GET_META_DATA)

    @Test fun `transcription worker is a non exported dataSync service`() {
        val s = service(AudioTranscriptionService::class.java)
        assertFalse(s.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, s.foregroundServiceType)
    }

    @Test fun `recorder is a non exported microphone service`() {
        val s = service(AudioRecorderService::class.java)
        assertFalse(s.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, s.foregroundServiceType)
    }

    @Test fun `typed foreground permissions are declared`() {
        @Suppress("DEPRECATION")
        val requested = pm.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions!!.toSet()
        assertTrue("android.permission.FOREGROUND_SERVICE_DATA_SYNC" in requested)
        assertTrue("android.permission.FOREGROUND_SERVICE_MICROPHONE" in requested)
        assertTrue("android.permission.FOREGROUND_SERVICE" in requested)
    }

    @Test fun `no storage or media read permissions were added`() {
        @Suppress("DEPRECATION")
        val requested = pm.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions!!.toSet()
        val banned = listOf("READ_MEDIA_AUDIO", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE", "READ_MEDIA_VIDEO", "READ_MEDIA_IMAGES")
        banned.forEach { b -> assertFalse(b, requested.any { it.endsWith(b) }) }
    }

    @Test fun `share target resolves audio send and is the only new exported surface`() {
        val send = Intent(Intent.ACTION_SEND).setType("audio/mp4").setPackage(ctx.packageName)
        val hits = pm.queryIntentActivities(send, 0).map { it.activityInfo.name }
        assertEquals(listOf(AudioShareActivity::class.java.name), hits)
        assertTrue(pm.getActivityInfo(ComponentName(ctx, AudioShareActivity::class.java), 0).exported)
        assertFalse(pm.getActivityInfo(ComponentName(ctx, AudioFilesActivity::class.java), 0).exported)
        val r = pm.getReceiverInfo(ComponentName(ctx, AudioJobActionReceiver::class.java), 0)
        assertFalse(r.exported)
    }

    @Test fun `share target does not claim non audio shares`() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(ctx.packageName)
        assertTrue(pm.queryIntentActivities(send, 0).none { it.activityInfo.name == AudioShareActivity::class.java.name })
    }
}
