package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class NetworkClientsTest {

    @Test fun `client is configured with bounded timeouts, not okhttp defaults`() {
        val client = NetworkClients.shared
        assertEquals(TimeUnit.SECONDS.toMillis(NetworkClients.CONNECT_TIMEOUT_SECONDS).toInt(), client.connectTimeoutMillis)
        assertEquals(TimeUnit.SECONDS.toMillis(NetworkClients.READ_TIMEOUT_SECONDS).toInt(), client.readTimeoutMillis)
        assertEquals(TimeUnit.SECONDS.toMillis(NetworkClients.WRITE_TIMEOUT_SECONDS).toInt(), client.writeTimeoutMillis)
        assertEquals(TimeUnit.SECONDS.toMillis(NetworkClients.CALL_TIMEOUT_SECONDS).toInt(), client.callTimeoutMillis)
    }

    @Test fun `shared client is a singleton so both callers share one connection pool`() {
        assertSame(NetworkClients.shared, NetworkClients.shared)
    }

    @Test fun `timeouts are sized well beyond okhttp's 10s default for multi-minute recordings`() {
        assertTrue(NetworkClients.READ_TIMEOUT_SECONDS > 10)
        assertTrue(NetworkClients.WRITE_TIMEOUT_SECONDS > 10)
        assertTrue(NetworkClients.CALL_TIMEOUT_SECONDS > NetworkClients.READ_TIMEOUT_SECONDS)
    }
}
