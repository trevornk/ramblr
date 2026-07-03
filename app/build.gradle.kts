plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kafkasl.phonewhisper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kafkasl.phonewhisper"
        minSdk = 30
        targetSdk = 34
        versionCode = 2
        versionName = "0.3.0"

        ndk { abiFilters += "arm64-v8a" }
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
