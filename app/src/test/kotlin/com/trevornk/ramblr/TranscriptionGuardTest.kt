package com.trevornk.ramblr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionGuardTest {

    @Test fun `token is current until cancelled`() {
        val guard = TranscriptionGuard()
        val token = guard.start()

        assertTrue(guard.isCurrent(token))

        guard.cancel()

        assertFalse(guard.isCurrent(token))
    }

    @Test fun `starting a new operation supersedes the previous token`() {
        val guard = TranscriptionGuard()
        val first = guard.start()
        val second = guard.start()

        assertNotEquals(first, second)
        assertFalse(guard.isCurrent(first))
        assertTrue(guard.isCurrent(second))
    }

    @Test fun `repeated cancel calls stay stale`() {
        val guard = TranscriptionGuard()
        val token = guard.start()

        guard.cancel()
        guard.cancel()

        assertFalse(guard.isCurrent(token))
    }
}
