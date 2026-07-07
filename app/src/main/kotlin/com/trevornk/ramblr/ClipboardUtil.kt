package com.trevornk.ramblr

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * Copies [text] to the clipboard flagged IS_SENSITIVE (Android 13+) so clipboard managers and
 * clipboard history don't retain dictated content (see #11). Shared by the injection path in
 * [WhisperAccessibilityService] and the tap-to-copy history list in [MainActivity].
 */
object ClipboardUtil {
    fun copy(context: Context, text: String) {
        val clip = ClipData.newPlainText("ramblr", text).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
        }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    }
}
