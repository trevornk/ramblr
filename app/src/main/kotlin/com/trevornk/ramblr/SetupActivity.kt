package com.trevornk.ramblr

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.ScrollView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * "Setup" category screen (#93 restructure): audio permission + accessibility service rows, split
 * out of the old single-Activity Settings ScrollView. No behavior change from what these two rows
 * did on MainActivity -- same prefs-free state (both read live from the system), same taps.
 */
class SetupActivity : BaseSettingsActivity() {

    private lateinit var audioRowSub: android.widget.TextView
    private lateinit var accRowSub: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        val header = android.widget.TextView(this).apply {
            text = "Setup"
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        }
        root.addView(header)

        val audioRow = settingsRow("Audio permission", "Checking...") {
            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
        }
        audioRowSub = audioRow.findViewWithTag("subtitle")
        root.addView(audioRow)

        val accRow = settingsRow("Accessibility service", "Checking...") {
            onAccessibilityRowTapped()
        }
        accRowSub = accRow.findViewWithTag("subtitle")
        root.addView(accRow)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            addView(root)
        })

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        // #H6: a permanently-denied mic ("don't ask again") makes requestPermissions a silent
        // no-op -- the row would then visibly do nothing forever, with no path to App info. When
        // the denial result comes back and Android won't show a rationale again, offer App info so
        // the user isn't stuck. (A soft first denial leaves shouldShow == true, so no dialog then.)
        if (!hasPerm(Manifest.permission.RECORD_AUDIO) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)
        ) {
            showMicPermanentlyDeniedDialog()
        }
        refresh()
    }

    /** Routes a permanently-denied mic permission to App info (#H6), since the system permission
     *  prompt will never appear again from an in-app request. */
    private fun showMicPermanentlyDeniedDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Microphone access needed")
            .setMessage(
                "Microphone access is turned off for Ramblr, and Android won't ask again from " +
                    "here. Ramblr can't record without it.\n\n" +
                    "Open Ramblr's App info, then choose Permissions → Microphone → Allow."
            )
            .setPositiveButton("Open App info") { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", packageName, null),
                    )
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refresh() {
        val audio = hasPerm(Manifest.permission.RECORD_AUDIO)
        val acc = WhisperAccessibilityService.instance != null
        audioRowSub.text = if (audio) "Granted" else "Tap to grant permission"
        accRowSub.text = if (acc) "Enabled" else "Tap to enable in settings"
    }

    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Sideloaded Ramblr installs (GitHub Releases, not Play Store) hit Android 13+'s Restricted
     * Settings block on Accessibility -- system Settings just silently refuses to let the toggle
     * turn on, with no explanation of why or how to fix it (Trevor hit this directly re-testing a
     * fresh sideload). Detected proactively via [RestrictedSettingsCheck] instead of letting the
     * user discover it themselves.
     *
     * The primary button routes to App info FIRST when blocked (not Accessibility Settings) --
     * sending the user to Accessibility Settings while still blocked just reproduces the exact
     * silent-dead-end this dialog exists to prevent, since the toggle there would still refuse to
     * turn on with no explanation. App info -> \u22ee menu -> "Allow restricted settings" -> back
     * here is the one-time real fix; only after that should the button point at Accessibility.
     */
    private fun onAccessibilityRowTapped() {
        if (RestrictedSettingsCheck.isBlocked(this)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("One extra step needed")
                .setMessage(
                    "Because Ramblr was installed outside the Play Store, Android blocks its " +
                        "Accessibility toggle by default (a security feature, not a bug).\n\n" +
                        "Tap below to open Ramblr's App info, then:\n" +
                        "1. Tap the \u22ee menu in the top-right corner\n" +
                        "2. Choose \"Allow restricted settings\"\n" +
                        "3. Come back here and enable Accessibility as normal\n\n" +
                        "This is a one-time step per install."
                )
                .setPositiveButton("Open App info") { _, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", packageName, null),
                        )
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    companion object {
        /** Category subtitle for MainActivity's Setup row (#93) -- read fresh, no Activity
         *  instance/lifecycle needed since both signals are process-global. */
        fun subtitle(hasAudio: Boolean, hasAccessibility: Boolean): String = when {
            hasAudio && hasAccessibility -> "Permission and accessibility service granted"
            !hasAudio && !hasAccessibility -> "Audio permission and accessibility service needed"
            !hasAudio -> "Audio permission needed"
            else -> "Accessibility service needed"
        }
    }
}
