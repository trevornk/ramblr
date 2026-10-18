package com.trevornk.ramblr

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders every settings screen to a PNG so layout changes can be reviewed without a device.
 * Opt-in (RAMBLR_SCREENSHOTS=1) so normal test runs and CI never pay for it; output lands in
 * app/build/ui-screenshots/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-420dpi")
class SettingsScreenshotHarness {

    private val screenClasses: List<Class<out Activity>> = listOf(
        MainActivity::class.java,
        SetupActivity::class.java,
        TranscriptionActivity::class.java,
        CleanupActivity::class.java,
        LivePreviewActivity::class.java,
        CloudProviderActivity::class.java,
        InvocationActivity::class.java,
        DataLogsActivity::class.java,
        BehaviorActivity::class.java,
        OverlayAppearanceActivity::class.java,
        AudioFilesActivity::class.java,
        StyleManagerActivity::class.java,
    )

    /** Name -> launch intent; the sectioned screens each render every section on its own. */
    private fun screens(): List<Pair<String, android.content.Intent>> {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        return screenClasses.map { it.simpleName to android.content.Intent(app, it) } +
            BehaviorActivity.Section.entries.map { "Behavior-${it.name}" to BehaviorActivity.intent(app, it) } +
            InvocationActivity.Section.entries.map { "Invocation-${it.name}" to InvocationActivity.intent(app, it) } +
            DataLogsActivity.Section.entries.map { "DataLogs-${it.name}" to DataLogsActivity.intent(app, it) }
    }

    @Test fun light() = renderAll("light")

    @Config(qualifiers = "+night")
    @Test fun dark() = renderAll("dark")

    private fun renderAll(theme: String) {
        assumeTrue(System.getenv("RAMBLR_SCREENSHOTS") == "1")
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        if (!androidx.work.WorkManager.isInitialized()) {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        val out = File("build/ui-screenshots/$theme").apply { mkdirs() }
        for ((name, intent) in screens()) {
            try {
                @Suppress("UNCHECKED_CAST")
                val type = Class.forName(intent.component!!.className) as Class<Activity>
                val activity = Robolectric.buildActivity(type, intent).setup().get()
                save(capture(activity), File(out, "$name.png"))
                File(out, "$name.txt").writeText(outline(activity))
            } catch (e: Throwable) {
                File(out, "$name.error.txt").writeText(e.stackTraceToString())
            }
        }
    }

    @Test fun modelPickerLight() = renderModelPicker("light")

    @Config(qualifiers = "+night")
    @Test fun modelPickerDark() = renderModelPicker("dark")

    /** Cloud providers' "Add provider" dialog, scrolled content in full, to review how the model
     *  options (name, tier, price, description) read. */
    private fun renderModelPicker(theme: String) {
        assumeTrue(System.getenv("RAMBLR_SCREENSHOTS") == "1")
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val out = File("build/ui-screenshots/$theme").apply { mkdirs() }
        if (!androidx.work.WorkManager.isInitialized()) {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        val activity = Robolectric.buildActivity(CloudProviderActivity::class.java).setup().get()
        fun View.rows(): List<View> = buildList {
            if (isClickable && findViewWithTag<android.widget.TextView>("title")?.text?.toString() == "Add provider") add(this@rows)
            if (this@rows is ViewGroup) for (i in 0 until childCount) addAll(getChildAt(i).rows())
        }
        activity.findViewById<View>(android.R.id.content).rows().first().performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        // The dialog's own message panel is a ScrollView too; ours sits inside the custom panel.
        val content = dialog.window!!.decorView.findViewById<View>(android.R.id.custom)
            .findFirst(ScrollView::class.java)!!.getChildAt(0)
        val shot = captureView(content, activity)
        val bitmap = Bitmap.createBitmap(shot.width, shot.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.colorBackground, bg, true)
        canvas.drawColor(bg.data)
        canvas.drawBitmap(shot, 0f, 0f, null)
        save(bitmap, File(out, "Dialog-add-provider.png"))

        // The main screen's Dictation mode chooser: a plain single-choice dialog.
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        fun View.titled(title: String): View? {
            if (isClickable && findViewWithTag<android.widget.TextView>("title")?.text?.toString() == title) return this
            if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).titled(title)?.let { return it }
            return null
        }
        main.findViewById<View>(android.R.id.content).titled("Dictation mode")!!.performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val modeDialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        val decor = modeDialog.window!!.decorView
        val width = main.resources.displayMetrics.widthPixels * 9 / 10
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST),
        )
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        val modeBitmap = Bitmap.createBitmap(width, decor.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(modeBitmap))
        save(modeBitmap, File(out, "Dialog-dictation-mode.png"))
    }

    /** The style editor opened on each built-in, to review the prompt text a user sees there. */
    @Test fun styleEditors() {
        assumeTrue(System.getenv("RAMBLR_SCREENSHOTS") == "1")
        val out = File("build/ui-screenshots/style-editor").apply { deleteRecursively(); mkdirs() }
        val activity = Robolectric.buildActivity(StyleManagerActivity::class.java).setup().get()
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        fun ViewGroup.editButtons(): List<View> = (0 until childCount).flatMap { i ->
            when (val c = getChildAt(i)) {
                is com.google.android.material.button.MaterialButton -> if (c.text == "✎") listOf(c) else emptyList()
                is ViewGroup -> c.editButtons()
                else -> emptyList()
            }
        }
        val buttons = content.editButtons()
        CleanupPersonas.BUILT_IN.forEachIndexed { index, persona ->
            buttons[index].performClick()
            val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            val decor = dialog.window!!.decorView
            val width = activity.resources.displayMetrics.widthPixels * 9 / 10
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
            val bitmap = Bitmap.createBitmap(width, decor.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
            save(bitmap, File(out, "%d-%s.png".format(index, persona.key)))
            dialog.dismiss()
        }
    }

    @Test fun keyboardLight() = renderKeyboard("light")

    @Config(qualifiers = "+night")
    @Test fun keyboardDark() = renderKeyboard("dark")

    /** The keyboard panel: fresh install, each dropdown open, recording with sound, and the
     *  opposite of the system theme forced from the keyboard's own setting. */
    private fun renderKeyboard(theme: String) {
        assumeTrue(System.getenv("RAMBLR_SCREENSHOTS") == "1")
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val out = File("build/ui-screenshots/$theme").apply { mkdirs() }
        val editor = android.view.inputmethod.EditorInfo().apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            packageName = "harness.example"
        }
        fun panel(): Pair<RamblrImeService, View> {
            val service = Robolectric.buildService(RamblrImeService::class.java).create().get()
            val view = service.onCreateInputView()
            service.onStartInput(editor, false)
            service.onStartInputView(editor, false)
            return service to view
        }
        save(captureView(panel().second, app), File(out, "Keyboard-default.png"))

        app.getSharedPreferences("ramblr", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean(PostProcessingToggle.KEY, true).putBoolean("use_local", false).apply()
        KeyboardTranslation.setChosen(app, listOf("en", "de", "ru"))
        KeyboardTranslation.setTarget(app, "en")

        panel().second.let { view ->
            view.findViewWithTag<View>(RamblrImeService.STYLE_PICKER_TAG).performClick()
            save(captureView(view, app), File(out, "Keyboard-style-list.png"))
        }
        panel().second.let { view ->
            view.findViewWithTag<View>(RamblrImeService.TRANSLATE_PICKER_TAG).performClick()
            save(captureView(view, app), File(out, "Keyboard-translate-list.png"))
        }

        val (service, view) = panel()
        service.javaClass.getDeclaredMethod("renderState", ImeUiState::class.java)
            .apply { isAccessible = true }.invoke(service, ImeUiState.RECORDING)
        val wave = view.findFirst(MicWaveView::class.java)!!
        listOf(0.35f, 0.7f, 0.95f, 0.6f).forEach { wave.push(it) }
        save(captureView(view, app), File(out, "Keyboard-recording.png"))
        service.javaClass.getDeclaredMethod("renderState", ImeUiState::class.java)
            .apply { isAccessible = true }.invoke(service, ImeUiState.TRANSCRIBING)
        // A progress indicator only draws while attached to a visible window, so host the panel in
        // an activity for this one. Robolectric does not run its spin; this is the first frame.
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        host.setContentView(view)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        save(captureView(view, app), File(out, "Keyboard-processing.png"))

        val forced = if (theme == "light") KeyboardTheme.DARK else KeyboardTheme.LIGHT
        KeyboardTheme.set(app, forced)
        save(captureView(panel().second, app), File(out, "Keyboard-forced-${forced.key}.png"))
        KeyboardTheme.set(app, KeyboardTheme.SYSTEM)
    }

    /** Animation frames of each wave style at the runtime's 20 updates a second. The levels come
     *  from the real [AudioLevelMeter] fed synthetic audio: room noise throughout, with speech-like
     *  bursts after a pause, so the frames show the noise gate too. Stitched into GIFs outside the
     *  test. */
    @Test fun keyboardWaves() {
        assumeTrue(System.getenv("RAMBLR_SCREENSHOTS") == "1")
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val editor = android.view.inputmethod.EditorInfo().apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            packageName = "harness.example"
        }
        val meter = AudioLevelMeter()
        val random = java.util.Random(7)
        val levels = (0 until 96).map { i ->
            val t = i * 0.05
            val syllable = kotlin.math.max(0.0, kotlin.math.sin(t * 2 * Math.PI * 3.1))
            val speaking = t % 2.4 in 0.8..2.0
            val amplitude = if (speaking) 7000 * Math.pow(syllable, 0.7) * (0.6 + 0.4 * kotlin.math.sin(t * 5.3)) else 0.0
            val chunk = ByteArray(1600) // 50 ms of 16 kHz mono PCM
            for (n in 0 until 800) {
                val voice = amplitude * kotlin.math.sin(2 * Math.PI * 180 * (i * 800 + n) / 16000.0)
                val v = (random.nextGaussian() * 180 + voice).toInt().coerceIn(-32768, 32767)
                chunk[2 * n] = (v and 0xFF).toByte()
                chunk[2 * n + 1] = (v shr 8).toByte()
            }
            meter.offer(chunk, chunk.size, i * 50L) ?: 0f
        }
        for (style in MicWaveView.Style.entries) {
            val out = File("build/ui-screenshots/waves-${style.name.lowercase()}").apply { deleteRecursively(); mkdirs() }
            val service = Robolectric.buildService(RamblrImeService::class.java).create().get()
            val view = service.onCreateInputView()
            service.onStartInput(editor, false)
            service.onStartInputView(editor, false)
            service.javaClass.getDeclaredMethod("renderState", ImeUiState::class.java)
                .apply { isAccessible = true }.invoke(service, ImeUiState.RECORDING)
            val wave = view.findFirst(MicWaveView::class.java)!!
            wave.style = style
            levels.forEachIndexed { index, level ->
                wave.push(level)
                org.robolectric.shadows.ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS)
                save(captureView(view, app), File(out, "%02d.png".format(index)))
            }
        }
    }

    private fun captureView(view: View, context: android.content.Context, settle: View? = null): Bitmap {
        val width = context.resources.displayMetrics.widthPixels
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        // Let an easing animation reach its target before the real capture.
        settle?.let { v -> val scratch = Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)); repeat(40) { v.draw(scratch) } }
        val bitmap = Bitmap.createBitmap(width, view.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    /** Full scroll content, not just the first screenful. */
    private fun capture(activity: Activity): Bitmap {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val scroll = content.findFirst(ScrollView::class.java)
        val target: View = scroll?.getChildAt(0) ?: content
        val width = activity.resources.displayMetrics.widthPixels
        target.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        target.layout(0, 0, target.measuredWidth, target.measuredHeight)
        val bitmap = Bitmap.createBitmap(width, target.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.colorBackground, bg, true)
        canvas.drawColor(bg.data)
        target.draw(canvas)
        return bitmap
    }

    /** Text outline of the screen (every visible TextView, indented by depth), for reviewing
     *  what each screen contains without reading its code. */
    private fun outline(activity: Activity): String {
        val sb = StringBuilder()
        fun walk(v: View, depth: Int) {
            if (v.visibility != View.VISIBLE) return
            if (v is android.widget.TextView && v.text.isNotBlank()) {
                val kind = when (v.tag) { "title" -> "T"; "subtitle" -> "  s"; else -> if (v is android.widget.CompoundButton) "[x]" else "-" }
                sb.append("  ".repeat(depth / 3)).append(kind).append(' ').append(v.text.toString().replace('\n', ' ')).append('\n')
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        walk(activity.findViewById(android.R.id.content), 0)
        return sb.toString()
    }

    private fun <T : View> View.findFirst(type: Class<T>): T? {
        if (type.isInstance(this)) return type.cast(this)
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).findFirst(type)?.let { return it }
        return null
    }

    private fun save(bitmap: Bitmap, file: File) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
