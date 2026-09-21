# Isolated runtimeProbe target only; this is not included in production release R8 rules.
# Instrumentation runs in the target process, while AndroidTest dependencies may be de-duplicated
# against that target. Preserve only facades proven missing by device entry smoke: SetsKt at runner
# linkage and RangesKt in the runner watchdog's coerceAtLeast path. This is not a general
# AndroidTest classpath closure rule; fresh device entry smoke remains required to expose any
# further missing dependencies.
-keep class kotlin.collections.SetsKt { *; }
-keep class kotlin.ranges.RangesKt { *; }
