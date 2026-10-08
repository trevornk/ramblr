package com.trevornk.ramblr

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioShareUrisTest {
    private val pkg = "com.trevornk.ramblr"

    @Test fun `accepts a foreign content uri`() = assertTrue(AudioShareUris.acceptable(Uri.parse("content://media/external/audio/media/1"), pkg))

    @Test fun `rejects file and http schemes`() {
        assertFalse(AudioShareUris.acceptable(Uri.parse("file:///sdcard/a.m4a"), pkg))
        assertFalse(AudioShareUris.acceptable(Uri.parse("https://example.com/a.m4a"), pkg))
    }

    @Test fun `rejects this app's own providers so a sender cannot aim the importer at private files`() {
        assertFalse(AudioShareUris.acceptable(Uri.parse("content://$pkg.fileprovider/benchmark_log/dictation_history.jsonl"), pkg))
        assertFalse(AudioShareUris.acceptable(Uri.parse("content://$pkg/x"), pkg))
    }

    @Test fun `single send`() {
        val u = Uri.parse("content://x/1")
        val i = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, u)
        assertEquals(listOf(u), AudioShareUris.fromIntent(i))
    }

    @Test fun `multiple send and clipdata are merged without duplicates`() {
        val a = Uri.parse("content://x/1"); val b = Uri.parse("content://x/2")
        val i = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b))
        i.clipData = ClipData.newRawUri("x", a)
        assertEquals(listOf(a, b), AudioShareUris.fromIntent(i))
    }

    @Test fun `nothing shared`() = assertTrue(AudioShareUris.fromIntent(Intent(Intent.ACTION_SEND)).isEmpty())
}
