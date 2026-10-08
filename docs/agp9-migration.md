# AGP 9 / Gradle 9 migration (Refs #250)

## Toolchain and upstream fix

The migration changes AGP 8.13.2 to 9.4.1, Gradle 8.13 to 9.8.1, and Kotlin
2.0.21 to 2.4.20 using AGP's built-in Kotlin support. JDK/JVM target 17,
compile/target SDK 36, min SDK 30, NDK 27.2.12479018, arm64-v8a, application ID,
versionName and versionCode are unchanged.

[Gradle PR #36227](https://github.com/gradle/gradle/pull/36227) fixes
[gradle/gradle#28974](https://github.com/gradle/gradle/issues/28974) by replacing
immutable-workspace atomic-move handling with `FineGrainedPersistentCache` locking.
Its merge commit `dc3705e6c217f0cb6879d7bdca023853123b4fae` is an ancestor of
[`v9.8.1`](https://github.com/gradle/gradle/compare/dc3705e6c217f0cb6879d7bdca023853123b4fae...v9.8.1).
This is taking the upstream fix, not adding a cache-clearing workaround.

[AGP 9.4's compatibility requirements](https://developer.android.com/build/releases/agp-9-4-0-release-notes)
are Gradle >=9.6 and JDK 17. [Gradle 9.8.1's tested matrix](https://docs.gradle.org/9.8.1/userguide/compatibility.html)
includes AGP 9.4 and Kotlin through 2.4.20-RC2.
[Kotlin's own matrix](https://kotlinlang.org/docs/gradle-configure-project.html)
currently lists fully supported maxima of Gradle 9.7.0 / AGP 9.3.1 for KGP
2.4.20, while explicitly permitting newer releases with possible warnings or
feature limitations. This triplet therefore is not claimed to fall entirely
inside Kotlin's fully-tested matrix; both flavor suites and real artifact gates
must pass. The KGP override follows [AGP's documented buildscript dependency approach](https://developer.android.com/build/releases/agp-9-0-0-release-notes#runtime-dependency-on-kotlin-gradle-plugin-upgrade),
without applying the incompatible `org.jetbrains.kotlin.android` plugin.

The complete wrapper is regenerated, including the JAR and both scripts. The
pinned distribution SHA-256 is `dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f`;
the official wrapper SHA-256 is `3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98`.

## API migration

- `kotlinOptions` becomes `kotlin.compilerOptions`, preserving JVM 17.
- Legacy `applicationVariants` / internal `BaseVariantOutputImpl` becomes
  `androidComponents.onVariants` / `VariantOutput.outputFileName`. The contract
  stays exactly `Ramblr-<version>-<flavor>-<type>.apk`.
- SDK discovery uses `androidComponents.sdkComponents.sdkDirectory`.
- Removed `Project.exec` uses `providers.exec`, consuming its result so native
  stripping executes and a nonzero process exit fails the task.
- The ONNX resolvable configuration is created explicitly, avoiding the delegated
  configuration name's collision with the generated Kotlin DSL accessor.

No legacy-DSL, built-in-Kotlin, R8, or dependency-verification opt-out is added.

## Changed defaults reviewed

See [AGP 9.0 behavior changes](https://developer.android.com/build/releases/agp-9-0-0-release-notes#android-gradle-plugin-behavior-changes),
[9.1 R8 changes](https://developer.android.com/build/releases/agp-9-1-0-release-notes#r8-changes),
and [9.2 R8 changes](https://developer.android.com/build/releases/agp-9-2-0-release-notes).

| Default change | Ramblr treatment |
| --- | --- |
| New DSL and built-in Kotlin enabled | Migrated to public APIs; no opt-outs. |
| NDK r28c, Java 11, target SDK follows compile SDK | Explicit existing NDK r27c, Java/Kotlin 17, target SDK 36 override these defaults. |
| AndroidX and AndroidJUnitRunner enabled by default | Already explicitly enabled/selected. |
| Unique library namespaces enforced | Adopted; one app module, no local Android library modules. |
| Dependency constraints only for device tests | Adopted; no custom cross-configuration constraints. Both flavor unit and device-test APK builds validate resolution. |
| Non-final app compile-time R class | Adopted; compilation and resource-backed unit tests validate existing usages. |
| Only tested build type gets unit-test components | Adopted; repository gates use both debug flavor suites, not release unit-test tasks. |
| Optimized resource shrinking | Already explicitly enabled on AGP 8.13; kept enabled, with artifact checks. |
| Strict R8 full-mode keep semantics | Adopted; native constructors/fields/methods and reflected members have explicit rules, checked in final DEX. |
| Missing keep files fail; non-optimized default ProGuard file disallowed | Existing keep file and optimized baseline retained. |
| Global options in dependency consumer rules disallowed/ignored | Adopted; inspect generated R8 configuration and verify final JNI/reflection boundaries. No broad keep/optimization opt-out added. |
| resValues and shaders disabled; explicit shader compiler required | Neither feature is used. |
| Providers disallowed through old source-set DSL | No such generated-source wiring exists. |
| R8 Kotlin null checks default to remove_message | Adopted; checks remain, exception messages may change. No app semantics depend on those compiler-generated messages. |
| Interface synthetic companion keep propagation removed, minimized L8 names | No separate pre-desugared library / applymapping pipeline; min SDK 30. Kotlin object/companion reflection is explicitly kept. |
| R8 source-file names become mapping IDs; repackaging to default package | Adopted for optimizable classes. Mapping artifacts retained; JNI/reflected class names remain fixed by rules. |
| Runtime-invisible annotations omitted by default | No runtime-reflection contract on invisible annotations; visible annotations continue under Android's optimized baseline. |
| AGP 9.4 dynamic-feature variant parity checks | No dynamic feature modules. |

AGP 9.3's new optimization DSL is available but the supported existing
`isMinifyEnabled`/`isShrinkResources`/`proguardFiles` DSL is retained to minimize
scope. Native C++ source, linker flags, library exclusions, JNI rules, and app
code are unchanged. A baseline warm-build failure exposed an existing CMake
cache leak: sherpa caches `BUILD_SHARED_LIBS=OFF`, so llama's next configure
fails because `GGML_BACKEND_DL` requires shared libraries. Llama now selects
`BUILD_SHARED_LIBS=ON` in its own directory scope; sherpa stays static. This
preserves the first clean build's library graph on subsequent configurations.

## Verification and release boundaries

Regenerate checksums with both flavor tests, debug/release APKs, and Android test
APKs, then rerun the same tasks with strict dependency verification. Generation
alone is not a verification pass. Review shared artifact hashes against the old
metadata and inspect newly trusted artifacts. Preserve all previously trusted
coordinates and their unchanged hashes rather than deleting platforms/configurations
not exercised on the migration host. Linux and Windows AAPT2 artifacts are fetched
from Google Maven, checked against its published SHA-1, and pinned with SHA-256;
Linux CI still verifies the pins through ordinary strict resolution.

Run `python -m unittest discover -s tools -p 'test_*.py'` and
`python tools/verify_r8_release.py --source` plus the checker's paired release-APK
and mapping arguments. Release-helper fixtures derive their APK version from the
source and isolate HOME so a developer's real SDK cannot override fixture tools;
these are hermetic control-flow tests, not proof of real release signing.
Kotlin 2.4 rejects the deprecated `createTempDir`; test harnesses now use
`createTempDirectory(...).toFile()`. JSON key assertions use Android's supported
`keys()` iterator rather than the standalone JVM JSON library's `keySet()`;
the assertions still compare the complete key set. No production behavior changes.

Neither build contains a release key unless the existing machine-local signing
configuration is supplied. With no signing configuration, inspect actual output
with `apksigner`; do not infer signing from old comments. Published signing
continuity must be verified separately by the release owner. Do not copy a
keystore or weaken `cut-release.sh` preflight to test this migration at an
already-published version.

Reproducibility requires separate clean builds of the exact candidate commit and
comparison of the complete unsigned APK SHA-256, not just native libraries or a
green workflow named "Reproducible release build". PR evidence records exact run
IDs, hashes, baseline/candidate artifact comparisons, and scoped device results.
