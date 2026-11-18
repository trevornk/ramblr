package com.trevornk.ramblr

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pure-JVM tests for the haptic settings model, effect planner and start/stop decision (#385). */
// Robolectric only so the swallow-and-log path's android.util.Log is not the unmocked stub.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictationHapticsTest {

    private class FakeOutput(
        var caps: HapticCapabilities? = HapticCapabilities(hasAmplitudeControl = true, predefinedSupported = true),
        var throwOnPlay: Boolean = false,
    ) : HapticOutput {
        val played = mutableListOf<HapticEffectSpec>()
        override fun capabilities(): HapticCapabilities? = caps
        override fun play(spec: HapticEffectSpec) {
            if (throwOnPlay) throw IllegalStateException("boom")
            played += spec
        }
    }

    private fun haptics(settings: HapticSettings, output: FakeOutput) =
        DictationHaptics(settings = { settings }, output = { output })

    private val on = HapticSettings(enabled = true, strength = HapticStrength.MEDIUM, lengthMs = 25)

    // --- prefs ---

    @Test fun `defaults are off, medium, 25 ms`() {
        val s = HapticPrefs.load(FakeSharedPreferences())
        assertFalse(s.enabled)
        assertEquals(HapticStrength.MEDIUM, s.strength)
        assertEquals(25, s.lengthMs)
    }

    @Test fun `settings round trip`() {
        val prefs = FakeSharedPreferences()
        HapticPrefs.setEnabled(prefs, true)
        HapticPrefs.setStrength(prefs, HapticStrength.STRONG)
        HapticPrefs.setLengthMs(prefs, 40)
        assertEquals(HapticSettings(true, HapticStrength.STRONG, 40), HapticPrefs.load(prefs))
    }

    @Test fun `out-of-range stored length is clamped on read and write`() {
        val prefs = FakeSharedPreferences(mutableMapOf(HapticPrefs.KEY_LENGTH_MS to 5000))
        assertEquals(HapticPrefs.MAX_LENGTH_MS, HapticPrefs.load(prefs).lengthMs)
        prefs.edit().putInt(HapticPrefs.KEY_LENGTH_MS, -3).apply()
        assertEquals(HapticPrefs.MIN_LENGTH_MS, HapticPrefs.load(prefs).lengthMs)
        HapticPrefs.setLengthMs(prefs, 1)
        assertEquals(HapticPrefs.MIN_LENGTH_MS, prefs.getInt(HapticPrefs.KEY_LENGTH_MS, 0))
    }

    @Test fun `unknown stored strength falls back to medium`() {
        val prefs = FakeSharedPreferences(mutableMapOf(HapticPrefs.KEY_STRENGTH to "NOPE"))
        assertEquals(HapticStrength.MEDIUM, HapticPrefs.load(prefs).strength)
    }

    // --- planner ---

    @Test fun `amplitude control maps strength to amplitude and keeps length`() {
        val caps = HapticCapabilities(hasAmplitudeControl = true, predefinedSupported = true)
        val amps = HapticStrength.values().map {
            (HapticEffectPlanner.plan(on.copy(strength = it, lengthMs = 30), caps) as HapticEffectSpec.OneShot)
        }
        assertTrue(amps.all { it.durationMs == 30L })
        assertTrue(amps[0].amplitude < amps[1].amplitude && amps[1].amplitude < amps[2].amplitude)
        assertTrue(amps.all { it.amplitude in 1..255 })
        assertEquals(255, amps[2].amplitude)
    }

    @Test fun `no amplitude control uses predefined effect per strength`() {
        val caps = HapticCapabilities(hasAmplitudeControl = false, predefinedSupported = true)
        fun kind(s: HapticStrength) =
            (HapticEffectPlanner.plan(on.copy(strength = s), caps) as HapticEffectSpec.Predefined).kind
        assertEquals(PredefinedHaptic.TICK, kind(HapticStrength.LIGHT))
        assertEquals(PredefinedHaptic.CLICK, kind(HapticStrength.MEDIUM))
        assertEquals(PredefinedHaptic.HEAVY_CLICK, kind(HapticStrength.STRONG))
    }

    @Test fun `neither amplitude nor predefined falls back to plain duration`() {
        val caps = HapticCapabilities(hasAmplitudeControl = false, predefinedSupported = false)
        assertEquals(HapticEffectSpec.Duration(25), HapticEffectPlanner.plan(on, caps))
    }

    @Test fun `planner clamps an out-of-range length`() {
        val caps = HapticCapabilities(true, true)
        val spec = HapticEffectPlanner.plan(on.copy(lengthMs = 9999), caps) as HapticEffectSpec.OneShot
        assertEquals(HapticPrefs.MAX_LENGTH_MS.toLong(), spec.durationMs)
    }

    // --- decision ---

    @Test fun `default off never touches the output`() {
        val out = FakeOutput()
        val h = haptics(on.copy(enabled = false), out)
        h.onRecordingStarted(); h.onRecordingStopped()
        assertTrue(out.played.isEmpty())
    }

    @Test fun `start and stop each tick once when enabled`() {
        val out = FakeOutput()
        val h = haptics(on, out)
        h.onRecordingStarted()
        assertEquals(1, out.played.size)
        h.onRecordingStopped()
        assertEquals(2, out.played.size)
    }

    @Test fun `stop without a preceding started tick is silent`() {
        val out = FakeOutput()
        haptics(on, out).onRecordingStopped()
        assertTrue(out.played.isEmpty())
    }

    @Test fun `second stop for the same recording does not tick again`() {
        val out = FakeOutput()
        val h = haptics(on, out)
        h.onRecordingStarted(); h.onRecordingStopped(); h.onRecordingStopped()
        assertEquals(2, out.played.size)
    }

    @Test fun `disabled between start and stop suppresses the stop tick`() {
        val out = FakeOutput()
        var enabled = true
        val h = DictationHaptics(settings = { on.copy(enabled = enabled) }, output = { out })
        h.onRecordingStarted()
        enabled = false
        h.onRecordingStopped()
        assertEquals(1, out.played.size)
    }

    @Test fun `reset clears a pending start so a later stop is silent`() {
        val out = FakeOutput()
        val h = haptics(on, out)
        h.onRecordingStarted()
        h.reset()
        h.onRecordingStopped()
        assertEquals(1, out.played.size)
    }

    @Test fun `no vibrator or muted system means no tick`() {
        val out = FakeOutput(caps = null)
        val h = haptics(on, out)
        h.onRecordingStarted(); h.onRecordingStopped()
        assertTrue(out.played.isEmpty())
    }

    @Test fun `output exceptions are swallowed`() {
        val out = FakeOutput(throwOnPlay = true)
        val h = haptics(on, out)
        h.onRecordingStarted(); h.onRecordingStopped()
    }

    @Test fun `test button plays even when master switch is off`() {
        val out = FakeOutput()
        haptics(on.copy(enabled = false, strength = HapticStrength.STRONG), out).playTest()
        assertEquals(1, out.played.size)
        assertEquals(255, (out.played[0] as HapticEffectSpec.OneShot).amplitude)
    }

    @Test fun `test button respects muted system`() {
        val out = FakeOutput(caps = null)
        haptics(on, out).playTest()
        assertTrue(out.played.isEmpty())
    }

    /** Minimal in-memory [SharedPreferences] fake covering the types [HapticPrefs] stores. */
    private class FakeSharedPreferences(
        private val values: MutableMap<String, Any?> = mutableMapOf()
    ) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            override fun putString(key: String?, value: String?) = apply { pending[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { pending[key!!] = values }
            override fun putInt(key: String?, value: Int) = apply { pending[key!!] = value }
            override fun putLong(key: String?, value: Long) = apply { pending[key!!] = value }
            override fun putFloat(key: String?, value: Float) = apply { pending[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { pending[key!!] = value }
            override fun remove(key: String?) = apply { pending.remove(key) }
            override fun clear() = apply { values.clear() }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() { values.putAll(pending) }
        }
    }
}
