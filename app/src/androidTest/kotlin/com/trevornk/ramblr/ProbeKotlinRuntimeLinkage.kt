package com.trevornk.ramblr

/**
 * Executes before any model/native stage and pins the AndroidTest Kotlin runtime contract.
 *
 * The runner lives in the instrumentation APK while the target is separately R8-optimized. Keep
 * this isolated-harness check here rather than keeping Kotlin classes in production R8 rules.
 */
object ProbeKotlinRuntimeLinkage {
    fun verify() {
        val stages = setOf("all", "asr", "cleanup", "provision", "vad")
        check(stages.contains("all")) { "Kotlin set runtime returned an invalid stage set" }
        check(Class.forName("kotlin.collections.SetsKt").name == "kotlin.collections.SetsKt") {
            "Kotlin SetsKt facade is not loadable from the probe test APK"
        }
    }
}
