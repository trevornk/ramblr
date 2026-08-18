// Vendored from shubham0204/SmolChat-Android's `smollm` module
// (Apache License 2.0, commit 8408e1ced09e, 2026-07-03):
// https://github.com/shubham0204/SmolChat-Android/blob/main/smollm/src/main/cpp/LLMInference.cpp
// See app/src/main/cpp/llama_cleanup/README.md for build status and what's adapted vs. vendored.
//
// Divergences from upstream (#87), all leak/correctness fixes:
//   - completionLoop's EOG path no longer double-strdups the assistant response (upstream leaked
//     one copy of the full response text per completion) and honors _storeChats like
//     stopCompletion does.
//   - startCompletion deletes the previous _batch before allocating a new one (upstream leaked
//     it on instance reuse through the exported JNI surface).
//   - loadModel adds the min-p sampler the minP parameter always promised (upstream accepted,
//     logged, and silently ignored it), and tracks _chatTemplate ownership so the destructor can
//     free the strdup'ed case.
//   - loadModel installs a ggml abort callback and completionLoop treats a non-zero llama_decode
//     return as a hard error (#92): a single decode is otherwise uninterruptible, so a decode that
//     runs pathologically long (mmap'd model pages re-faulted from swap under memory pressure --
//     the live-confirmed device cause) blocks past the waterfall's whole wall-clock budget with no
//     way out. setInferenceBudgetMs arms a deadline the callback checks between graph nodes.
//     Upstream never bounded a decode and only checked `< 0`, so it would sample garbage after an
//     abort (which returns 2, not a negative).
//   - storeChats=false now actually means "one-shot, no history" on a reused instance (#74):
//     startCompletion clears the KV cache so a new turn's positions restart at 0 instead of
//     appending after (and attending to) the previous turn's tokens, and stopCompletion frees and
//     clears _messages so the next turn's chat template isn't rendered over every prior turn's
//     system+user pair (upstream only ever cleared _messages in loadModel) and clears the partial
//     UTF-8 accumulator so an aborted multi-byte sequence can't prepend garbage to the next
//     response. Upstream never reused an instance with storeChats=false, so none of this bit it.
#include "LLMInference.h"
#include <android/log.h>
#include <cstring>
#include <iomanip>
#include <iostream>

#define TAG "[SmolLMAndroid-Cpp]"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

void
LLMInference::loadModel(const char *model_path, float minP, float temperature, bool storeChats, long contextSize,
                        const char *chatTemplate, int nThreads, bool useMmap, bool useMlock) {
    LOGi("loading model with"
         "\n\tmodel_path = %s"
         "\n\tminP = %f"
         "\n\ttemperature = %f"
         "\n\tstoreChats = %d"
         "\n\tcontextSize = %li"
         "\n\tchatTemplate = %s"
         "\n\tnThreads = %d"
         "\n\tuseMmap = %d"
         "\n\tuseMlock = %d",
         model_path, minP, temperature, storeChats, contextSize, chatTemplate, nThreads, useMmap, useMlock);

    // load dynamic backends
    ggml_backend_load_all();

    // create an instance of llama_model
    llama_model_params model_params = llama_model_default_params();
    model_params.use_mmap = useMmap;
    model_params.use_mlock = useMlock;
    _model = llama_model_load_from_file(model_path, model_params);
    if (!_model) {
        LOGe("failed to load model from %s", model_path);
        throw std::runtime_error("loadModel() failed");
    }

    // create an instance of llama_context
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = contextSize;
    ctx_params.n_batch = contextSize;
    ctx_params.n_threads = nThreads;
    // Keep llama's own perf counters live. They are what separates model load from prompt
    // evaluation from token generation; without them a slow completion on-device is a single
    // opaque wall-clock number, which is exactly why an 8s local cleanup failure was originally
    // misattributed to prompt length (see
    // .hermes/plans/2026-08-18-local-cleanup-latency-measured.md). The counters are a few
    // integer accumulators around calls that already cost milliseconds -- immeasurable next to
    // the decode itself -- and logPerfMetrics() below emits the breakdown once per completion.
    ctx_params.no_perf = false;
    _ctx = llama_init_from_model(_model, ctx_params);
    if (!_ctx) {
        LOGe("llama_new_context_with_model() returned null)");
        throw std::runtime_error("llama_new_context_with_model() returned null");
    }

    // Give an in-flight llama_decode() an interruption point (#92). ggml calls this between
    // graph nodes on the compute thread; returning true aborts the decode (which then returns 2,
    // handled in completionLoop). Disarmed here (_abortAtUs == 0) and armed per completion by
    // setInferenceBudgetMs, so the speculative warm-up load never carries a stale deadline.
    llama_set_abort_callback(_ctx, &LLMInference::abortCallback, this);

    // create an instance of llama_sampler
    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    sampler_params.no_perf = true; // disable performance metrics
    _sampler = llama_sampler_chain_init(sampler_params);
    // min-p first, then temperature, then the final sampler -- the standard llama.cpp chain
    // order. Upstream accepted and logged minP but never added this sampler (#87 item 4).
    llama_sampler_chain_add(_sampler, llama_sampler_init_min_p(minP, 1));
    llama_sampler_chain_add(_sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    _formattedMessages = std::vector<char>(llama_n_ctx(_ctx));
    _messages.clear();

    if (chatTemplate == nullptr) {
        _chatTemplate      = llama_model_chat_template(_model, nullptr);
        _chatTemplateOwned = false; // model-owned memory; must not be freed by us
    } else {
        _chatTemplate      = strdup(chatTemplate);
        _chatTemplateOwned = true;
    }
    this->_storeChats = storeChats;
}

void
LLMInference::addChatMessage(const char *message, const char *role) {
    _messages.push_back({strdup(role), strdup(message)});
}

bool
LLMInference::abortCallback(void *data) {
    auto *self = static_cast<LLMInference *>(data);
    const int64_t deadline = self->_abortAtUs.load(std::memory_order_relaxed);
    return deadline != 0 && ggml_time_us() >= deadline;
}

void
LLMInference::setInferenceBudgetMs(int64_t budgetMs) {
    if (budgetMs == 0) {
        _abortAtUs.store(0, std::memory_order_relaxed); // no deadline
    } else if (budgetMs < 0) {
        // Budget already spent before we even started: arm an already-reached deadline so the
        // first abort-callback check trips. ggml_time_us() is monotonic and > 0 once the backend
        // is up, so any positive value in the past works; use 1 (never itself 0 == "disabled").
        _abortAtUs.store(1, std::memory_order_relaxed);
    } else {
        _abortAtUs.store(ggml_time_us() + budgetMs * 1000, std::memory_order_relaxed);
    }
}

float
LLMInference::getResponseGenerationTime() const {
    return (float) _responseNumTokens / (_responseGenerationTime / 1e6);
}

int
LLMInference::getContextSizeUsed() const {
    return _nCtxUsed;
}

size_t
LLMInference::getReusedPrefixLen() const {
    return _reusedPrefixLen;
}

void
LLMInference::logPerfMetrics() const {
    if (!_ctx) {
        return;
    }
    // llama_perf_context reports cumulative counters for this context, split into the three
    // phases that actually matter when a completion is slow: t_load_ms (one-off model load),
    // t_p_eval_ms/n_p_eval (prompt evaluation) and t_eval_ms/n_eval (token generation). Logging
    // the split per completion is the difference between "local cleanup took 8s" and knowing
    // which phase to go fix -- the ambiguity that made a vocabulary cap look like the answer.
    const llama_perf_context_data perf = llama_perf_context(_ctx);
    LOGi("perf: load %.2fms | prompt eval %.2fms / %d tokens (%.2f t/s) | gen %.2fms / %d tokens (%.2f t/s) | prefix reused %zu",
         perf.t_load_ms,
         perf.t_p_eval_ms, perf.n_p_eval,
         perf.n_p_eval > 0 && perf.t_p_eval_ms > 0 ? perf.n_p_eval / (perf.t_p_eval_ms / 1000.0) : 0.0,
         perf.t_eval_ms, perf.n_eval,
         perf.n_eval > 0 && perf.t_eval_ms > 0 ? perf.n_eval / (perf.t_eval_ms / 1000.0) : 0.0,
         _reusedPrefixLen);
}

bool
LLMInference::startCompletion(const char *query) {
    if (!_storeChats) {
        _formattedMessages.clear();
        _formattedMessages = std::vector<char>(llama_n_ctx(_ctx));
        // One-shot mode on a reused instance (#74): the batch below carries no positions, so
        // llama_decode appends after llama_memory_seq_pos_max -- without a clear, a second
        // completion's prompt would be decoded after (and attend to) the previous completion's
        // tokens, and _nCtxUsed would grow across turns until "context size reached". _messages
        // itself is cleared in stopCompletion, not here: the caller has already added this
        // turn's system message by the time startCompletion runs (see LlamaCppInference.complete).
        //
        // The clear is deferred until after tokenization below, because the previous turn's KV
        // is exactly what prefix reuse needs to keep. See _cachedTokens.
    }
    _responseGenerationTime = 0;
    _responseNumTokens = 0;
    addChatMessage(query, "user");
    // apply the chat-template
    std::vector<common_chat_msg> messages;
    for (const llama_chat_message& message : _messages) {
        common_chat_msg msg;
        msg.role    = message.role;
        msg.content = message.content;
        messages.push_back(msg);
    }
    auto templates = common_chat_templates_init(_model, _chatTemplate ? _chatTemplate : "");

    common_chat_templates_inputs inputs;
    inputs.messages = messages;

    // Try Jinja rendering first with tools defined to prevent "tojson on Undefined" errors.
    // If Jinja fails (e.g. unsupported filters like lstrip), fall back to legacy rendering.
    inputs.use_jinja = true;
    inputs.chat_template_kwargs["tools"] = "[]";

    std::string prompt;
    bool usedJinja = true;
    try {
        prompt = common_chat_templates_apply(templates.get(), inputs).prompt;
    } catch (const std::exception &e) {
        LOGe("Jinja template failed: %s — retrying with legacy renderer", e.what());
        inputs.use_jinja = false;
        inputs.chat_template_kwargs.clear();
        prompt = common_chat_templates_apply(templates.get(), inputs).prompt;
        usedJinja = false;
    }
    _promptTokens = common_tokenize(llama_model_get_vocab(_model), prompt, true, true);

    // Prefix reuse (#155 follow-up). Ramblr's cleanup prompt is a *stable prefix by
    // construction*: the system prompt (task instructions + the user's personal vocabulary) is
    // rendered first and is byte-identical across dictations, while only the transcript at the
    // tail varies. Re-decoding that shared prefix on every completion is pure waste -- it is the
    // single largest avoidable cost on the local cleanup path, and it grows with the user's
    // vocabulary, which is why it previously looked like "long vocabularies are slow".
    //
    // This is the same optimization llama.cpp's own server performs for `cache_prompt`: find the
    // longest common prefix between what is already in the KV cache and the new prompt, keep that
    // much, and evaluate only the divergent tail. We hold a single sequence (id 0), so trimming
    // is one llama_memory_seq_rm call.
    //
    // Correctness: the reused span must match the new prompt token-for-token, so we compare the
    // actual token ids from the previous turn (_cachedTokens), never a hash or a length. We also
    // stop one token short of a full match (see below) because llama_decode needs at least one
    // token to produce logits to sample from.
    size_t reuse = 0;
    if (!_storeChats) {
        const size_t maxReuse = std::min(_cachedTokens.size(), _promptTokens.size());
        while (reuse < maxReuse && _cachedTokens[reuse] == _promptTokens[reuse]) {
            reuse++;
        }
        // Never reuse the entire new prompt: llama_decode must evaluate >=1 token this turn to
        // produce the logits completionLoop() samples from. Backing off by one keeps the
        // invariant that the batch is non-empty even when a dictation repeats verbatim.
        if (reuse == _promptTokens.size() && reuse > 0) {
            reuse--;
        }

        // Drop everything at/after the divergence point, keep [0, reuse). Passing p1 = -1 means
        // "to the end".
        //
        // llama_memory_seq_rm returns false when a PARTIAL removal is impossible -- notably for
        // recurrent/hybrid memory (LFM2 is a conv+attention hybrid), where per-position eviction
        // isn't representable. Ignoring that return would leave the cache holding the previous
        // turn's tokens while we decoded only a short tail against it, silently corrupting the
        // model's view of the conversation. On failure we fall back to the old unconditional
        // full clear and reuse nothing, which is exactly the pre-optimization behaviour.
        const bool trimmed =
            reuse == 0 ? false
                       : llama_memory_seq_rm(llama_get_memory(_ctx), 0, (llama_pos) reuse, -1);
        if (!trimmed) {
            llama_memory_clear(llama_get_memory(_ctx), false);
            reuse = 0;
        }

        LOGi("prefix reuse: %zu/%zu prompt tokens cached, evaluating %zu",
             reuse, _promptTokens.size(), _promptTokens.size() - reuse);
    }
    _reusedPrefixLen = reuse;
    // The cache now holds exactly [0, reuse) from the previous turn plus the tail we are about to
    // decode. Record that, and completionLoop appends each generated token as it decodes it, so
    // _cachedTokens always mirrors the real KV contents at the next diff.
    _cachedTokens.assign(_promptTokens.begin(), _promptTokens.end());

    // create a llama_batch containing a single sequence
    // see llama_batch_init for more details
    delete _batch; // a previous completion's batch would otherwise leak on reuse (#87 item 2)
    _batch = new llama_batch();
    // Only the divergent tail is decoded; the reused prefix already sits in the KV cache at
    // positions [0, reuse). llama_decode appends after llama_memory_seq_pos_max, so the tail
    // lands at exactly the right positions without us assigning them by hand.
    _batch->token = _promptTokens.data() + reuse;
    _batch->n_tokens = (int32_t) (_promptTokens.size() - reuse);

    return usedJinja;
}


// taken from:
// https://github.com/ggerganov/llama.cpp/blob/master/examples/llama.android/llama/src/main/cpp/llama-android.cpp#L38
bool
LLMInference::_isValidUtf8(const char *response) {
    if (!response) {
        return true;
    }
    const unsigned char *bytes = (const unsigned char *) response;
    int num;
    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            // U+0000 to U+007F
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            // U+0080 to U+07FF
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            // U+0800 to U+FFFF
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            // U+10000 to U+10FFFF
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

std::string
LLMInference::completionLoop() {
    // check if the length of the inputs to the model
    // have exceeded the context size of the model
    uint32_t contextSize = llama_n_ctx(_ctx);
    _nCtxUsed = llama_memory_seq_pos_max(llama_get_memory(_ctx), 0) + 1;
    if (_nCtxUsed + _batch->n_tokens > contextSize) {
        throw std::runtime_error("context size reached");
    }

    auto start = ggml_time_us();
    // run the model. Any non-zero return means no usable logits were produced, so sampling below
    // would read stale/garbage values -- treat every non-success code as a hard stop rather than
    // the original `< 0`-only check, which silently ignored 2 (aborted) and 1 (no KV slot) and
    // sampled anyway. 2 is our own deadline abort (#92): the decode ran past the waterfall's
    // wall-clock budget and ggml stopped it mid-graph via abortCallback.
    const int32_t decodeStatus = llama_decode(_ctx, *_batch);
    if (decodeStatus == 2) {
        throw std::runtime_error("llama_decode() aborted: inference budget exceeded");
    }
    if (decodeStatus != 0) {
        throw std::runtime_error("llama_decode() failed (status " + std::to_string(decodeStatus) + ")");
    }

    // sample a token and check if it is an EOG (end of generation token)
    // convert the integer token to its corresponding word-piece
    _currToken = llama_sampler_sample(_sampler, _ctx, -1);
    if (llama_vocab_is_eog(llama_model_get_vocab(_model), _currToken)) {
        // Upstream passed strdup(_response.data()) here: addChatMessage strdups its argument
        // again, so the outer copy leaked on every successful completion -- and unlike
        // stopCompletion, it ignored _storeChats (#87 item 1).
        if (_storeChats) {
            addChatMessage(_response.c_str(), "assistant");
        }
        _response.clear();
        return "[EOG]";
    }
    std::string piece = common_token_to_piece(_ctx, _currToken, true);
    auto end = ggml_time_us();
    _responseGenerationTime += (end - start);
    _responseNumTokens += 1;
    _cacheResponseTokens += piece;

    // Every sampled token is fed back through llama_decode below, so it occupies a KV position
    // just like a prompt token does. _cachedTokens must therefore mirror the *whole* cache
    // contents (prompt + generated response), not just the prompt: the next completion trims at
    // the first divergence, and anything we failed to record would survive past that point and
    // silently attend to the next turn (#155 follow-up).
    _cachedTokens.push_back(_currToken);

    // re-init the batch with the newly predicted token
    // key, value pairs of all previous tokens have been cached
    // in the KV cache
    _batch->token = &_currToken;
    _batch->n_tokens = 1;

    if (_isValidUtf8(_cacheResponseTokens.c_str())) {
        _response += _cacheResponseTokens;
        std::string valid_utf8_piece = _cacheResponseTokens;
        _cacheResponseTokens.clear();
        return valid_utf8_piece;
    }

    return "";
}

void
LLMInference::stopCompletion() {
    if (_storeChats) {
        addChatMessage(_response.c_str(), "assistant");
    } else {
        // One-shot mode (#74): drop this turn's system+user pair so the next completion on a
        // reused instance renders its chat template over only its own messages. Upstream only
        // cleared _messages in loadModel, which the old load-per-call pattern masked. Free the
        // strdup'ed strings first, same pattern as the destructor.
        for (llama_chat_message &message: _messages) {
            free(const_cast<char *>(message.role));
            free(const_cast<char *>(message.content));
        }
        _messages.clear();
    }
    _response.clear();
    // An aborted multi-byte UTF-8 sequence would otherwise prepend stale bytes to the next
    // completion's first piece on a reused instance (#74).
    _cacheResponseTokens.clear();
    // Disarm the wall-clock deadline so it can't carry into the next completion on a reused
    // instance before that call re-arms it (#92). setInferenceBudgetMs re-arms per completion.
    _abortAtUs.store(0, std::memory_order_relaxed);
    // One line per completion with the load/prompt-eval/generation split, so a slow local cleanup
    // in the field can be attributed to a phase instead of guessed at.
    logPerfMetrics();
}

LLMInference::~LLMInference() {
    // free memory held by the message text in messages
    // (as we had used strdup() to create a malloc'ed copy)
    for (llama_chat_message &message: _messages) {
        free(const_cast<char *>(message.role));
        free(const_cast<char *>(message.content));
    }
    if (_chatTemplateOwned) {
        free(const_cast<char *>(_chatTemplate)); // strdup'ed in loadModel (#87 item 2)
    }
    llama_free(_ctx);
    llama_model_free(_model);
    delete _batch;
    llama_sampler_free(_sampler);
}

std::string
LLMInference::benchModel(int pp, int tg, int pl, int nr) {
    g_batch     = llama_batch_init(pp, 0, pl);
    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    const uint32_t n_ctx = llama_n_ctx(this->_ctx);
    LOGi("n_ctx = %d", n_ctx);

    int i, j;
    int nri;
    for (nri = 0; nri < nr; nri++) {
        LOGi("Benchmark prompt processing (pp = %d)", pp);

        common_batch_clear(g_batch);

        const int n_tokens = pp;
        for (i = 0; i < n_tokens; i++) {
            common_batch_add(g_batch, 1, i, { 0 }, false);
        }

        g_batch.logits[g_batch.n_tokens - 1] = true;
        llama_memory_clear(llama_get_memory(this->_ctx), false);

        const auto t_pp_start = ggml_time_us();
        if (llama_decode(this->_ctx, g_batch) != 0) {
            LOGe("llama_decode() failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        // bench text generation

        LOGi("Benchmark text generation (tg = %d)", tg);

        llama_memory_clear(llama_get_memory(this->_ctx), false);
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {
            common_batch_clear(g_batch);
            for (j = 0; j < pl; j++) {
                common_batch_add(g_batch, 0, i, { j }, true);
            }

            if (llama_decode(this->_ctx, g_batch) != 0) {
                LOGe("llama_decode() failed during text generation");
            }
        }
        const auto t_tg_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(this->_ctx), false);

        const auto t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
        const auto t_tg = double(t_tg_end - t_tg_start) / 1000000.0;

        const auto speed_pp = double(pp) / t_pp;
        const auto speed_tg = double(pl * tg) / t_tg;

        pp_avg += speed_pp;
        tg_avg += speed_tg;

        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;

        LOGi("pp %f t/s, tg %f t/s", speed_pp, speed_tg);
    }

    llama_batch_free(g_batch);

    pp_avg /= double(nr);
    tg_avg /= double(nr);

    if (nr > 1) {
        pp_std = sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
        tg_std = sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
    } else {
        pp_std = 0;
        tg_std = 0;
    }

    char model_desc[128];
    llama_model_desc(this->_model, model_desc, sizeof(model_desc));

    const auto model_size     = double(llama_model_size(this->_model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(this->_model)) / 1e9;

    std::vector<std::string> backends;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        auto*       reg  = ggml_backend_reg_get(i);
        std::string name = ggml_backend_reg_name(reg);
        if (name != "CPU") {
            backends.push_back(ggml_backend_reg_name(reg));
        }
    }
    std::ostringstream str;
    for (size_t i = 0; i < backends.size(); i++) {
        str << backends[i];
        if (i < backends.size() - 1) {
            str << ",";
        }
    }
    const auto backend = str.str();

    std::stringstream result;
    result << std::setprecision(3);
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | " << backend << " | pp "
           << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | " << backend << " | tg "
           << tg << " | " << tg_avg << " ± " << tg_std << " |\n";
    return result.str();
}
