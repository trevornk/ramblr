package com.trevornk.ramblr

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile (#127): an alternative dictation trigger for users who want to hide the
 * floating ring entirely (swipe down, tap, instead of a screen-obstructing overlay). This is
 * purely a new trigger surface -- it never touches the recording state machine directly, only
 * [WhisperAccessibilityService.requestToggleRecording] (start-if-idle / stop-if-recording, the
 * same toggle semantics as the ring's own [WhisperAccessibilityService.onTap]).
 *
 * Deliberately NOT the FLAG_REQUEST_ACCESSIBILITY_BUTTON (OS-rendered nav-bar accessibility
 * button) approach -- that wires directly into WhisperAccessibilityService's own flags/service
 * config and risks interacting with other accessibility features. A QS tile is a fully separate
 * Android component with no such coupling, which is why it was scoped out as the safer piece to
 * ship first (see #127).
 */
class RamblrQsTileService : TileService() {

    private val handler = Handler(Looper.getMainLooper())

    /** While the QS panel is open, [onStartListening]/[onStopListening] bracket its visible
     *  lifetime -- polling on a short interval here is how the tile picks up a recording that
     *  finishes on its own (silence auto-stop (#108), watchdog timeout, max-duration auto-stop)
     *  while the panel is still showing, without WhisperAccessibilityService needing to know
     *  this tile exists at all. Never runs while the panel is closed. */
    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateTileState()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
        handler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
    }

    override fun onStopListening() {
        handler.removeCallbacks(refreshRunnable)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val serviceRunning = WhisperAccessibilityService.currentRecordingState() != null
        if (!serviceRunning) {
            // #127 requirement 3: accessibility service isn't enabled -- don't crash or silently
            // no-op forever, deep-link the user to the settings screen where they can turn it on.
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivityAndCollapse(intent)
            } catch (_: Exception) {
                // Best-effort; nothing else to safely do from a tile click if this fails.
            }
            return
        }
        WhisperAccessibilityService.requestToggleRecording()
        // Toggle takes effect on the service's handler asynchronously (RECORDING/TRANSCRIBING
        // engine start has real I/O in it), so reflect the immediately-known intent right away
        // and let the next onStartListening/refresh tick reconcile with the real state.
        handler.postDelayed({ updateTileState() }, TOGGLE_REFLECT_DELAY_MS)
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val state = WhisperAccessibilityService.currentRecordingState()
        tile.icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_mic)
        tile.label = getString(R.string.tile_label)
        when (state) {
            null -> {
                // Accessibility service not enabled/running. Deliberately STATE_INACTIVE, not
                // STATE_UNAVAILABLE: an unavailable tile is greyed out and the platform may
                // suppress onClick entirely on some Android versions, which would make the
                // "tap to deep-link to Settings" affordance below unreachable. INACTIVE keeps
                // the tile tappable while the subtitle still makes the disabled state clear.
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = getString(R.string.tile_subtitle_service_disabled)
            }
            RecordingStateMachine.State.RECORDING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = getString(R.string.tile_subtitle_recording)
            }
            RecordingStateMachine.State.TRANSCRIBING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = null
            }
            RecordingStateMachine.State.IDLE -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = null
            }
        }
        tile.updateTile()
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 1000L
        private const val TOGGLE_REFLECT_DELAY_MS = 150L
    }
}
