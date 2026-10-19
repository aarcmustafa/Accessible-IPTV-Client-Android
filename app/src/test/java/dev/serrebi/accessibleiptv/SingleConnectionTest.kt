package dev.serrebi.accessibleiptv

import android.content.Intent
import android.os.Looper
import android.widget.TextView
import androidx.media3.common.Player as M3Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One provider stream per device: watching, recording and leaving all hold to it. */
@RunWith(RobolectricTestRunner::class)
class SingleConnectionTest {
	/** Live handlers stay open until the test releases them, so no socket ends on its own. */
	private val hold = CountDownLatch(1)
	private val app get() = RuntimeEnvironment.getApplication() as App

	@After fun tearDown() {
		hold.countDown()
		LiveChannels.reset()
		Streams.reset()
	}

	/** A playlist with one live channel, so the player knows what it is watching. */
	private fun channel(server: FakeServer) {
		server.text("/list.m3u", "#EXTM3U\n#EXTINF:-1,BBC One\n${server.url}/live.ts")
		app.prefs.sources = listOf(Source("src", Source.TYPE_M3U, "List", "${server.url}/list.m3u"))
		runBlocking { app.load(App.LIVE, false) }
	}

	/** A live channel that keeps its connection open. */
	private fun live(server: FakeServer, path: String) {
		server.on(path) { ex ->
			ex.sendResponseHeaders(200, 0)
			ex.responseBody.write(ByteArray(5000) { (it % 251).toByte() })
			ex.responseBody.flush()
			hold.await(10, TimeUnit.SECONDS)
		}
	}

	private fun player(key: String, server: FakeServer) = Robolectric.buildActivity(
		PlayerActivity::class.java,
		Intent(app, PlayerActivity::class.java).putExtra("url", "${server.url}/live.ts").putExtra("title", "BBC One").putExtra("key", key),
	).setup()

	private fun awaitRequest(server: FakeServer, path: String): Boolean {
		val end = System.currentTimeMillis() + 3_000
		while (System.currentTimeMillis() < end) {
			if (server.requests.count { it == path } >= 1) return true
			shadowOf(Looper.getMainLooper()).idle()
			Thread.sleep(10)
		}
		return false
	}

	@Test fun leavingLocalPlaybackStopsLoadingInsteadOfOnlyPausing() {
		FakeServer().use { server ->
			server.on("/live.ts") { hold.await(10, TimeUnit.SECONDS) }
			val controller = player("", server)
			try {
				val exo = PlayerActivity::class.java.getDeclaredField("exo").apply { isAccessible = true }.get(controller.get()) as ExoPlayer
				shadowOf(Looper.getMainLooper()).idle()
				assertEquals(M3Player.STATE_BUFFERING, exo.playbackState)
				controller.pause().stop()
				assertEquals("hidden players must not retain provider connections", M3Player.STATE_IDLE, exo.playbackState)
				assertNull("leaving frees the one provider stream", Streams.current())
			} finally {
				controller.destroy()
			}
		}
	}

	@Test fun recordingTheChannelBeingWatchedSharesOneConnection() {
		FakeServer().use { server ->
			channel(server)
			live(server, "/live.ts")
			val key = app.channels[0].key
			val controller = player(key, server)
			try {
				assertTrue("the player opened the channel", awaitRequest(server, "/live.ts"))
				assertNotNull("watching holds the channel's one stream", LiveChannels.current(key))
				assertTrue("the record action is allowed", Recorder.canStart(app.channels[0]))
				// Record pressed: the service joins the running channel instead of opening a socket.
				val recording = LiveChannels.attach(key) {
					fail("a watched channel is joined, never reopened")
					InputStream.nullInputStream()
				}
				assertSame("recording shares the player's connection", LiveChannels.current(key), recording)
				assertEquals("one provider connection for watching and recording", 1, server.requests.count { it == "/live.ts" })
				LiveChannels.leave(recording!!)
			} finally {
				controller.pause().stop().destroy()
			}
		}
	}

	@Test fun decoderRecoveryKeepsTheExistingProviderConnection() {
		FakeServer().use { server ->
			channel(server)
			live(server, "/live.ts")
			val key = app.channels[0].key
			val controller = player(key, server)
			try {
				assertTrue(awaitRequest(server, "/live.ts"))
				val original = LiveChannels.current(key)!!
				PlayerActivity::class.java.getDeclaredMethod("prepareLive").apply { isAccessible = true }.invoke(controller.get())
				val end = System.currentTimeMillis() + 500
				while (System.currentTimeMillis() < end) {
					shadowOf(Looper.getMainLooper()).idle()
					Thread.sleep(10)
				}
				assertSame("a decoder reset must not disconnect the provider", original, LiveChannels.current(key))
				assertEquals(1, server.requests.count { it == "/live.ts" })
			} finally { controller.pause().stop().destroy() }
			assertNull("leaving the screen still releases the retained connection", Streams.current())
		}
	}

	@Test fun recordingAnUnwatchedChannelTakesTheOneConnection() {
		FakeServer().use { server ->
			channel(server)
			live(server, "/live.ts")
			val recording = LiveChannels.attach(app.channels[0].key) { Net.open("${server.url}/live.ts") }
			try {
				assertNotNull(recording)
				assertTrue(awaitRequest(server, "/live.ts"))
				assertFalse("the player cannot start a second stream", Player.freeFor(app, "${server.url}/other.ts", "", false))
				assertFalse("a download needs the stream to itself", Recorder.canDownload())
			} finally {
				LiveChannels.leave(recording!!)
			}
		}
	}

	@Test fun aRefusedLiveRecordingFailsLoudlyInsteadOfOpeningASecondConnection() {
		FakeServer().use { server ->
			channel(server)
			live(server, "/live.ts")
			val key = app.channels[0].key
			// A download holds the one stream, so a live recording of another channel cannot run.
			assertTrue(Streams.acquire(Streams.downloadId("${server.url}/movie.mp4")))
			val service = Robolectric.buildService(RecordService::class.java).create()
			Recorder.active[key] = Recorder.Active(key, "BBC One", key, 0, 0)
			try {
				RecordService::class.java.getDeclaredMethod(
					"record", String::class.java, String::class.java, String::class.java, String::class.java,
					Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
				).apply { isAccessible = true }.invoke(service.get(), key, "BBC One", "", "${server.url}/live.ts", 0L, 0L)
				assertFalse("a refused recording is not left running", Recorder.isRecording(key))
				assertNull("the refused channel holds no stream", LiveChannels.current(key))
				assertTrue("no provider connection was opened", server.requests.none { it == "/live.ts" })
				val notices = shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).allNotifications
				assertTrue("the user is told", notices.any { it.extras.getCharSequence("android.title").toString() == app.getString(R.string.recording_failed, "BBC One") })
			} finally {
				Recorder.active.clear()
				Streams.release(Streams.downloadId("${server.url}/movie.mp4"))
				service.destroy()
			}
		}
	}

	@Test fun aDifferentChannelIsRefusedWhileWatchingAndSaysWhy() {
		FakeServer().use { server ->
			channel(server)
			live(server, "/live.ts")
			val controller = player(app.channels[0].key, server)
			try {
				assertTrue(awaitRequest(server, "/live.ts"))
				val status = TextView(app)
				Player.play(app, "${server.url}/other.ts", "Other", "src:Other", status = status)
				assertEquals(app.getString(R.string.stream_busy), status.text.toString())
				assertNull("no second player was opened", shadowOf(app).nextStartedActivity)
				assertNull("the second channel was refused", LiveChannels.attach("src:Other") {
					InputStream.nullInputStream()
				})
			} finally {
				controller.pause().stop().destroy()
			}
		}
	}
}
