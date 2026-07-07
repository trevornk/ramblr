package com.trevornk.ramblr

import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure directory-shape detection tests for [LocalTranscriber.detectModelConfig] (#98) -- no
 * native sherpa-onnx recognizer construction involved, just the config-building logic that picks
 * which [com.k2fsa.sherpa.onnx.OfflineModelConfig] branch to populate from files on disk.
 *
 * Canary (#98) was added to [MODEL_CATALOG] with the exact same file layout as Whisper (an
 * `encoder`-named file + a `decoder`-named file, no `joiner`) -- these tests exist specifically
 * to guard against Canary silently loading as (garbage) Whisper, which the directory-name check
 * in [LocalTranscriber.detectModelConfig] exists to prevent.
 */
class LocalTranscriberDetectModelConfigTest {

    private fun tempModelDir(name: String, fileNames: List<String>): File {
        val dir = File.createTempFile("model", null).let {
            it.delete()
            File(it.parentFile, "$name-${System.nanoTime()}")
        }
        dir.mkdirs()
        dir.deleteOnExit()
        fileNames.forEach { File(dir, it).apply { createNewFile(); deleteOnExit() } }
        return dir
    }

    @Test fun `a canary-named directory with encoder plus decoder detects as canary, not whisper`() {
        val dir = tempModelDir(
            "sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8",
            listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"),
        )

        val config = LocalTranscriber.detectModelConfig(dir)

        assertNotNull(config)
        assertEquals(OfflineWhisperModelConfig(), config!!.modelConfig.whisper) // whisper left default/unset
        assertTrue(config.modelConfig.canary.encoder.contains("encoder"))
        assertTrue(config.modelConfig.canary.decoder.contains("decoder"))
    }

    @Test fun `a whisper-named directory with encoder plus decoder still detects as whisper`() {
        val dir = tempModelDir(
            "sherpa-onnx-whisper-base.en",
            listOf("base.en-encoder.onnx", "base.en-decoder.onnx", "base.en-tokens.txt"),
        )

        val config = LocalTranscriber.detectModelConfig(dir)

        assertNotNull(config)
        assertEquals(OfflineCanaryModelConfig(), config!!.modelConfig.canary) // canary left default/unset
        assertTrue(config.modelConfig.whisper.encoder.contains("encoder"))
        assertTrue(config.modelConfig.whisper.decoder.contains("decoder"))
    }

    @Test fun `a directory with no recognizable model files returns null`() {
        val dir = tempModelDir("sherpa-onnx-mystery-model", listOf("tokens.txt"))

        assertNull(LocalTranscriber.detectModelConfig(dir))
    }
}
