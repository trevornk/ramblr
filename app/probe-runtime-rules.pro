# Isolated runtimeProbe target only; this is not included in production release R8 rules.
# Instrumentation runs in the target process, while AndroidTest dependencies are de-duplicated
# against that target. Preserve the exact Kotlin facade proven missing in the failed probe8 smoke.
-keep class kotlin.collections.SetsKt { *; }
