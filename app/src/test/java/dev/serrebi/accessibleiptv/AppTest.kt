package dev.serrebi.accessibleiptv

import android.app.NotificationManager
import android.content.Intent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File

/** Playlist loading, the channel cache, favourites, guides named by playlists, scheduled recordings. */
@RunWith(RobolectricTestRunner::class)
class AppTest {
	private val server = FakeServer()
	private val app get() = RuntimeEnvironment.getApplication() as App
	private val src get() = Source("src", Source.TYPE_M3U, "List", "${server.url}/list.m3u")

	private val playlist = """
		#EXTM3U x-tvg-url="GUIDE"
		#EXTINF:-1 tvg-id="bbc1.uk" tvg-logo="http://l/1.png" group-title="News" catchup="xc" catchup-days="2",BBC One
		http://h/live/u/p/1.ts
		#EXTINF:-1 group-title="Sport",Arena
		#EXTVLCOPT:http-user-agent=Box
		http://h/live/u/p/2.ts
		#EXTINF:-1 group-title="Films" tvg-type="movie",Film
		http://h/movie/u/p/3.mp4
	""".trimIndent()

	@Before fun setUp() {
		server.text("/list.m3u", playlist.replace("GUIDE", "${server.url}/guide.xml"))
		app.prefs.sources = listOf(src)
	}

	@After fun stop() = server.close()

	private fun load(section: String = App.LIVE, force: Boolean = false) = runBlocking { app.load(section, force) }

	/** What Android leaves after killing the process: prefs and files, no lists in memory. */
	@Suppress("UNCHECKED_CAST")
	private fun forgetLists() = (App::class.java.getDeclaredField("lists").apply { isAccessible = true }.get(app) as MutableMap<String, *>).clear()

	@Test fun loadsSectionsAndKinds() {
		assertEquals(emptyList<String>(), load())
		assertEquals(listOf("BBC One", "Arena"), app.channels.map { it.name })
		load(App.MOVIES)
		assertEquals(listOf("Film"), app.list(App.MOVIES).map { it.name })
		assertEquals(listOf(App.ALL, App.FAVORITES, "News", "Sport"), app.groups(App.LIVE))
	}

	@Test fun cacheIsUsedAndRoundTripsEveryField() {
		load()
		val fetched = app.channels
		val hits = server.requests.size
		forgetLists()
		load()
		assertEquals("a fresh cache is not downloaded again", hits, server.requests.size)
		assertEquals(fetched, app.channels)
		assertEquals("Box", M3uParser.splitHeaders(app.channels[1].url).second["User-Agent"])
	}

	@Test fun aDownFailureKeepsTheCachedChannels() {
		load()
		server.text("/list.m3u", "busy", status = 503)
		val errors = load(force = true)
		assertEquals(1, errors.size)
		assertTrue(errors[0], errors[0].contains("503"))
		assertEquals(2, app.channels.size)
	}

	@Test fun favouritesSurviveTheProviderReorderingTheList() {
		load()
		app.prefs.toggleFavorite(app.channels.first { it.name == "Arena" }.key)
		server.text("/list.m3u", "#EXTM3U\n#EXTINF:-1,New\nhttp://h/9.ts\n#EXTINF:-1 group-title=\"Sport\",Arena\nhttp://h/live/u/p/2.ts")
		load(force = true)
		assertEquals(listOf("Arena"), app.channelsIn(App.LIVE, App.FAVORITES).map { it.name })
	}

	@Test fun favouritesFromPositionIdsAreMovedToTheNewIds() {
		// 0.2.5 and older keyed M3U channels by position: "src:1" was Arena.
		val old = listOf(Channel("src", "0", "BBC One", "News", "http://h/live/u/p/1.ts"), Channel("src", "1", "Arena", "Sport", "http://h/live/u/p/2.ts|User-Agent=Box"))
		File(app.filesDir, "channels_src_live.json").writeText(old.joinToString(",", "[", "]") { it.toJson().toString() })
		app.prefs.favorites = setOf("src:1", "other:5")
		load(force = true)
		assertEquals(listOf("Arena"), app.channelsIn(App.LIVE, App.FAVORITES).map { it.name })
		assertTrue("other sources' favourites are left alone", "other:5" in app.prefs.favorites)
	}

	@Test fun aPlaylistsOwnGuideIsUsed() {
		load()
		assertEquals("${server.url}/guide.xml", app.prefs.sources[0].effectiveEpgUrl)
	}

	@Test fun aGuideTheUserTypedWins() {
		app.prefs.sources = listOf(src.copy(epgUrl = "http://mine/g.xml"))
		load()
		assertEquals("http://mine/g.xml", app.prefs.sources[0].epgUrl)
	}

	@Test fun guideImportAndStaleness() {
		server.text("/guide.xml", "<tv><channel id=\"bbc1.uk\"><display-name>BBC One</display-name></channel></tv>")
		load()
		assertTrue(app.guideStale())
		runBlocking { app.importGuide { } }
		assertFalse(app.guideStale())
		// A channel the guide was not built for means import again now, not tomorrow.
		server.text("/list.m3u", playlist.replace("Arena", "Arena 2"))
		load(force = true)
		assertTrue(app.guideStale())
	}

	@Test fun restoredScreensFindTheirChannel() {
		load()
		val key = app.channels[0].key
		forgetLists()
		assertNull(app.channel(key))
		assertEquals("BBC One", runBlocking { app.findChannel(key, App.LIVE) }?.name)
	}

	@Test fun catchupOnlyInsideTheWindow() {
		load()
		val bbc = app.channels[0]
		val now = System.currentTimeMillis()
		assertNotNull(app.catchupUrl(bbc, now - 3_600_000, now - 1_800_000))
		assertNull(app.catchupUrl(bbc, now - 3 * 86_400_000L, now - 3 * 86_400_000L + 60_000))
		assertNull(app.catchupUrl(app.channels[1], now - 3_600_000, now))
	}

	@Test fun accountInfoFromAnXtreamM3uLink() {
		server.text("/player_api.php", """{"user_info":{"status":"Active","exp_date":null,"is_trial":"0","active_cons":"1","max_connections":"2"}}""")
		val m3u = src.copy(url = "${server.url}/get.php?username=u&password=p&type=m3u_plus")
		assertEquals("Active", app.accountInfo(m3u).toMap()["Status"])
		assertTrue(server.requests.last().startsWith("/player_api.php?username=u&password=p"))
	}

	// ---- Scheduled recordings ----

	@Test fun scheduleArmsAnAlarmAMinuteEarlyAndCancelRemovesIt() {
		load()
		val start = System.currentTimeMillis() + 3_600_000
		Recorder.schedule(app, app.channels[0], "News", start, start + 1_800_000)
		val s = Recorder.scheduled(app).single()
		val am = shadowOf(app.getSystemService(android.app.AlarmManager::class.java))
		assertEquals(start - 60_000, am.peekNextScheduledAlarm()!!.triggerAtMs)
		Recorder.cancel(app, s.id)
		assertEquals(emptyList<Recorder.Scheduled>(), Recorder.scheduled(app))
		assertNull(am.peekNextScheduledAlarm())
	}

	@Test fun finishedSchedulesAreDropped() {
		load()
		val past = System.currentTimeMillis() - 7_200_000
		Recorder.schedule(app, app.channels[0], "Old", past, past + 60_000)
		assertEquals(emptyList<Recorder.Scheduled>(), Recorder.scheduled(app))
	}

	private fun fire(id: Int) {
		// Delivered the way Android does, so goAsync() has a pending result.
		app.sendBroadcast(Intent(app, Recorder.AlarmReceiver::class.java).putExtra("id", id))
		shadowOf(android.os.Looper.getMainLooper()).idle()
		val end = System.currentTimeMillis() + 5_000
		while (shadowOf(app).peekNextStartedService() == null && shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty()) {
			if (System.currentTimeMillis() > end) break
			Thread.sleep(20)
		}
	}

	@Test fun alarmStartsTheRecordingAfterTheProcessWasKilled() {
		load()
		val start = System.currentTimeMillis() + 30_000
		Recorder.schedule(app, app.channels[0], "News", start, start + 60_000)
		forgetLists()
		val s = Recorder.scheduled(app).single()
		assertEquals("default padding: 2 minutes after, as on the desktop", start + 60_000 + 120_000, s.end)
		fire(s.id)
		val started = shadowOf(app).nextStartedService
		assertEquals(app.channels[0].key, started.getStringExtra("key"))
		assertEquals(s.end, started.getLongExtra("end", 0))
	}

	@Test fun aRecordingWhoseChannelIsGoneSaysSo() {
		load()
		val start = System.currentTimeMillis() + 30_000
		Recorder.schedule(app, Channel("src", "gone", "Gone TV", "", "http://h/x.ts"), "Show", start, start + 60_000)
		fire(Recorder.scheduled(app).single().id)
		val n = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
		assertEquals(1, n.size)
		assertEquals(app.getString(R.string.recording_failed, "Gone TV"), n[0].extras.getCharSequence("android.title").toString())
	}
}
