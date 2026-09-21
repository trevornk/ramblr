# Only the isolated runtimeProbe androidTest APK consumes this file.
# The runner executes this facade before any native/model stage, so preserve its standard name
# and implementation rather than relying on the target APK's independently optimized classpath.
-keep class kotlin.collections.SetsKt { *; }
