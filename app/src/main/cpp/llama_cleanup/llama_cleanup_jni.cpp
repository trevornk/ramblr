// Adapted from shubham0204/SmolChat-Android's `smollm` module JNI glue (Apache License 2.0,
// commit 8408e1ced09e, 2026-07-03):
// https://github.com/shubham0204/SmolChat-Android/blob/main/smollm/src/main/cpp/smollm.cpp
//
// Adaptation from the original (diff summary -- see #37 closing comment for the full diff):
//   - JNI symbol names renamed from Java_io_shubham0204_smollm_SmolLM_* to
//     Java_com_kafkasl_phonewhisper_LlamaCppInference_*, matching this app's package/class.
//   - startCompletion() no longer returns jboolean (Jinja-vs-legacy template info); Ramblr's
//     Kotlin wrapper (see LlamaCppInference.kt) doesn't surface that distinction, so the
//     original two functions (jboolean-returning startCompletion + separate
//     getResponseGenerationSpeed/getContextSizeUsed/benchModel accessors) are trimmed to exactly
//     the 6 functions LlamaCppInference.kt declares: loadModel, addChatMessage, startCompletion,
//     completionLoop, stopCompletion, close. Cleanup is a single-shot completion, not an
//     interactive chat session, so the trimmed functions (perf metrics, benchmarking) would be
//     unused surface (YAGNI) -- LLMInference.cpp/.h themselves are vendored unmodified, so
//     restoring them later is a small, low-risk addition to this file alone.
//
// This file IS compiled and packaged by the Gradle build (externalNativeBuild/CMake against the
// pinned llama.cpp submodule -- see app/build.gradle.kts and this directory's README.md for the
// full build setup, #37/#87).

#include "LLMInference.h"
#include <jni.h>

extern "C" JNIEXPORT jlong JNICALL
Java_com_kafkasl_phonewhisper_LlamaCppInference_loadModel(JNIEnv* env, jobject thiz, jstring modelPath, jfloat minP,
                                                           jfloat temperature, jboolean storeChats, jlong contextSize,
                                                           jstring chatTemplate, jint nThreads, jboolean useMmap,
                                                           jboolean useMlock) {
    jboolean    isCopy           = true;
    const char* modelPathCstr    = env->GetStringUTFChars(modelPath, &isCopy);
    auto*       llmInference     = new LLMInference();
    const char* chatTemplateCstr = env->GetStringUTFChars(chatTemplate, &isCopy);
    // An empty chat template string (Ramblr always passes "" -- see LlamaCppInference.load())
    // means "use the model's own embedded template", matching LLMInference::loadModel's null check.
    const char* effectiveTemplate = (chatTemplateCstr[0] == '\0') ? nullptr : chatTemplateCstr;

    try {
        llmInference->loadModel(modelPathCstr, minP, temperature, storeChats, contextSize, effectiveTemplate, nThreads,
                                useMmap, useMlock);
    } catch (std::exception& error) {
        env->ReleaseStringUTFChars(modelPath, modelPathCstr);
        env->ReleaseStringUTFChars(chatTemplate, chatTemplateCstr);
        delete llmInference;
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return 0;
    }

    env->ReleaseStringUTFChars(modelPath, modelPathCstr);
    env->ReleaseStringUTFChars(chatTemplate, chatTemplateCstr);
    return reinterpret_cast<jlong>(llmInference);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kafkasl_phonewhisper_LlamaCppInference_addChatMessage(JNIEnv* env, jobject thiz, jlong modelPtr,
                                                                jstring message, jstring role) {
    jboolean    isCopy       = true;
    const char* messageCstr  = env->GetStringUTFChars(message, &isCopy);
    const char* roleCstr     = env->GetStringUTFChars(role, &isCopy);
    auto*       llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    llmInference->addChatMessage(messageCstr, roleCstr);
    env->ReleaseStringUTFChars(message, messageCstr);
    env->ReleaseStringUTFChars(role, roleCstr);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kafkasl_phonewhisper_LlamaCppInference_close(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    delete llmInference;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kafkasl_phonewhisper_LlamaCppInference_startCompletion(JNIEnv* env, jobject thiz, jlong modelPtr,
                                                                 jstring prompt) {
    jboolean    isCopy       = true;
    const char* promptCstr   = env->GetStringUTFChars(prompt, &isCopy);
    auto*       llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    try {
        llmInference->startCompletion(promptCstr);
    } catch (std::exception& error) {
        env->ReleaseStringUTFChars(prompt, promptCstr);
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return;
    }
    env->ReleaseStringUTFChars(prompt, promptCstr);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_kafkasl_phonewhisper_LlamaCppInference_completionLoop(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    try {
        std::string response = llmInference->completionLoop();
        // Return raw UTF-8 bytes and decode with String(bytes, UTF_8) on the Kotlin side (#75):
        // NewStringUTF requires Modified UTF-8, where supplementary-plane characters (emoji)
        // must be CESU-8 surrogate pairs -- the standard 4-byte UTF-8 sequences that
        // completionLoop() emits are illegal input to it (CheckJNI aborts the process).
        jbyteArray result = env->NewByteArray(static_cast<jsize>(response.size()));
        if (result == nullptr) {
            return nullptr; // OutOfMemoryError is already pending
        }
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(response.size()),
                                reinterpret_cast<const jbyte*>(response.data()));
        return result;
    } catch (std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_kafkasl_phonewhisper_LlamaCppInference_stopCompletion(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    llmInference->stopCompletion();
}
