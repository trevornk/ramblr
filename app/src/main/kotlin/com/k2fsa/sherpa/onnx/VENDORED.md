# Vendored sherpa-onnx Kotlin bindings

These `com.k2fsa.sherpa.onnx.*` files are copied verbatim from upstream
[k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), not written for this app. They are the
Kotlin/JNI bindings for the native libraries (`libsherpa-onnx-jni.so`, `libonnxruntime.so`) that
`app/build.gradle.kts`'s `fetchSherpaOnnxNativeLibs` task downloads at build time. The bindings and
the native libraries **must stay in lockstep** — a JNI method signature mismatch between them is an
`UnsatisfiedLinkError` at runtime, not a compile error.

## Provenance

- **Upstream tag:** `v1.13.3`
- **Upstream source path:** `sherpa-onnx/kotlin-api/`
- **Pinned by:** `sherpaOnnxVersion = "1.13.3"` in `app/build.gradle.kts` (same version whose AAR the
  build fetches and SHA-256-pins for the `.so` files these bindings call into).

## When bumping `sherpaOnnxVersion`

1. Re-vendor these `.kt` files from the matching upstream tag (`sherpa-onnx/kotlin-api/` at that tag).
2. Update `sherpaOnnxVersion` **and** `sherpaOnnxAarSha256` in `app/build.gradle.kts`.
3. Update the tag recorded above.

Recorded to satisfy security audit L-7 (vendored code lacking recorded provenance).
