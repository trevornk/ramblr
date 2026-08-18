// Vendored from shubham0204/SmolChat-Android's `smollm` module
// (Apache License 2.0, commit 8408e1ced09e, 2026-07-03):
// https://github.com/shubham0204/SmolChat-Android/blob/main/smollm/src/main/cpp/LLMInference.h
// See app/src/main/cpp/llama_cleanup/README.md for build status and what's adapted vs. vendored.
//
// Divergences from upstream (#87): pointer members carry `= nullptr` default initializers so a
// stack-constructed instance (see tools/llama_cleanup_probe) has a safe destructor on error
// paths, and `_chatTemplateOwned` tracks whether `_chatTemplate` was strdup'ed (owned) vs.
// borrowed from the model, so the destructor can free the owned case without freeing
// model-owned memory.
#pragma once
#include "chat.h"
#include "common.h"
#include "llama.h"
#include <atomic>
#include <string>
#include <vector>

class LLMInference {
    // llama.cpp-specific types
    llama_context* _ctx     = nullptr;
    llama_model*   _model   = nullptr;
    llama_sampler* _sampler = nullptr;
    llama_token    _currToken;
    llama_batch*   _batch   = nullptr;

    llama_batch g_batch;

    // container to store user/assistant messages in the chat
    std::vector<llama_chat_message> _messages;
    // stores the string generated after applying
    // the chat-template to all messages in `_messages`
    std::vector<char> _formattedMessages;
    // stores the tokens for the last query
    // appended to `_messages`
    std::vector<llama_token> _promptTokens;

    // The exact prompt-token sequence whose keys/values are currently resident in the KV cache,
    // i.e. what the *previous* completion decoded. startCompletion diffs the incoming prompt
    // against this to find the longest reusable prefix, so it must hold real token ids rather
    // than a length or a hash -- a length alone cannot prove the cached tokens still match after
    // the user edits their personal vocabulary or switches prompts (#155 follow-up).
    std::vector<llama_token> _cachedTokens;

    // How many leading prompt tokens the last startCompletion reused from the KV cache instead of
    // re-decoding. Diagnostics only (exposed via getReusedPrefixLen for the host probe and
    // instrumentation); the decode path derives everything it needs from `_batch`.
    size_t _reusedPrefixLen = 0;
    const char*              _chatTemplate = nullptr;
    // true when `_chatTemplate` was strdup'ed by loadModel (caller-supplied template) and must
    // be freed by the destructor; false when it points at model-owned memory (#87).
    bool _chatTemplateOwned = false;

    // stores the complete response for the given query
    std::string _response;
    std::string _cacheResponseTokens;
    // whether to cache previous messages in `_messages`
    bool _storeChats;

    // response generation metrics
    int64_t _responseGenerationTime = 0;
    long    _responseNumTokens      = 0;

    // length of context window consumed during the conversation
    int _nCtxUsed = 0;

    // Absolute deadline (in the ggml monotonic clock, microseconds) past which an in-flight
    // llama_decode() aborts itself; 0 means "no deadline" (#92). Set per completion via
    // setInferenceBudgetMs and read by abortCallback, which ggml invokes between graph nodes
    // during llama_decode -- the only interruption point a single synchronous decode call has.
    // atomic because abortCallback runs on the ggml compute thread while the value is written
    // from the caller thread just before decoding starts.
    std::atomic<int64_t> _abortAtUs{0};

    // Installed on the llama_context (loadModel) as its ggml abort callback. Returns true to
    // abort the current llama_decode once _abortAtUs has passed. `data` is the owning
    // LLMInference*; static so it has C-compatible linkage for the C callback pointer.
    static bool abortCallback(void* data);

    bool _isValidUtf8(const char* response);

  public:
    void loadModel(const char* modelPath, float minP, float temperature, bool storeChats, long contextSize,
                   const char* chatTemplate, int nThreads, bool useMmap, bool useMlock);

    std::string benchModel(int pp, int tg, int pl, int nr);

    void addChatMessage(const char* message, const char* role);

    // Arms (or disarms) the wall-clock budget for the completion that follows (#92). A single
    // llama_decode() is otherwise uninterruptible: the Kotlin-side deadline in
    // LlamaCompletionAccumulator only runs *between* completionLoop() calls, so one decode that
    // runs pathologically long (e.g. mmap'd model pages being re-faulted from swap under memory
    // pressure -- the live-confirmed cause on device) blocks past the whole waterfall budget with
    // no way out. Arming this makes ggml's abort callback fire mid-decode instead.
    //   budgetMs  > 0 : abort once this many ms elapse from now.
    //   budgetMs == 0 : no deadline (default; used by tests / non-deadline callers).
    //   budgetMs  < 0 : deadline already passed -- abort at the first check (an already-spent
    //                   waterfall budget maps here, so decoding fails fast instead of starting).
    void setInferenceBudgetMs(int64_t budgetMs);

    float getResponseGenerationTime() const;

    int getContextSizeUsed() const;

    // How many leading prompt tokens the most recent startCompletion() served from the KV cache
    // instead of re-decoding. 0 on the first completion after a load (nothing cached yet) and
    // whenever the prompt's prefix changed. Used by tools/llama_cleanup_probe to *prove* the
    // reuse actually happens rather than inferring it from wall-clock timings.
    size_t getReusedPrefixLen() const;

    // Returns true if Jinja template was used, false if legacy fallback was needed.
    bool startCompletion(const char* query);

    std::string completionLoop();

    void stopCompletion();

    ~LLMInference();
};