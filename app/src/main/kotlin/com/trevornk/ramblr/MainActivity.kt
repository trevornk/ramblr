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
    private lateinit var vocabularyRowSub: TextView
    private lateinit var invocationRowSub: TextView
    private lateinit var serviceKilledBanner: View

    // First-run wizard state (#6). Tracked in-memory so a dialog already on screen is never
    // duplicated by a stray onResume, and reset per Activity instance so a fresh launch always
    // re-shows the intro while setup remains incomplete.
    private var onboardingIntroShown = false
    private var onboardingDialog: android.app.AlertDialog? = null

    // Sticky forced re-entry (#H4). "Redo setup walkthrough" / a Status-row tap forces the intro
    // via advanceOnboardingStep(force = true), but the intro's own "Get started" button (and the
    // mic/accessibility steps that resume via onResume) call the *unforced* advance -- which, for a
    // returning user whose onboarding_complete is already true, refuses to advance, dead-ending the
    // wizard on screen 1. Keeping the forced flag for the whole re-entry session and OR-ing it into
    // every advance call is what carries the user past the intro. Cleared when the wizard finishes
    // or is explicitly abandoned, so it never re-nags on a later onResume.
    private var walkthroughForced = false

    // #H6: tracks whether we've already requested mic permission this session, so the mic step can
    // tell a fresh never-asked state (shouldShowRequestPermissionRationale is also false there)
    // apart from a permanent "don't ask again" denial -- the latter makes requestPermissions a
    // silent no-op that would otherwise loop the wizard on the mic dialog forever.
    private var micPermissionAsked = false

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

        // #156 guard rail: the invisible-toggle recovery banner. Built unconditionally and
        // shown/hidden in refresh() (the #L16 lesson from BehaviorActivity's iconHiddenRow --
        // the triggering state changes precisely while this Activity is paused, in system
        // Settings), so onResume picks up a fresh kill immediately.
        serviceKilledBanner = buildServiceKilledBanner()
        root.addView(serviceKilledBanner)

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

        // Top-level Personal vocabulary entry (#217): the editor stayed reachable only via
        // Advanced > Behavior for so long that #140's reporter asked for the feature without
        // finding it. Same shared editor as the Behavior row ([VocabularyEditor]) -- this row
        // just makes it discoverable, with a live term count as its subtitle.
        val vocabularyRow = settingsRow("Personal vocabulary", "Checking...") {
            VocabularyEditor.prompt(this) { refresh() }
        }
        vocabularyRowSub = vocabularyRow.findViewWithTag("subtitle")
        root.addView(vocabularyRow)

        // Invocation-method chooser (#156): one screen for every way to start dictation --
        // app-owned (ring, QS tile) and OS-owned (a11y button/gesture, volume keys) -- with the
        // invisible-toggle education the OS's own UI lacks. Top-level rather than under
        // Advanced/Behavior: the OS-owned methods are how #156's service-kill trap gets sprung,
        // so the safe path to them should be as discoverable as the trap is.
        val invocationRow = settingsRow("Invocation", "Checking...") {
            startActivity(Intent(this, InvocationActivity::class.java))
        }
        invocationRowSub = invocationRow.findViewWithTag("subtitle")
        root.addView(invocationRow)

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
        // Only auto-request permissions for a returning (already-onboarded) user: during onboarding
        // the wizard owns the mic ask, and the notification ask is deferred until after onboarding.
        // Mic + notifications go in ONE requestPermissions call (M13): two back-to-back calls both
        // using request code 1 could cancel each other on some Android versions, so the mic prompt
        // might never appear. Notifications (#56) never block a download if declined -- see
        // DownloadNotifications.
        if (alreadyOnboarded) {
            val needed = mutableListOf<String>()
            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) needed += Manifest.permission.RECORD_AUDIO
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPerm(Manifest.permission.POST_NOTIFICATIONS)) {
                needed += Manifest.permission.POST_NOTIFICATIONS
            }
            if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
        }

        handleWalkthroughIntent(intent)
        refresh()

        // Part 5, first-run scheduling (github flavor only): a fresh install's notify toggle
        // defaults to true (see SelfUpdatePrefs.KEY_NOTIFY_ENABLED), but until now nothing ever
        // called SelfUpdateCheckWorker.schedule() for a user who never opens the Updates screen
        // and flips the toggle off/on -- so the periodic check silently never started. Hooked
        // into onCreate (not onResume): this codebase has no existing "run once per
        // install/process" idiom to mirror (onboarding's KEY_ONBOARDING_COMPLETE tracks wizard
        // completion, not a general first-run marker), and onCreate already fires far less often
        // than onResume, so calling schedule() here is a small enough redundancy to accept rather
        // than invent a new one-shot-marker mechanism for this alone -- enqueueUniquePeriodicWork
        // + KEEP (see SelfUpdateCheckWorker.schedule) makes every repeat call a harmless no-op
        // regardless. Reflective lookup because SelfUpdateCheckWorker/SelfUpdatePrefs live in
        // src/github/ and this Activity is compiled into every flavor including storefront,
        // which never has those classes at all -- mirrors AdvancedActivity's identical
        // selfUpdateSettingsActivityClass() Class.forName pattern.
        scheduleSelfUpdateCheckIfEnabled()
    }

    /** No-op on the storefront flavor (ClassNotFoundException, caught silently) -- see call
     *  site's kdoc in [onCreate] for why this must be reflective rather than a direct reference. */
    private fun scheduleSelfUpdateCheckIfEnabled() {
        try {
            val prefsClass = Class.forName("com.trevornk.ramblr.SelfUpdatePrefs")
            val prefsInstance = prefsClass.getField("INSTANCE").get(null)
            val notifyEnabled = prefsClass.getMethod("isNotifyEnabled", android.content.Context::class.java)
                .invoke(prefsInstance, this) as Boolean
            if (!notifyEnabled) return

            val workerClass = Class.forName("com.trevornk.ramblr.SelfUpdateCheckWorker")
            val companion = workerClass.getField("Companion").get(null)
            companion.javaClass.getMethod("schedule", android.content.Context::class.java)
                .invoke(companion, this)
        } catch (_: ClassNotFoundException) {
            // storefront flavor: these classes don't exist in the compiled classes at all.
        } catch (_: ReflectiveOperationException) {
            // Defensive: a reflection signature mismatch must not crash app launch.
        }
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
        // Any configured non-LOCAL transcription-capable provider counts as "has a cloud key", not
        // just OpenAI, so a Gemini-only cloud-transcription user isn't stuck on "Setup required" (M8).
        val hasKey = hasConfiguredCloudTranscription(ProviderChainStore.load(this)) {
            ProviderCredentialStore.isConfigured(this, it)
        }
        val hasModel = LocalTranscriber.availableModels(this).isNotEmpty()

        setupRowSub.text = SetupActivity.subtitle(hasAudio = audio, hasAccessibility = acc)
        transcriptionRowSub.text = TranscriptionActivity.subtitle(this)
        cleanupRowSub.text = CleanupActivity.subtitle(this)
        livePreviewRowSub.text = LivePreviewActivity.subtitle(this)
        cloudRowSub.text = CloudProviderActivity.subtitle(this)
        vocabularyRowSub.text = vocabularyMainRowSubtitleText(VocabularyEditor.terms(this).size)
        invocationRowSub.text = InvocationActivity.subtitle(this)
        serviceKilledBanner.visibility =
            if (InvocationGuardRail.shouldShowBanner(this)) View.VISIBLE else View.GONE

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

    // --- #156 guard rail: "Ramblr was turned off by the system shortcut switch" banner ---

    /**
     * The recovery banner for the invisible-toggle kill (#156): the OS disables the whole
     * service when its last shortcut is removed in system Settings, silently and with the app
     * closed. Detection is [InvocationGuardRail.shouldShowBanner] (pure decision:
     * [shouldShowServiceKilledBanner], unit-tested); this just builds the card once -- visibility
     * is owned by [refresh], mirroring how the other rows update.
     *
     * Tap = the best available recovery per tier: with WRITE_SECURE_SETTINGS granted, a true
     * one-tap re-enable (raw write to enabled_accessibility_services, preserving other services'
     * entries); base tier deep-links straight to Ramblr's own Accessibility page where the
     * enable switch lives. Dismiss = quiet until a FRESH kill (the dismissal is cleared when the
     * service next connects), so the banner never turns into a nag.
     */
    private fun buildServiceKilledBanner(): View {
        val banner = vertical(dp(16), dp(12)).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(withAlpha(attrColor(com.google.android.material.R.attr.colorPrimary), 0x22))
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply {
                setMargins(dp(16), 0, dp(16), dp(8))
            }
            visibility = View.GONE
        }
        banner.addView(TextView(this).apply {
            text = "Ramblr was turned off by the system shortcut switch"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(attrColor(android.R.attr.textColorPrimary))
        })
        banner.addView(TextView(this).apply {
            text = if (InvocationSecureSettings.canWrite(this@MainActivity))
                "Tap to turn it back on."
            else
                "Tap to open its Accessibility page and turn it back on."
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(4), 0, 0)
        })
        val dismiss = TextView(this).apply {
            text = "Dismiss"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
            setPadding(dp(8), dp(8), dp(8), dp(4))
            setOnClickListener {
                InvocationGuardRail.dismissBanner(this@MainActivity)
                refresh()
            }
        }
        banner.addView(dismiss)
        banner.setOnClickListener { onServiceKilledBannerTapped() }
        return banner
    }

    private fun onServiceKilledBannerTapped() {
        // Advanced tier: true one-tap re-enable via a raw Secure write (#156 memo §3 -- raw
        // writes bypass the invisible-toggle sync, and here that's exactly what we want: restore
        // enabled_accessibility_services without the OS "helpfully" re-syncing shortcuts).
        if (InvocationSecureSettings.reEnableService(this)) {
            toast("Ramblr re-enabled")
            refresh()
            return
        }
        // Base tier: the OS offers no app-side re-enable, so the best path IS the service's own
        // Settings page (resolvable since API 30 = minSdk; the action is a string constant --
        // see InvocationSecureSettings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS -- because the SDK
        // symbol is @hide), where the enable switch is one tap away.
        val details = Intent(InvocationSecureSettings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS).putExtra(
            Intent.EXTRA_COMPONENT_NAME,
            android.content.ComponentName(this, WhisperAccessibilityService::class.java).flattenToString(),
        )
        try {
            startActivity(details)
        } catch (_: android.content.ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    // --- Onboarding wizard (#6/#52/#80/#98) -- kept on MainActivity, see class kdoc ---

    /** Advances the wizard from wherever it's paused, called on every entry point (onResume) and
     *  point the Activity resumes control (onResume, permission results) so a step that requires
     *  leaving the app (mic prompt, Accessibility settings) picks back up automatically. */
    private fun advanceOnboarding() = advanceOnboardingStep(force = walkthroughForced)

    /** Re-enters the walkthrough on demand (#52) -- from the Status row when setup isn't fully
     *  done, or Advanced's "Redo setup walkthrough" row -- always starting from the intro, same
     *  as a fresh install, though steps already satisfied (permissions already granted) are
     *  skipped as usual by [advanceOnboardingStep]'s own `when`. */
    private fun startWalkthrough() {
        walkthroughForced = true
        onboardingIntroShown = false
        // A deliberate "redo" starts the callback-chained steps over from the mode step rather than
        // resuming at whatever step a prior incomplete run left persisted (#L15).
        markOnboardingStep(STEP_MODE)
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
            // Resume the callback-chained steps 4/5 at the furthest one reached, so process death
            // mid-onboarding doesn't drop the user back to the mode step (#L15).
            else -> when (prefs().getString(KEY_ONBOARDING_STEP, STEP_MODE)) {
                STEP_TRY -> showOnboardingTryStep()
                STEP_STREAMING -> showOnboardingStreamingStep()
                STEP_CLEANUP -> showOnboardingCleanupStep()
                else -> showOnboardingModeStep()
            }
        }
    }

    /** Persists the furthest onboarding dialog reached so it can be resumed after process death (#L15). */
    private fun markOnboardingStep(step: String) {
        prefs().edit().putString(KEY_ONBOARDING_STEP, step).apply()
    }

    /** Whether the Status row's "tap to finish setup" affordance should do anything (#52) --
     *  mirrors [refresh]'s own ready computation so tapping Status while "Ready" is a no-op. */
    private fun onStatusRowTapped() {
        val ready = OnboardingWizard.isSetupComplete(
            audioGranted = hasPerm(Manifest.permission.RECORD_AUDIO),
            accessibilityEnabled = WhisperAccessibilityService.instance != null,
            transcriptionLocal = prefs().getBoolean("use_local", true),
            hasLocalModel = LocalTranscriber.availableModels(this).isNotEmpty(),
            hasApiKey = hasConfiguredCloudTranscription(ProviderChainStore.load(this)) {
                ProviderCredentialStore.isConfigured(this, it)
            },
        )
        if (!ready) startWalkthrough()
    }

    private fun dismissOnboarding() { onboardingDialog = null }

    /** A top-level "Not now" that abandons the wizard entirely (as opposed to a mid-wizard "Skip"
     *  that continues to the next step): drops the forced-re-entry stickiness so a returning user
     *  who bailed out isn't re-nagged on the next onResume (#H4). */
    private fun abandonWalkthrough() {
        walkthroughForced = false
        dismissOnboarding()
    }

    private fun finishOnboarding() {
        prefs().edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).remove(KEY_ONBOARDING_STEP).apply()
        // #98: belt-and-suspenders alongside the OnboardingWizard.shouldAdvance fix -- resetting
        // this here means even a future logic change to shouldAdvance can't silently reintroduce
        // the same "wizard state never clears" class of bug.
        onboardingIntroShown = false
        walkthroughForced = false // #H4: forced re-entry ends when the wizard completes
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
            .setNegativeButton("Not now") { _, _ -> abandonWalkthrough() }
            .show()
    }

    private fun showOnboardingMicStep() {
        // #H6: after a permanent denial ("don't ask again", or Android 11+'s second denial),
        // requestPermissions is auto-denied with no dialog -- re-prompting would loop the wizard
        // here forever. Detect it (we've asked at least once, the system won't show a rationale,
        // and it's still denied) and route to App info instead, mirroring the restricted-settings
        // Accessibility flow.
        if (micPermissionAsked &&
            !hasPerm(Manifest.permission.RECORD_AUDIO) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)
        ) {
            showOnboardingMicPermanentlyDeniedStep()
            return
        }
        onboardingDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Step 1 of 5: Microphone access")
            .setMessage("Ramblr needs the microphone to record what you say before transcribing it.")
            .setCancelable(false)
            .setPositiveButton("Continue") { _, _ ->
                dismissOnboarding()
                micPermissionAsked = true
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
            .setNegativeButton("Not now") { _, _ -> abandonWalkthrough() }
            .show()
    }

    /** Mic step's dead-end recovery (#H6): the permission is permanently denied, so the only way
     *  forward is App info -> Permissions -> Microphone -> Allow. Routes there instead of firing a
     *  request that Android silently drops. */
    private fun showOnboardingMicPermanentlyDeniedStep() {
        onboardingDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Step 1 of 5: Microphone access")
            .setMessage(
                "Microphone access is turned off for Ramblr, and Android won't ask again from " +
                    "here. Ramblr can't record without it.\n\n" +
                    "Tap below to open Ramblr's App info, then choose Permissions → Microphone " +
                    "→ Allow, and come back here."
            )
            .setCancelable(false)
            .setPositiveButton("Open App info") { _, _ ->
                dismissOnboarding()
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", packageName, null),
                    )
                )
            }
            .setNegativeButton("Not now") { _, _ -> abandonWalkthrough() }
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
            .setNegativeButton("Not now") { _, _ -> abandonWalkthrough() }
            .show()
    }

    /**
     * Provisions the Silero VAD model as soon as on-device transcription is chosen (#169).
     *
     * Without this the model is only fetched by the first local dictation itself, and because
     * that fetch is asynchronous the triggering take always decodes unsegmented — the single-shot
     * native decode #132 exists to avoid, measured at 665 MB RSS on a 4 GB device. Enqueueing at
     * selection time lets the ~644 KB download overlap the rest of setup so the first real
     * dictation already has it.
     *
     * Best-effort by design: [ModelDownloadWorker.enqueue]'s network constraint means an offline
     * user simply gets it later, and [VadModelProvisioning.shouldFetch] remains the backstop at
     * transcription time. Nothing here can block or fail onboarding.
     */
    private fun prefetchVadModel() {
        if (!VadModelProvisioning.shouldPrefetchForLocalMode(
                localTranscriptionSelected = prefs().getBoolean("use_local", true),
                modelInstalled = ModelDownloader.vadModelFile(this, SILERO_VAD_MODEL) != null,
            )
        ) {
            return
        }
        android.util.Log.i(TAG, "Local mode selected — prefetching VAD model for segmented decode (#169)")
        ModelDownloadWorker.enqueue(this, SILERO_VAD_MODEL)
    }

    private fun showOnboardingModeStep() {
        markOnboardingStep(STEP_MODE)
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
                prefetchVadModel()
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
                promptOnboardingProviderKey(ProviderKind.OPENAI) { showOnboardingCleanupStep() }
            }
            .setNeutralButton("Not now") { _, _ -> abandonWalkthrough() }
            .show()
    }

    /** Cloud-credential entry point for [showOnboardingCloudProviderChoiceStep] (#98 revision,
     *  unified post-spaghetti-cleanup): one path for every addable [ProviderKind], including
     *  OpenAI. Reads/writes [ProviderCredentialStore] exclusively -- the same store
     *  [CloudProviderActivity] and the live provider chain read at runtime. The legacy split
     *  store this replaced (and the one-time migration that seeded from it) have since been
     *  deleted as dead code. */
    private fun promptOnboardingProviderKey(kind: ProviderKind, onDone: () -> Unit) {
        if (ProviderCredentialStore.isConfigured(this, kind)) {
            onDone()
            return
        }
        val label = when (kind) {
            ProviderKind.OPENAI -> "OpenAI"
            ProviderKind.GEMINI -> "Gemini"
            ProviderKind.ANTHROPIC -> "Anthropic"
            ProviderKind.OMNIROUTE -> "OmniRoute"
            else -> kind.name
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Paste key"
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("$label API Key")
            .setMessage("Used only to call $label's API directly from your phone — billed pay-per-use to your own account.")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setCancelable(false)
            .setPositiveButton("Save", null) // real handler wired below so a blank key doesn't dismiss
            .setNegativeButton("Skip") { _, _ -> dismissOnboarding(); onDone() }
            .create()
        // Override Save so a blank entry shows an inline error and keeps the dialog open instead of
        // silently proceeding with no key stored (which then leaves the user on "Setup required"
        // with no explanation) -- "Skip" is the explicit no-key path (M12).
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entered = input.text.toString().trim()
                if (entered.isBlank()) {
                    input.error = "Enter a key, or tap Skip"
                } else {
                    ProviderCredentialStore.set(this, kind, entered)
                    dismissOnboarding()
                    dialog.dismiss()
                    refresh()
                    onDone()
                }
            }
        }
        onboardingDialog = dialog
        dialog.show()
    }

    /**
     * Cleanup onboarding step (#98, revised): optional, off by default, same Local/Cloud shape
     * as Transcription. Neither Cloud nor On-device is labeled "recommended" here -- there is no
     * verified latency data comparing Gemini vs OpenAI (the "Gemini is faster" idea floating
     * around this project traced back to an unverifiable eval-report reference that turned out
     * not to exist in the repo, and a real live side-by-side check found Gemini's latency
     * inconsistent, sometimes much slower, not clearly faster). Cloud vs Local is described
     * factually (cloud needs a key and network; on-device can be slow on a phone under memory
     * pressure, as Trevor's was independently measured to be) without a speed claim on either
     * side; if Cloud is chosen, [showOnboardingCloudProviderChoiceStep] lets the user pick
     * Gemini or OpenAI directly with no bias toward either.
     */
    private fun showOnboardingCleanupStep() {
        markOnboardingStep(STEP_CLEANUP)
        val recommendedLocal = LOCAL_CLEANUP_MODEL_CATALOG.firstOrNull { it.recommended } ?: LOCAL_CLEANUP_MODEL_CATALOG.first()
        onboardingDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Step 4 of 5: Clean up dictation with AI? (optional)")
            .setMessage(
                "Cleanup rewrites your raw dictation to fix grammar, punctuation, and filler words — " +
                    "off by default.\n\nCloud: uses your own API key (Gemini or OpenAI, your choice) — " +
                    "no download, and doesn't compete with your phone's other apps for memory.\n\n" +
                    "On-device: downloads \"${recommendedLocal.name}\" and keeps the text on this phone, " +
                    "but on a phone that's also running lots of other apps, it can sometimes be too slow " +
                    "to finish and fall back to raw text."
            )
            .setCancelable(false)
            .setPositiveButton("Use cloud") { _, _ ->
                dismissOnboarding()
                showOnboardingCloudProviderChoiceStep()
            }
            .setNegativeButton("Use on-device") { _, _ ->
                dismissOnboarding()
                enableOnboardingCleanupLocal(recommendedLocal) { showOnboardingStreamingStep() }
            }
            // #174: the label asserts an end state ("leave off"), so the handler must actually
            // write it. A pure no-op diverged from the label whenever onboarding re-ran on a
            // device with cleanup already on (Advanced -> "Redo setup walkthrough"): the user
            // picked "leave off" and finished with cleanup still enabled. A user re-running
            // setup and choosing this option is expressing intent about the end state, not
            // asking to preserve whatever was configured before.
            .setNeutralButton("Skip (leave off)") { _, _ ->
                dismissOnboarding()
                PostProcessingToggle.setEnabled(this, false)
                showOnboardingStreamingStep()
            }
            .show()
    }

    /** Sub-step of [showOnboardingCleanupStep]'s Cloud choice (#98 revision): lets the user pick
     *  Gemini or OpenAI directly, with neither marked "recommended" -- see the kdoc above for why
     *  no latency claim is made either way. Both are legitimate, inexpensive choices; picking
     *  whichever account the user already has (or wants to try) is a perfectly good reason. */
    private fun showOnboardingCloudProviderChoiceStep() {
        onboardingDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Step 4b of 5: Choose a cloud cleanup provider")
            .setMessage(
                "Gemini and OpenAI are both good, inexpensive choices for cleanup. Pick whichever " +
                    "you'd like to use — e.g. one you already have an account or API key for."
            )
            .setCancelable(false)
            .setPositiveButton("Gemini") { _, _ ->
                dismissOnboarding()
                enableOnboardingCleanupCloud(ProviderKind.GEMINI)
                promptOnboardingProviderKey(ProviderKind.GEMINI) { showOnboardingStreamingStep() }
            }
            .setNegativeButton("OpenAI") { _, _ ->
                dismissOnboarding()
                enableOnboardingCleanupCloud(ProviderKind.OPENAI)
                promptOnboardingProviderKey(ProviderKind.OPENAI) { showOnboardingStreamingStep() }
            }
            // Escape hatch (M12): a mistaken "Use cloud" tap on the previous step can otherwise
            // not be backed out. "Back" re-shows the cleanup step (Local/Skip still available); no
            // cloud config is seeded until a provider is actually picked above.
            .setNeutralButton("Back") { _, _ -> dismissOnboarding(); showOnboardingCleanupStep() }
            .show()
    }

    /**
     * Onboarding step 4's "Use on-device" branch.
     *
     * Ordering matters (#153): a non-free model must not be *selected* until its license is
     * accepted. This used to write the selection first and prompt afterwards, so cancelling
     * the license dialog left cleanup enabled and pointed at a model that was never
     * installed -- the exact slip F-Droid review caught in fdroiddata!42401.
     *
     * Sequencing matters too (#170): the caller used to invoke this and then advance the wizard
     * on the very next line, but for a not-yet-installed model this only *starts* an
     * asynchronous license dialog, so step 5 and FINISH SETUP stacked over an unanswered consent
     * prompt. [advance] is therefore run by [OnboardingCleanupLocalFlow] instead -- after the
     * license is answered, either way, and never before. See that object for the full rule.
     */
    private fun enableOnboardingCleanupLocal(model: Model, advance: () -> Unit) {
        OnboardingCleanupLocalFlow.start(
            isInstalled = ModelDownloader.isInstalled(this, model),
            commit = { commitOnboardingCleanupLocal(model) },
            advance = advance,
            requestConsent = { onAccepted, onDeclined ->
                downloadModelWithLicenseConsent(
                    model,
                    onStarted = {
                        onAccepted()
                        toast("Downloading ${model.name}...")
                    },
                    onDeclined = onDeclined,
                )
            },
        )
    }

    /** Persists the local-cleanup selection for [model]. Split out of
     *  [enableOnboardingCleanupLocal] so it can be gated behind license consent (#153). */
    private fun commitOnboardingCleanupLocal(model: Model) {
        prefs().edit()
            .putBoolean("use_post_processing", true)
            .putString(KEY_LOCAL_CLEANUP_MODEL_NAME, model.archive)
            .apply()
        // #37/#52 follow-up: add/update only the LOCAL floor entry, never overwrite the whole
        // chain (see ProviderChain.withLocalFloor's kdoc -- the same real regression Trevor hit
        // live applies here too, even though onboarding usually runs on a fresh chain).
        ProviderChainStore.save(this, ProviderChainStore.load(this).withLocalFloor(model.archive))
        CloudFeatureToggle.setCleanupEnabled(this, false)
        refresh()
    }

    /** The dialog in [showOnboardingCleanupStep] already explains that Cloud cleanup sends text
     *  off-device even when transcription stays local, so this also marks that one-time consent
     *  (#23) satisfied -- otherwise Settings' own toggle would show the same warning again right
     *  after onboarding finishes. [kind] is whichever provider the user picked in
     *  [showOnboardingCloudProviderChoiceStep] (Gemini or OpenAI) -- neither is defaulted to
     *  automatically; the model id used is that provider's catalog-recommended entry, falling
     *  back to its hardcoded default constant if the catalog is somehow unavailable. */
    private fun enableOnboardingCleanupCloud(kind: ProviderKind) {
        prefs().edit()
            .putBoolean("use_post_processing", true)
            .putBoolean(KEY_LOCAL_CLEANUP_CONSENT, true)
            .apply()
        // #37/#52 follow-up: seed a default entry for the chosen provider only if the chain has
        // no cloud-capable entry yet -- same non-destructive rule as
        // CleanupActivity.onSelectSimpleCleanup.
        val chain = ProviderChainStore.load(this)
        if (chain.capableEntriesFor(needsTranscription = false).none { it.kind != ProviderKind.LOCAL }) {
            val catalog = ModelCatalogStore.currentCatalog(this)
            // ModelUseCase.CLEANUP filter (#104): recommendedEntryFor(catalog, kind) alone
            // returns the top entry across ALL use cases for kind, so it could recommend a
            // transcription-only model (e.g. gpt-4o-transcribe) as the CLEANUP model this saves
            // to entry.model -- today it happens to pick the right one only because the cleanup
            // entry sorts first in BUNDLED_DEFAULT_MODEL_CATALOG's tier order, not because
            // anything enforces it. See CloudProviderActivity.kt's matching fix for the same bug.
            val model = ModelCatalogResolver.entriesFor(catalog, kind, ModelUseCase.CLEANUP).firstOrNull()?.modelId
                ?: when (kind) {
                    ProviderKind.GEMINI -> GeminiCleanupProvider.DEFAULT_MODEL
                    else -> PostProcessor.DEFAULT_MODEL
                }
            ProviderChainStore.save(this, ProviderChain(chain.entries + ProviderChainEntry(kind, model)))
        }
        CloudFeatureToggle.setCleanupEnabled(this, true)
        refresh()
    }

    /** Streaming live-preview onboarding step (#52, revised): a plain on/off toggle, not a
     *  Local/Cloud choice -- the streaming path is always on-device (#29), there is no cloud
     *  option to offer. "Skip (leave off)" is now the recommended choice: live preview runs a
     *  second on-device model continuously throughout the whole recording, competing for CPU
     *  with local (Parakeet) transcription right as that transcription needs to run at full
     *  speed the moment recording stops -- real, code-confirmed contention (see
     *  StreamingTranscriber's numThreads=2 decode running the entire time the user is talking),
     *  not a guess. This delays the whole pipeline (transcribe -> cleanup -> inject), which is
     *  what actually produces the "cleanup feels slower" perception some users report -- cleanup
     *  itself (a network call) isn't affected by local CPU load, but it starts later when local
     *  transcription had to compete with the streaming model for the phone's cores. */
    private fun showOnboardingStreamingStep() {
        markOnboardingStep(STEP_STREAMING)
        val recommended = STREAMING_MODEL_CATALOG.firstOrNull { it.recommended } ?: STREAMING_MODEL_CATALOG.first()
        onboardingDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Step 5 of 5: Show live text while you speak? (optional)")
            .setMessage(
                "Streaming preview shows your words appearing in the field as you talk, using a small " +
                    "on-device model — always local, nothing is ever sent anywhere for this. The final " +
                    "text after you stop recording still comes from your Transcription + Cleanup " +
                    "settings above; this only changes what's shown while recording.\n\n" +
                    "Recommended off: this model runs continuously while you talk, competing for your " +
                    "phone's CPU with transcription right when recording stops — it can make the final " +
                    "text feel slower to appear, especially if you're also using on-device " +
                    "transcription.\n\nTurning this on downloads \"${recommended.name}\" now."
            )
            .setCancelable(false)
            .setPositiveButton("Turn on") { _, _ ->
                dismissOnboarding()
                enableOnboardingStreaming(recommended)
                showOnboardingTryStep()
            }
            .setNegativeButton("Skip (recommended)") { _, _ -> dismissOnboarding(); showOnboardingTryStep() }
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
        markOnboardingStep(STEP_TRY)
        val testField = EditText(this).apply {
            hint = "Tap here, then use the floating button to dictate a test phrase"
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        val modelReady = onboardingTranscriptionModelReady()
        val message = if (modelReady) {
            "Setup is done. If the floating button is visible, tap it, speak a test phrase, and " +
                "tap it again to confirm the text lands in the field below."
        } else {
            "Setup is done, but your on-device model is still downloading in the background — " +
                "dictation won't work until that finishes. Feel free to close this now; the " +
                "floating button will start working as soon as the download completes, no need " +
                "to wait here."
        }
        // #103: without this, the overlay stays hidden the whole time this dialog is up -- #35's
        // "don't cover Settings switches while MainActivity is foregrounded" logic can't tell the
        // difference between "user is configuring a setting" and "user is being asked to tap the
        // icon this very dialog is testing", so the step was genuinely impossible to complete
        // without backing out of onboarding first. Reset to false on every dismissal path
        // (Finish setup, and the dialog's own onDismiss as a catch-all for back-press/outside-tap,
        // even though setCancelable(false) below blocks the common ones) so the override never
        // outlives this one step.
        WhisperAccessibilityService.setOverlayForceVisibleOverride(true)
        onboardingDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Try it out (optional)")
            .setMessage(message)
            .setView(testField)
            .setCancelable(false)
            .setPositiveButton("Finish setup") { _, _ -> finishOnboarding() }
            .setOnDismissListener { WhisperAccessibilityService.setOverlayForceVisibleOverride(false) }
            .show()
    }

    private fun selectOnboardingModel(archive: String) {
        prefs().edit().putString("model_name", archive).apply()
        WhisperAccessibilityService.instance?.reloadModel()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val KEY_LOCAL_CLEANUP_CONSENT = "local_cleanup_consent_seen"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        /** Instance-state key: whether the wizard already started this session (#80). */
        private const val STATE_ONBOARDING_INTRO_SHOWN = "state_onboarding_intro_shown"
        private const val KEY_STREAMING_PREVIEW = "streaming_preview_enabled"
        private const val KEY_STREAMING_MODEL_NAME = "streaming_model_name"
        private const val KEY_LOCAL_CLEANUP_MODEL_NAME = "local_cleanup_model_name"

        /** Furthest onboarding dialog reached, persisted so the callback-chained steps 4/5 resume
         *  at the right place after process death rather than restarting from the mode step (#L15).
         *  The earlier steps (intro/mic/accessibility) are already state-driven and resume on their
         *  own. Cleared in [finishOnboarding]. */
        private const val KEY_ONBOARDING_STEP = "onboarding_step"
        private const val STEP_MODE = "mode"
        private const val STEP_CLEANUP = "cleanup"
        private const val STEP_STREAMING = "streaming"
        private const val STEP_TRY = "try"

        /** Intent extra AdvancedActivity sets when handing off "Redo setup walkthrough" back to
         *  this Activity (#93) -- see [handleWalkthroughIntent]. */
        const val EXTRA_START_WALKTHROUGH = "start_walkthrough"
    }
}
