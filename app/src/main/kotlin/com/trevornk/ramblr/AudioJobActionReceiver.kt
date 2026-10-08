package com.trevornk.ramblr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * #285: the notification buttons ("Copy" on a finished transcription, "Cancel" on the ongoing
 * one). Not exported: only this app's own PendingIntents reach it. The copy reads the text from
 * History by timestamp at tap time, so the notification itself never carries transcript text.
 */
class AudioJobActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AudioJobNotifications.ACTION_COPY -> {
                val ts = intent.getLongExtra(AudioJobNotifications.EXTRA_HISTORY_TS, -1L)
                val entry = DictationHistoryStore.forContext(context).all().firstOrNull { it.timestamp == ts }
                if (entry == null) {
                    Toast.makeText(context, R.string.audio_files_not_found, Toast.LENGTH_SHORT).show()
                } else {
                    ClipboardUtil.copy(context, entry.cleanedText ?: entry.rawText)
                    Toast.makeText(context, R.string.audio_files_copied, Toast.LENGTH_SHORT).show()
                }
            }
            AudioJobNotifications.ACTION_CANCEL -> {
                intent.getStringExtra(AudioJobNotifications.EXTRA_JOB_ID)?.let { AudioJobs.cancel(context, it) }
            }
        }
    }
}
