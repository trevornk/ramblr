package com.trevornk.ramblr

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AudioChunkPlannerTest {
    private val sr = AudioChunkPlanner.SAMPLE_RATE.toLong()

    private fun assertCovers(total: Long, chunks: List<AudioChunkPlanner.Chunk>, max: Long) {
        assertEquals(0L, chunks.first().startSample)
        assertEquals(total, chunks.last().endSample)
        chunks.zipWithNext { a, b -> assertEquals("contiguous", a.endSample, b.startSample) }
        chunks.forEachIndexed { i, c -> assertEquals(i, c.index); assertTrue("<= max", c.length in 1..max) }
    }

    @Test fun `empty input plans nothing`() = assertTrue(AudioChunkPlanner.plan(0, 60 * sr).isEmpty())

    @Test fun `short audio is one chunk`() {
        val c = AudioChunkPlanner.plan(10 * sr, 60 * sr)
        assertEquals(1, c.size)
        assertEquals(10 * sr, c[0].length)
    }

    @Test fun `exactly the limit is one chunk`() = assertEquals(1, AudioChunkPlanner.plan(60 * sr, 60 * sr).size)

    @Test fun `long audio without energies cuts at the limit and covers everything`() {
        val total = 200 * sr
        val c = AudioChunkPlanner.plan(total, 60 * sr)
        assertCovers(total, c, 60 * sr)
        assertEquals(60 * sr, c[0].length)
        assertEquals(4, c.size)
    }

    @Test fun `a tiny tail is merged into the previous chunk boundary instead of standing alone`() {
        val total = 60 * sr + sr // 1 s over the limit
        val c = AudioChunkPlanner.plan(total, 60 * sr)
        assertCovers(total, c, 60 * sr)
        assertTrue(c.last().length >= AudioChunkPlanner.MIN_TAIL_SAMPLES)
    }

    @Test fun `cut moves to the quietest spot near the limit`() {
        val total = 130 * sr
        val frames = (total / AudioChunkPlanner.FRAME_SAMPLES).toInt() + 2
        val e = FloatArray(frames) { 1000f }
        val quietAt = (50 * sr / AudioChunkPlanner.FRAME_SAMPLES).toInt() // 50 s boundary
        e[quietAt - 1] = 0f; e[quietAt] = 0f
        val c = AudioChunkPlanner.plan(total, 60 * sr, e)
        assertCovers(total, c, 60 * sr)
        assertEquals(50 * sr, c[0].endSample)
    }

    @Test fun `a quiet spot too early is ignored so chunks stay at least half full`() {
        val total = 130 * sr
        val frames = (total / AudioChunkPlanner.FRAME_SAMPLES).toInt() + 2
        val e = FloatArray(frames) { 1000f }
        val early = (5 * sr / AudioChunkPlanner.FRAME_SAMPLES).toInt()
        e[early - 1] = 0f; e[early] = 0f
        val c = AudioChunkPlanner.plan(total, 60 * sr, e)
        assertTrue(c[0].length >= 30 * sr)
    }

    @Test fun `ties prefer the latest cut`() {
        val total = 130 * sr
        val e = FloatArray((total / AudioChunkPlanner.FRAME_SAMPLES).toInt() + 2) { 7f }
        assertEquals(60 * sr, AudioChunkPlanner.plan(total, 60 * sr, e)[0].endSample)
    }

    @Test fun `a very long file plans a bounded number of chunks`() {
        val total = 3L * 3600 * sr // 3 h
        val c = AudioChunkPlanner.plan(total, 5 * 60 * sr)
        assertCovers(total, c, 5 * 60 * sr)
        assertEquals(36, c.size)
    }

    @Test fun `scanEnergies and extract round-trip through a real pcm file`() {
        val total = 5 * sr.toInt()
        val bytes = ByteArray(total * 2)
        // loud first 2 s, silent the rest
        for (i in 0 until 2 * sr.toInt()) { val s = if (i % 2 == 0) 8000 else -8000; bytes[i * 2] = (s and 0xFF).toByte(); bytes[i * 2 + 1] = (s shr 8).toByte() }
        val f = File.createTempFile("pcm", ".pcm").apply { deleteOnExit(); writeBytes(bytes) }
        val e = AudioChunkPlanner.scanEnergies(f)
        assertTrue(e[0] > 7000f)
        assertEquals(0f, e[(3 * sr / AudioChunkPlanner.FRAME_SAMPLES).toInt()], 0f)

        val chunk = AudioChunkPlanner.Chunk(0, 1000, 4000)
        val out = File.createTempFile("chunk", ".pcm").apply { deleteOnExit() }
        AudioChunkPlanner.extract(f, chunk, out)
        assertArrayEquals(bytes.copyOfRange(2000, 8000), out.readBytes())
    }

    @Test(expected = java.io.EOFException::class)
    fun `extract past the end of the source fails loudly`() {
        val f = File.createTempFile("pcm", ".pcm").apply { deleteOnExit(); writeBytes(ByteArray(100)) }
        AudioChunkPlanner.extract(f, AudioChunkPlanner.Chunk(0, 0, 1000), File.createTempFile("out", ".pcm").apply { deleteOnExit() })
    }
}

class TranscriptChunkerTest {
    @Test fun `short text is one piece`() = assertEquals(listOf("hello there"), TranscriptChunker.split("  hello there \n"))
    @Test fun `blank text is nothing`() = assertTrue(TranscriptChunker.split("  \n ").isEmpty())

    @Test fun `long text splits at sentence ends and loses no words`() {
        val sentence = "This is a sentence of moderate length. "
        val text = sentence.repeat(200)
        val parts = TranscriptChunker.split(text, 500)
        assertTrue(parts.size > 5)
        parts.forEach { assertTrue(it.length <= 500); assertTrue(it.endsWith(".")) }
        assertEquals(text.trim().split(Regex("\\s+")), parts.joinToString(" ").split(Regex("\\s+")))
    }

    @Test fun `prefers a paragraph break`() {
        val a = "a".repeat(30) + " word. " + "b".repeat(250)
        val text = a + "\n\n" + "c".repeat(200)
        val parts = TranscriptChunker.split(text, 400)
        assertEquals(a, parts[0])
    }

    @Test fun `text with no spaces still splits hard`() {
        val parts = TranscriptChunker.split("x".repeat(1000), 300)
        assertEquals(listOf(300, 300, 300, 100), parts.map { it.length })
    }
}
