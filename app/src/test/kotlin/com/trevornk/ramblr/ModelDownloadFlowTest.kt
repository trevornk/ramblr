package com.trevornk.ramblr

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * End-to-end download -> verify -> install behaviour against a MockWebServer (#368): "download
 * reaches 100% and then restarts at 0%" must not be reachable, and verification failures must
 * surface as an error rather than silently re-downloading.
 */
class ModelDownloadFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var root: File
    private val payload = ByteArray(200_000) { (it * 31 + 7).toByte() }
    private val payloadSha = ModelDownloaderTestHashes.sha256(payload)
    private var fullRequests = 0
    private var rangeRequests = 0
    /** Real hosts (HF -> xet CDN) send no Content-Range on the 416 (#368); tests flip this off. */
    private var send416ContentRange = true

    private fun model(sha: String? = payloadSha) = Model(
        name = "Test cleanup", archive = "test-cleanup", sizeMb = 1, quality = "t",
        sha256 = sha, isLocalCleanup = true, sourceUrl = server.url("/m.gguf").toString(),
        fileName = "m.gguf",
    )

    @Before fun setUp() {
        root = Files.createTempDirectory("dl-flow").toFile()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.headers["Range"]
                if (range == null) {
                    fullRequests++
                    return MockResponse.Builder().body(Buffer().write(payload)).build()
                }
                rangeRequests++
                val start = range.removePrefix("bytes=").removeSuffix("-").toLong()
                if (start >= payload.size) {
                    val b = MockResponse.Builder().code(416)
                    if (send416ContentRange) b.addHeader("Content-Range", "bytes */${payload.size}")
                    return b.build()
                }
                return MockResponse.Builder().code(206)
                    .addHeader("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
                    .body(Buffer().write(payload, start.toInt(), payload.size - start.toInt())).build()
            }
        }
        server.start()
    }

    @After fun tearDown() {
        server.close()
        root.deleteRecursively()
    }

    private class Run(val states: MutableList<DownloadState> = mutableListOf()) {
        val error get() = states.filterIsInstance<DownloadState.Error>().firstOrNull()
        val done get() = states.any { it is DownloadState.Done }
    }

    private fun run(model: Model, tmp: File = File(root, "cache/m.gguf")): Run {
        tmp.parentFile!!.mkdirs()
        val r = Run()
        ModelDownloader.runDownload(
            model, model.sourceUrl!!, tmp, File(root, "staging"), File(root, "files/cleanup_models/test-cleanup"),
            availableBytes = Long.MAX_VALUE / 4, isCancelled = { false }, onState = { r.states.add(it) },
        )
        return r
    }

    @Test fun `fresh download verifies installs and marks complete`() {
        val r = run(model())
        assertTrue(r.states.toString(), r.done)
        val dir = File(root, "files/cleanup_models/test-cleanup")
        assertTrue(ModelDownloader.isInstalledDir(dir))
        assertEquals(payload.size.toLong(), File(dir, "m.gguf").length())
        assertFalse(File(root, "cache/m.gguf").exists())
    }

    @Test fun `phase switches to verifying before the checksum runs`() {
        val r = run(model())
        val lastDownloading = r.states.indexOfLast { it is DownloadState.Downloading }
        val firstExtracting = r.states.indexOfFirst { it is DownloadState.Extracting }
        assertTrue(firstExtracting > lastDownloading)
    }

    @Test fun `a complete leftover file is verified and installed, not re-downloaded from zero`() {
        val tmp = File(root, "cache/m.gguf").also { it.parentFile!!.mkdirs(); it.writeBytes(payload) }
        val r = run(model(), tmp)
        assertTrue(r.states.toString(), r.done)
        assertEquals("must not re-download the body", 0, fullRequests)
        assertEquals(1, rangeRequests)
    }

    @Test fun `416 without Content-Range - complete correct leftover is verified and installed with no body transfer`() {
        send416ContentRange = false
        val tmp = File(root, "cache/m.gguf").also { it.parentFile!!.mkdirs(); it.writeBytes(payload) }
        val r = run(model(), tmp)
        assertTrue(r.states.toString(), r.done)
        assertEquals("must not re-download the body", 0, fullRequests)
        assertEquals(1, rangeRequests)
        assertTrue(ModelDownloader.isInstalledDir(File(root, "files/cleanup_models/test-cleanup")))
    }

    @Test fun `416 without Content-Range - equal-length wrong bytes restart from zero and install the right file`() {
        send416ContentRange = false
        val tmp = File(root, "cache/m.gguf").also { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(payload.size) { 1 }) }
        val r = run(model(), tmp)
        assertTrue(r.states.toString(), r.done)
        assertEquals(1, fullRequests)
        assertEquals(1, rangeRequests)
        assertEquals(payloadSha, ModelDownloader.sha256(File(root, "files/cleanup_models/test-cleanup/m.gguf")))
    }

    @Test fun `416 without Content-Range - shorter partial still resumes via 206`() {
        send416ContentRange = false
        val tmp = File(root, "cache/m.gguf").also { it.parentFile!!.mkdirs(); it.writeBytes(payload.copyOf(1000)) }
        val r = run(model(), tmp)
        assertTrue(r.states.toString(), r.done)
        assertEquals(0, fullRequests)
        assertEquals(1, rangeRequests)
    }

    @Test fun `a genuinely stale oversized partial still restarts from zero`() {
        val tmp = File(root, "cache/m.gguf").also { it.parentFile!!.mkdirs(); it.writeBytes(payload + ByteArray(10)) }
        val r = run(model(), tmp)
        assertTrue(r.states.toString(), r.done)
        assertEquals(1, fullRequests)
    }

    @Test fun `wrong checksum from scratch surfaces a terminal error after exactly one download`() {
        val r = run(model(sha = "0".repeat(64)))
        assertTrue(r.error?.cause is ChecksumMismatchException)
        assertEquals(1, fullRequests)
        assertEquals(0, rangeRequests)
        assertFalse(r.done)
        assertFalse(File(root, "cache/m.gguf").exists())
        assertFalse(ModelDownloaderTestHashes.shouldRetry(r.error!!))
    }

    @Test fun `wrong checksum on a resumed partial retries clean once then errors`() {
        val tmp = File(root, "cache/m.gguf").also { it.parentFile!!.mkdirs(); it.writeBytes(payload.copyOf(1000)) }
        val r = run(model(sha = "0".repeat(64)), tmp)
        assertTrue(r.error?.cause is ChecksumMismatchException)
        assertEquals("one clean restart, never a loop", 1, fullRequests)
        assertEquals(1, rangeRequests)
    }

    @Test fun `install failure is terminal and keeps the verified file for the next attempt`() {
        // Block the install destination: finalDir's parent is a regular file.
        File(root, "files").writeText("not a directory")
        val r = run(model())
        assertTrue(r.error?.cause is ModelInstallException)
        assertFalse(ModelDownloaderTestHashes.shouldRetry(r.error!!))
        assertTrue(File(root, "cache/m.gguf").exists())
        // Fix the obstruction; the next attempt completes without a second full download.
        File(root, "files").delete()
        val r2 = run(model())
        assertTrue(r2.states.toString(), r2.done)
        assertEquals(1, fullRequests)
    }

    @Test fun `truncated body is a retryable IOException and keeps the partial`() {
        // Raw socket: promise the full length, send 5000 bytes, hang up.
        val raw = java.net.ServerSocket(0)
        val t = Thread {
            raw.accept().use { sock ->
                sock.getInputStream().bufferedReader().let { rd -> while (rd.readLine().orEmpty().isNotEmpty()) { } }
                val out = sock.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(payload, 0, 5000)
                out.flush()
            }
        }.also { it.start() }
        val m = model().copy(sourceUrl = "http://127.0.0.1:${raw.localPort}/m.gguf")
        val r = run(m)
        t.join(); raw.close()
        assertTrue(r.states.toString(), r.error?.cause is IOException)
        assertTrue(ModelDownloaderTestHashes.shouldRetry(r.error!!))
        assertTrue(File(root, "cache/m.gguf").exists())
    }

    @Test fun `isCompleteFromRangeNotSatisfiable parses the total`() {
        assertTrue(ModelDownloader.isCompleteFromRangeNotSatisfiable(100, "bytes */100"))
        assertFalse(ModelDownloader.isCompleteFromRangeNotSatisfiable(101, "bytes */100"))
        assertFalse(ModelDownloader.isCompleteFromRangeNotSatisfiable(100, null))
        assertFalse(ModelDownloader.isCompleteFromRangeNotSatisfiable(100, "bytes */*"))
        assertFalse(ModelDownloader.isCompleteFromRangeNotSatisfiable(0, "bytes */0"))
    }

    @Test fun `install failures are never retried by the worker`() {
        assertFalse(ModelDownloadWorker.shouldRetry(ModelInstallException("x", null), 0))
    }
}

private object ModelDownloaderTestHashes {
    fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun shouldRetry(e: DownloadState.Error) = ModelDownloadWorker.shouldRetry(e.cause, 0)
}
