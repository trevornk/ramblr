# Isolated runtimeProbe target only; this is not included in production release R8 rules.
# Instrumentation runs in the target process, while AndroidTest dependencies may be de-duplicated
# against that target. Preserve only the exact Kotlin facade proven missing in the failed probe8
# smoke. This is not a general AndroidTest classpath closure rule; fresh device entry smoke remains
# required to expose any further missing dependencies.
-keep class kotlin.collections.SetsKt { *; }
