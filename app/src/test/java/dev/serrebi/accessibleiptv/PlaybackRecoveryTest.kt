package dev.serrebi.accessibleiptv

import android.content.Intent
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class PlaybackRecoveryTest {

	@Test fun byteTargetCannotDeadlockAPlayerWithOnlyOneTenthSecondBuffered() {
		val cushion = Cushion(25.0, 30.0)
		val id = PlayerId.UNSET
		cushion.onPrepared(id)
		val allocator = cushion.getAllocator(id)
		val blocks = List(DefaultLoadControl.DEFAULT_MIN_BUFFER_SIZE / allocator.individualAllocationLength + 1) { allocator.allocate() }
		try {
			val timeline = SinglePeriodTimeline(C.TIME_UNSET, true, false, false, null, MediaItem.fromUri("http://provider/live.ts"))
			val period = MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0))
			val parameters = LoadControl.Parameters(id, timeline, period, 0, 100_000, 1f, true, true, C.TIME_UNSET, C.TIME_UNSET)
			assertTrue("keep loading media instead of getting stuck at the byte target", cushion.shouldContinueLoading(parameters))
		} finally {
			blocks.forEach { allocator.release(it) }
			cushion.onReleased(id)
		}
	}

	private fun fixture(catchup: Boolean = false, body: (PlayerActivity, ExoPlayer, MutableList<String>) -> Unit) {
		val server = FakeServer()
		val hold = CountDownLatch(1)
		val app = RuntimeEnvironment.getApplication() as App
		app.prefs.lastUpdateCheck = System.currentTimeMillis()
		server.text("/list.m3u", "#EXTM3U\n#EXTINF:-1,BBC One\n${server.url}/live.ts")
		server.on("/live.ts") { hold.await(30, TimeUnit.SECONDS) }
		app.prefs.sources = listOf(Source("src", Source.TYPE_M3U, "List", "${server.url}/list.m3u"))
		runBlocking { app.load(App.LIVE, false) }
		val controller = Robolectric.buildActivity(PlayerActivity::class.java,
			Intent(app, PlayerActivity::class.java).putExtra("url", "${server.url}/live.ts")
				.putExtra("title", "BBC One").putExtra("key", app.channels[0].key).putExtra("catchup", catchup)).setup()
		val activity = controller.get()
		fun field(name: String) = PlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
		val exo = field("exo").get(activity) as ExoPlayer
		val calls = mutableListOf<String>()
		var ready = true
		val item = MediaItem.fromUri("${server.url}/live.ts")
		val fake = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
			when (method.name) {
				"getDeviceInfo" -> DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_LOCAL).build()
				"getCurrentMediaItem" -> item
				"getPlayWhenReady" -> ready
				"getPlaybackState" -> Player.STATE_BUFFERING
				"isPlaying" -> false
				"stop", "setMediaItem", "prepare", "play" -> { calls += method.name; if (method.name == "play") ready = true; null }
				"pause" -> { ready = false; null }
				else -> null
			}
		} as Player
		field("player").set(activity, fake)
		try {
			shadowOf(Looper.getMainLooper()).idle()
			body(activity, exo, calls)
		} finally {
			controller.pause().stop().destroy()
			exo.release()
			hold.countDown()
			server.close()
		}
	}

	private fun fail(activity: PlayerActivity) {
		val field = PlayerActivity::class.java.getDeclaredField("listener").apply { isAccessible = true }
		(field.get(activity) as Player.Listener).onPlayerError(PlaybackException("stuck", null, PlaybackException.ERROR_CODE_UNSPECIFIED))
	}

	@Test fun duplicateErrorsScheduleOneFreshTimeline() = fixture { activity, _, calls ->
		fail(activity); fail(activity)
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
		assertEquals(listOf("stop", "setMediaItem", "prepare", "play"), calls)
	}

	@Test fun leavingTheAppCancelsPendingPlaybackRecovery() = fixture { activity, _, calls ->
		fail(activity)
		PlayerActivity::class.java.getDeclaredMethod("onStop").apply { isAccessible = true }.invoke(activity)
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
		assertTrue("a pending retry must not play after leaving the app", calls.isEmpty())
	}

	@Test fun aBackwardsTimestampFollowedByStalledBufferingRecovers() = fixture { _, exo, calls ->
		assertEquals(Player.STATE_BUFFERING, exo.playbackState)
		exo.analyticsCollector.onAudioSinkError(AudioSink.UnexpectedDiscontinuityException(0, 900_000_000))
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
		assertTrue("give Media3 a chance to recover the timestamp itself", calls.isEmpty())
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
		assertEquals(listOf("stop", "setMediaItem", "prepare", "play"), calls)
	}

	@Test fun aForwardTimestampDiscontinuityDoesNotRestartPlayback() = fixture { _, exo, calls ->
		exo.analyticsCollector.onAudioSinkError(AudioSink.UnexpectedDiscontinuityException(900_000_000, 0))
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7))
		assertTrue(calls.isEmpty())
	}

	@Test fun catchupDoesNotRetryTimestampFaults() = fixture(catchup = true) { _, exo, calls ->
		exo.analyticsCollector.onAudioSinkError(AudioSink.UnexpectedDiscontinuityException(0, 900_000_000))
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7))
		assertTrue(calls.isEmpty())
	}
}
