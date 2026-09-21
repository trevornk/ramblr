package com.trevornk.ramblr

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.util.Log

object ProbeRuntimeContext {
    lateinit var targetContext: Context
}

/**
 * Minimal target-process runner for the isolated R8 probe. It avoids AndroidJUnitRunner's optional
 * tracing dependency, which otherwise changes the target runtime before native validation begins.
 */
class ProbeInstrumentationRunner : Instrumentation() {
    override fun onStart() {
        super.onStart()
        ProbeRuntimeContext.targetContext = targetContext
        val result = Bundle()
        try {
            VoiceImeDeviceMetadataTest().compiledVoiceSubtypeIsDiscoverableAndStandalone()
            if (arguments.getString("voiceImeOnly") == "true") {
                result.putString("voiceImeProbe", "PASS")
            } else {
                NativeProbeModelProvisioningTest().downloadsVerifiedModelsOnlyInsideNonDebuggableProbe()
                AsrDecodeBenchmark().benchmarkDecode()
                NativeProbeVadTest().emitsSpeechSegmentFrom512SampleFrames()
                LocalNumericCleanupDeviceTest().numericPreservationThroughRealLocalModel()
                NativeProbeEvidenceAndCleanupTest().reportsNativeResultsAndRemovesProbeModels()
                result.putString("nativeProbe", "PASS")
            }
            sendStatus(0, result)
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            Log.e(TAG, "native probe failed", t)
            result.putString("nativeProbe", "FAIL: ${t.javaClass.name}: ${t.message}")
            sendStatus(0, result)
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private companion object { const val TAG = "NativeProbeRunner" }
}
