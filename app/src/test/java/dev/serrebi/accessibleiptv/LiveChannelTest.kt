package dev.serrebi.accessibleiptv

import com.sun.net.httpserver.HttpExchange
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The one-connection rules: the shared live tap and the stream gate. */
@RunWith(RobolectricTestRunner::class)
class LiveChannelTest {

	@After fun tearDown() {
		LiveChannels.reset()
		Streams.reset()
	}

	private fun bytes(n: Int, seed: Int = 0) = ByteArray(n) { ((it + seed) % 251).toByte() }

	/** Reads exactly [n] bytes; a live reader blocks until the bytes arrive. */
	private fun InputStream.readN(n: Int): ByteArray {
		val out = ByteArray(n)
		var at = 0
		while (at < n) {
			val read = read(out, at, n - at)
			if (read < 0) break
			at += read
		}
		return out.copyOf(at)
	}

	private fun HttpExchange.chunked(body: ByteArray) {
		sendResponseHeaders(200, 0) // chunked: the connection stays open after the body
		responseBody.write(body)
		responseBody.flush()
	}

	private fun awaitRequests(server: FakeServer, path: String, n: Int): Boolean {
		val end = System.currentTimeMillis() + 3_000
		while (System.currentTimeMillis() < end) {
			if (server.requests.count { it.startsWith(path) } >= n) return true
			Thread.sleep(10)
		}
		return false
	}

	@Test fun aReaderThatFellBehindJumpsToTheOldestRetainedByte() {
		val tap = Tap(16)
		val reader = tap.reader()
		tap.write(bytes(32), 0, 32)
		// positions 0..15 were dropped: a live reader continues at the oldest byte still held
		assertArrayEquals(bytes(16, 16), reader.readN(16))
	}

	@Test fun everyConsumerOfATapSeesTheSameBytes() {
		val tap = Tap(4096)
		val player = tap.reader()
		val recorder = tap.reader()
		tap.write(bytes(1000), 0, 1000)
		assertArrayEquals(bytes(1000), recorder.readN(1000))
		assertArrayEquals("the second reader is not behind the first", bytes(1000), player.readN(1000))
	}

	@Test fun aBlockingReadReturnsWhenBytesArriveAndTheEndWhenClosed() {
		val tap = Tap(64)
		val reader = tap.reader()
		val writer = Thread { Thread.sleep(100); tap.write(bytes(8, 3), 0, 8) }
		writer.start()
		assertArrayEquals(bytes(8, 3), reader.readN(8))
		writer.join()
		tap.close()
		assertEquals(-1, reader.read())
	}

	@Test fun attachJoinsTheRunningChannelInsteadOfOpeningASecondSocket() {
		val hold = CountDownLatch(1)
		FakeServer().use { server ->
			server.on("/live.ts") { ex -> ex.chunked(bytes(50_000)); hold.await(10, TimeUnit.SECONDS) }
			val first = LiveChannels.attach("src:1") { Net.open("${server.url}/live.ts") }
			assertNotNull("the first consumer opens the channel", first)
			assertTrue("the socket was opened once", awaitRequests(server, "/live.ts", 1))
			val second = LiveChannels.attach("src:1") {
				fail("a running channel is joined, never reopened")
				InputStream.nullInputStream()
			}
			assertSame("recording the watched channel shares its socket", first, second)
			assertEquals("one provider connection for watching and recording", 1, server.requests.count { it == "/live.ts" })
			assertArrayEquals(bytes(50_000), second!!.reader().readN(50_000))
			hold.countDown()
			LiveChannels.leave(first!!)
			LiveChannels.leave(second)
			assertTrue("the last consumer closes the socket", first.isClosed)
			assertNull("the stream is free again", Streams.current())
		}
	}

	@Test fun aDifferentLiveChannelIsRefusedWhileOneIsInUse() {
		val hold = CountDownLatch(1)
		FakeServer().use { server ->
			server.on("/live.ts") { ex -> ex.chunked(bytes(100)); hold.await(10, TimeUnit.SECONDS) }
			val first = LiveChannels.attach("src:1") { Net.open("${server.url}/live.ts") }
			assertTrue(awaitRequests(server, "/live.ts", 1))
			assertNull("a different stream must wait until this one stops", LiveChannels.attach("src:2") { InputStream.nullInputStream() })
			hold.countDown()
			LiveChannels.leave(first!!)
			assertNotNull("once it stopped, the next channel may start", LiveChannels.attach("src:2") { InputStream.nullInputStream() })
		}
	}

	@Test fun aStreamThatNeverPlaysGivesUpAndFreesTheOneStream() {
		FakeServer().use { server ->
			server.text("/live.ts", "gone", status = 404)
			val ch = LiveChannels.attach("src:1") { Net.open("${server.url}/live.ts") }
			assertNotNull(ch)
			val end = System.currentTimeMillis() + 10_000
			while (Streams.current() != null && System.currentTimeMillis() < end) Thread.sleep(20)
			assertTrue("a stream that never plays gives up", ch!!.isClosed)
			assertNull("the one stream is free again", Streams.current())
			assertNull("the dead channel is gone", LiveChannels.current("src:1"))
			// The player's own reconnect path opens it again once the provider answers.
			server.text("/live.ts", "now on")
			assertNotNull("a new attempt may take the stream", LiveChannels.attach("src:1") { Net.open("${server.url}/live.ts") })
		}
	}

	@Test fun aDownloadHoldingTheStreamBlocksALiveChannel() {
		assertTrue(Streams.acquire(Streams.downloadId("http://h/movie.mp4")))
		assertNull("a download needs the stream to itself", LiveChannels.attach("src:1") { InputStream.nullInputStream() })
	}

	@Test fun aLiveChannelBlocksEveryOtherStream() {
		val channel = LiveChannels.attach("src:1") { InputStream.nullInputStream() }
		assertNotNull(channel)
		assertFalse("the gate is held for the live channel", Streams.acquire(Streams.downloadId("http://h/movie.mp4")))
		assertFalse(Streams.acquire(Streams.urlId("http://h/other.ts")))
		assertTrue("the channel's own stream may re-take it", Streams.acquire(Streams.liveId("src:1")))
		LiveChannels.leave(channel!!)
		assertTrue("stopping frees the stream", Streams.acquire(Streams.downloadId("http://h/movie.mp4")))
	}
}
