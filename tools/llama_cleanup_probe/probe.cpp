// Drives the real, production LLMInference.cpp exactly the way LlamaCppInference.kt's complete()
// does (see app/src/main/kotlin/com/trevornk/ramblr/LlamaCppInference.kt): one addChatMessage
// call for the system prompt, then startCompletion for the user text, then completionLoop until
// "[EOG]" -- capped at the same 512 pieces MAX_RESPONSE_TOKENS enforces (#60/#87). Same defaults
// LlamaCppInference.kt hardcodes (minP/temperature/contextSize/nThreads/useMmap/useMlock) so this
// is a faithful proof of the Kotlin call signature, not a looser test.
//
// An optional <runs> argument (default 1) repeats the whole system+user+completion sequence that
// many times on the SAME instance, mirroring how LocalCleanupModelHolder now reuses one loaded
// LlamaCppInference across dictations (#74). With the storeChats=false one-shot fixes in
// LLMInference.cpp, every run should behave like the first: same response for the same input, and
// a "context used" figure that stays flat across runs instead of accumulating -- a growing figure
// means prior turns are leaking into later prompts or the KV cache.
//
// An optional <budget_ms> argument (default 0 = no deadline) mirrors the native inference budget
// LlamaCppInference.complete() now arms per completion (#92): a positive value aborts a decode
// that runs past it, and a negative value ("already expired") aborts at the first check. Pass a
// negative value to prove the deadline path actually stops a decode (completion should throw
// "llama_decode() aborted: inference budget exceeded" with zero pieces) instead of hanging.
#include "LLMInference.h"
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <stdexcept>
#include <string>

// Mirrors LlamaCppInference.MAX_RESPONSE_TOKENS -- without it a non-terminating model looped
// forever here even though the app itself would have bailed at 512 pieces (#87 item 6).
static const int kMaxPieces = 512;

int main(int argc, char** argv) {
    if (argc < 4) {
        fprintf(stderr, "usage: %s <model.gguf> <system_prompt> <user_text> [runs] [budget_ms]\n", argv[0]);
        return 1;
    }
    const std::string modelPath = argv[1];
    const std::string systemPrompt = argv[2];
    const std::string userText = argv[3];
    const int numRuns = argc > 4 ? atoi(argv[4]) : 1;
    if (numRuns < 1) {
        fprintf(stderr, "runs must be >= 1, got %s\n", argv[4]);
        return 1;
    }
    // 0 (default) = no deadline, matching LlamaCppInference.complete()'s Long.MAX_VALUE case.
    const long budgetMs = argc > 5 ? atol(argv[5]) : 0;

    LLMInference llm;
    fprintf(stderr, "loading model...\n");
    auto loadStart = std::chrono::steady_clock::now();
    // try/catch mirrors the Kotlin side's exception boundary: LLMInference throws
    // std::runtime_error on a bad model path / failed load / context overflow, which previously
    // ended in std::terminate here, discarding all output (#87 item 6).
    try {
        llm.loadModel(modelPath.c_str(), /*minP=*/0.05f, /*temperature=*/0.0f, /*storeChats=*/false,
                      /*contextSize=*/2048L, /*chatTemplate=*/nullptr, /*nThreads=*/4,
                      /*useMmap=*/true, /*useMlock=*/false);
    } catch (const std::exception& e) {
        fprintf(stderr, "loadModel failed: %s\n", e.what());
        return 1;
    }
    auto loadEnd = std::chrono::steady_clock::now();
    double loadSecs = std::chrono::duration<double>(loadEnd - loadStart).count();
    fprintf(stderr, "model loaded in %.2fs\n", loadSecs);

    for (int run = 1; run <= numRuns; run++) {
        llm.addChatMessage(systemPrompt.c_str(), "system");
        auto genStart = std::chrono::steady_clock::now();
        std::string response;
        int numPieces = 0;
        try {
            llm.startCompletion(userText.c_str());
            // Mirrors complete()'s call order: arm the budget after startCompletion, before the
            // first completionLoop decode it bounds (#92).
            llm.setInferenceBudgetMs(budgetMs);
            while (true) {
                std::string piece = llm.completionLoop();
                if (piece == "[EOG]") break;
                response += piece;
                numPieces++;
                if (numPieces >= kMaxPieces) {
                    fprintf(stderr, "aborting: exceeded %d pieces without end-of-generation (same cap as the app)\n",
                            kMaxPieces);
                    break;
                }
            }
        } catch (const std::exception& e) {
            fprintf(stderr, "run %d: completion failed after %d pieces: %s\n", run, numPieces, e.what());
            llm.stopCompletion();
            return 1;
        }
        llm.stopCompletion();
        auto genEnd = std::chrono::steady_clock::now();
        double genSecs = std::chrono::duration<double>(genEnd - genStart).count();

        printf("=== RESPONSE (run %d of %d) ===\n%s\n=== END ===\n", run, numRuns, response.c_str());
        fprintf(stderr, "run %d: generated %d pieces in %.2fs (%.1f tok/s), context used: %d\n",
                run, numPieces, genSecs, numPieces / genSecs, llm.getContextSizeUsed());
    }
    return 0;
}
