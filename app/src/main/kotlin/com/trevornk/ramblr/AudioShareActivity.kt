package com.trevornk.ramblr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * #285: share target for audio ("Share -> Ramblr" from a voice recorder, a file manager, a chat
 * app). Queues each file and finishes immediately: the user stays in the app they shared from, and
 * gets a notification when the text is ready.
 *
 * Exported because the share sheet must reach it; the only input it trusts is the list of
 * `content://` URIs, filtered by [AudioShareUris.acceptable] (no `file://`, never this app's own
 * provider). It reads nothing itself: the worker opens each URI with the grant the sharer gave.
 * Needs no storage permission.
 */
class AudioShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = AudioShareUris.fromIntent(intent).filter { AudioShareUris.acceptable(it, packageName) }
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.audio_files_share_none, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val added = AudioJobs.enqueueImported(this, uris, getString(R.string.audio_files_shared_name))
        Toast.makeText(this, R.string.audio_files_added, Toast.LENGTH_SHORT).show()
        if (uris.size > added) {
            Toast.makeText(this, getString(R.string.audio_files_share_too_many, AudioJobs.MAX_FILES_PER_SHARE), Toast.LENGTH_LONG).show()
        }
        // Without the notification grant the user would never learn the job finished: open the
        // Audio files screen, which asks for it, instead of leaving them guessing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            startActivity(Intent(this, AudioFilesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}

/** Pure URI handling for the share target. */
internal object AudioShareUris {
    fun fromIntent(intent: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> intent.streamExtra()?.let { out += it }
            Intent.ACTION_SEND_MULTIPLE -> out += intent.streamListExtra()
        }
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out += it }
        }
        return out.toList()
    }

    /** Only `content://`, and never this app's own providers (a hostile sharer must not be able to
     *  point the importer at Ramblr's private files). */
    fun acceptable(uri: Uri, ownPackage: String): Boolean {
        if (uri.scheme != "content") return false
        val authority = uri.authority ?: return false
        return !authority.equals(ownPackage, ignoreCase = true) && !authority.startsWith("$ownPackage.", ignoreCase = true)
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamExtra(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    @Suppress("DEPRECATION")
    private fun Intent.streamListExtra(): List<Uri> =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()
}
