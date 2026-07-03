package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DictationHistoryStoreTest {

    private fun tempFile(): File =
        File.createTempFile("dictation_history_", ".jsonl").apply { deleteOnExit(); delete() }

    private fun entry(n: Long, raw: String = "raw $n", cleaned: String? = null) =
        DictationHistoryEntry(timestamp = n, rawText = raw, cleanedText = cleaned)

    @Test fun `starts empty when the backing file does not exist yet`() {
        val store = DictationHistoryStore(tempFile())
        assertTrue(store.all().isEmpty())
    }

    @Test fun `add then read back returns the same entry`() {
        val store = DictationHistoryStore(tempFile())
        store.add(entry(1, "hello world", "Hello, world."))

        val all = store.all()
        assertEquals(1, all.size)
        assertEquals(1L, all[0].timestamp)
        assertEquals("hello world", all[0].rawText)
        assertEquals("Hello, world.", all[0].cleanedText)
    }

    @Test fun `preserves a null cleanedText across a write-read cycle`() {
        val store = DictationHistoryStore(tempFile())
        store.add(entry(1, cleaned = null))

        assertNull(store.all().single().cleanedText)
    }

    @Test fun `returns entries newest first`() {
        val store = DictationHistoryStore(tempFile())
        store.add(entry(1))
        store.add(entry(2))
        store.add(entry(3))

        assertEquals(listOf(3L, 2L, 1L), store.all().map { it.timestamp })
    }

    @Test fun `evicts the oldest entries beyond maxEntries`() {
        val store = DictationHistoryStore(tempFile(), maxEntries = 3)
        for (i in 1..5) store.add(entry(i.toLong()))

        assertEquals(listOf(5L, 4L, 3L), store.all().map { it.timestamp })
    }

    @Test fun `persists across separate store instances backed by the same file`() {
        val file = tempFile()
        DictationHistoryStore(file).add(entry(1, "persisted"))

        val reopened = DictationHistoryStore(file)
        assertEquals("persisted", reopened.all().single().rawText)
    }

    @Test fun `clear removes all entries`() {
        val store = DictationHistoryStore(tempFile())
        store.add(entry(1))
        store.add(entry(2))

        store.clear()

        assertTrue(store.all().isEmpty())
    }

    @Test fun `clear on a store that was never written to is a no-op`() {
        val store = DictationHistoryStore(tempFile())
        store.clear()
        assertTrue(store.all().isEmpty())
    }

    @Test fun `delete removes only the entry with the matching timestamp`() {
        val store = DictationHistoryStore(tempFile())
        store.add(entry(1))
        store.add(entry(2))
        store.add(entry(3))

        store.delete(2)

        assertEquals(listOf(3L, 1L), store.all().map { it.timestamp })
    }

    @Test fun `delete of an unknown timestamp is a no-op`() {
        val store = DictationHistoryStore(tempFile())
        store.add(entry(1))

        store.delete(999)

        assertEquals(listOf(1L), store.all().map { it.timestamp })
    }

    @Test fun `delete persists across separate store instances backed by the same file`() {
        val file = tempFile()
        val store = DictationHistoryStore(file)
        store.add(entry(1))
        store.add(entry(2))

        store.delete(1)

        val reopened = DictationHistoryStore(file)
        assertEquals(listOf(2L), reopened.all().map { it.timestamp })
    }

    @Test fun `skips a corrupt line instead of losing the rest of the file`() {
        val file = tempFile()
        val store = DictationHistoryStore(file)
        store.add(entry(1, "good one"))
        file.appendText("not json at all\n")
        store.add(entry(2, "good two"))

        val texts = store.all().map { it.rawText }
        assertEquals(listOf("good two", "good one"), texts)
    }

    @Test fun `round-trips text containing newlines and quotes safely`() {
        val store = DictationHistoryStore(tempFile())
        val tricky = "line one\nline two with \"quotes\" and a backslash \\"
        store.add(entry(1, tricky, tricky))

        val readBack = store.all().single()
        assertEquals(tricky, readBack.rawText)
        assertEquals(tricky, readBack.cleanedText)
    }
}
