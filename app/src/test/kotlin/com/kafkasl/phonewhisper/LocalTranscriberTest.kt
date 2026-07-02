package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Covers the sherpa-onnx model layout detection described in #18: the Whisper
 * archives from the sherpa-onnx asr-models release prefix every filename with
 * the model name (e.g. `base.en-tokens.txt`), unlike Parakeet/Moonshine's bare
 * filenames. These tests build fake archive layouts on disk with plain
 * java.io.File and exercise detection directly -- no Android/native deps.
 */
class LocalTranscriberTest {

    // -- findTokens --

    @Test fun `findTokens finds a bare tokens txt`() {
        withTempDir { tmp ->
            File(tmp, "tokens.txt").writeText("hi")
            assertEquals(File(tmp, "tokens.txt").absolutePath, LocalTranscriber.findTokens(tmp.absolutePath))
        }
    }

    @Test fun `findTokens finds a model-name-prefixed tokens txt`() {
        withTempDir { tmp ->
            File(tmp, "base.en-tokens.txt").writeText("hi")
            assertEquals(
                File(tmp, "base.en-tokens.txt").absolutePath,
                LocalTranscriber.findTokens(tmp.absolutePath)
            )
        }
    }

    @Test fun `findTokens is null when no tokens file exists`() {
        withTempDir { tmp ->
            File(tmp, "encoder.onnx").writeText("x")
            assertNull(LocalTranscriber.findTokens(tmp.absolutePath))
        }
    }

    // -- findFile --

    @Test fun `findFile matches a prefixed filename by contains, not just startsWith`() {
        withTempDir { tmp ->
            File(tmp, "base.en-encoder.onnx").writeText("x")
            assertEquals(
                File(tmp, "base.en-encoder.onnx").absolutePath,
                LocalTranscriber.findFile(tmp.absolutePath, "encoder")
            )
        }
    }

    @Test fun `findFile prefers the int8 quantized variant when both exist`() {
        withTempDir { tmp ->
            File(tmp, "base.en-encoder.onnx").writeText("x")
            File(tmp, "base.en-encoder.int8.onnx").writeText("x")
            assertEquals(
                File(tmp, "base.en-encoder.int8.onnx").absolutePath,
                LocalTranscriber.findFile(tmp.absolutePath, "encoder")
            )
        }
    }

    @Test fun `findFile is null when nothing matches`() {
        withTempDir { tmp ->
            File(tmp, "tokens.txt").writeText("x")
            assertNull(LocalTranscriber.findFile(tmp.absolutePath, "joiner"))
        }
    }

    // -- detectModelConfig: Whisper (prefixed) --

    @Test fun `detects a Whisper archive with model-name-prefixed filenames`() {
        withTempDir { tmp ->
            layout(
                tmp, "base.en-tokens.txt", "base.en-encoder.int8.onnx", "base.en-decoder.int8.onnx"
            )

            val config = LocalTranscriber.detectModelConfig(tmp)

            assertNotNull(config)
            assertEquals("whisper", config!!.modelConfig.modelType)
            assertEquals(File(tmp, "base.en-tokens.txt").absolutePath, config.modelConfig.tokens)
            assertEquals(
                File(tmp, "base.en-encoder.int8.onnx").absolutePath,
                config.modelConfig.whisper.encoder
            )
            assertEquals(
                File(tmp, "base.en-decoder.int8.onnx").absolutePath,
                config.modelConfig.whisper.decoder
            )
        }
    }

    @Test fun `detects a Whisper archive without int8 quantized files`() {
        withTempDir { tmp ->
            layout(tmp, "tiny.en-tokens.txt", "tiny.en-encoder.onnx", "tiny.en-decoder.onnx")

            val config = LocalTranscriber.detectModelConfig(tmp)

            assertNotNull(config)
            assertEquals("whisper", config!!.modelConfig.modelType)
        }
    }

    // -- detectModelConfig: Parakeet TDT (bare, transducer) --

    @Test fun `detects a bare-filename Parakeet transducer archive`() {
        withTempDir { tmp ->
            layout(tmp, "tokens.txt", "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx")

            val config = LocalTranscriber.detectModelConfig(tmp)

            assertNotNull(config)
            assertEquals("nemo_transducer", config!!.modelConfig.modelType)
            assertEquals(File(tmp, "tokens.txt").absolutePath, config.modelConfig.tokens)
            assertEquals(
                File(tmp, "encoder.int8.onnx").absolutePath,
                config.modelConfig.transducer.encoder
            )
            assertEquals(
                File(tmp, "decoder.int8.onnx").absolutePath,
                config.modelConfig.transducer.decoder
            )
            assertEquals(
                File(tmp, "joiner.int8.onnx").absolutePath,
                config.modelConfig.transducer.joiner
            )
        }
    }

    // -- detectModelConfig: Moonshine (bare) --

    @Test fun `detects a bare-filename Moonshine archive`() {
        withTempDir { tmp ->
            layout(
                tmp, "tokens.txt", "preprocess.onnx",
                "encode.int8.onnx", "uncached_decode.int8.onnx", "cached_decode.int8.onnx"
            )

            val config = LocalTranscriber.detectModelConfig(tmp)

            assertNotNull(config)
            assertEquals(
                File(tmp, "preprocess.onnx").absolutePath,
                config!!.modelConfig.moonshine.preprocessor
            )
        }
    }

    // -- detectModelConfig: NeMo CTC (bare, single model file) --

    @Test fun `detects a bare-filename NeMo CTC archive`() {
        withTempDir { tmp ->
            layout(tmp, "tokens.txt", "model.int8.onnx")

            val config = LocalTranscriber.detectModelConfig(tmp)

            assertNotNull(config)
            assertEquals(File(tmp, "model.int8.onnx").absolutePath, config!!.modelConfig.nemo.model)
        }
    }

    // -- detectModelConfig: no match --

    @Test fun `detectModelConfig is null when tokens file is missing entirely`() {
        withTempDir { tmp ->
            layout(tmp, "encoder.onnx", "decoder.onnx")
            assertNull(LocalTranscriber.detectModelConfig(tmp))
        }
    }

    @Test fun `detectModelConfig is null when tokens exists but no recognizable model files do`() {
        withTempDir { tmp ->
            layout(tmp, "tokens.txt", "README.txt")
            assertNull(LocalTranscriber.detectModelConfig(tmp))
        }
    }

    // -- helpers --

    private fun withTempDir(block: (File) -> Unit) {
        val tmp = Files.createTempDirectory("local-transcriber-test").toFile()
        try { block(tmp) } finally { tmp.deleteRecursively() }
    }

    private fun layout(dir: File, vararg fileNames: String) {
        fileNames.forEach { File(dir, it).writeText("fake-data") }
    }
}
