package com.trevornk.ramblr

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Slim category-list launcher (#93 restructure): status row, then one row per Settings category
 * that navigates to its own Activity (SetupActivity/TranscriptionActivity/CleanupActivity/
 * LivePreviewActivity/AdvancedActivity). This used to be one continuous ScrollView holding every
 * row for every category directly -- see git history before #93 for that version.
 *
 * The onboarding wizard (dialogs + advance/finish state machine) stays owned by this Activity
 * rather than moving to any of the category screens: it's a cross-cutting flow that touches
 * Transcription, Cleanup, and Streaming Preview state in sequence, so splitting it across those
 * screens would mean either duplicating the dialog logic or having each screen reach into its
 * siblings' prefs. AdvancedActivity's "Redo setup walkthrough" row hands off to this Activity via
 * [EXTRA_START_WALKTHROUGH] rather than re-implementing any wizard step itself.
 */
class MainActivity : BaseSettingsActivity() {

    private lateinit var statusSubtitle: TextView
    private lateinit var setupRowSub: TextView
    private lateinit var transcriptionRowSub: TextView
    private lateinit var cleanupRowSub: TextView
    private lateinit var livePreviewRowSub: TextView
    private lateinit var cloudRowSub: TextView

    // First-run wizard state (#6). Tracked in-memory so a dialog already on screen is never
    // duplicated by a stray onResume, and reset per Activity instance so a fresh launch always
    // re-shows the intro while setup remains incomplete.
    private var onboardingIntroShown = false
    private var onboardingDialog: android.app.AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A fold/rotation recreates this Activity mid-wizard; without restoring "the wizard has
        // started", OnboardingWizard.shouldAdvance stops advancing the moment Accessibility gets
        // enabled (its shouldShow signal clears) and strands the wizard before the
        // transcription/cleanup/streaming steps (#80). Fold-posture change while in system
        // Settings is the *normal* way this happens on a Fold.
        onboardingIntroShown = savedInstanceState?.getBoolean(STATE_ONBOARDING_INTRO_SHOWN) ?: false

        val root = vertical(0, 0)

        val header = TextView(this).apply {
            text = "Ramblr"
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        }
        root.addView(header)

        // Status row -- tapping it (re-)launches the setup walkthrough when setup isn't done yet
        // (#52); once ready it's just informational, same as before.
        val statusRow = settingsRow("Status", "Checking...") { onStatusRowTapped() }
        statusSubtitle = statusRow.findViewWithTag("subtitle")
        root.addView(statusRow)

        root.addView(sectionHeader("Settings"))

        val setupRow = settingsRow("Setup", "Checking...") {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        setupRowSub = setupRow.findViewWithTag("subtitle")
        root.addView(setupRow)

        val transcriptionRow = settingsRow("Transcription", "Checking...") {
            startActivity(Intent(this, TranscriptionActivity::class.java))
        }
        transcriptionRowSub = transcriptionRow.findViewWithTag("subtitle")
        root.addView(transcriptionRow)

        val cleanupRow = settingsRow("Cleanup", "Checking...") {
            startActivity(Intent(this, CleanupActivity::class.java))
        }
        cleanupRowSub = cleanupRow.findViewWithTag("subtitle")
        root.addView(cleanupRow)

        val livePreviewRow = settingsRow("Live Preview", "Checking...") {
            startActivity(Intent(this, LivePreviewActivity::class.java))
        }
        livePreviewRowSub = livePreviewRow.findViewWithTag("subtitle")
        root.addView(livePreviewRow)

        val cloudRow = settingsRow("Cloud", "Checking...") {
            startActivity(Intent(this, CloudProviderActivity::class.java))
        }
        cloudRowSub = cloudRow.findViewWithTag("subtitle")
        root.addView(cloudRow)

        root.addView(settingsRow("Advanced", AdvancedActivity.subtitle(this)) {
            startActivity(Intent(this, AdvancedActivity::class.java))
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            addView(root)
        })

        // The wizard requests mic permission itself as one of its steps; only auto-request here
        // for a returning user who's already past onboarding.
        val alreadyOnboarded = !OnboardingWizard.shouldShow(
            accessibilityEnabled = WhisperAccessibilityService.instance != null,
            onboardingComplete = prefs().getBoolean(KEY_ONBOARDING_COMPLETE, false)
        )
        if (alreadyOnboarded && !hasPerm(Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        // Independent of the mic grant above: needed for model-download progress/result
        // notifications (#56) to actually display on API 33+. Never blocks a download if
        // declined -- see DownloadNotifications.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPerm(Manifest.permission.POST_NOTIFICATIONS)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        handleWalkthroughIntent(intent)
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWalkthroughIntent(intent)
    }

    /** AdvancedActivity's "Redo setup walkthrough" row hands off here rather than duplicating any
     *  wizard-dialog logic (#93) -- see this class's own kdoc. */
    private fun handleWalkthroughIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_START_WALKTHROUGH, false) == true) {
            intent.removeExtra(EXTRA_START_WALKTHROUGH)
            startWalkthrough()
        }
    }

    override fun onResume() {
        super.onResume() // sets the #35 foreground flag via BaseSettingsActivity
        refresh()
        advanceOnboarding()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ONBOARDING_INTRO_SHOWN, onboardingIntroShown)
    }

    override fun onDestroy() {
        // The wizard dialog would otherwise leak its window on recreation (WindowLeaked) and
        // vanish; onResume of the new instance re-advances the wizard to the right step (#80).
        onboardingDialog?.dismiss()
        onboardingDialog = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r); refresh(); advanceOnboarding()
    }

    private fun refresh() {
        val audio = hasPerm(Manifest.permission.RECORD_AUDIO)
        val acc = WhisperAccessibilityService.instance != null
        val useLocal = prefs().getBoolean("use_local", true)
        val hasKey = ApiKeyStore.getApiKey(this).isNotBlank()
        val hasModel = LocalTranscriber.availableModels(this).isNotEmpty()

        setupRowSub.text = SetupActivity.subtitle(hasAudio = audio, hasAccessibility = acc)
        transcriptionRowSub.text = TranscriptionActivity.subtitle(this)
        cleanupRowSub.text = CleanupActivity.subtitle(this)
        livePreviewRowSub.text = LivePreviewActivity.subtitle(this)
        cloudRowSub.text = CloudProviderActivity.subtitle(this)

        // Ready logic -- see OnboardingWizard.isSetupComplete for what "ready" means (#52).
        val ready = OnboardingWizard.isSetupComplete(
            audioGranted = audio,
            accessibilityEnabled = acc,
            transcriptionLocal = useLocal,
            hasLocalModel = hasModel,
            hasApiKey = hasKey,
        )

        statusSubtitle.text = if (ready) "Ready — tap the overlay dot to dictate" else "Setup required — tap to finish setup"
        statusSubtitle.setTextColor(if (ready) attrColor(com.google.android.material.R.attr.colorPrimary) else attrColor(android.R.attr.textColorSecondary))
    }

    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == android.content.pm.PackageManager.PERMISSION_GRANTED

    // --- Onboarding wizard (#6/#52/#80/#98) -- kept on MainActivity, see class kdoc ---

    /** Advances the wizard from wherever it's paused, called on every entry point (onResume) and
     *  point the Activity resumes control (onResume, permission results) so a step that requires
     *  leaving the app (mic prompt, Accessibility settings) picks back up automatically. */
    private fun advanceOnboarding() = advanceOnboardingStep(force = false)

    /** Re-enters the walkthrough on demand (#52) -- from the Status row when setup isn't fully
     *  done, or Advanced's "Redo setup walkthrough" row -- always starting from the intro, same
     *  as a fresh install, though steps already satisfied (permissions already granted) are
     *  skipped as usual by [advanceOnboardingStep]'s own `when`. */
    private fun startWalkthrough() {
        onboardingIntroShown = false
        advanceOnboardingStep(force = true)
    }

    private fun advanceOnboardingStep(force: Boolean) {
        if (onboardingDialog?.isShowing == true) return
        val accessibilityEnabled = WhisperAccessibilityService.instance != null
        val complete = prefs().getBoolean(KEY_ONBOARDING_COMPLETE, false)
        if (!OnboardingWizard.shouldAdvance(onboardingIntroShown, force, accessibilityEnabled, complete)) return

        when {
            !onboardingIntroShown -> {
                onboardingIntroShown = true
                showOnboardingIntro()
            }
            !hasPerm(Manifest.permission.RECORD_AUDIO) -> showOnboardingMicStep()
            !accessibilityEnabled -> showOnboardingAccessibilityStep()
            else -> showOnboardingModeStep()
        }
    }

    /** Whether the Status row's "tap to finish setup" affordance should do anything (#52) --
     *  mirrors [refresh]'s own ready computation so tapping Status while "Ready" is a no-op. */
    private fun onStatusRowTapped() {
        val ready = OnboardingWizard.isSetupComplete(
            audioGranted = hasPerm(Manifest.permission.RECORD_AUDIO),
            accessibilityEnabled = WhisperAccessibilityService.instance != null,
            transcriptionLocal = prefs().getBoolean("use_local", true),
            hasLocalModel = LocalTranscriber.availableModels(this).isNotEmpty(),
            hasApiKey = ApiKeyStore.getApiKey(this).isNotBlank(),
        )
        if (!ready) startWalkthrough()
    }

    private fun dismissOnboarding() { onboardingDialog = null }

    private fun finishOnboarding() {
        prefs().edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
        // #98: belt-and-suspenders alongside the OnboardingWizard.shouldAdvance fix -- resetting
        // this here means even a future logic change to shouldAdvance can't silently reintroduce
        // the same "wizard state never clears" class of bug.
        onboardingIntroShown = false
        onboardingDialog = null
        refresh()
    }

    private fun showOnboardingIntro() {
        onboardingDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Welcome to Ramblr")
            .setMessage(
                "Ramblr lets you dictate into any text field: tap the floating button, " +
                    "speak, tap again, and your words are inserted where you were typing.\n\n" +
                    "It needs two things to do that:\n" +
                    "• Microphone — to record what you say\n" +
                    "• Accessibility service — to insert the text into the focused field across apps. " +
                    "It doesn't read your screen or run background automation; it only acts after you " +
                    "tap the overlay button."
            )
            .setCancelable(false)
            .setPositiveButton("Get started") { _, _ -> dismissOnboarding(); advanceOnboarding() }
            .setNegativeButton("Not now") { _, _ -> dismissOnboarding() }
            .show()
    }

    private fun showOnboardingMicStep() {
        onboardingDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Step 1 of 5: Microphone access")
            .setMessage("Ramblr needs the microphone to record what you say before transcribing it.")
            .setCancelable(false)
            .setPositiveButton("Continue") { _, _ ->
                dismissOnboarding()
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
            .setNegativeButton("Not now") { _, _ -> dismissOnboarding() }
            .show()
    }

    private fun showOnboardingAccessibilityStep() {
        // #44 follow-up: a sideloaded install (GitHub Releases, not Play Store) hits Android 13+'s
        // Restricted Settings block here -- the toggle on the next screen will silently refuse to
        // turn on with zero explanation. Previously this dialog only *explained* the block in text
        // while still routing to Accessibility Settings, where the tap would fail again exactly as
        // before -- the user had to notice, back out, and find App info on their own. Now the
        // primary button routes to App info FIRST when blocked, so the flow is a real fix instead
        // of just a warning: unblock there, come back, onResume() re-runs this same step, and since
        // isBlocked() is now false the button correctly points at Accessibility Settings instead.
        val blocked = RestrictedSettingsCheck.isBlocked(this)
        val message = if (blocked) {
            "Ramblr uses Android's Accessibility service for one narrow reason: to insert " +
                "dictated text into whatever text field is currently focused. It doesn't replace your " +
                "keyboard and doesn't run background automation.\n\n" +
                "\u26a0\ufe0f Since Ramblr was installed outside the Play Store, Android blocks the " +
                "Accessibility toggle by default (a security feature, not a bug) until you allow it " +
                "once per install.\n\n" +
                "Tap below to open Ramblr's App info, then:\n" +
                "1. Tap the \u22ee menu in the top-right corner\n" +
                "2. Choose \"Allow restricted settings\"\n" +
                "3. Come back here"
        } else {
            "Ramblr uses Android's Accessibility service for one narrow reason: to insert " +
                "dictated text into whatever text field is currently focused. It doesn't replace your " +
                "keyboard and doesn't run background automation.\n\n" +
                "On the next screen, look for \"Ramblr\" in the list and turn it on."
        }
        onboardingDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Step 2 of 5: Turn on Accessibility")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(if (blocked) "Open App info" else "Open Accessibility Settings") { _, _ ->
                dismissOnboarding()
                if (blocked) {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", packageName, null),
                        )
                    )
                } else {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            .setNegativeButton("Not now") { _, _ -> dismissOnboarding() }
            .show()
    }

    private fun showOnboardingModeStep() {
        val recommended = MODEL_CATALOG.firstOrNull { it.recommended } ?: MODEL_CATALOG.first()
        onboardingDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Step 3 of 5: Choose transcription mode")
            .setMessage(
                "On-device (recommended): downloads \"${recommended.name}\" and keeps your audio on " +
                    "this phone.\n\nCloud: uses OpenAI's API with your own key — no download, but audio " +
                    "leaves your device and OpenAI usage is billed to you."
            )
            .setCancelable(false)
            .setPositiveButton("Use on-device (recommended)") { _, _ ->
                dismissOnboarding()
                prefs().edit().putBoolean("use_local", true).apply()
                if (ModelDownloader.isInstalled(this, recommended)) {
                    selectOnboardingModel(recommended.archive)
                } else {
                    ModelDownloadWorker.enqueue(this, recommended)
                    toast("Downloading ${recommended.name}...")
                }
                refresh()
                showOnboardingCleanupStep()
            }
            .setNegativeButton("Use cloud (needs API key)") { _, _ ->
                dismissOnboarding()
                prefs().edit().putBoolean("use_local", false).apply()
                refresh()
                promptOnboardingApiKey { showOnboardingCleanupStep() }
            }
            .setNeutralButton("Not now") { _, _ -> dismissOnboarding() }
            .show()
    }

    /** Shared cloud-API-key entry point for both the Transcription and Cleanup onboarding steps
     *  (#52) -- both ultimately read [ApiKeyStore]'s one key (see TranscriptionActivity's/
     *  CleanupActivity's own contextual key rows), so a user who already entered it for one isn't
     *  asked again for the other. */
    private fun promptOnboardingApiKey(onDone: () -> Unit) {
        if (ApiKeyStore.getApiKey(this).isNotBlank()) {
            onDone()
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "sk-..."
        }
        onboardingDialog = android.app.AlertDialog.Builder(this)
            .setTitle("OpenAI API Key")
            .setMessage("Used only to call OpenAI's API directly from your phone — billed pay-per-use to your own account.")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setCancelable(false)
            .setPositiveButton("Save") { _, _ ->
                val entered = input.text.toString().trim()
                if (entered.isNotBlank()) ApiKeyStore.setApiKey(this, entered)
                refresh()
                onDone()
            }
            .setNegativeButton("Skip") { _, _ -> dismissOnboarding(); onDone() }
            .show()
    }

    /**
     * Cleanup onboarding step (#98): optional, off by default, same Local/Cloud shape as
     * Transcription. Cloud is now the RECOMMENDED default (was previously Local) -- following
     * real on-device measurement this session: Trevor's phone was independently confirmed to be
     * under persistent, real memory pressure (LowMemoryKiller actively killing background apps,
     * MemAvailable as low as 1.1-1.4GB even after the LFM2.5-350M model swap cut local cleanup's
     * own footprint from 651MB to ~450MB), and local cleanup generation reliably could not finish
     * within its time budget under those conditions -- not a one-off, a persistent state of this
     * device. Cloud cleanup has zero local memory footprint and consistent latency regardless of
     * what else is running, so it's the more reliable default for a phone that's also a normal
     * daily driver running many other apps. Local remains available and fully supported for
     * anyone who prefers it or is offline-first, just no longer the recommended path.
     */
    private fun showOnboardingCleanupStep() {
        val recommendedLocal = LOCAL_CLEANUP_MODEL_CATALOG.firstOrNull { it.recommended } ?: LOCAL_CLEANUP_MODEL_CATALOG.first()
        onboardingDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Step 4 of 5: Clean up dictation with AI? (optional)")
            .setMessage(
                "Cleanup rewrites your raw dictation to fix grammar, punctuation, and filler words — " +
                    "off by default.\n\nCloud (recommended): uses your own API key -- no download, " +
                    "consistently fast, and doesn't compete with your phone's other apps for memory.\n\n" +
                    "On-device: downloads \"${recommendedLocal.name}\" and keeps the text on this phone, " +
                    "but on a phone that's also running lots of other apps, it can sometimes be too slow " +
                    "to finish and fall back to raw text."
            )
            .setCancelable(false)
            .setPositiveButton("Use cloud (recommended)") { _, _ ->
                dismissOnboarding()
                enableOnboardingCleanupCloud()
                promptOnboardingApiKey { showOnboardingStreamingStep() }
            }
            .setNegativeButton("Use on-device") { _, _ ->
                dismissOnboarding()
                enableOnboardingCleanupLocal(recommendedLocal)
                showOnboardingStreamingStep()
            }
            .setNeutralButton("Skip (leave off)") { _, _ -> dismissOnboarding(); showOnboardingStreamingStep() }
            .show()
    }

    private fun enableOnboardingCleanupLocal(model: Model) {
        prefs().edit()
            .putBoolean("use_post_processing", true)
            .putString(KEY_LOCAL_CLEANUP_MODEL_NAME, model.archive)
            .apply()
        // #37/#52 follow-up: add/update only the LOCAL floor entry, never overwrite the whole
        // chain (see ProviderChain.withLocalFloor's kdoc -- the same real regression Trevor hit
        // live applies here too, even though onboarding usually runs on a fresh chain).
        ProviderChainStore.save(this, ProviderChainStore.load(this).withLocalFloor(model.archive))
        CloudFeatureToggle.setCleanupEnabled(this, false)
        if (!ModelDownloader.isInstalled(this, model)) {
            ModelDownloadWorker.enqueue(this, model)
            toast("Downloading ${model.name}...")
        }
        refresh()
    }

    /** The dialog in [showOnboardingCleanupStep] already explains that Cloud cleanup sends text
     *  off-device even when transcription stays local, so this also marks that one-time consent
     *  (#23) satisfied -- otherwise Settings' own toggle would show the same warning again right
     *  after onboarding finishes. */
    private fun enableOnboardingCleanupCloud() {
        prefs().edit()
            .putBoolean("use_post_processing", true)
            .putBoolean(KEY_LOCAL_CLEANUP_CONSENT, true)
            .apply()
        // #37/#52 follow-up: seed a default OpenAI entry only if the chain has no cloud-capable
        // entry yet -- same non-destructive rule as CleanupActivity.onSelectSimpleCleanup.
        val chain = ProviderChainStore.load(this)
        if (chain.capableEntriesFor(needsTranscription = false).none { it.kind != ProviderKind.LOCAL }) {
            ProviderChainStore.save(this, ProviderChain(chain.entries + ProviderChainEntry(ProviderKind.OPENAI, PostProcessor.DEFAULT_MODEL)))
        }
        CloudFeatureToggle.setCleanupEnabled(this, true)
        refresh()
    }

    /** Streaming live-preview onboarding step (#52): a plain on/off toggle, not a Local/Cloud
     *  choice -- the streaming path is always on-device (#29), there is no cloud option to offer. */
    private fun showOnboardingStreamingStep() {
        val recommended = STREAMING_MODEL_CATALOG.firstOrNull { it.recommended } ?: STREAMING_MODEL_CATALOG.first()
        onboardingDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Step 5 of 5: Show live text while you speak? (optional)")
            .setMessage(
                "Streaming preview shows your words appearing in the field as you talk, using a small " +
                    "on-device model — always local, nothing is ever sent anywhere for this. The final " +
                    "text after you stop recording still comes from your Transcription + Cleanup " +
                    "settings above; this only changes what's shown while recording.\n\n" +
                    "Turning this on downloads \"${recommended.name}\" now."
            )
            .setCancelable(false)
            .setPositiveButton("Turn on") { _, _ ->
                dismissOnboarding()
                enableOnboardingStreaming(recommended)
                showOnboardingTryStep()
            }
            .setNegativeButton("Skip (leave off)") { _, _ -> dismissOnboarding(); showOnboardingTryStep() }
            .show()
    }

    private fun enableOnboardingStreaming(model: Model) {
        prefs().edit()
            .putBoolean(KEY_STREAMING_PREVIEW, true)
            .putString(KEY_STREAMING_MODEL_NAME, model.archive)
            .apply()
        if (!ModelDownloader.isInstalled(this, model)) {
            ModelDownloadWorker.enqueue(this, model)
            toast("Downloading ${model.name}...")
        }
        WhisperAccessibilityService.instance?.reloadStreamingModel()
        refresh()
    }

    /**
     * Whether the currently-selected transcription model is actually ready to use right now
     * (#98 UX follow-up): true either when cloud transcription is selected (nothing to download)
     * or the on-device model chosen during onboarding has genuinely finished downloading.
     * [showOnboardingTryStep] uses this to avoid the false "Setup is done" claim that previously
     * appeared even while a model download was still in progress -- silently misrouting a test
     * dictation into the cloud path (see [WhisperAccessibilityService.continueTranscription]'s
     * comment on the same issue), which then failed with a confusing "Set API key" error that had
     * nothing to do with the real cause.
     */
    private fun onboardingTranscriptionModelReady(): Boolean {
        if (!prefs().getBoolean("use_local", true)) return true // cloud: nothing to download
        val archive = prefs().getString("model_name", "") ?: ""
        val model = MODEL_CATALOG.firstOrNull { it.archive == archive } ?: return false
        return ModelDownloader.isInstalled(this, model)
    }

    private fun showOnboardingTryStep() {
        val testField = EditText(this).apply {
            hint = "Tap here, then use the floating button to dictate a test phrase"
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        val modelReady = onboardingTranscriptionModelReady()
        val message = if (modelReady) {
            "Setup is done. If the floating button is visible, tap it, speak a test phrase, and " +
                "tap it again to confirm the text lands in the field below."
        } else {
            "Setup is done, but your on-device model is still downloading in the background -- " +
                "dictation won't work until that finishes. Feel free to close this now; the " +
                "floating button will start working as soon as the download completes, no need " +
                "to wait here."
        }
        onboardingDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Try it out (optional)")
            .setMessage(message)
            .setView(testField)
            .setCancelable(false)
            .setPositiveButton("Finish setup") { _, _ -> finishOnboarding() }
            .show()
    }

    private fun selectOnboardingModel(archive: String) {
        prefs().edit().putString("model_name", archive).apply()
        WhisperAccessibilityService.instance?.reloadModel()
    }

    companion object {
        private const val KEY_LOCAL_CLEANUP_CONSENT = "local_cleanup_consent_seen"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        /** Instance-state key: whether the wizard already started this session (#80). */
        private const val STATE_ONBOARDING_INTRO_SHOWN = "state_onboarding_intro_shown"
        private const val KEY_STREAMING_PREVIEW = "streaming_preview_enabled"
        private const val KEY_STREAMING_MODEL_NAME = "streaming_model_name"
        private const val KEY_LOCAL_CLEANUP_MODEL_NAME = "local_cleanup_model_name"

        /** Intent extra AdvancedActivity sets when handing off "Redo setup walkthrough" back to
         *  this Activity (#93) -- see [handleWalkthroughIntent]. */
        const val EXTRA_START_WALKTHROUGH = "start_walkthrough"
    }
}
