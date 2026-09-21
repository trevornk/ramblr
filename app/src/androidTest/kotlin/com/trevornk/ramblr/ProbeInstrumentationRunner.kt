package com.trevornk.ramblr

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import org.junit.AssumptionViolatedException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object ProbeRuntimeContext {
    lateinit var targetContext: Context
}

/**
 * Minimal target-process runner for the isolated R8 probe. It avoids AndroidJUnitRunner's optional
 * tracing dependency, which otherwise changes the target runtime before native validation begins.
 *
 * Instrumentation's base onCreate() is intentionally empty: custom runners must call start() to
 * create the framework thread which invokes onStart(). Status lines are deliberately small and
 * streamed so a host can distinguish entry, a bounded stage, and terminal completion.
 */
class ProbeInstrumentationRunner : Instrumentation() {
    private var runtimeArguments = Bundle.EMPTY
    private val terminalOnce = AtomicBoolean(false)
    private val stageToken = AtomicLong(0)
    @Volatile private var currentStage = "runner.onCreate"
    @Volatile private var stageDeadline = 0L
    @Volatile private var lastStageElapsedMs = 0L

    override fun onCreate(arguments: Bundle) {
        runtimeArguments = arguments
        super.onCreate(arguments)
        emitStage("runner.onCreate", "START", 0)
        start()
    }

    override fun onStart() {
        super.onStart()
        emitStage("runner.onStart", "START", 0)
        val selected = runtimeArguments.getString("stage") ?: "all"
        try {
            require(isValidStage(selected)) {
                "unknown stage '$selected'; expected one of all, asr, cleanup, provision, vad"
            }
            runStage("harness", HARNESS_BUDGET_MS) { RuntimeProbeEntry.run(targetContext, "harness") }
            runStage("voiceIme", VOICE_IME_BUDGET_MS) {
                RuntimeProbeEntry.run(targetContext, "voiceIme")
            }
            if (runtimeArguments.getString("voiceImeOnly") == "true") {
                terminal(Activity.RESULT_OK, "voiceIme", "PASS", "voiceImeProbe=PASS")
                return
            }
            if (selected == "all" || selected == "provision") {
                runStage("provision", PROVISION_BUDGET_MS) { RuntimeProbeEntry.run(targetContext, "provision") }
            }
            if (selected == "all" || selected == "asr") {
                runStage("asr", ASR_BUDGET_MS) { RuntimeProbeEntry.run(targetContext, "asr") }
            }
            if (selected == "all" || selected == "vad") {
                runStage("vad", VAD_BUDGET_MS) { RuntimeProbeEntry.run(targetContext, "vad") }
            }
            if (selected == "all" || selected == "cleanup") {
                runStage("cleanup", CLEANUP_BUDGET_MS) { RuntimeProbeEntry.run(targetContext, "cleanup") }
            }
            terminal(Activity.RESULT_OK, selected, "PASS", "nativeProbe=PASS")
        } catch (t: Throwable) {
            val status = when (t) {
                is AssumptionViolatedException -> "FAILED_PRECONDITION"
                is StageWatchdogExpired -> "WATCHDOG_TIMEOUT"
                else -> "FAIL"
            }
            Log.e(TAG, "native probe failed stage=$currentStage status=$status", t)
            terminal(
                Activity.RESULT_CANCELED,
                currentStage,
                status,
                "${t.javaClass.name}: ${t.message}",
                stageStartedAt = if (stageDeadline == 0L) null else stageDeadline - stageBudgetFor(currentStage),
            )
        }
    }

    private fun runStage(name: String, budgetMs: Long, body: () -> Unit) {
        check(!terminalOnce.get()) { "probe already reached a terminal result" }
        currentStage = name
        val token = stageToken.incrementAndGet()
        val started = SystemClock.elapsedRealtime()
        stageDeadline = started + budgetMs
        emitStage(name, "START", 0)
        val watchdog = Thread({
            val now = SystemClock.elapsedRealtime()
            val remaining = if (stageDeadline > now) stageDeadline - now else 0L
            try {
                Thread.sleep(remaining)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (stageToken.get() == token && !terminalOnce.get()) {
                val elapsed = SystemClock.elapsedRealtime() - started
                val stacks = Thread.getAllStackTraces().entries.joinToString("\n") { (thread, stack) ->
                    "thread=${thread.name} state=${thread.state}\n" + stack.joinToString("\n") { "  at $it" }
                }
                Log.e(TAG, "WATCHDOG stage=$name elapsedMs=$elapsed deadlineMs=$budgetMs\n$stacks")
                terminal(
                    Activity.RESULT_CANCELED,
                    name,
                    "WATCHDOG_TIMEOUT",
                    "deadlineMs=$budgetMs elapsedMs=$elapsed",
                )
            }
        }, "NativeProbeWatchdog-$name").apply { isDaemon = true }
        watchdog.start()
        try {
            body()
            if (terminalOnce.get()) throw StageWatchdogExpired(name)
            emitStage(name, "END", SystemClock.elapsedRealtime() - started)
        } finally {
            lastStageElapsedMs = SystemClock.elapsedRealtime() - started
            stageToken.compareAndSet(token, 0)
            watchdog.interrupt()
            stageDeadline = 0L
        }
    }

    // This runner is loaded before AndroidTest can provide Kotlin runtime classes. Keep stage
    // validation free of Kotlin collection initialization so runner.onStart remains reachable.
    private fun isValidStage(stage: String): Boolean =
        stage == "provision" || stage == "asr" || stage == "vad" || stage == "cleanup" || stage == "all"

    private fun emitStage(stage: String, status: String, elapsedMs: Long, detail: String? = null) {
        val result = Bundle().apply {
            putString("stage", stage)
            putString("status", status)
            putLong("elapsedMs", elapsedMs)
            detail?.let { putString("detail", it) }
        }
        Log.i(TAG, "STAGE stage=$stage status=$status elapsedMs=$elapsedMs${detail?.let { " detail=$it" } ?: ""}")
        sendStatus(1, result)
    }

    private fun terminal(code: Int, stage: String, status: String, detail: String, stageStartedAt: Long? = null) {
        if (!terminalOnce.compareAndSet(false, true)) return
        val result = Bundle().apply {
            putString("stage", stage)
            putString("status", status)
            putString("detail", detail)
            putLong("elapsedMs", stageStartedAt?.let { SystemClock.elapsedRealtime() - it } ?: lastStageElapsedMs)
        }
        Log.i(TAG, "TERMINAL stage=$stage status=$status detail=$detail")
        sendStatus(0, result)
        finish(code, result)
    }

    private class StageWatchdogExpired(stage: String) : IllegalStateException("watchdog expired in $stage")

    private fun stageBudgetFor(stage: String): Long = when (stage) {
        "harness" -> HARNESS_BUDGET_MS
        "voiceIme" -> VOICE_IME_BUDGET_MS
        "provision" -> PROVISION_BUDGET_MS
        "asr" -> ASR_BUDGET_MS
        "vad" -> VAD_BUDGET_MS
        "cleanup" -> CLEANUP_BUDGET_MS
        else -> 0L
    }

    private companion object {
        const val TAG = "NativeProbeRunner"
        const val HARNESS_BUDGET_MS = 10_000L
        const val VOICE_IME_BUDGET_MS = 10_000L
        const val PROVISION_BUDGET_MS = 1_440_000L
        const val ASR_BUDGET_MS = 60_000L
        const val VAD_BUDGET_MS = 60_000L
        const val CLEANUP_BUDGET_MS = 720_000L
    }
}
