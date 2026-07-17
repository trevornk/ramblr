import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional self-hosted OmniRoute-style cleanup gateway (see OmniRoute.kt / ADR-0001). Never
// committed: read from local.properties (gitignored, machine-local) so a public clone builds
// fine with this feature simply dormant. Set OMNIROUTE_BASE_URL=https://your-gateway/v1 in your
// own local.properties to enable it for your own builds only.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val omniRouteBaseUrl: String = (localProperties.getProperty("OMNIROUTE_BASE_URL") ?: "").trim()

// onnxruntime for the sherpa-onnx speech-to-text native build (F-Droid prep, #36): the
// official Microsoft artifact on Maven Central (a source F-Droid trusts, unlike GitHub
// Releases). 1.24.3 is the exact version sherpa-onnx 1.13.3 builds/tests against (its
// build-android-arm64-v8a.sh default) and byte-for-byte the libonnxruntime.so the old
// prebuilt AAR shipped. Its .so is packaged into the APK by AGP; its headers + .so are
// also extracted (see extractOnnxRuntimeNative below) for the from-source JNI link.
val onnxRuntimeVersion = "1.24.3"

// A separate resolvable configuration holding just the onnxruntime AAR, so the native
// build can unzip its headers/ and arm64-v8a/libonnxruntime.so without disturbing the
// normal `implementation` classpath.
val onnxRuntimeAar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// Release signing (F-Droid reproducible-build requirement, MR !42401): keystore.properties is
// gitignored and machine-local, holding paths/passwords for the real release key. A public
// clone or CI run without it still builds fine -- the release variant just falls back to the
// debug signing config, producing an unsigned-for-distribution-but-buildable APK. The actual
// key material lives only in 1Password ("Ramblr Android Release Signing Key", vault AI Server)
// and the release-signed APK that F-Droid verifies against is published to GitHub Releases.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.trevornk.ramblr"
    compileSdk = 34
    ndkVersion = "27.2.12479018"

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            // F-Droid reproducible-build requirement (MR !42401): AGP embeds a
            // META-INF/version-control-info.textproto file recording the exact git commit hash
            // (and whether the tree is clean) into the APK whenever it can detect a git repo --
            // a separate feature from the dependenciesInfo{} block above (that one is Play
            // Store's dependency-transparency block; this is AGP's distinct VCS-info feature).
            // This broke v1.0.20's reproducibility: an isolated `git worktree` checkout (used to
            // build a byte-exact release APK for an already-tagged commit without disturbing the
            // main working tree) has a `.git` *file* pointing back to the main repo rather than a
            // `.git` *directory`, so AGP's git detection there failed outright (embedding
            // "generate_error_reason: NO_VALID_GIT_FOUND"), while F-Droid's own from-source clone
            // correctly detected git and embedded a real revision block -- two different bytes
            // for genuinely identical app code and source commit. Disabling this entirely (the
            // documented fix per F-Droid's own guidance, since this app's release process doesn't
            // guarantee every local/CI build environment has an identical git metadata layout at
            // build time) removes that non-deterministic input, the same fix class as pinning
            // GGML_COMMIT for the native library.
            vcsInfo.include = false
        }
    }

    defaultConfig {
        applicationId = "com.trevornk.ramblr"
        minSdk = 30
        targetSdk = 34
        versionCode = 23
        versionName = "1.0.20"

        buildConfigField("String", "OMNIROUTE_BASE_URL", "\"$omniRouteBaseUrl\"")

        ndk { abiFilters += "arm64-v8a" }
    }

    // Distribution split (self-update mechanism, Google Play policy compliance): Google Play's
    // Developer Program Policy explicitly bans an app updating itself by any method other than
    // Play's own mechanism, and Trevor wants to keep pursuing both Play and F-Droid listings, so
    // the self-update notify/auto-update code must not just be *disabled* in those builds -- it
    // must be physically absent from the compiled classes. buildConfigField alone can't do that
    // (a boolean flag still ships the reachable code); the actual isolation is the flavor-specific
    // source set (app/src/github/kotlin/...), which the "github" flavor's compile task includes
    // and the "storefront" flavor's compile task never even sees. This buildConfigField is kept
    // anyway as a cheap, greppable belt-and-suspenders signal for anyone reading BuildConfig, not
    // as the actual isolation mechanism -- see AGENTS note in SelfUpdateChecker.kt.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE_ENABLED", "true")
        }
        create("storefront") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE_ENABLED", "false")
        }
    }

    // externalNativeBuild's cmake block belongs directly under defaultConfig (arguments passed to
    // every native build regardless of flavor/build type) -- kept here, right after defaultConfig
    // and the flavor block above, rather than re-nested inside defaultConfig itself, since AGP
    // resolves this the same either way and this reads more clearly next to the flavor split it's
    // adjacent to.
    defaultConfig {
        externalNativeBuild {
            cmake {
                abiFilters += "arm64-v8a"
                // Point sherpa-onnx's from-source build at the Maven onnxruntime AAR's
                // extracted headers + arm64 .so (see extractOnnxRuntimeNative below and
                // src/main/cpp/sherpa_onnx/CMakeLists.txt). Uses toString() rather than
                // .get().asFile so the path resolves lazily at task-graph time.
                val onnxDir = layout.buildDirectory.dir("onnxruntime-$onnxRuntimeVersion")
                arguments += "-DSHERPA_ONNXRUNTIME_INCLUDE_DIR=${onnxDir.get().asFile}/headers"
                arguments += "-DSHERPA_ONNXRUNTIME_LIB_DIR=${onnxDir.get().asFile}/jni/arm64-v8a"
            }
        }
    }

    // Both native components are built from source via CMake. AGP allows only one
    // externalNativeBuild.cmake.path per module, so a thin root CMakeLists.txt
    // add_subdirectory()s both: llama_cleanup (on-device LLM cleanup, #37, against the
    // ../llama.cpp submodule) and sherpa_onnx (speech-to-text, F-Droid prep, against the
    // ../sherpa-onnx submodule). See src/main/cpp/CMakeLists.txt.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    // The onnxruntime AAR bundles arm64-v8a/libonnxruntime4j_jni.so -- the JNI glue for
    // onnxruntime's *own* Java API, which the app never touches (sherpa-onnx calls the C++
    // API directly). Drop it so only the two .so files actually used ship in the APK.
    packaging {
        jniLibs {
            excludes += "**/libonnxruntime4j_jni.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    @Suppress("DEPRECATION")
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { buildConfig = true }

    testOptions { unitTests { isIncludeAndroidResources = true } }

    // Names the built APK "Ramblr-<versionName>-<flavor>-<buildType>.apk" (e.g.
    // Ramblr-1.0.10-github-debug.apk) instead of Gradle's generic default
    // "app-github-debug.apk"/"app-storefront-release.apk", so a file downloaded from GitHub
    // Releases or shared directly is recognizable by filename alone. The flavor name was added
    // alongside the "distribution" flavor split (self-update mechanism) -- before that split
    // there was only ever one flavor-less variant, so this used to just be
    // "Ramblr-<versionName>-<buildType>.apk"; anything reading that old filename pattern (CI
    // artifact globs, the release workflow) needs updating in lockstep with this rename.
    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "Ramblr-${versionName}-${flavorName}-${buildType.name}.apk"
        }
    }

    // F-Droid reproducible-build requirement (MR !42401): AGP's dependency metadata block
    // embeds the exact git revision the APK was built from (and the Play Store SDK/library
    // dependency list) into a signed block inside the APK. F-Droid's own from-source rebuild
    // is a *different* checkout of the *same* tagged commit, so if the git working tree wasn't
    // bit-for-bit identical at build time (e.g. a version-bump commit landed on the branch tip
    // after this APK was actually built, as happened releasing v1.0.20 -- see the "revision"
    // mismatch in the failed fdroid build job), AGP embeds a different revision string than
    // what F-Droid's rebuild embeds, and the reproducibility byte-comparison fails even though
    // the actual app code is identical. Disabling this block removes that non-deterministic
    // input entirely -- mirrors how GGML_COMMIT was pinned to "unknown" for the native library
    // for the exact same class of problem (a build-environment-dependent string leaking into
    // the compiled artifact). This block also 100% doesn't matter for GitHub/F-Droid
    // distribution -- it exists for Play Store's dependency transparency feature, which this
    // app doesn't use (see #99, Play Store distribution is still just a pre-effort evaluation).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha07")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Packages arm64-v8a/libonnxruntime.so into the APK; the sherpa-onnx JNI lib (built
    // from source) links against it at runtime. See onnxRuntimeVersion above.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:$onnxRuntimeVersion")
    // Same artifact, resolved into a private configuration purely so the native build can
    // read its headers + .so (the `@aar` classifier keeps transitive deps out).
    onnxRuntimeAar("com.microsoft.onnxruntime:onnxruntime-android:$onnxRuntimeVersion@aar")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// Unzips the onnxruntime AAR's C/C++ headers and arm64-v8a libonnxruntime.so into
// build/onnxruntime-<version>/ so the sherpa-onnx from-source CMake build can compile and
// link against them (see src/main/cpp/sherpa_onnx/CMakeLists.txt). Hooked onto the CMake
// configure/build tasks below (not preBuild) so it runs only for assemble*, never for
// testDebugUnitTest, which doesn't touch the native build.
val onnxRuntimeExtractDir = layout.buildDirectory.dir("onnxruntime-$onnxRuntimeVersion")
val extractOnnxRuntimeNative = tasks.register<Copy>("extractOnnxRuntimeNative") {
    description = "Extracts onnxruntime $onnxRuntimeVersion headers + arm64-v8a .so for " +
        "the sherpa-onnx from-source native build (F-Droid prep, #36)."
    from(onnxRuntimeAar.elements.map { it.map { artifact -> zipTree(artifact) } }) {
        include("headers/**", "jni/arm64-v8a/libonnxruntime.so")
    }
    into(onnxRuntimeExtractDir)
}

tasks.matching {
    it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake")
}.configureEach {
    dependsOn(extractOnnxRuntimeNative)
}

// --- Post-link .comment stripping (F-Droid MR fdroid/fdroiddata!42401 follow-up, 2026-07-16) --
//
// Root cause: F-Droid's Vagrant buildserver and GitHub Actions' ubuntu-latest runner produce
// byte-different native .so files even from an identical source commit + pinned NDK version.
// Diffed with llvm-readelf -S against a real failed F-Droid buildserver artifact vs. a GitHub
// Actions build of the same commit: every section matched except a single ELF `.comment`
// (SHF_MERGE|SHF_STRINGS) section, which differs in size/content because each host's linker
// pulls a different prebuilt NDK crt/libunwind object first, appending a distinct linker-ident
// string (+bolt/-bolt, +mlgo/-mlgo flags baked in per build host, not per source or compiler
// invocation this build controls). Verified fix: `llvm-strip --strip-all` on both hosts'
// output produces byte-identical libraries (sha256-matched for all 7 native libs Ramblr ships).
//
// TWO THINGS THAT DID NOT WORK, kept here so a future pass doesn't repeat them:
// 1. `-Wl,--strip-all` as a CMAKE_SHARED_LINKER_FLAGS entry (src/main/cpp/CMakeLists.txt) --
//    this is a LINKER flag and only strips .symtab/.strtab, never touches .comment (confirmed:
//    a real MR pipeline run with only this flag still failed with all 7 libs differing).
// 2. A CMake-level add_custom_target(... ALL) with a llvm-strip POST_BUILD step -- CMake's own
//    "ALL" target is never invoked by AGP: android_gradle_build.json's buildTargetsCommandComponents
//    shows AGP calls `ninja <explicit target list>`, never `ninja all`, so any CMake target not
//    in that explicit list (which only ever contains the actual shared-library targets AGP
//    needs to package) silently never runs, however it's declared.
//
// Actual fix: a real strip pass over AGP's OWN merged-native-libs intermediate directory,
// hooked as a Gradle task between mergeNativeLibs (creates the merged tree) and
// stripDebugSymbols (AGP's own debug-symbol strip, further downstream) so this runs on every
// release variant's native libs before packaging, regardless of which CMake targets AGP chose
// to invoke that run.
//
// NDK host-tag detection: this must resolve on both a macOS dev machine (darwin-x86_64) and
// Linux CI/F-Droid buildservers (linux-x86_64) -- os.name is the standard Gradle-visible JVM
// property for this, no NDK-side "current host" indirection exists to query instead.
val ndkHostTag = when {
    org.gradle.internal.os.OperatingSystem.current().isMacOsX -> "darwin-x86_64"
    org.gradle.internal.os.OperatingSystem.current().isLinux -> "linux-x86_64"
    else -> "windows-x86_64"
}
val ramblrLlvmStrip = android.sdkDirectory.resolve(
    "ndk/${android.ndkVersion}/toolchains/llvm/prebuilt/$ndkHostTag/bin/llvm-strip"
)
tasks.matching { it.name.matches(Regex("merge.*ReleaseNativeLibs")) }.configureEach {
    doLast {
        if (!ramblrLlvmStrip.exists()) {
            logger.warn("Ramblr reproducible-build strip skipped: llvm-strip not found at $ramblrLlvmStrip")
            return@doLast
        }
        outputs.files.files.forEach { outDir ->
            outDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".so") }
                .forEach { soFile ->
                    exec {
                        commandLine(ramblrLlvmStrip.absolutePath, "--strip-all", soFile.absolutePath)
                    }
                }
        }
    }
}

// Manual dev tool (issue #2): compares PostProcessor prompts against the real OpenAI API.
// Deliberately NOT a dependency of `test`/`check`/`build` — it costs real API credits and
// requires OPENAI_API_KEY, so it must only ever run when a developer invokes it directly.
// Usage: OPENAI_API_KEY=sk-... ./gradlew runEvalHarness --args="SIMPLE_PROMPT,DEV_PROMPT"
// See the "Prompt eval harness" section of README.md.
//
// Task names became flavor-qualified once the "distribution" flavor dimension (self-update
// mechanism) was added -- plain "testDebugUnitTest"/"compileDebugUnitTestKotlin" no longer
// resolve. PostProcessor (what this tool actually exercises) lives in the common source set,
// unaffected by which flavor's test task runs it, so "Github" here is an arbitrary but stable
// pick, not a meaningful dependency on the github flavor's self-update code.
tasks.register<JavaExec>("runEvalHarness") {
    group = "verification"
    description = "Manual dev tool: runs eval_samples/ through PostProcessor prompts against " +
        "the real OpenAI API and writes a before/after report. Costs real API credits; " +
        "not part of build/test/check."
    dependsOn("compileGithubDebugUnitTestKotlin")
    mainClass.set("com.trevornk.ramblr.tools.EvalHarnessKt")
    classpath = tasks.named<Test>("testGithubDebugUnitTest").get().classpath
}

// Native libs (#36/#37, F-Droid prep): both the speech-to-text lib (libsherpa-onnx-jni.so,
// against the ../sherpa-onnx submodule) and the on-device LLM cleanup shim (against the
// ../llama.cpp submodule) are built from source via the externalNativeBuild/cmake block in
// the `android {}` block above (root CMakeLists at src/main/cpp/CMakeLists.txt). sherpa-onnx
// used to be fetched as a prebuilt release AAR from GitHub Releases, but F-Droid disallows
// prebuilt binaries from untrusted sources, so that fetchSherpaOnnxNativeLibs task and its
// SHA-256 pin were removed in favour of this source build. onnxruntime now comes from Maven
// Central (see onnxRuntimeVersion / extractOnnxRuntimeNative above). See
// app/src/main/cpp/sherpa_onnx/README.md and llama_cleanup/README.md for the full setup.
