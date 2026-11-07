package com.trevornk.ramblr

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import javax.net.SocketFactory
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
    @Test fun `OkHttp defaults to Happy Eyeballs`() {
        assertTrue(okhttp3.OkHttpClient().fastFallback)
    }

    @Test fun `every shared production client enables Happy Eyeballs`() {
        val clients = listOf(
            NetworkClients.shared,
            NetworkClients.noRedirects,
            ModelDownloader.client,
            ModelCatalogStore.client,
            RealCleanupHttpTransport.clientFor(CleanupStepTimeouts(1_000, 2_000)),
            RealCleanupHttpTransport.clientFor(CleanupStepTimeouts(1_000, 2_000, 3_000)),
            NetworkWarmup.client,
        )
        clients.forEach { assertTrue("Happy Eyeballs must be enabled", it.fastFallback) }
        assertEquals(10_000, NetworkClients.shared.connectTimeoutMillis)
        assertSame(NetworkClients.shared, NetworkWarmup.client)
    }

    @Test(timeout = 15_000) fun `blackholed first DNS route falls back well before the connect timeout`() {
        val blackhole = InetAddress.getByName("2001:db8::1")
        val attemptedBlackhole = CountDownLatch(1)
        val closedBlackhole = CountDownLatch(1)
        // Make the documentation address deterministically drop connects, even on CI hosts
        // without IPv6. The second route still uses a real TCP socket and MockWebServer.
        val sockets = object : SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                @Volatile private var blackholed = false

                override fun connect(endpoint: SocketAddress, timeout: Int) {
                    if ((endpoint as InetSocketAddress).address != blackhole) {
                        super.connect(endpoint, timeout)
                        return
                    }
                    blackholed = true
                    attemptedBlackhole.countDown()
                    closedBlackhole.await(timeout.toLong(), TimeUnit.MILLISECONDS)
                    throw SocketTimeoutException("Simulated silently blackholed IPv6 route")
                }

                override fun close() {
                    if (blackholed) closedBlackhole.countDown()
                    super.close()
                }
            }

            override fun createSocket(host: String, port: Int): Socket = error("Unexpected eager connect")
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = error("Unexpected eager connect")
            override fun createSocket(host: InetAddress, port: Int): Socket = error("Unexpected eager connect")
            override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = error("Unexpected eager connect")
        }
        MockWebServer().use { server ->
            val loopback = InetAddress.getByName("127.0.0.1")
            server.start(loopback, 0)
            server.enqueue(MockResponse(body = "connected via IPv4"))
            val client = NetworkClients.shared.newBuilder()
                .proxy(Proxy.NO_PROXY)
                .dns(object : Dns {
                    override fun lookup(hostname: String) = listOf(blackhole, loopback)
                })
                .socketFactory(sockets)
                .build()
            try {
                val start = System.nanoTime()
                client.newCall(Request.Builder().url(server.url("/").newBuilder().host("fallback.test").build()).build())
                    .execute().use { response ->
                        assertEquals("connected via IPv4", response.body.string())
                    }
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                assertEquals("The blackholed route must actually be attempted", 0L, attemptedBlackhole.count)
                assertTrue("Fallback took ${elapsedMs}ms (connect timeout ${client.connectTimeoutMillis}ms)", elapsedMs < 3_000)
                assertTrue("Losing route must be cancelled", closedBlackhole.await(1, TimeUnit.SECONDS))
            } finally {
                closedBlackhole.countDown()
                client.connectionPool.evictAll()
            }
        }
    }
}
