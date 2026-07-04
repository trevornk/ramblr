// Drives the real, production LLMInference.cpp exactly the way LlamaCppInference.kt's complete()
// does (see app/src/main/kotlin/com/kafkasl/phonewhisper/LlamaCppInference.kt): one addChatMessage
// call for the system prompt, then startCompletion for the user text, then completionLoop until
// "[EOG]". Same defaults LlamaCppInference.kt hardcodes (minP/temperature/contextSize/nThreads/
// useMmap/useMlock) so this is a faithful proof of the Kotlin call signature, not a looser test.
#include "LLMInference.h"
#include <chrono>
#include <cstdio>
#include <string>

int main(int argc, char** argv) {
    if (argc < 4) {
        fprintf(stderr, "usage: %s <model.gguf> <system_prompt> <user_text>\n", argv[0]);
        return 1;
    }
    const std::string modelPath = argv[1];
    const std::string systemPrompt = argv[2];
    const std::string userText = argv[3];

    LLMInference llm;
    fprintf(stderr, "loading model...\n");
    auto loadStart = std::chrono::steady_clock::now();
    llm.loadModel(modelPath.c_str(), /*minP=*/0.05f, /*temperature=*/0.0f, /*storeChats=*/false,
                  /*contextSize=*/2048L, /*chatTemplate=*/nullptr, /*nThreads=*/4,
                  /*useMmap=*/true, /*useMlock=*/false);
    auto loadEnd = std::chrono::steady_clock::now();
    double loadSecs = std::chrono::duration<double>(loadEnd - loadStart).count();
    fprintf(stderr, "model loaded in %.2fs\n", loadSecs);

    llm.addChatMessage(systemPrompt.c_str(), "system");
    auto genStart = std::chrono::steady_clock::now();
    llm.startCompletion(userText.c_str());

    std::string response;
    int numPieces = 0;
    while (true) {
        std::string piece = llm.completionLoop();
        if (piece == "[EOG]") break;
        response += piece;
        numPieces++;
    }
    llm.stopCompletion();
    auto genEnd = std::chrono::steady_clock::now();
    double genSecs = std::chrono::duration<double>(genEnd - genStart).count();

    printf("=== RESPONSE ===\n%s\n=== END ===\n", response.c_str());
    fprintf(stderr, "generated %d pieces in %.2fs (%.1f tok/s), context used: %d\n",
            numPieces, genSecs, numPieces / genSecs, llm.getContextSizeUsed());
    return 0;
}
