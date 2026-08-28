package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Direct coverage for [SerialCallbackExecutor], the serialization primitive the whole
 * exactly-one-terminal cloud-live contract rests on. It had none: every
 * [GeminiCloudLiveTranscriptionClient] test passes a same-thread `Executor { it.run() }`, which
 * never exercises the queue/drain state machine at all.
 *
 * The blocker these cover: one listener callback throwing used to unwind `drain()` with
 * `running == true` and a non-empty queue, so every later `execute()` computed `shouldStart =
 * false`, enqueued, and was never drained again. Since the executor is a FACTORY-level field
 * shared by every session the factory creates, a single throwing `onTerminal`/`onInterim`
 * disabled cloud-live for the rest of the process lifetime.
 */
class SerialCallbackExecutorTest {

    private val pools = mutableListOf<ExecutorService>()

    private fun pool(threads: Int, thrown: MutableList<Throwable>? = null): ExecutorService =
        Executors.newFixedThreadPool(threads) { runnable ->
            Thread(runnable).apply {
                isDaemon = true
                if (thrown != null) setUncaughtExceptionHandler { _, t -> thrown += t }
            }
        }.also(pools::add)

    private fun shutdownPools() = pools.forEach { it.shutdownNow() }

    /** Total failures reported, counting suppressed ones attached to the same drain's throwable. */
    private fun surfacedCount(thrown: List<Throwable>): Int =
        thrown.sumOf { 1 + it.suppressed.size }

    @Test fun `a throwing task does not wedge the executor for later tasks`() {
        val thrown = CopyOnWriteArrayList<Throwable>()
        val executor = SerialCallbackExecutor(pool(1, thrown))
        val ran = CopyOnWriteArrayList<String>()
        val afterThrow = CountDownLatch(2)

        try {
            executor.execute { ran += "first"; afterThrow.countDown() }
            executor.execute { throw IllegalStateException("listener blew up") }
            executor.execute { ran += "second"; afterThrow.countDown() }

            assertTrue(
                "tasks after a throwing one must still be delivered; ran=$ran",
                afterThrow.await(5, TimeUnit.SECONDS),
            )
            assertEquals(listOf("first", "second"), ran)

            // ...and the executor must still accept work afterwards, not just finish its backlog.
            val later = CountDownLatch(1)
            executor.execute { ran += "third"; later.countDown() }
            assertTrue("the executor must stay usable after a throw", later.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("first", "second", "third"), ran)
        } finally {
            shutdownPools()
        }
    }

    @Test fun `the thrown exception is surfaced on the delegate thread, never swallowed`() {
        val thrown = CopyOnWriteArrayList<Throwable>()
        val executor = SerialCallbackExecutor(pool(1, thrown))
        val done = CountDownLatch(1)
        val boom = IllegalStateException("listener blew up")

        try {
            executor.execute { throw boom }
            executor.execute { done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))

            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && thrown.isEmpty()) Thread.sleep(10)
            assertEquals("the failure must reach the delegate thread's uncaught handler", 1, thrown.size)
            assertTrue(thrown.single() === boom || thrown.single().cause === boom)
        } finally {
            shutdownPools()
        }
    }

    @Test fun `several consecutive throwing tasks still leave every survivor delivered in order`() {
        val thrown = CopyOnWriteArrayList<Throwable>()
        val executor = SerialCallbackExecutor(pool(2, thrown))
        val ran = CopyOnWriteArrayList<Int>()
        val survivors = CountDownLatch(5)

        try {
            repeat(10) { i ->
                if (i % 2 == 0) executor.execute { throw RuntimeException("boom-$i") }
                else executor.execute { ran += i; survivors.countDown() }
            }
            assertTrue("every non-throwing task must run; ran=$ran", survivors.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(1, 3, 5, 7, 9), ran)
            // Every failure must be surfaced somewhere -- directly, or suppressed onto the first
            // of its drain -- rather than silently dropped.
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && surfacedCount(thrown) < 5) Thread.sleep(10)
            assertEquals(5, surfacedCount(thrown))
        } finally {
            shutdownPools()
        }
    }

    @Test fun `ordering and mutual exclusion hold when the delegate is a real multi threaded pool`() {
        val executor = SerialCallbackExecutor(pool(8))
        val ran = CopyOnWriteArrayList<Int>()
        val concurrent = AtomicInteger(0)
        val overlapped = AtomicInteger(0)
        val count = 400
        val done = CountDownLatch(count)

        try {
            repeat(count) { i ->
                executor.execute {
                    if (concurrent.incrementAndGet() > 1) overlapped.incrementAndGet()
                    ran += i
                    concurrent.decrementAndGet()
                    done.countDown()
                }
            }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(0, overlapped.get())
            assertEquals((0 until count).toList(), ran)
        } finally {
            shutdownPools()
        }
    }

    @Test fun `a task enqueued from inside a running task is drained without re-entering`() {
        // The same-thread delegate every client test uses: a nested execute() must be queued and
        // run by the in-flight drain, not recursively re-entered.
        val executor = SerialCallbackExecutor(Executor { it.run() })
        val ran = CopyOnWriteArrayList<String>()
        var depth = 0
        var maxDepth = 0

        executor.execute {
            depth++; maxDepth = maxOf(maxDepth, depth)
            ran += "outer"
            executor.execute {
                depth++; maxDepth = maxOf(maxDepth, depth)
                ran += "nested"
                depth--
            }
            assertFalse("the nested task must not run inside the outer one", ran.contains("nested"))
            depth--
        }

        assertEquals(listOf("outer", "nested"), ran)
        assertEquals(1, maxDepth)
    }
}
