package com.trevornk.ramblr

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** #285: the notification's Copy button reads History at tap time; the notification never carries text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioJobActionReceiverTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val clip get() = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip

    @Before fun setUp() {
        DictationHistoryStore.forContext(ctx).clear()
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).clearPrimaryClip()
    }

    private fun copyIntent(ts: Long) = Intent(AudioJobNotifications.ACTION_COPY).putExtra(AudioJobNotifications.EXTRA_HISTORY_TS, ts)

    @Test fun `copy puts the cleaned text on the clipboard`() {
        DictationHistoryStore.forContext(ctx).add(DictationHistoryEntry(42L, "raw words", "Cleaned words."))
        AudioJobActionReceiver().onReceive(ctx, copyIntent(42L))
        assertEquals("Cleaned words.", clip!!.getItemAt(0).text.toString())
    }

    @Test fun `copy falls back to the raw text when there is no cleaned version`() {
        DictationHistoryStore.forContext(ctx).add(DictationHistoryEntry(7L, "only raw", null))
        AudioJobActionReceiver().onReceive(ctx, copyIntent(7L))
        assertEquals("only raw", clip!!.getItemAt(0).text.toString())
    }

    @Test fun `copy for an entry that was deleted from history copies nothing`() {
        AudioJobActionReceiver().onReceive(ctx, copyIntent(999L))
        assertNull(clip)
    }

    @Test fun `result notification carries no transcript text and has a copy action`() {
        val job = AudioJob("id1", "n", JobKind.IMPORTED, JobStatus.DONE, 1, historyTimestamp = 5L)
        DictationHistoryStore.forContext(ctx).add(DictationHistoryEntry(5L, "SECRET WORDS", null))
        val n = AudioJobNotifications.result(ctx, job, AudioNoticeKind.DONE)
        val extras = n.extras
        val shown = listOf(extras.getCharSequence("android.title"), extras.getCharSequence("android.text"), extras.getCharSequence("android.bigText"))
            .joinToString(" ")
        org.junit.Assert.assertFalse(shown.contains("SECRET"))
        assertEquals(listOf("Copy", "Open History"), n.actions.map { it.title.toString() })
    }

    @Test fun `failed notification offers no copy`() {
        val job = AudioJob("id2", "n", JobKind.IMPORTED, JobStatus.FAILED, 1, failure = JobFailure.NO_SPEECH)
        val n = AudioJobNotifications.result(ctx, job, AudioNoticeKind.FAILED)
        assertNull(n.actions)
    }
}
