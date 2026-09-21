# Isolated runtimeProbe target only; this is not included in production release R8 rules.
# Instrumentation runs in the target process, while AndroidTest dependencies may be de-duplicated
# against that target. Keep one tiny Java static boundary and move all production API calls into
# the target graph behind it. Do not keep production implementation classes for AndroidTest.
-keep class com.trevornk.ramblr.RuntimeProbeEntry {
    public static void run(android.content.Context, java.lang.String);
}
