import java.net.URI
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kafkasl.phonewhisper"
    compileSdk = 34
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.kafkasl.phonewhisper"
        minSdk = 30
        targetSdk = 34
        versionCode = 2
        versionName = "0.3.0"

        ndk { abiFilters += "arm64-v8a" }

        externalNativeBuild {
            cmake {
                abiFilters += "arm64-v8a"
            }
        }
    }

    // Builds the llama_cleanup JNI shim (#37) against the llama.cpp submodule pinned in
    // ../llama.cpp (see llama_cleanup/CMakeLists.txt's add_subdirectory path).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/llama_cleanup/CMakeLists.txt")
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
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// Manual dev tool (issue #2): compares PostProcessor prompts against the real OpenAI API.
// Deliberately NOT a dependency of `test`/`check`/`build` — it costs real API credits and
// requires OPENAI_API_KEY, so it must only ever run when a developer invokes it directly.
// Usage: OPENAI_API_KEY=sk-... ./gradlew runEvalHarness --args="SIMPLE_PROMPT,DEV_PROMPT"
// See the "Prompt eval harness" section of README.md.
tasks.register<JavaExec>("runEvalHarness") {
    group = "verification"
    description = "Manual dev tool: runs eval_samples/ through PostProcessor prompts against " +
        "the real OpenAI API and writes a before/after report. Costs real API credits; " +
        "not part of build/test/check."
    dependsOn("compileDebugUnitTestKotlin")
    mainClass.set("com.kafkasl.phonewhisper.tools.EvalHarnessKt")
    classpath = tasks.named<Test>("testDebugUnitTest").get().classpath
}

// libsherpa-onnx-jni.so and libonnxruntime.so are required at runtime by the vendored
// com.k2fsa.sherpa.onnx.* Kotlin bindings (app/src/main/kotlin/com/k2fsa/sherpa/), but that
// groupId isn't published on Maven Central -- the only working source is the prebuilt aar on
// GitHub Releases. Fetched here instead of committed, since app/.gitignore already excludes
// jniLibs/ to keep ~50-60MB of binary out of git (see issue #36).
val sherpaOnnxVersion = "1.13.3"
val sherpaOnnxAarUrl =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar"
val sherpaOnnxJniLibsDir = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")
val sherpaOnnxNativeLibs = listOf("libsherpa-onnx-jni.so", "libonnxruntime.so")

tasks.register("fetchSherpaOnnxNativeLibs") {
    description = "Downloads the sherpa-onnx $sherpaOnnxVersion release aar and extracts the " +
        "arm64-v8a native libs into src/main/jniLibs/ (see issue #36)."
    val outputDir = sherpaOnnxJniLibsDir.asFile
    // Kept outside jniLibs/ so only the .so files themselves live in the dir Android packages.
    val versionMarker = layout.buildDirectory.file("sherpaOnnx/.sherpa-onnx-version").get().asFile

    onlyIf("native libs for sherpa-onnx $sherpaOnnxVersion already present") {
        !(versionMarker.exists() && versionMarker.readText().trim() == sherpaOnnxVersion &&
            sherpaOnnxNativeLibs.all { File(outputDir, it).exists() })
    }

    doLast {
        outputDir.mkdirs()
        versionMarker.parentFile.mkdirs()
        val aarFile = File(temporaryDir, "sherpa-onnx-$sherpaOnnxVersion.aar")
        logger.lifecycle("Fetching sherpa-onnx $sherpaOnnxVersion native libs from $sherpaOnnxAarUrl")
        URI(sherpaOnnxAarUrl).toURL().openStream().use { input ->
            aarFile.outputStream().use { output -> input.copyTo(output) }
        }

        ZipFile(aarFile).use { zip ->
            sherpaOnnxNativeLibs.forEach { libName ->
                val entryPath = "jni/arm64-v8a/$libName"
                val entry = zip.getEntry(entryPath)
                    ?: error("$entryPath not found in $aarFile -- sherpa-onnx aar layout may have changed")
                zip.getInputStream(entry).use { input ->
                    File(outputDir, libName).outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        versionMarker.writeText(sherpaOnnxVersion)
    }
}

// Hooked onto each variant's JNI-lib-merge step (not preBuild) so it only runs for
// assembleDebug/assembleRelease packaging, not testDebugUnitTest, which never touches jniLibs/.
tasks.matching { it.name.matches(Regex("merge[A-Za-z]+JniLibFolders")) }.configureEach {
    dependsOn("fetchSherpaOnnxNativeLibs")
}

// llama.cpp native libs for on-device cleanup (#37): unlike sherpa-onnx above, there's no
// prebuilt release artifact with this app's JNI entry points, so the shim in
// app/src/main/cpp/llama_cleanup/ is built from source against the llama.cpp submodule (../llama.cpp)
// via the externalNativeBuild/cmake block in the `android {}` block above. See
// app/src/main/cpp/llama_cleanup/README.md for the full setup.
