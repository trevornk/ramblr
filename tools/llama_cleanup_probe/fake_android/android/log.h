// Minimal stand-in for the NDK's <android/log.h>, used only so LLMInference.cpp -- vendored
// verbatim from app/src/main/cpp/llama_cleanup/ -- can be compiled and run as a plain host binary
// (see tools/llama_cleanup_probe/README.md). Not part of the app build.
#pragma once
#include <cstdio>
#include <cstdarg>

#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_ERROR 6

inline int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    fprintf(prio >= ANDROID_LOG_ERROR ? stderr : stdout, "%s ", tag);
    int ret = vfprintf(prio >= ANDROID_LOG_ERROR ? stderr : stdout, fmt, args);
    va_end(args);
    fprintf(prio >= ANDROID_LOG_ERROR ? stderr : stdout, "\n");
    return ret;
}
