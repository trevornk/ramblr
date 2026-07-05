package com.kafkasl.phonewhisper

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
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
        refresh()
    }

    private fun refresh() {
        val audio = hasPerm(Manifest.permission.RECORD_AUDIO)
        val acc = WhisperAccessibilityService.instance != null
        audioRowSub.text = if (audio) "Granted" else "Tap to grant permission"
        accRowSub.text = if (acc) "Enabled" else "Tap to enable in settings"
    }

    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == android.content.pm.PackageManager.PERMISSION_GRANTED

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
