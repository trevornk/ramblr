package com.trevornk.ramblr

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/**
 * #256 settings screen: the per-app exclusion list picker. Manual exact-package entry rather than
 * a device app picker on purpose -- listing every installed app requires either
 * `QUERY_ALL_PACKAGES` or a broad `<queries>` declaration, both of which are exactly the kind of
 * package-visibility footprint this app has deliberately avoided everywhere else (see
 * AndroidManifest's `ProcessTextActivity` comment on why its own `<queries>` are scoped as
 * narrowly as possible). An exact-package text field needs neither, matches the issue's own
 * "explicit list, not wildcard" product decision, and a user excluding "my banking app" can always
 * find its package name via Settings > Apps > (app) > Advanced, or a long-press "App info" -- the
 * one extra step buys real package-visibility restraint.
 *
 * This screen owns no gating logic itself -- it's a thin CRUD layer over [PerAppExclusionStore];
 * every actual gate lives at the call sites named in the issue (ring visibility, `onTap`/
 * `requestToggleRecording`, final injection, and the IME's own editor-package check).
 */
class ExclusionListActivity : BaseSettingsActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        root.addView(TextView(this).apply {
            text = "App exclusions"
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        })

        // The privacy-copy constraint from #256's issue body, verbatim in spirit: this is a
        // behavioral opt-out, not a way to detach the accessibility service, and getting that
        // distinction wrong here would misrepresent a security property Ramblr doesn't provide.
        root.addView(TextView(this).apply {
            text = "This suppresses Ramblr's behavior in the apps you list below — no new " +
                "recording starts, and no dictated text is inserted while one of these apps is " +
                "in the foreground. It does not detach the accessibility service: Ramblr stays " +
                "enabled and bound like normal, and this list does not bypass or weaken your " +
                "bank's own security detections. If you want Ramblr fully off before opening a " +
                "sensitive app, use Settings > Invocation > \"Turn Ramblr off\", or switch to " +
                "Floating icon mode's off switch."
            textSize = 13f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(8))
        })

        // Honest limit on the ring-hide side specifically (#256's own open question, answered
        // "hidden" but only ever checked on-demand -- see ExclusionGating's kdoc). Surfaced here
        // rather than left implicit, since a user staring at a ring that's slow to disappear after
        // switching apps deserves to know why, not conclude the feature is broken.
        root.addView(TextView(this).apply {
            text = "The floating ring hides for an excluded app the next time Ramblr checks " +
                "(switching apps, screen on/off, or unlocking) — not necessarily the instant you " +
                "switch. Starting a new recording and inserting the finished text are always " +
                "blocked immediately, with no such delay."
            textSize = 13f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(20))
        })

        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), 0, dp(24), dp(16))
        }
        val packageInput = EditText(this).apply {
            hint = "Exact package name, e.g. com.example.bank"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val addButton = MaterialButton(this).apply {
            text = "Add"
            setOnClickListener {
                val pkg = packageInput.text?.toString()?.trim().orEmpty()
                if (pkg.isBlank()) {
                    toast("Enter a package name first")
                } else if (!isPlausiblePackageName(pkg)) {
                    toast("Doesn't look like a package name (e.g. com.example.app)")
                } else {
                    PerAppExclusionStore.setExcluded(this@ExclusionListActivity, pkg, excluded = true)
                    packageInput.setText("")
                    refresh()
                }
            }
        }
        addRow.addView(packageInput)
        addRow.addView(addButton)
        root.addView(addRow)

        emptyLabel = TextView(this).apply {
            text = "No apps excluded yet"
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), dp(8), dp(24), dp(16))
        }
        root.addView(emptyLabel)

        listContainer = vertical(0)
        root.addView(listContainer)

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

    private fun refresh() {
        listContainer.removeAllViews()
        val excluded = PerAppExclusionStore.exclusions(this).sorted()
        emptyLabel.visibility = if (excluded.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        for (pkg in excluded) listContainer.addView(buildExclusionRow(pkg))
    }

    private fun buildExclusionRow(pkg: String): android.view.View {
        val removeBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "🗑"
            textSize = 16f
            setOnClickListener {
                PerAppExclusionStore.setExcluded(this@ExclusionListActivity, pkg, excluded = false)
                refresh()
            }
        }
        return settingsRow(pkg, "Ramblr stays out of the way in this app", removeBtn)
    }

    /** Loose sanity check, not a real validator: catches an obviously-wrong entry (an app label
     *  typed by mistake, whitespace, etc.) without pretending to verify the package is installed
     *  -- the store intentionally accepts any package name, installed or not, since excluding an
     *  app before installing it is a reasonable thing to want to do ahead of time. */
    private fun isPlausiblePackageName(value: String): Boolean =
        value.contains('.') && value.none { it.isWhitespace() }
}
